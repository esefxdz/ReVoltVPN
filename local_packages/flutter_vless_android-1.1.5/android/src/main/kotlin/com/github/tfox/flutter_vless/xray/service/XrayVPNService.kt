package com.github.tfox.flutter_vless.xray.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.github.tfox.flutter_vless.xray.core.XrayCoreManager
import com.github.tfox.flutter_vless.xray.dto.XrayConfig
import com.github.tfox.flutter_vless.xray.utils.AppConfigs
import com.github.tfox.flutter_vless.xray.utils.RevoltAlerts
import org.json.JSONObject
import java.io.File

/**
 * Android VPN Service implementation.
 *
 * This service owns the TUN interface and keeps runtime state generation-scoped.
 * Session deadlines are enforced both by an in-process monotonic timer and by
 * AlarmManager, while only the non-secret token/deadline pair is persisted.
 * Per-session VLESS/SOCKS credentials remain memory-only.
 */
class XrayVPNService : VpnService() {

    private var mInterface: ParcelFileDescriptor? = null
    private var tun2socksProcess: Process? = null
    @Volatile private var isRunning = false
    @Volatile private var recoveringXray = false
    @Volatile private var recoveringTun2socks = false
    private var tun2socksRecoveryAttempt = 0
    private var currentConfig: XrayConfig? = null
    private var currentProxyOnly = false
    private var shuttingDownIntentionally = false

    private val deadlineHandler = Handler(Looper.getMainLooper())
    private var sessionDeadlineElapsed: Long? = null
    private var sessionDeadlineToken: String? = null
    private val sessionDeadlineRunnable = Runnable { enforceSessionDeadline() }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (isRunning && currentConfig != null) return START_REDELIVER_INTENT

            // Session credentials intentionally stay memory-only. If Android
            // cannot redeliver the original START intent, fail closed instead
            // of rebuilding a tunnel from sensitive material stored on disk.
            clearPersistedDeadline()
            cancelSessionExpiryAlarm()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.getBooleanExtra(EXTRA_SESSION_EXPIRED, false)) {
            enforcePersistedDeadline(intent.getStringExtra("RUNTIME_TOKEN").orEmpty())
            return START_NOT_STICKY
        }

        // Must stay above the command handling below: an intent carrying no
        // COMMAND falls through to the final else and stops a live tunnel - in
        // this case five minutes early, which is what it warns about.
        if (intent.getBooleanExtra(EXTRA_SESSION_WARNING, false)) {
            handleSessionWarningAlarm(intent.getStringExtra("RUNTIME_TOKEN").orEmpty())
            return START_NOT_STICKY
        }

        val command = if (Build.VERSION.SDK_INT >= 33) {
            intent.getSerializableExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("COMMAND") as? AppConfigs.V2RAY_SERVICE_COMMANDS
        }

        if (command == AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE) {
            val requestedToken = intent.getStringExtra("RUNTIME_TOKEN").orEmpty()
            val activeToken = currentConfig?.RUNTIME_TOKEN.orEmpty()
            if (requestedToken.isEmpty() ||
                (activeToken.isNotEmpty() && requestedToken != activeToken)
            ) {
                Log.w(TAG, "Ignoring STOP_SERVICE without matching runtime generation")
                return START_NOT_STICKY
            }
            stopAll(requestedToken)
            return START_NOT_STICKY
        }

        if (command == AppConfigs.V2RAY_SERVICE_COMMANDS.UPDATE_SESSION_DEADLINE) {
            updateSessionDeadline(intent)
            return if (isRunning) START_REDELIVER_INTENT else START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = createNotification("VPN Service Running")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
            clearPersistedDeadline()
            cancelSessionExpiryAlarm()
            stopSelf()
            return START_NOT_STICKY
        }

        if (command == AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE) {
            val config = if (Build.VERSION.SDK_INT >= 33) {
                intent.getSerializableExtra("V2RAY_CONFIG", XrayConfig::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra("V2RAY_CONFIG") as? XrayConfig
            }

            if (config != null) {
                if (XrayCoreManager.isXrayRunning()) {
                    Log.w(TAG, "Stopping stale Xray core before restart")
                    XrayCoreManager.stopCore(this)
                }
                cleanup(clearDeadline = true)
                RevoltAlerts.cancelAll(this)
                shuttingDownIntentionally = false
                currentConfig = config

                val proxyOnly = intent.getBooleanExtra("PROXY_ONLY", false)
                currentProxyOnly = proxyOnly
                armSessionDeadline(config, BOOTSTRAP_SESSION_SECONDS)
                if (shuttingDownIntentionally) return START_NOT_STICKY

                if (XrayCoreManager.startCore(this, config)) {
                    if (!proxyOnly) {
                        setupVpn(config)
                    } else {
                        isRunning = true
                        Log.d(TAG, "Starting in PROXY_ONLY mode")
                        XrayCoreManager.markRuntimeReady(this, config)
                    }
                } else {
                    stopAll(config.RUNTIME_TOKEN)
                    return START_NOT_STICKY
                }
            } else {
                Log.e(TAG, "START_SERVICE missing V2RAY_CONFIG")
                stopAll()
                return START_NOT_STICKY
            }
        } else {
            stopAll()
            return START_NOT_STICKY
        }

        return START_REDELIVER_INTENT
    }

    private fun setupVpn(config: XrayConfig) {
        try {
            if (mInterface != null) {
                mInterface?.close()
                mInterface = null
            }

            val builder = Builder()
            builder.setSession(config.REMARK)
            builder.setMtu(1500)
            builder.addAddress("26.26.26.1", 30)
            builder.addAddress("fd00:26:26::1", 126)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            if (config.BLOCKED_APPS.isNotEmpty()) {
                throw IllegalStateException("Per-app VPN bypass is disabled in ReVolt")
            }
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to exclude ReVolt from its own VPN", e)
            }

            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)

            try {
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("1.1.1.1")
            } catch (e: Exception) {
                throw IllegalStateException("Failed to configure VPN DNS", e)
            }

            mInterface = builder.establish()
                ?: throw IllegalStateException("Android refused to establish VPN interface")
            isRunning = true
            runTun2socks(config)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup VPN", e)
            stopAll()
        }
    }

    private data class SecureSocksCredentials(
        val port: Int,
        val username: String,
        val password: String,
    )

    private fun secureSocksCredentials(config: XrayConfig): SecureSocksCredentials? {
        val inbounds = try {
            JSONObject(config.V2RAY_FULL_JSON_CONFIG).optJSONArray("inbounds")
        } catch (_: Exception) {
            null
        } ?: return null

        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("tag") != "revolt-secure-socks") continue
            if (inbound.optString("protocol") != "socks" ||
                inbound.optString("listen") != "127.0.0.1"
            ) return null

            val settings = inbound.optJSONObject("settings") ?: return null
            if (settings.optString("auth") != "password") return null
            val user = settings.optJSONArray("users")?.optJSONObject(0) ?: return null
            val port = inbound.optInt("port", -1)
            val username = user.optString("user")
            val password = user.optString("pass")
            if (port <= 1024 || port > 65535 || username.isEmpty() || password.isEmpty()) {
                return null
            }
            return SecureSocksCredentials(port, username, password)
        }
        return null
    }

    private fun scheduleTun2socksRecovery(config: XrayConfig, reason: String) {
        if (!isRunning || currentConfig !== config || recoveringTun2socks) return
        XrayCoreManager.markRuntimeConnecting(this, config)
        recoveringTun2socks = true
        val attempt = ++tun2socksRecoveryAttempt
        val shift = (attempt - 1).coerceIn(0, 6)
        val delayMs = (500L * (1L shl shift)).coerceAtMost(30_000L)
        Log.w(TAG, "Scheduling tun2socks recovery attempt $attempt in ${delayMs}ms: $reason")

        Thread {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                recoveringTun2socks = false
                return@Thread
            }

            recoveringTun2socks = false
            if (isRunning && currentConfig === config) {
                runTun2socks(config)
            }
        }.start()
    }

    private fun runTun2socks(config: XrayConfig) {
        val tun2socksPath = File(applicationInfo.nativeLibraryDir, "libtun2socks.so").absolutePath
        val socketFile = File(filesDir, "sock_path")
        if (socketFile.exists() && !socketFile.delete()) {
            Log.w(TAG, "Could not delete stale tun2socks socket")
        }
        val sockPath = socketFile.absolutePath

        val secure = secureSocksCredentials(config)
            ?: throw IllegalStateException("Authenticated ReVolt SOCKS5 inbound missing")
        config.LOCAL_SOCKS5_PORT = secure.port

        val cmd = arrayListOf(
            tun2socksPath,
            "-sock-path", sockPath,
            "-proxy", "socks5://${secure.username}:${secure.password}@127.0.0.1:${secure.port}",
            "-mtu", "1500",
            "-loglevel", "debug"
        )

        Log.d(TAG, "Starting tun2socks with authenticated loopback SOCKS5")

        try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.directory(filesDir)
            val process = pb.start()
            tun2socksProcess = process

            Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        reader.forEachLine { _ -> }
                    }

                    process.waitFor()
                    if (isRunning && tun2socksProcess === process && currentConfig === config) {
                        Log.e(TAG, "tun2socks exited unexpectedly; keeping TUN fail-closed")
                        tun2socksProcess = null
                        scheduleTun2socksRecovery(config, "process exited")
                    }
                } catch (_: java.io.InterruptedIOException) {
                    // Expected when stopping
                } catch (_: InterruptedException) {
                    // Expected when stopping
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading tun2socks output", e)
                }
            }.start()

            sendFd(process, config)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tun2socks; keeping TUN fail-closed", e)
            scheduleTun2socksRecovery(config, "start failure")
        }
    }

    private fun sendFd(process: Process, config: XrayConfig) {
        val fd = mInterface?.fileDescriptor ?: return
        val sockFile = File(filesDir, "sock_path").absolutePath

        Thread {
            var tries = 0
            while (tries < 10 && isRunning && tun2socksProcess === process && process.isAlive) {
                var localSocket: LocalSocket? = null
                try {
                    Thread.sleep(500)
                    localSocket = LocalSocket()
                    localSocket.connect(LocalSocketAddress(sockFile, LocalSocketAddress.Namespace.FILESYSTEM))
                    val out = localSocket.outputStream
                    localSocket.setFileDescriptorsForSend(arrayOf(fd))
                    out.write(32)
                    out.flush()
                    localSocket.setFileDescriptorsForSend(null)
                    localSocket.shutdownOutput()
                    localSocket.close()
                    if (isRunning && currentConfig === config && tun2socksProcess === process) {
                        tun2socksRecoveryAttempt = 0
                        XrayCoreManager.markRuntimeReady(this, config)
                    }
                    return@Thread
                } catch (_: Exception) {
                    tries++
                    try {
                        localSocket?.close()
                    } catch (closeError: Exception) {
                        Log.w(TAG, "Failed to close tun2socks handoff socket", closeError)
                    }
                }
            }

            if (isRunning && tun2socksProcess === process) {
                Log.e(TAG, "FD handoff failed; recycling tun2socks without dropping TUN")
                try {
                    process.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to recycle tun2socks after FD handoff failure", e)
                }
            }
        }.start()
    }

    fun handleXrayCoreExit(config: XrayConfig) {
        if (!isRunning || currentConfig !== config || recoveringXray) return
        XrayCoreManager.markRuntimeConnecting(this, config)
        recoveringXray = true

        Thread {
            var attempt = 0
            try {
                while (isRunning && currentConfig === config) {
                    attempt++
                    val shift = (attempt - 1).coerceIn(0, 6)
                    val delayMs = (500L * (1L shl shift)).coerceAtMost(30_000L)
                    Log.w(TAG, "Recovering Xray core attempt $attempt in ${delayMs}ms")
                    Thread.sleep(delayMs)
                    if (!isRunning || currentConfig !== config) break

                    if (XrayCoreManager.startCore(this, config)) {
                        Log.w(TAG, "Xray core recovered without dropping TUN")
                        if (currentProxyOnly || (mInterface != null && tun2socksProcess?.isAlive == true)) {
                            XrayCoreManager.markRuntimeReady(this, config)
                        }
                        return@Thread
                    }
                }
            } catch (_: InterruptedException) {
                // Expected when stopping
            } finally {
                recoveringXray = false
            }
        }.start()
    }

    private fun cleanup(clearDeadline: Boolean = true) {
        isRunning = false
        if (clearDeadline) {
            clearSessionDeadline()
        } else {
            deadlineHandler.removeCallbacks(sessionDeadlineRunnable)
            sessionDeadlineElapsed = null
            sessionDeadlineToken = null
        }
        recoveringXray = false
        recoveringTun2socks = false
        tun2socksRecoveryAttempt = 0
        currentProxyOnly = false
        AppConfigs.RUNTIME_READY = false
        tun2socksProcess?.destroy()
        tun2socksProcess = null
        try {
            mInterface?.close()
            mInterface = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close VPN interface during cleanup", e)
        }
    }

    private fun stopAll(confirmationToken: String = currentConfig?.RUNTIME_TOKEN.orEmpty()) {
        if (shuttingDownIntentionally) return
        shuttingDownIntentionally = true
        cleanup(clearDeadline = true)
        currentConfig = null
        XrayCoreManager.stopCore(this, confirmationToken)
        stopForeground(true)
        stopSelf()
    }

    private fun armSessionDeadline(config: XrayConfig, remainingSeconds: Long) {
        val boundedSeconds = remainingSeconds
            .coerceAtLeast(0L)
            .coerceAtMost(Long.MAX_VALUE / 1000L)
        val delayMs = boundedSeconds * 1000L
        val nowEpochMs = System.currentTimeMillis()
        val epochDeadline = if (Long.MAX_VALUE - nowEpochMs < delayMs) {
            Long.MAX_VALUE
        } else {
            nowEpochMs + delayMs
        }

        sessionDeadlineToken = config.RUNTIME_TOKEN
        sessionDeadlineElapsed = SystemClock.elapsedRealtime() + delayMs

        if (!persistDeadline(config.RUNTIME_TOKEN, epochDeadline) ||
            !scheduleSessionExpiryAlarm(config.RUNTIME_TOKEN, epochDeadline)
        ) {
            Log.e(TAG, "Could not arm OS-level session expiry; stopping fail-closed")
            stopAll(config.RUNTIME_TOKEN)
            return
        }

        armExpiryWarning(config.RUNTIME_TOKEN, epochDeadline)

        deadlineHandler.removeCallbacks(sessionDeadlineRunnable)
        if (delayMs == 0L) deadlineHandler.post(sessionDeadlineRunnable)
        else deadlineHandler.postDelayed(sessionDeadlineRunnable, delayMs)
    }

    // Schedules the five-minute warning off the same deadline the expiry alarm
    // holds. Unlike the deadline this is advisory: a warning that cannot be
    // scheduled must never stop a working tunnel, so nothing here fails closed.
    // Dart re-pushes the deadline on every sync, so this runs about once a minute.
    private fun armExpiryWarning(runtimeToken: String, expiresAtEpochMs: Long) {
        cancelSessionWarningAlarm()
        if (runtimeToken.isEmpty()) return

        val warnAt = expiresAtEpochMs - RevoltAlerts.WARNING_LEAD_MS
        // Deadline already inside the warning window (or past): either the alarm
        // has fired and its notification should stay up, or this is the short
        // bootstrap deadline at connect, which must not warn at all.
        if (warnAt <= System.currentTimeMillis()) return

        // More than the lead away, so any warning on screen belongs to an older
        // deadline - a support top-up just pushed it out.
        RevoltAlerts.cancelExpiryWarning(this)

        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = sessionWarningPendingIntent(runtimeToken)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Inexact on purpose: minute-level slop is fine for a warning,
                // and it keeps this off the SCHEDULE_EXACT_ALARM path.
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    warnAt,
                    pendingIntent,
                )
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, warnAt, pendingIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule session-expiry warning", e)
        }
    }

    private fun handleSessionWarningAlarm(alarmToken: String) {
        val config = currentConfig
        if (config == null) {
            // The alarm woke a service process with no live runtime; there is
            // no session left to warn about.
            stopSelf()
            return
        }
        if (alarmToken.isEmpty() || alarmToken != config.RUNTIME_TOKEN) {
            Log.w(TAG, "Ignoring session warning for stale runtime generation")
            return
        }

        val deadline = sessionDeadlineElapsed ?: return
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0L || remaining > RevoltAlerts.WARNING_LEAD_MS + WARNING_SLACK_MS) {
            Log.w(TAG, "Ignoring session warning outside the warning window")
            return
        }

        RevoltAlerts.postExpiryWarning(this)
    }

    private fun clearSessionDeadline() {
        deadlineHandler.removeCallbacks(sessionDeadlineRunnable)
        sessionDeadlineElapsed = null
        sessionDeadlineToken = null
        clearPersistedDeadline()
        cancelSessionExpiryAlarm()
        cancelSessionWarningAlarm()
        RevoltAlerts.cancelExpiryWarning(this)
    }

    private fun enforceSessionDeadline() {
        val token = sessionDeadlineToken ?: return
        val deadline = sessionDeadlineElapsed ?: return
        val config = currentConfig ?: return
        if (config.RUNTIME_TOKEN != token) return
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining > 0L) {
            deadlineHandler.postDelayed(sessionDeadlineRunnable, remaining)
            return
        }
        Log.w(TAG, "Native session deadline expired; stopping runtime fail-closed")
        stopAll(token)
        // Only the deadline paths post this natively. A STOP_SERVICE command
        // cannot tell a user tap from a Dart-side session end, so Dart posts for
        // its own reasons; the shared id means a double post just replaces.
        RevoltAlerts.postDisconnected(this)
    }

    private fun updateSessionDeadline(intent: Intent) {
        val config = currentConfig ?: return
        val token = intent.getStringExtra("RUNTIME_TOKEN").orEmpty()
        if (token.isEmpty() || token != config.RUNTIME_TOKEN) {
            Log.w(TAG, "Ignoring session deadline update for stale runtime generation")
            return
        }
        val remainingSeconds = intent.getLongExtra("REMAINING_SECONDS", -1L)
        if (remainingSeconds < 0L) {
            Log.w(TAG, "Ignoring invalid negative session deadline")
            return
        }
        armSessionDeadline(config, remainingSeconds)
    }

    private fun persistDeadline(runtimeToken: String, expiresAtEpochMs: Long): Boolean {
        return try {
            getSharedPreferences(DEADLINE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_RUNTIME_TOKEN, runtimeToken)
                .putLong(PREF_EXPIRES_AT_MS, expiresAtEpochMs)
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist non-secret session deadline", e)
            false
        }
    }

    private fun clearPersistedDeadline() {
        try {
            getSharedPreferences(DEADLINE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_RUNTIME_TOKEN)
                .remove(PREF_EXPIRES_AT_MS)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear persisted session deadline", e)
        }
    }

    private fun enforcePersistedDeadline(alarmToken: String) {
        val stored = try {
            val prefs = getSharedPreferences(DEADLINE_PREFS, Context.MODE_PRIVATE)
            prefs.getString(PREF_RUNTIME_TOKEN, null).orEmpty() to
                prefs.getLong(PREF_EXPIRES_AT_MS, 0L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read persisted session deadline", e)
            clearPersistedDeadline()
            cancelSessionExpiryAlarm()
            stopSelf()
            return
        }

        val storedToken = stored.first
        val storedDeadline = stored.second
        if (alarmToken.isEmpty() || storedToken.isEmpty() || alarmToken != storedToken) {
            Log.w(TAG, "Ignoring stale session-expiry alarm")
            if (currentConfig == null) stopSelf()
            return
        }

        val remaining = storedDeadline - System.currentTimeMillis()
        if (storedDeadline > 0L && remaining > 0L) {
            if (!scheduleSessionExpiryAlarm(storedToken, storedDeadline)) {
                Log.e(TAG, "Failed to re-arm early session-expiry alarm")
                val activeToken = currentConfig?.RUNTIME_TOKEN.orEmpty()
                if (activeToken == storedToken) stopAll(storedToken) else stopSelf()
                return
            }
            // An alarm can wake a fresh service process after clock changes.
            // If there is no live runtime in this process, do not leave a
            // background service instance hanging around just to hold a timer.
            if (currentConfig == null) stopSelf()
            return
        }

        Log.w(TAG, "OS session-expiry alarm fired; stopping runtime fail-closed")
        val activeToken = currentConfig?.RUNTIME_TOKEN.orEmpty()
        if (activeToken.isNotEmpty() && activeToken == storedToken) {
            stopAll(storedToken)
        } else {
            clearPersistedDeadline()
            cancelSessionExpiryAlarm()
            cancelSessionWarningAlarm()
            RevoltAlerts.cancelExpiryWarning(this)
            stopForeground(true)
            stopSelf()
        }
        RevoltAlerts.postDisconnected(this)
    }

    private fun scheduleSessionExpiryAlarm(runtimeToken: String, expiresAtEpochMs: Long): Boolean {
        cancelSessionExpiryAlarm()
        if (runtimeToken.isEmpty() || expiresAtEpochMs <= 0L) return false

        return try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = sessionExpiryPendingIntent(runtimeToken)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        expiresAtEpochMs,
                        pendingIntent,
                    )
                } catch (_: SecurityException) {
                    Log.w(TAG, "Exact alarm unavailable; using inexact idle-capable expiry alarm")
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        expiresAtEpochMs,
                        pendingIntent,
                    )
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, expiresAtEpochMs, pendingIntent)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule OS session-expiry alarm", e)
            false
        }
    }

    private fun cancelSessionExpiryAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(sessionExpiryPendingIntent(""))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel session-expiry alarm", e)
        }
    }

    private fun cancelSessionWarningAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(sessionWarningPendingIntent(""))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel session-warning alarm", e)
        }
    }

    // A PendingIntent is identified by component plus request code, not extras,
    // so the warning needs its own code or it would overwrite - and later
    // cancel - the expiry alarm.
    private fun sessionWarningPendingIntent(runtimeToken: String): PendingIntent {
        val intent = Intent(this, XrayVPNService::class.java)
            .putExtra(EXTRA_SESSION_WARNING, true)
            .putExtra("RUNTIME_TOKEN", runtimeToken)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, SESSION_WARNING_REQUEST_CODE, intent, flags)
    }

    private fun sessionExpiryPendingIntent(runtimeToken: String): PendingIntent {
        val intent = Intent(this, XrayVPNService::class.java)
            .putExtra(EXTRA_SESSION_EXPIRED, true)
            .putExtra("RUNTIME_TOKEN", runtimeToken)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, SESSION_EXPIRY_REQUEST_CODE, intent, flags)
    }

    override fun onDestroy() {
        if (!shuttingDownIntentionally) {
            val confirmationToken = currentConfig?.RUNTIME_TOKEN.orEmpty()
            cleanup(clearDeadline = false)
            currentConfig = null
            XrayCoreManager.stopCore(this, confirmationToken)
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        stopAll()
        super.onRevoke()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "REVOLT_VPN_SERVICE"
            val channelName = "VPN Service"
            val channel = android.app.NotificationChannel(
                channelId,
                channelName,
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): android.app.Notification {
        val channelId = "REVOLT_VPN_SERVICE"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }

        val icon = resources.getIdentifier("notification_icon", "drawable", packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

        val notification = builder
            .setContentTitle("VPN Service")
            .setContentText(content)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()

        notification.flags = notification.flags or
            android.app.Notification.FLAG_ONGOING_EVENT or
            android.app.Notification.FLAG_NO_CLEAR
        return notification
    }

    companion object {
        private const val TAG = "XrayVPNService"
        private const val FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 0x40000000
        private const val BOOTSTRAP_SESSION_SECONDS = 120L
        private const val DEADLINE_PREFS = "revolt_session_deadline"
        private const val PREF_RUNTIME_TOKEN = "runtime_token"
        private const val PREF_EXPIRES_AT_MS = "expires_at_ms"
        private const val EXTRA_SESSION_EXPIRED = "SESSION_EXPIRED"
        private const val EXTRA_SESSION_WARNING = "SESSION_WARNING"
        private const val SESSION_EXPIRY_REQUEST_CODE = 1001
        private const val SESSION_WARNING_REQUEST_CODE = 1002
        // An idle-capable alarm can run late; accept that much drift before
        // treating a warning as belonging to a deadline that has moved.
        private const val WARNING_SLACK_MS = 60_000L
    }
}
