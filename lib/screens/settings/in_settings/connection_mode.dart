import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:revoltvpn/logic/app_colors.dart';
import 'package:revoltvpn/logic/connection_settings.dart';
import 'package:revoltvpn/logic/local_socks_tester.dart';
import 'package:revoltvpn/logic/session_timer.dart';
import 'package:revoltvpn/logic/vpn_connection.dart';

class ConnectionModeTile extends StatefulWidget {
  final ValueChanged<ConnectionMode>? onChanged;

  const ConnectionModeTile({super.key, this.onChanged});

  @override
  State<ConnectionModeTile> createState() => _ConnectionModeTileState();
}

class _ConnectionModeTileState extends State<ConnectionModeTile> {
  ConnectionMode _mode = ConnectionSettings.mode;
  bool _testingLocalSocks = false;
  bool _reconnecting = false;
  LocalSocksTestResult? _lastSocksTest;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    await ConnectionSettings.initialize();
    if (mounted) setState(() => _mode = ConnectionSettings.mode);
  }

  bool _isTunnelBusy(VpnStatus status) {
    return status == VpnStatus.connected ||
        status == VpnStatus.connecting ||
        status == VpnStatus.disconnecting;
  }

  Future<void> _changeMode(ConnectionMode? next) async {
    if (next == null || next == _mode) return;

    final vpn = context.read<VpnConnection>();
    if (_isTunnelBusy(vpn.status)) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Disconnect before changing connection mode.'),
        ),
      );
      return;
    }

    final saved = await ConnectionSettings.setMode(next);
    if (!mounted) return;
    if (!saved) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Could not save connection mode.')),
      );
      return;
    }

    setState(() {
      _mode = next;
      _lastSocksTest = null;
    });
    widget.onChanged?.call(next);
  }

  /// Recovers from an orphaned proxy: tear the runtime down and start a fresh
  /// session so a readable set of credentials exists again. No rewarded ad —
  /// the user is repairing a connection they already paid for, not opening a
  /// new one.
  Future<void> _reconnect() async {
    if (_reconnecting) return;
    final vpn = context.read<VpnConnection>();
    final timer = context.read<SessionTimer>();

    setState(() => _reconnecting = true);
    try {
      await timer.disconnect();
      if (!mounted) return;
      if (await vpn.connect()) {
        if (!mounted) return;
        await timer.start();
      }
    } finally {
      if (mounted) {
        setState(() {
          _reconnecting = false;
          _lastSocksTest = null;
        });
      }
    }
  }

  Future<void> _testLocalSocks() async {
    if (_testingLocalSocks) return;
    final vpn = context.read<VpnConnection>();

    if (vpn.status != VpnStatus.connected || !vpn.canTestActiveLocalSocks) {
      final orphaned =
          vpn.status == VpnStatus.connected && vpn.adoptedRunningRuntime;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            orphaned
                ? 'Credentials were lost when the app restarted. Reconnect first.'
                : 'Connect with SOCKS5 first, then run the local test.',
          ),
        ),
      );
      return;
    }

    setState(() => _testingLocalSocks = true);
    final result = await vpn.testActiveLocalSocks();
    if (!mounted) return;

    setState(() {
      _testingLocalSocks = false;
      _lastSocksTest = result;
    });

    final latency = result.latencyMs == null ? '' : ' (${result.latencyMs} ms)';
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('${result.message}$latency')),
    );
  }

  String get _subtitle {
    switch (_mode) {
      case ConnectionMode.tun:
        return 'TUN: Android VPN routing for normal device traffic.';
      case ConnectionMode.proxy:
        return 'SOCKS5: local authenticated proxy — point apps at 127.0.0.1:<port> with the credentials shown below.';
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ListTile(
          title: const Text(
            'Connection mode',
            style: TextStyle(color: AppColors.textWhite, fontSize: 15),
          ),
          subtitle: Text(
            _subtitle,
            style: const TextStyle(color: AppColors.textDim, fontSize: 12),
          ),
          trailing: DropdownButtonHideUnderline(
            child: DropdownButton<ConnectionMode>(
              value: _mode,
              dropdownColor: AppColors.bgCard,
              onChanged: _changeMode,
              items: const [
                DropdownMenuItem(
                  value: ConnectionMode.tun,
                  child: Text('TUN'),
                ),
                DropdownMenuItem(
                  value: ConnectionMode.proxy,
                  child: Text('SOCKS5'),
                ),
              ],
            ),
          ),
        ),
        if (_mode == ConnectionMode.proxy) ...[
          Consumer<VpnConnection>(
            builder: (context, vpn, _) {
              final session = vpn.activeSocksSession;
              if (session == null) {
                // The proxy can be running while this session is unknown:
                // Android killed the app process, the foreground service kept
                // the listener alive, and its credentials existed only in the
                // memory that died with it. Saying "Connect" there would be a
                // lie — the user is already connected.
                final orphaned = vpn.status == VpnStatus.connected &&
                    vpn.adoptedRunningRuntime;
                if (orphaned) {
                  return Padding(
                    padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'The proxy is still running, but its credentials were '
                          'lost when the app restarted. Reconnect to get a new '
                          'endpoint.',
                          style: TextStyle(
                            color: AppColors.textMuted,
                            fontSize: 12,
                          ),
                        ),
                        const SizedBox(height: 8),
                        OutlinedButton.icon(
                          onPressed: _reconnecting ? null : _reconnect,
                          icon: _reconnecting
                              ? const SizedBox(
                                  width: 16,
                                  height: 16,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Icon(Icons.refresh),
                          label: Text(
                            _reconnecting ? 'Reconnecting…' : 'Reconnect',
                          ),
                        ),
                      ],
                    ),
                  );
                }
                return const Padding(
                  padding: EdgeInsets.fromLTRB(20, 0, 20, 8),
                  child: Text(
                    'Connect to start a local SOCKS5 proxy. Credentials change every session.',
                    style: TextStyle(color: AppColors.textMuted, fontSize: 12),
                  ),
                );
              }
              return Padding(
                padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Proxy: 127.0.0.1:${session.port}',
                      style: const TextStyle(
                        color: AppColors.textMuted,
                        fontSize: 12,
                        fontFamily: 'monospace',
                      ),
                    ),
                    Text(
                      'Username: ${session.username}',
                      style: const TextStyle(
                        color: AppColors.textMuted,
                        fontSize: 12,
                        fontFamily: 'monospace',
                      ),
                    ),
                    Text(
                      'Password: ${session.password}',
                      style: const TextStyle(
                        color: AppColors.textMuted,
                        fontSize: 12,
                        fontFamily: 'monospace',
                      ),
                    ),
                  ],
                ),
              );
            },
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
            child: OutlinedButton.icon(
              onPressed: _testingLocalSocks ? null : _testLocalSocks,
              icon: _testingLocalSocks
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.network_check),
              label: Text(
                _testingLocalSocks ? 'Testing…' : 'Test Local SOCKS',
              ),
            ),
          ),
          if (_lastSocksTest != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 12),
              child: Text(
                _lastSocksTest!.ok
                    ? 'Last test: OK${_lastSocksTest!.latencyMs == null ? '' : ' · ${_lastSocksTest!.latencyMs} ms'}'
                    : 'Last test: Failed · ${_lastSocksTest!.message}',
                style: const TextStyle(
                  color: AppColors.textMuted,
                  fontSize: 12,
                ),
              ),
            ),
        ],
      ],
    );
  }
}
