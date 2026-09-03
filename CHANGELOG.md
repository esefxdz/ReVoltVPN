# Changelog

All notable changes to the ReVoltVPN Flutter app.
Versions follow [semver](https://semver.org/): MAJOR.MINOR.PATCH

---

## [3.3.5] — 2026-09-03

### Added
- **OS-level session-expiry enforcement.** The native VPN service now enforces the
  server deadline with both a monotonic in-process handler and `AlarmManager`, so
  expiry does not depend on the Flutter UI process staying alive.
- Only the non-secret runtime generation token and absolute expiry timestamp are
  persisted app-private for the OS alarm. Per-session VLESS UUID/SOCKS credentials
  remain memory-only; if Android cannot redeliver the original start intent, the
  runtime fails closed instead of reconstructing a tunnel from secrets on disk.
- A successful support reward now requests an immediate control-plane refresh so the
  extended deadline is pushed to the native service without waiting for the next poll.

### Fixed
- **Session clock reattach race.** `SessionTimer` is eager and evaluates the current
  VPN state once after construction, so a UI process attaching to an already-live
  tunnel no longer leaves the countdown at `00:00:00`.
- Forced support sync no longer disappears behind an already-running periodic sync;
  it waits for the active request and then performs a fresh control-plane refresh.
- VPN permission requests now fail with `NO_ACTIVITY` during Android activity detach
  instead of dereferencing a null Activity.
- Receiver cleanup logs lifecycle races instead of silently swallowing them.
- An early/stale session-expiry alarm no longer leaves an otherwise unused service
  process running.

### Changed
- Unknown/obsolete persisted connection-mode labels, including the old `ass`/`auto`
  values, now fail closed to TUN. Only the explicit current `proxy` value restores
  SOCKS5 mode.
- App version is `3.3.5+31`.

### Security
- Existing AdMob bypass/callback behavior is intentionally unchanged.
- Build-time mutation of the global Flutter pub cache remains removed; the hardened
  `flutter_vless_android` 1.1.5 source remains vendored and immutable during builds.
- Full IPv4/IPv6 TUN routing, fail-closed DNS/TUN setup, authenticated ephemeral
  loopback SOCKS5, generation-scoped STOP/state handling, production config pinning,
  dependency verification, R8 gates and updater host allowlists remain in force.

---

## [3.3.4] — 2026-09-02

### Added
- **OS-level session-expiry enforcement.** The VPN service now holds an absolute
  deadline and tears the tunnel down on its own clock — a main-looper handler
  plus an `AlarmManager` exact alarm (falling back to an inexact alarm on
  Android 13+ when exact alarms aren't granted). The app pushes
  `now + expires_in_seconds` on each session sync, so a session ends even if the
  UI process has been killed.
- **Session survival across process death.** The service persists the active
  session and re-establishes it when Android restarts it (`START_STICKY`).
- **Support-ad extension reflected immediately.** A rewarded "Support us" ad now
  forces an immediate server sync, so the countdown and expiry deadline update
  without waiting for the next heartbeat.

### Changed
- **Session-status beacon slowed.** Foreground poll: 5 s → 60 s; background poll:
  30 s → 60 s.
- **Background-reliability guidance.** The "Always-on VPN" settings deep-link was
  removed; the battery-optimisation exemption remains, with expiry now enforced
  by the service itself.

### Removed
- Legacy `ass` connection-mode migration — unrecognized persisted modes now
  default to TUN.

### Security & privacy
- Session credentials and the per-user VLESS UUID are now held app-private on
  disk (`active_session.bin`) for the session's lifetime and removed on
  disconnect or expiry. Previously they were memory-only.

---

## [3.3.3] — 2026-09-01

### Fixed
- The session timer now resumes when the Flutter process attaches to a tunnel that was
  already connected before the timer listener existed. This also prevents the
  foreground countdown from freezing after UI-process recreation.

## [3.3.2] — 2026-09-01

Background reliability. 3.3.1 could show "connected" against a tunnel that was no
longer running; this release fixes the cause rather than the symptom, and removes the
machinery that existed to work around it.

### Added
- **Background reliability settings.** Android stops background services of apps that
  are not exempt from Doze, and tears down the process group when a task is swiped
  away. Two OS mechanisms prevent that, and the app now surfaces both: a one-tap
  **battery optimisation exemption**, and a deep link to Android's **Always-on VPN**
  settings (with a note about "Block connections without VPN"). Neither can be enabled
  programmatically — each needs a single user tap, once.

### Fixed
- **The app could report "connected" with no traffic flowing.** Startup state came from
  `getConnectedServerDelay()`, which measures through
  `AppConfigs.V2RAY_CONFIG?.LOCAL_SOCKS5_PORT ?: 10807`. `AppConfigs` lives in the VPN's
  own process, so from the UI process it is always null and the port always fell back to
  10807. That was accidentally correct while the SOCKS port was fixed; 3.3.1's
  per-session ephemeral ports made it permanently wrong. Liveness is now determined from
  the OS — whether our VPN service process is running, and whether a VPN transport is
  actually present.
- **A frozen countdown could outlive the tunnel.** The foreground notification (id 1) is
  owned by the VPN service via `startForeground`, but the UI process was posting to the
  same id. When that process died, its notification stayed on screen with nothing left to
  update it. The app no longer posts for a runtime that is not alive, and clears a stale
  notification instead.

### Removed
- **Standard/Extreme resilience modes** and the runtime-restart machinery behind them.
  Recovery ran off a disconnect broadcast delivered to the app process, so it could never
  fire in the case that actually matters — Android killing that process. It was
  complexity substituting for background persistence.
- **Startup restoration**, including the config-snapshot rebuild. With the tunnel kept
  alive by the OS, there is nothing to restore; the app simply reports what is running.
- The `resilience_mode` preference and its settings tile.

## [3.3.1] — 2026-08-31

### Added
- **Explicit TUN and SOCKS5 connection modes.** The previous Auto mode is gone, so
  routing is chosen deliberately instead of inferred at runtime. TUN is the default.
  SOCKS5 runs a local authenticated proxy with no VPN interface, and therefore never
  requests the Android VPN permission.
- **Authenticated per-session Local SOCKS5.** Every connection binds an ephemeral
  loopback port and mints fresh 16-byte username / 32-byte password credentials.
  Android loopback is reachable by every app on the device, so this authentication is
  load-bearing rather than decorative. Credentials stay in memory. Imported
  `socks`/`http` inbounds are dropped so no unauthenticated listener can survive
  alongside the session one.
- **Network resilience profiles** — Standard and Extreme. Extreme restarts a failed
  Xray/tun2socks runtime up to twice before giving up.
- **Android physical-network monitoring**, filtered on `NET_CAPABILITY_NOT_VPN` so the
  VPN's own network cannot feed back a reconnect loop. Informational only; the active
  transport (Wi-Fi / cellular / ethernet) now appears in the status bar.
- **Local SOCKS5 diagnostics.** "Test Local SOCKS" authenticates against the exact
  active session over loopback — a readiness handshake plus a full CONNECT — instead
  of checking whether a fixed port accepts connections.
- Native Android bridges for network state, haptic feedback and install-source
  detection.

### Fixed
- **Extreme recovery now survives an app-process restart.** Android can kill the Dart
  process while the VPN foreground service keeps running, leaving the app attached to
  a live tunnel it holds no configuration for — so recovery had nothing to restart
  from. In TUN mode the app rebuilds that snapshot from the still-active session,
  without opening a new session, showing an ad, or interrupting traffic.
- **The proxy settings tile no longer misreports state after a process restart.** Its
  credentials genuinely cannot be recovered — they are random, generated on device and
  never transmitted — so instead of telling an already-connected user to "Connect", it
  now says the credentials were lost and offers a Reconnect action.
- **The first tap on the connect button was ignored.** The debounce timestamp was
  initialized to the current time instead of the epoch, so the first press was always
  swallowed.
- **The health probe polled `/health` every 30 s even while connected.** Restored to
  idle-only — a live tunnel already proves reachability, and the extra direct requests
  were unwanted.
- **A user reconnect could race an in-flight Extreme recovery.** A connection
  generation counter now aborts stale recovery attempts.
- Recoverable Xray/tun2socks startup and readiness failures no longer follow the
  unstable self-stop path used during development.
- Returning from Android background state reconciles the session countdown against
  elapsed wall-clock time and re-syncs immediately, reducing stale countdowns and false
  `Syncing…` presentation after Dart throttling.
- Sidebar update checks retain valid navigator/messenger handles after the drawer
  closes.

### Changed
- **The pinned `flutter_vless_android` 1.1.5 runtime is patched at build time** instead
  of vendored. Three idempotent patchers are chained into `preBuild`, so a plain
  `flutter build apk` behaves like CI rather than depending on a manual step against
  the pub cache. They are fail-closed: an upstream change breaks the build loudly
  instead of silently producing an unpatched runtime.
- Legacy `auto` preferences migrate to TUN; legacy `ass` preferences migrate to SOCKS5.
- Haptic feedback is a single confirmation on connect/disconnect, routed through the
  native Android vibration channel, and **defaults to off**.
- The VPN notification uses a dedicated 24 dp status icon and stays ongoing/no-clear
  while the foreground service is alive.
- Hivemind session parsing is split into typed validation steps; a malformed port now
  fails instead of silently falling back.
- The update checker parses semver with prerelease ordering and validates release URLs
  against an HTTPS host allowlist.
- The support button shows a claimed/disabled state once the session bonus is used.
- The status bar distinguishes `Ready` from `Syncing…` and folds in server
  reachability.
- Gradle release heap raised to 4 GiB with `MaxMetaspaceSize=1g` and `workers.max=2`,
  so Flutter/Jetifier transforms do not fail on the release classpath under constrained
  runners.
- App version is now `3.3.1+27`.

### Removed
- **Per-app routing**, including the app picker, the installed-apps native bridge and
  the blocked-apps mechanism. It only ever functioned in TUN — the underlying
  `addDisallowedApplication` call lives inside the VPN-interface setup that SOCKS5 mode
  skips entirely — and its allowlist half was never wired to any caller. Removed rather
  than finished.
- The legacy `auto` connection mode.
- `flutter_local_notifications`, and the unused notification manager it backed.
- A global touch listener that fired a haptic on every tap.
- Unused runtime telemetry fields.

### Security & privacy
- **Session metadata is hidden from public lock-screen previews.** The live foreground
  notification is built with `VISIBILITY_PRIVATE` plus a generic public version, so the
  countdown and speed are visible only after unlock. *(An earlier attempt at this
  landed in an unreferenced class and never took effect.)*
- Local SOCKS5 listens only on `127.0.0.1`, requires per-session authentication, and
  its credentials are neither persisted nor logged.
- The app requests no package visibility at all. `QUERY_ALL_PACKAGES` was never used,
  and the launcher-enumeration query was removed along with the app picker.
- Session nonces use `Random.secure()` and are never logged.
- Device UUIDs are validated as v4 and regenerated if corrupt, so tampered identifiers
  cannot reach the API.
- `FOREGROUND_SERVICE_SPECIAL_USE` is declared for Android 14+; the merged service
  declaration carries the `vpn` special-use subtype.
- Existing AdMob bypass/callback behavior is intentionally unchanged by this release.
- Server, nginx and Reality configuration are not modified by this client-side release.

### Validation
- `flutter analyze` — 0 errors, 0 warnings, 7 style infos (6 of them pre-existing).
- All three build-time patchers verified against a clean pub cache, including that each
  applies independently of the others.
- **Not yet verified:** Android release Kotlin compilation and APK build.

## [3.3.0] — 2026-08-30

### Added
- Session countdown in the VPN notification, updated each second while connected.

### Fixed
- **Session-expiry desync** — the app could show "Online" with no internet after
  the server ended the session. The countdown no longer freezes when a poll fails.
- **Notification Disconnect button** disappeared a second after connecting.
  Re-posting the foreground notification replaces it wholesale, so the action,
  colour and tap-intent flutter_vless sets are now rebuilt rather than dropped.
- **Speed readout** divided the byte delta by the nominal poll interval instead of
  the real time between polls — wrong by up to 6x around every background switch.
- **Settings toggles** now apply at launch. Rain, lightning and haptics were only
  read from disk when Settings mounted, so an effect turned off came back — ticker
  and all — on every relaunch, and haptic feedback did nothing until Settings had
  been opened at least once.
- **Haptic feedback** now fires `mediumImpact()` instead of `lightImpact()` (which
  is imperceptible on many Android devices), wrapped best-effort so a platform
  vibration failure cannot fail a connect/disconnect, with a preview tap when the
  toggle is switched on.
- First-launch disclosure dialog said data was linked to "your account". It is
  linked to a randomly generated device ID, and the policy is in the sidebar, not
  on a website.
- Sidebar "Settings" and "Check for updates" used the drawer's context after
  `Navigator.pop()` — a defunct context. They now capture the root navigator (and
  messenger) before closing the drawer. *(Fix ported from Naveax's review.)*

### Performance
- Rain overlay ticks only while enabled, repaints through a scoped `Listenable`
  instead of rebuilding every frame, and precomputes drop colours.
- Lightning no longer fires strikes while disabled.
- Connect button pulse pauses when backgrounded; brand image hoisted out of the
  per-frame rebuild.
- Server poll drops to 30 s while backgrounded (5 s foreground), with an immediate
  re-sync on resume. The 1 s countdown keeps running.
- Health probe runs only while disconnected, and pauses in the background.
- Speed and support widgets rebuild on value change instead of every second.
- Notification channel is created once per process, not once per second.

### Changed
- VLESS engine initializes under the splash screen; the intro waits on
  `VpnConnection.ready` (8 s cap) before showing the main screen.
- AdMob SDK init moved off the blocking pre-`runApp()` path so startup cannot hang
  on an unreachable Google endpoint.
- `app_config.example.dart` drops the dead bootstrap fields and documents
  `hivemindApiPublic` in their place.
- `.gitignore` reorganized around type and directory rules; the enumerated
  filename list is gone (redundant with the type rules) and `.claude/` is now
  ignored.

### Security
- Update checker pins the release URL to this repo's path on `github.com`, not just
  the host. Anything else falls back to the releases page.
- Notification timer updates are gated on a live tunnel, so a late tick cannot leave
  an ongoing notification with no foreground service behind it.
- Dropped `READ_EXTERNAL_STORAGE`, which `flutter_vless` pulls in but the app never uses
  (least privilege).
- Pinned the Gradle 8.14 distribution SHA-256; `validateDistributionUrl` enabled
  (supply-chain integrity).

### Build & tooling (ported from Naveax)
- Removed the deprecated `android.enableR8` flag.
- Added `analysis_options.yaml` (flutter_lints, `local_packages/**` excluded, `avoid_print`).
- Added `.gitattributes` to pin LF line endings repo-wide.
- Credit: reviewed and ported from **Naveax**.

### Docs
- Privacy policy: disclosed that the server sees the connecting IP, corrected the
  "stored in memory" claim, and documented consent being changeable from the sidebar.

## [3.2.2] — 2026-08-08

### Fixed
- Updated Discord invite link.

## [3.2.1] — 2026-08-08

### Google Play compliance
- Added first-launch data disclosure dialog ("Before you connect") explaining
  that connection duration and data usage are logged for quota enforcement, and
  that traffic content is never monitored.

## [3.2.0] — 2026-08-08

### DPI hardening
- Spoof Chrome-on-Android User-Agent on all API calls instead of `Dart/3.x`.
- Renamed domain from `getrevolt.app` to `userevolt.app`.

### Branding
- App name changed from "REVOLT VPN" to "Revolt VPN" across manifest, splash,
  notification, and VLESS URL remark.

### Notification
- Removed duplicate `flutter_local_notifications` notification. Only the
  Android VPN foreground-service notification remains — non-swipeable.
- Patched `flutter_vless` native code to use custom notification icon
  (`@drawable/notification_icon`) with dark background (`#0D1117`).

### Connection reliability
- Merged five separate disconnect paths into a single idempotent
  `_doDisconnect()` gate. Eliminated race between timer ticks and VPN
  teardown where the countdown could briefly show -1.
- `stopVless()` timeout (5 s) now correctly shows an error instead of
  silently pretending the VPN disconnected while the tunnel was still alive.

### AdMob bypass
- `kDebugMode` guard removed — the debug AdMob callback now fires in release
  builds so release APKs can create sessions without real ads during testing.
  The real gate is `ADMOB_BYPASS` on the server.

### Fixed
- **Notification icon:** Replaced five density-specific `ic_launcher.png`
  files with `notification_icon.png`, then reverted to avoid changing the
  home-screen icon. The custom icon is now injected through flutter_vless's
  `initializeVless()` parameters.

## [3.1.0] — 2026-08-06

### Architecture — domain-free client
- **Zero domain strings in APK.** RKN has nothing to DNS-block. API calls go
  through the Reality tunnel to `10.254.254.1:5000`, redirected to Flask by
  Xray's `api` freedom outbound (`redirect: 127.0.0.1:5000`).
- **Bootstrap tunnel.** Bundled Reality config in APK → connect → fetch real
  per-session VLESS URL from inside the tunnel → reconnect. Bootstrap client
  permanently in `xray_config_reality.json`, restricted by Xray routing to
  internal API only.
- `serverDomain` removed from `AppConfig`. `fetchConfigThroughTunnel()`
  replaces `fetchConfigWithPolling()`. `connect()` rewritten.

### Protocol — TCP to XHTTP
- Transport: TCP → XHTTP (H2 stream-up with Reality). No more XTLS-Vision,
  no `flow` field. Path-hidden behind `/revolt`.

### Fixed
- **RenderFlex overflow** — status bar text wrapped in `Flexible` with
  `TextOverflow.ellipsis` to prevent overflow on narrow screens (≤360dp).
- **Circular import** — removed `import 'package:revoltvpn/main.dart'` from
  `sidebar_drawer.dart`. `SidebarDrawer` converted to `StatefulWidget`;
  version footer loaded via `PackageInfo.fromPlatform()`.
- Client hot-reload was silently broken — `xray api adi` no-op. Fixed:
  `adu`/`rmu` commands per Xray HandlerService spec.
- Missing XHTTP `path` param in VLESS URL — now reads `xhttp_path` from
  `/session/status`.
- `providerBundleIdentifier` was `com.revoltvpn.app` — must match
  `com.paladinvpn.app` for VPN service registration.
- Reality keypair generated (was placeholder). Throttle `uplinkOnly`/`downlinkOnly`
  corrected (seconds, not bytes/sec). Fallback rate limits removed (fingerprint).

### Added
- **Settings screen** — reached from a new sidebar row (between Website and
  Check for updates). Slide-from-right navigation via `PageRouteBuilder`.
  Folder: `lib/screens/settings/` with one file per feature in `in_settings/`.
- **Haptic feedback toggle** — on/off switch in Settings for `lightImpact()`
  vibration on successful connect/disconnect. Default: off. Uses sync-bool
  pattern (no await on tap, no SharedPreferences in connect button).
- **Rain/Lightning effect toggles** — two switches in Settings to control
  the animated background effects. Default: on. Effects detect the toggle
  within one frame (no Provider/notifier needed).

### Changed
- **Timer box spinner removed** — the "Connecting…"/"Syncing…" spinner under
  the countdown was redundant with the connect button's spinner. Timer card
  is now a clean countdown only (gray '00:00:00' when idle).
- **Sidebar** now has 7 rows (added Settings). `FRONTEND_POLISH_REVIEW.md`
  fully triaged: 4 items fixed, 11 skipped.

### Server
- `api` outbound + 3 routing rules in `xray_config_reality.json` (bootstrap
  isolation + API routing). Permanent bootstrap client. Hivemind unchanged.
- `xhttp_path` in `/session/status` response.
- Full deployment guide: `SERVER_DEPLOYMENT.md`.

---

## [2.0.7] — 2026-07-31

### Fixed
- **Notification bug** — `SessionTimer.stop()` now cancels the persistent
  Android notification on user-initiated disconnect.
- **Concurrent sync guard** — `_syncInProgress` flag prevents duplicate
  HTTP requests from overlapping in `_syncWithHivemind()`.
- **Debug bypass gate** — `AppConfig.enableAdBypass` replaces `kDebugMode`
  for fake AdMob callback. Tree-shaken in release builds.

### Changed
- **Responsive layout** — main screen uses `LayoutBuilder` with proportional
  fractions. Scales to any screen size.
- **Dynamic version** — sidebar footer reads from `PackageInfo` instead of
  hardcoded string.
- **Swarm/log blocked** — nginx returns 404 for `/api/swarm` and `/api/log`.
  Monitoring runs locally via `swarm_watch.py`.

### Added
- **Update checker** in sidebar — polls GitHub Releases API.
- **Website button** in sidebar → opens `https://userevolt.app`.
- **Timer syncing indicator** — spinner during handshake.
- Dependency: `package_info_plus`.

### Server
- `/session/stop` fetches stats BEFORE popping session (race fix).
- `XrayManager._stats_lock` for diagnostic counter thread safety.
- `ManagementLoop._start_lock` prevents TOCTOU on daemon startup.

---

## [1.0.6] — 2026-06

- Initial VLESS + Xray Reality tunnel
- 60 min session, 4 GB throttle, support top-up
- Server health dot, speed display, countdown timer
- Persistent Android notification
- Glass-morphism dark UI with yellow accent
- Right-edge sidebar with Ad Consent, Privacy, About, Discord
