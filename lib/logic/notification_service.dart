import 'package:flutter/services.dart';

// ── lib/logic/notification_service.dart ─────────────────────────────────────
// Bridge to the native VPN notification, which flutter_vless owns. This only
// refreshes its content text with the session countdown, so a failure here is
// never worth propagating to the session timer.

class NotificationService {
  NotificationService._();

  static const MethodChannel _channel =
      MethodChannel('com.revoltvpn.app/notification');

  static String? _lastText;

  /// Sets the notification's content text to [text].
  ///
  /// The native side rebuilds the whole notification, so skip the round-trip
  /// when the text has not changed. A failed native update is intentionally
  /// not cached so the next tick can retry it.
  static Future<void> updateTimer(String text) async {
    if (text == _lastText) return;
    try {
      await _channel.invokeMethod<void>('updateNotificationText', {
        'title': 'Revolt VPN',
        'text': text,
        'disconnectLabel': 'Disconnect',
      });
      _lastText = text;
    } catch (_) {
      // Cosmetic.
    }
  }

  // Same id as the service's own alert, so a double post replaces.
  static Future<void> showDisconnectedAlert() async {
    try {
      await _channel.invokeMethod<void>('showDisconnectedAlert');
    } catch (_) {
      // Informational only.
    }
  }

  /// Forces the next connect to re-post even if the countdown resumes on the
  /// same value.
  static void reset() => _lastText = null;
}
