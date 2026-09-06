import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

enum ConnectionMode { tun, proxy }

abstract final class ConnectionSettings {
  ConnectionSettings._();

  static const String _modeKey = 'connection_mode';

  static ConnectionMode _mode = ConnectionMode.tun;
  static bool _initialized = false;

  static ConnectionMode get mode => _mode;

  static Future<void> initialize() async {
    if (_initialized) return;

    final prefs = await SharedPreferences.getInstance();
    final storedMode = prefs.getString(_modeKey);

    // Only the explicit current proxy value restores SOCKS5. Unknown and
    // obsolete values, including the pre-V3 'auto' label, fail closed to
    // full-tunnel TUN instead of silently selecting a weaker route.
    _mode = storedMode == ConnectionMode.proxy.name
        ? ConnectionMode.proxy
        : ConnectionMode.tun;

    if (storedMode != _mode.name) {
      // Best-effort. _mode is already the safe TUN default in memory, so a
      // failed write leaves nothing insecure — it only means the migration
      // is retried next launch. initialize() runs from main() before
      // runApp(), with no zone guard, so throwing here would black-screen
      // the app on the fresh-install path for no security gain.
      final migrated = await prefs.setString(_modeKey, _mode.name);
      if (!migrated) {
        debugPrint('[ConnectionSettings] Could not persist migrated mode.');
      }
    }

    _initialized = true;
  }

  static Future<bool> setMode(ConnectionMode mode) async {
    if (_initialized && _mode == mode) return true;

    final prefs = await SharedPreferences.getInstance();
    final saved = await prefs.setString(_modeKey, mode.name);
    if (!saved) return false;

    _mode = mode;
    _initialized = true;
    return true;
  }
}
