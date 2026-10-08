import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

class LocalSocksTestResult {
  final bool ok;
  final int? latencyMs;
  final String message;

  const LocalSocksTestResult({
    required this.ok,
    required this.latencyMs,
    required this.message,
  });
}

abstract final class LocalSocksTester {
  LocalSocksTester._();

  /// Fast local readiness probe used while starting the VPN.
  ///
  /// This stops after authenticated SOCKS5 negotiation. Startup must not depend
  /// on an unrelated external host being reachable.
  static Future<LocalSocksTestResult> testListener({
    String host = '127.0.0.1',
    required int port,
    required String username,
    required String password,
  }) async {
    final stopwatch = Stopwatch()..start();
    Socket? socket;
    _SocketReader? reader;

    try {
      socket = await Socket.connect(
        host,
        port,
        timeout: const Duration(seconds: 1),
      );
      socket.setOption(SocketOption.tcpNoDelay, true);
      reader = _SocketReader(socket);

      final authenticated = await _authenticate(
        reader,
        socket,
        username: username,
        password: password,
        timeout: const Duration(seconds: 1),
      );
      if (!authenticated) {
        return const LocalSocksTestResult(
          ok: false,
          latencyMs: null,
          message: 'Local SOCKS5 listener rejected authentication.',
        );
      }

      stopwatch.stop();
      return LocalSocksTestResult(
        ok: true,
        latencyMs: stopwatch.elapsedMilliseconds,
        message: 'Local SOCKS5 listener is ready.',
      );
    } on TimeoutException {
      return const LocalSocksTestResult(
        ok: false,
        latencyMs: null,
        message: 'Local SOCKS5 listener timed out.',
      );
    } on SocketException {
      return LocalSocksTestResult(
        ok: false,
        latencyMs: null,
        message: 'Local SOCKS5 is not listening on $host:$port.',
      );
    } catch (_) {
      return const LocalSocksTestResult(
        ok: false,
        latencyMs: null,
        message: 'Local SOCKS5 listener check failed.',
      );
    } finally {
      await reader?.close();
      await socket?.close();
    }
  }

  /// Full user-facing test: authenticated local SOCKS handshake, an outbound
  /// CONNECT through the active ReVolt route, and — critically — a real
  /// request/response exchange over that CONNECT.
  ///
  /// The exchange is not optional politeness. Xray answers a SOCKS CONNECT
  /// before it dials upstream, so the reply code alone says nothing about the
  /// outbound: it returns success for unroutable addresses and for hostnames
  /// that can never resolve. Without a round trip this test reports OK while
  /// the server is down, which is precisely when a user runs it.
  static Future<LocalSocksTestResult> test({
    String host = '127.0.0.1',
    required int port,
    required String username,
    required String password,
    String targetHost = 'example.com',
    int targetPort = 80,
  }) async {
    const totalTimeout = Duration(seconds: 8);
    final stopwatch = Stopwatch()..start();
    Socket? socket;
    _SocketReader? reader;

    Duration remaining() {
      final micros = totalTimeout.inMicroseconds - stopwatch.elapsedMicroseconds;
      if (micros <= 0) throw TimeoutException('SOCKS5 test timed out.', totalTimeout);
      return Duration(microseconds: micros);
    }

    try {
      if (targetPort <= 0 || targetPort > 65535) {
        return const LocalSocksTestResult(
          ok: false,
          latencyMs: null,
          message: 'SOCKS5 test target is invalid.',
        );
      }

      socket = await Socket.connect(
        host,
        port,
        timeout: remaining(),
      );
      socket.setOption(SocketOption.tcpNoDelay, true);
      reader = _SocketReader(socket);

      final authenticated = await _authenticate(
        reader,
        socket,
        username: username,
        password: password,
        timeout: remaining(),
      );
      if (!authenticated) {
        return const LocalSocksTestResult(
          ok: false,
          latencyMs: null,
          message: 'Local SOCKS5 listener rejected authentication.',
        );
      }

      final hostBytes = utf8.encode(targetHost);
      if (hostBytes.isEmpty || hostBytes.length > 255) {
        return const LocalSocksTestResult(
          ok: false,
          latencyMs: null,
          message: 'SOCKS5 test target is invalid.',
        );
      }

      socket.add(<int>[
        0x05,
        0x01,
        0x00,
        0x03,
        hostBytes.length,
        ...hostBytes,
        (targetPort >> 8) & 0xff,
        targetPort & 0xff,
      ]);
      await socket.flush();

      final replyHead = await reader.readExactly(4, timeout: remaining());
      if (replyHead[0] != 0x05 || replyHead[1] != 0x00) {
        return const LocalSocksTestResult(
          ok: false,
          latencyMs: null,
          message:
              'SOCKS5 is listening, but the ReVolt route could not reach the test target.',
        );
      }

      await _consumeAddress(reader, replyHead[3], timeout: remaining());

      // The CONNECT reply above is not evidence of an outbound. Force a round
      // trip and require the peer's answer before claiming the route works.
      socket.add(
        utf8.encode(
          'GET / HTTP/1.1\r\nHost: $targetHost\r\nConnection: close\r\n\r\n',
        ),
      );
      await socket.flush();

      List<int> banner;
      try {
        banner = await reader.readExactly(5, timeout: remaining());
      } on TimeoutException {
        return const LocalSocksTestResult(
          ok: false,
          latencyMs: null,
          message: 'Local SOCKS5 is up, but the ReVolt outbound never replied.',
        );
      } on SocketException {
        return const LocalSocksTestResult(
          ok: false,
          latencyMs: null,
          message:
              'Local SOCKS5 is up, but the ReVolt outbound dropped the connection.',
        );
      }

      if (String.fromCharCodes(banner) != 'HTTP/') {
        return const LocalSocksTestResult(
          ok: false,
          latencyMs: null,
          message:
              'Local SOCKS5 is up, but the ReVolt outbound returned unexpected data.',
        );
      }

      stopwatch.stop();
      return LocalSocksTestResult(
        ok: true,
        latencyMs: stopwatch.elapsedMilliseconds,
        message: 'Local SOCKS5 and the ReVolt outbound are reachable.',
      );
    } on TimeoutException {
      return const LocalSocksTestResult(
        ok: false,
        latencyMs: null,
        message: 'Local SOCKS5 test timed out.',
      );
    } on SocketException {
      return LocalSocksTestResult(
        ok: false,
        latencyMs: null,
        message: 'Local SOCKS5 is not listening on $host:$port.',
      );
    } catch (_) {
      return const LocalSocksTestResult(
        ok: false,
        latencyMs: null,
        message: 'Local SOCKS5 test failed.',
      );
    } finally {
      await reader?.close();
      await socket?.close();
    }
  }

  static Future<bool> _authenticate(
    _SocketReader reader,
    Socket socket, {
    required String username,
    required String password,
    Duration timeout = const Duration(seconds: 4),
  }) async {
    final userBytes = utf8.encode(username);
    final passBytes = utf8.encode(password);
    if (userBytes.isEmpty ||
        passBytes.isEmpty ||
        userBytes.length > 255 ||
        passBytes.length > 255) {
      return false;
    }

    // ReVolt never accepts unauthenticated local SOCKS. Advertising only RFC
    // 1929 username/password auth makes a future credential-plumbing regression
    // fail closed instead of silently falling back to method 0x00.
    socket.add(const <int>[0x05, 0x01, 0x02]);
    await socket.flush();

    final greeting = await reader.readExactly(2, timeout: timeout);
    if (greeting[0] != 0x05 || greeting[1] != 0x02) return false;

    socket.add(<int>[
      0x01,
      userBytes.length,
      ...userBytes,
      passBytes.length,
      ...passBytes,
    ]);
    await socket.flush();

    final authReply = await reader.readExactly(2, timeout: timeout);
    return authReply[0] == 0x01 && authReply[1] == 0x00;
  }

  static Future<void> _consumeAddress(
    _SocketReader reader,
    int addressType, {
    required Duration timeout,
  }) async {
    if (addressType == 0x01) {
      await reader.readExactly(4 + 2, timeout: timeout);
      return;
    }
    if (addressType == 0x04) {
      await reader.readExactly(16 + 2, timeout: timeout);
      return;
    }
    if (addressType == 0x03) {
      final length = (await reader.readExactly(1, timeout: timeout)).first;
      if (length == 0) {
        throw const FormatException('Invalid SOCKS5 domain length');
      }
      await reader.readExactly(length + 2, timeout: timeout);
      return;
    }
    throw const FormatException('Unsupported SOCKS5 address type');
  }
}

class _SocketReader {
  final StreamIterator<Uint8List> _iterator;
  final List<int> _buffer = <int>[];

  _SocketReader(Socket socket) : _iterator = StreamIterator<Uint8List>(socket);

  Future<List<int>> readExactly(
    int count, {
    Duration timeout = const Duration(seconds: 4),
  }) async {
    final elapsed = Stopwatch()..start();
    while (_buffer.length < count) {
      final remainingMicros = timeout.inMicroseconds - elapsed.elapsedMicroseconds;
      if (remainingMicros <= 0) {
        throw TimeoutException('Socket read timed out', timeout);
      }
      final hasNext = await _iterator.moveNext().timeout(
            Duration(microseconds: remainingMicros),
          );
      if (!hasNext) {
        throw const SocketException('Socket closed early');
      }
      _buffer.addAll(_iterator.current);
    }

    final result = _buffer.sublist(0, count);
    _buffer.removeRange(0, count);
    return result;
  }

  Future<void> close() => _iterator.cancel();
}
