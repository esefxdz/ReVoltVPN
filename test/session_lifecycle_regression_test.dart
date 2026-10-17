import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

// Source assertions, in the style of release_verification_regression_test.dart.
// The behaviours below were all found broken on a device and fixed on
// 2026-10-17 (internal/DEVICE_TEST_2026-10-17.md). None of them can be covered
// by a Dart unit test - they live in the vendored Android runtime, and the one
// that does not needs a live tunnel - but every one of them regresses silently
// and looks like tidying when it does. Pin the shape instead of the behaviour.

const _runtime = 'local_packages/flutter_vless_android-1.1.5/android/src/main/'
    'kotlin/com/github/tfox/flutter_vless/xray';

void main() {
  test('Xray core recovery survives running off a bare thread', () {
    final core = File('$_runtime/core/XrayCoreManager.kt').readAsStringSync();

    // startTimer builds a CountDownTimer, which builds a Handler. Recovery runs
    // on a plain Thread, so constructing it inline threw Looper.prepare() and
    // killed every restart attempt.
    expect(core, contains('Handler(Looper.getMainLooper())'));
    expect(core, contains('runOnMain'));
    expect(
      core,
      contains('runOnMain { startTimerOnMain(context, token) }'),
      reason: 'startTimer must not construct the CountDownTimer inline',
    );

    // The post must not outlive the generation that scheduled it.
    expect(core, contains('if (AppConfigs.V2RAY_CONFIG?.RUNTIME_TOKEN != token) return'));

    // pb.start() succeeds before the throw, so the catch has to kill the
    // process or each retry strands another live core.
    expect(core, contains('val orphan = xrayProcess'));
    expect(
      core.indexOf('xrayProcess = null'),
      lessThan(core.indexOf('orphan?.destroy()')),
      reason: 'clear the field before destroying, so the monitor thread\'s '
          'identity guard fails and recovery is not re-entered',
    );
  });

  test('a failed Xray core still reports why, without logging destinations', () {
    final core = File('$_runtime/core/XrayCoreManager.kt').readAsStringSync();

    expect(core, contains('STARTUP_LOG_WINDOW_MS'));
    expect(core, contains('STARTUP_LOG_MAX_LINES'));
    expect(
      core,
      isNot(contains('reader.forEachLine { _ -> }')),
      reason: 'discarding the core output leaves exit 23 undiagnosable',
    );
    // Rule 95: a core that has been up long enough to carry traffic must not
    // have its log retained.
    expect(core, contains('startupLog.clear()'));
  });

  test('the expiry warning has the same backstop as the deadline', () {
    final service = File('$_runtime/service/XrayVPNService.kt').readAsStringSync();

    // The alarm alone is inexact on purpose (no SCHEDULE_EXACT_ALARM) and Doze
    // deferred it past the end of the session it was warning about.
    expect(service, contains('sessionWarningRunnable'));
    expect(
      service,
      contains('deadlineHandler.postDelayed(sessionWarningRunnable, warnDelay)'),
      reason: 'without this the warning never arrives when the app is closed',
    );
    expect(
      service,
      contains('deadlineHandler.removeCallbacks(sessionWarningRunnable)'),
      reason: 'a cancelled warning must drop its Handler callback too',
    );
    expect(service, isNot(contains('SCHEDULE_EXACT_ALARM"')));
  });

  test('the warning promises neither a number of minutes nor an ad', () {
    final alerts = File('$_runtime/utils/RevoltAlerts.kt').readAsStringSync();

    // It is accepted anywhere from WARNING_FLOOR_MS to LEAD + SLACK remaining,
    // so a figure can be off by minutes; and the support bypass is still
    // kDebugMode-gated (rule 87), so an ad is a dead end in release.
    expect(alerts, isNot(contains('disconnects in 5 minutes')));
    expect(alerts, isNot(contains('Watch an ad')));
    expect(alerts, contains('Revolt VPN disconnects soon'));
  });

  test('the countdown notification is taken down when the tunnel stops', () {
    final service = File('lib/logic/notification_service.dart').readAsStringSync();
    final timer = File('lib/logic/session_timer.dart').readAsStringSync();
    final activity =
        File('android/app/src/main/kotlin/com/paladinvpn/app/MainActivity.kt')
            .readAsStringSync();

    // updateTimer re-posts an ongoing, non-dismissible notification every tick,
    // so a tick after the service stops strands a frozen countdown.
    expect(service, contains('cancelTimer'));
    expect(activity, contains('"cancelNotification"'));
    expect(timer, contains('await NotificationService.cancelTimer()'));

    // Must run after the service is down, or the platform swallows the cancel.
    expect(
      timer.indexOf('await vpnConnection.disconnect();'),
      lessThan(timer.indexOf('await NotificationService.cancelTimer()')),
    );
  });

  test('only a user-requested disconnect hands the session back', () {
    final timer = File('lib/logic/session_timer.dart').readAsStringSync();
    final hivemind = File('lib/logic/hivemind_service.dart').readAsStringSync();

    expect(hivemind, contains('releaseSession'));
    expect(hivemind, contains("_publicUrl('/session/stop')"));

    // /session/stop takes a device_id as its only proof of ownership, so it
    // must never be reachable from an error path. disconnect() is called only
    // by the connect button; _doDisconnect is every other ending.
    final disconnectEntry = timer.indexOf('Future<void> disconnect(');
    final doDisconnect = timer.indexOf('Future<void> _doDisconnect(');
    final release = timer.indexOf('HivemindService.releaseSession()');
    expect(release, greaterThan(disconnectEntry));
    expect(
      release,
      lessThan(doDisconnect),
      reason: 'releaseSession belongs in disconnect(), not _doDisconnect',
    );
    expect(
      'HivemindService.releaseSession()'.allMatches(timer).length,
      1,
      reason: 'exactly one call site',
    );
  });

  test('SOCKS5 Reconnect re-attaches instead of handing the session back', () {
    final timer = File('lib/logic/session_timer.dart').readAsStringSync();
    final mode =
        File('lib/screens/settings/in_settings/connection_mode.dart')
            .readAsStringSync();

    // Reconnect repairs an orphaned proxy by re-attaching to the session the
    // device already holds. Releasing it on the way out would destroy the very
    // session the follow-up connect(resume: true) then asks for.
    expect(timer, contains('bool release = true'));
    expect(timer, contains('if (release) unawaited(HivemindService.releaseSession())'));
    expect(mode, contains('timer.disconnect(release: false)'));
    expect(mode, contains('vpn.connect(resume: true)'));

    // And it must not hand back a second support top-up for the same session.
    expect(mode, contains('timer.start(resetSupportClaim: false)'));
  });
}
