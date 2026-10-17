import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_vless/flutter_vless.dart';
import 'package:revoltvpn/logic/connection_settings.dart';
import 'package:revoltvpn/logic/hivemind_service.dart';
import 'package:revoltvpn/logic/local_socks_tester.dart';
import 'package:revoltvpn/logic/network_monitor.dart';
import 'package:revoltvpn/logic/secure_socks_session.dart';

enum VpnStatus {
  disconnected,
  connecting,
  connected,
  disconnecting,
  error,
}

class VpnConnection extends ChangeNotifier {
  static const MethodChannel _nativeControl = MethodChannel('flutter_vless');

  int _connectEpoch = 0;
  bool _suppressNativeConnect = false;
  bool _disposed = false;

  VpnStatus _status = VpnStatus.disconnected;
  VpnStatus get status => _status;

  String _statusMessage = 'Tap to connect';
  String get statusMessage => _statusMessage;

  String? _errorMessage;
  String? get errorMessage => _errorMessage;

  /// True when the UI attached to a tunnel that was already running, rather
  /// than starting one itself. The session timer uses this to resume ticking.
  bool _adoptedRunningRuntime = false;
  bool get adoptedRunningRuntime => _adoptedRunningRuntime;

  bool _serverReachable = false;
  bool get serverReachable => _serverReachable;

  ConnectionMode _activeMode = ConnectionMode.tun;
  ConnectionMode get activeMode => _activeMode;

  String _networkTransport = 'unknown';
  String get networkTransport => _networkTransport;

  SecureSocksSession? get activeSocksSession => _lastSecureSocks;

  bool get canTestActiveLocalSocks =>
      !_disposed &&
      _status == VpnStatus.connected &&
      _activeMode == ConnectionMode.proxy &&
      _lastSecureSocks != null;

  Future<LocalSocksTestResult> testActiveLocalSocks() async {
    final active = _lastSecureSocks;
    if (!canTestActiveLocalSocks || active == null) {
      return const LocalSocksTestResult(
        ok: false,
        latencyMs: null,
        message: 'No active authenticated SOCKS5 session.',
      );
    }

    return LocalSocksTester.test(
      host: '127.0.0.1',
      port: active.port,
      username: active.username,
      password: active.password,
    );
  }

  Timer? _healthTimer;
  StreamSubscription<NetworkSnapshot>? _networkSubscription;

  SecureSocksSession? _lastSecureSocks;
  bool _userDisconnecting = false;

  late final FlutterVless _vless;
  bool _initialized = false;
  final Completer<void> _readyCompleter = Completer<void>();
  Future<void> get ready => _readyCompleter.future;

  VpnConnection() {
    _activeMode = ConnectionSettings.mode;
    _init();
  }

  Future<void> _init() async {
    try {
      await _startEngine();
    } catch (e) {
      debugPrint('[VPN] Engine init failed: $e');
    } finally {
      if (!_readyCompleter.isCompleted) _readyCompleter.complete();
    }
  }

  Future<void> _startEngine() async {
    if (kIsWeb || _disposed) return;

    await ConnectionSettings.initialize();
    if (_disposed) return;
    _activeMode = ConnectionSettings.mode;

    _vless = FlutterVless(
      onStatusChanged: (status) {
        debugPrint(
          '[VPN] Status: state=${status.state} '
          'connection=${status.connectionState.name}',
        );
        _mapStatus(status);
      },
    );

    try {
      await _vless.initializeVless(
        providerBundleIdentifier: 'com.paladinvpn.app',
        notificationIconResourceType: 'drawable',
        notificationIconResourceName: 'notification_icon',
      );
      if (_disposed) return;
      _initialized = true;
    } catch (e) {
      debugPrint('[VPN] VLESS init error (expected on emulator): $e');
    }
    if (_disposed) return;

    _networkSubscription = NetworkMonitor.changes.listen(
      (snapshot) {
        if (_disposed) return;
        // ConnectivityManager is informational only. Restarting from every
        // callback caused the old VPN-created-network reconnect loop.
        _networkTransport = snapshot.transport;
        notifyListeners();
      },
      onError: (Object error) {
        debugPrint('[VPN] Network monitor error: $error');
      },
    );

    unawaited(_checkHealth());
    _healthTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      unawaited(_checkHealth());
    });

    try {
      final coreVersion = await _vless.getCoreVersion();
      if (!_disposed) {
        debugPrint('[VPN] Xray core version: $coreVersion');
      }
    } catch (_) {}
  }

  String get _connectedLabel {
    switch (_activeMode) {
      case ConnectionMode.proxy:
        return 'SOCKS5 gateway active';
      case ConnectionMode.tun:
        return 'Secured';
    }
  }

  bool _isCurrentConnect(int epoch) =>
      !_disposed && epoch == _connectEpoch && !_userDisconnecting;

  void _mapStatus(VlessStatus status) {
    if (_disposed) return;
    switch (status.connectionState) {
      case VlessConnectionState.connected:
        if (_suppressNativeConnect || _userDisconnecting) return;
        if (_connectEpoch == 0) _adoptedRunningRuntime = true;
        _setStatus(VpnStatus.connected, _connectedLabel);
        break;

      case VlessConnectionState.disconnected:
        _clearRuntimeSnapshot();
        _setStatus(VpnStatus.disconnected, 'Tap to connect');
        break;

      case VlessConnectionState.connecting:
        if (_suppressNativeConnect || _userDisconnecting) return;
        _setStatus(VpnStatus.connecting, 'Establishing tunnel…');
        break;

      case VlessConnectionState.disconnecting:
        _setStatus(VpnStatus.disconnecting, 'Tearing down…');
        break;

      case VlessConnectionState.unknown:
        if (_suppressNativeConnect || _userDisconnecting) return;
        if (_status != VpnStatus.connected &&
            _status != VpnStatus.disconnected) {
          _setStatus(VpnStatus.error, 'Connection failed');
        }
        break;
    }
  }

  Future<bool> connect({String? adNonce}) async {
    if (_disposed ||
        _userDisconnecting ||
        _status == VpnStatus.connected ||
        _status == VpnStatus.connecting ||
        _status == VpnStatus.disconnecting) {
      return false;
    }

    final connectEpoch = ++_connectEpoch;
    _suppressNativeConnect = false;
    _userDisconnecting = false;

    await ConnectionSettings.initialize();
    if (!_isCurrentConnect(connectEpoch)) return false;
    _activeMode = ConnectionSettings.mode;

    if (!kIsWeb && !_initialized) {
      _suppressNativeConnect = true;
      _errorMessage = 'VPN service unavailable.';
      _setStatus(VpnStatus.error, 'Service unavailable');
      return false;
    }

    // Proxy mode skips requestPermission below (VPN consent it never needs),
    // so it has to ask for POST_NOTIFICATIONS on its own.
    if (!kIsWeb && _activeMode == ConnectionMode.proxy) {
      try {
        await _nativeControl.invokeMethod<void>('requestNotificationPermission');
      } catch (e) {
        debugPrint('[VPN] Notification permission request failed: $e');
      }
      if (!_isCurrentConnect(connectEpoch)) return false;
    }

    if (!kIsWeb && _activeMode == ConnectionMode.tun) {
      final ok = await _vless.requestPermission();
      if (!_isCurrentConnect(connectEpoch)) return false;
      if (!ok) {
        _suppressNativeConnect = true;
        _errorMessage = 'VPN permission denied.';
        _setStatus(VpnStatus.error, 'Permission required');
        return false;
      }
    }

    final startingMessage = switch (_activeMode) {
      ConnectionMode.tun => 'Establishing secure channel…',
      ConnectionMode.proxy => 'Starting local SOCKS5 proxy…',
    };
    _setStatus(VpnStatus.connecting, startingMessage);
    _errorMessage = null;

    if (kIsWeb) {
      await Future.delayed(const Duration(seconds: 1));
      if (!_isCurrentConnect(connectEpoch)) return false;
      _setStatus(VpnStatus.connected, 'Secured (dev mode)');
      return true;
    }

    _setStatus(VpnStatus.connecting, 'Fetching config…');

    String realUrl;
    try {
      realUrl = await HivemindService.fetchConfigDirectly(
        adNonce: adNonce,
        onAttempt: (attempt, total) {
          if (!_isCurrentConnect(connectEpoch)) return;
          _setStatus(
            VpnStatus.connecting,
            'Contacting server ($attempt/$total)…',
          );
        },
      );
    } catch (e) {
      if (!_isCurrentConnect(connectEpoch)) return false;
      debugPrint('[VPN] Config fetch error: $e');
      final raw = e.toString().replaceAll('Exception: ', '');
      if (raw.contains('Cancelled')) return false;
      _suppressNativeConnect = true;
      if (raw.contains('timed out') || raw.contains('Session not activated')) {
        _errorMessage =
            'The server did not respond in time.\nCheck your connection and try again.';
        _setStatus(VpnStatus.error, 'Server unreachable');
      } else {
        _errorMessage = raw;
        _setStatus(VpnStatus.error, 'Config fetch error');
      }
      return false;
    }

    if (!_isCurrentConnect(connectEpoch)) return false;
    _setStatus(VpnStatus.connecting, 'Starting secure route…');

    try {
      final parsed = FlutterVless.parse(realUrl);
      final baseConfig = parsed.getFullConfiguration();
      final remark = parsed.remark.isNotEmpty ? parsed.remark : 'Revolt VPN';
      final secureSocks = await SecureSocksSession.create(baseConfig);
      if (!_isCurrentConnect(connectEpoch)) return false;
      final verifyLocalSocks = _activeMode == ConnectionMode.proxy;

      await _startRuntime(
        config: secureSocks.configJson,
        remark: remark,
        proxyOnly: verifyLocalSocks,
      );
      if (!_isCurrentConnect(connectEpoch)) return false;

      _setStatus(
        VpnStatus.connecting,
        verifyLocalSocks
            ? 'Waiting for local SOCKS5…'
            : 'Waiting for VPN interface…',
      );
      if (!await _waitForNativeConnected(connectEpoch)) {
        throw StateError('VPN runtime did not report CONNECTED');
      }

      if (verifyLocalSocks) {
        if (!_isCurrentConnect(connectEpoch)) return false;
        _setStatus(VpnStatus.connecting, 'Checking Local SOCKS5…');
        if (!await _waitForLocalSocksListener(
          secureSocks,
          connectEpoch,
        )) {
          throw StateError('Local SOCKS5 listener did not become ready');
        }
      }

      if (!_isCurrentConnect(connectEpoch)) return false;
      _lastSecureSocks = secureSocks;
    } catch (e) {
      if (!_isCurrentConnect(connectEpoch)) return false;
      debugPrint('[VPN] Tunnel start error: $e');
      _suppressNativeConnect = true;
      try {
        await _stopRuntime();
      } catch (stopError) {
        debugPrint('[VPN] Runtime cleanup failed after start error: $stopError');
        if (!_isCurrentConnect(connectEpoch)) return false;
        _errorMessage = 'VPN failed to shut down cleanly.\nPlease restart the app.';
        _setStatus(VpnStatus.error, 'Shutdown failed');
        return false;
      }
      if (!_isCurrentConnect(connectEpoch)) return false;
      _clearRuntimeSnapshot();
      _errorMessage = _activeMode == ConnectionMode.proxy
          ? 'SOCKS5 gateway failed to start.\nTry reconnecting.'
          : 'Connection failed to start.\nTry reconnecting.';
      _setStatus(VpnStatus.error, 'Connection failed');
      return false;
    }

    if (!_isCurrentConnect(connectEpoch)) return false;
    _setStatus(VpnStatus.connected, _connectedLabel);
    return true;
  }

  Future<void> _startRuntime({
    required String config,
    required String remark,
    required bool proxyOnly,
  }) async {
    await _vless.startVless(
      remark: remark,
      config: config,
      proxyOnly: proxyOnly,
    );
  }

  Future<bool> _waitForLocalSocksListener(
    SecureSocksSession session,
    int connectEpoch,
  ) async {
    for (var attempt = 0; attempt < 4; attempt++) {
      if (!_isCurrentConnect(connectEpoch)) return false;
      final result = await LocalSocksTester.testListener(
        host: '127.0.0.1',
        port: session.port,
        username: session.username,
        password: session.password,
      );
      if (!_isCurrentConnect(connectEpoch)) return false;
      if (result.ok) return true;
      if (attempt < 3) {
        await Future.delayed(const Duration(milliseconds: 250));
      }
    }
    return false;
  }

  Future<bool> _waitForNativeConnected(int connectEpoch) async {
    for (var attempt = 0; attempt < 24; attempt++) {
      if (!_isCurrentConnect(connectEpoch)) return false;
      if (_status == VpnStatus.connected) return true;
      if (_status == VpnStatus.error || _status == VpnStatus.disconnected) {
        return false;
      }
      await Future.delayed(const Duration(milliseconds: 250));
    }
    return _isCurrentConnect(connectEpoch) &&
        _status == VpnStatus.connected;
  }

  Future<void> _stopRuntime() async {
    await _vless.stopVless().timeout(const Duration(seconds: 8));
  }

  Future<void> setNativeSessionDeadline(int remainingSeconds) async {
    if (kIsWeb) return;
    if (_disposed) throw StateError('VPN connection has been disposed');
    if (!_initialized) throw StateError('VPN native service is not initialized');
    if (remainingSeconds < 0) {
      throw ArgumentError.value(remainingSeconds, 'remainingSeconds');
    }
    await _nativeControl.invokeMethod<void>(
      'setSessionDeadline',
      <String, Object>{'remainingSeconds': remainingSeconds},
    );
  }

  Future<void> disconnect() async {
    if (_disposed || _status == VpnStatus.disconnecting) return;

    _connectEpoch++;
    _suppressNativeConnect = true;
    _userDisconnecting = true;
    HivemindService.cancel();

    _setStatus(VpnStatus.disconnecting, 'Tearing down…');

    if (kIsWeb) {
      await Future.delayed(const Duration(milliseconds: 500));
      if (_disposed) return;
      _clearRuntimeSnapshot();
      _setStatus(VpnStatus.disconnected, 'Tap to connect');
      _userDisconnecting = false;
      return;
    }

    try {
      await _vless.stopVless().timeout(const Duration(seconds: 8));
    } catch (e) {
      if (_disposed) return;
      debugPrint('[VPN] VLESS stop error: $e');
      _errorMessage = 'VPN shutdown error.\nPlease restart the app.';
      _clearRuntimeSnapshot();
      _setStatus(VpnStatus.error, 'Shutdown failed');
      _userDisconnecting = false;
      return;
    }

    if (_disposed) return;
    _errorMessage = null;
    _clearRuntimeSnapshot();
    _setStatus(VpnStatus.disconnected, 'Tap to connect');
    _userDisconnecting = false;
  }

  void _clearRuntimeSnapshot() {
    _lastSecureSocks = null;
    _adoptedRunningRuntime = false;
  }

  void _setStatus(VpnStatus status, String message) {
    if (_disposed) return;
    _status = status;
    _statusMessage = message;
    notifyListeners();
  }

  Future<void> _checkHealth() async {
    if (_disposed || _status == VpnStatus.connected) return;
    final reachable = await HivemindService.checkHealth();
    if (_disposed || _status == VpnStatus.connected) return;
    _serverReachable = reachable;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _connectEpoch++;
    _suppressNativeConnect = true;
    _userDisconnecting = true;
    HivemindService.cancel();
    _healthTimer?.cancel();
    _networkSubscription?.cancel();
    // Provider/UI disposal is not a user disconnect command. Keeping teardown
    // in disconnect() prevents lifecycle churn from silently dropping the VPN.
    super.dispose();
  }
}
