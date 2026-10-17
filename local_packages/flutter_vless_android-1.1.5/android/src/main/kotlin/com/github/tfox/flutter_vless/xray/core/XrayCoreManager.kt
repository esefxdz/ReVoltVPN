package com.github.tfox.flutter_vless.xray.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.github.tfox.flutter_vless.xray.dto.XrayConfig
import com.github.tfox.flutter_vless.xray.service.XrayVPNService
import com.github.tfox.flutter_vless.xray.utils.AppConfigs
import com.github.tfox.flutter_vless.xray.utils.Utilities
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the Xray Core process (libxray.so).
 * 
 * This singleton object is responsible for:
 * 1. Generating the final Xray configuration file (config.json).
 * 2. Injecting necessary inbounds (SOCKS, HTTP, API) into the user-provided config.
 * 3. Starting and monitoring the Xray process.
 * 4. Collecting traffic statistics via the Xray API.
 * 5. Showing the persistent foreground notification.
 */
object XrayCoreManager {

    private const val NOTIFICATION_ID = 1
    private const val TAG = "XrayCoreManager"

    // The core's output is kept only for its first seconds, and only to explain
    // a non-zero exit. A core that has been up longer than this may be carrying
    // traffic, and its log can name destinations, which must never reach the
    // device log (rule 95) - so past the window the buffer is dropped.
    private const val STARTUP_LOG_WINDOW_MS = 10_000L
    private const val STARTUP_LOG_MAX_LINES = 40
    private var xrayProcess: Process? = null
    private var countDownTimer: CountDownTimer? = null
    private var seconds = 0

    private fun deleteEphemeralConfigOnFailure(filesDir: File) {
        try {
            val config = File(filesDir, "config.json")
            if (config.exists() && !config.delete()) {
                Log.w(TAG, "Could not delete failed ephemeral Xray config")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete failed ephemeral Xray config", e)
        }
    }

    private fun nextFreePort(preferredPort: Int, usedPorts: Set<Int>): Int {
        var port = preferredPort
        while (usedPorts.contains(port)) {
            port++
        }
        return port
    }

    private fun uniqueInboundTag(inbounds: JSONArray, preferredTag: String): String {
        val tags = mutableSetOf<String>()
        for (i in 0 until inbounds.length()) {
            val tag = inbounds.optJSONObject(i)?.optString("tag").orEmpty()
            if (tag.isNotEmpty()) tags.add(tag)
        }

        if (!tags.contains(preferredTag)) return preferredTag

        var index = 1
        var candidate = "${preferredTag}_$index"
        while (tags.contains(candidate)) {
            index++
            candidate = "${preferredTag}_$index"
        }
        return candidate
    }

    private fun normalizeRuntimeConfig(value: Any?): Any? {
        return when (value) {
            is JSONObject -> {
                val normalized = JSONObject()
                val aliases = mapOf(
                    "xHTTPSettings" to "xhttpSettings",
                    "httpUpgradeSettings" to "httpupgradeSettings",
                    "splitHTTPSettings" to "splithttpSettings"
                )
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "allowInsecure") continue

                    val targetKey = aliases[key] ?: key
                    if (aliases.containsKey(key) && value.has(targetKey)) continue

                    val normalizedValue = normalizeRuntimeConfig(value.opt(key))
                    if (targetKey == "network" && normalizedValue is String) {
                        normalized.put(targetKey, normalizedValue.lowercase())
                    } else {
                        normalized.put(targetKey, normalizedValue)
                    }
                }
                normalized
            }
            is JSONArray -> {
                val normalized = JSONArray()
                for (i in 0 until value.length()) {
                    normalized.put(normalizeRuntimeConfig(value.opt(i)))
                }
                normalized
            }
            else -> value
        }
    }

    private fun normalizeVlessOutbounds(configJson: JSONObject) {
        val outbounds = configJson.optJSONArray("outbounds") ?: return
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            if (outbound.optString("protocol") != "vless") continue

            val settings = outbound.optJSONObject("settings") ?: continue
            if (settings.has("vnext")) continue

            val address = settings.optString("address")
            val id = settings.optString("id")
            val port = settings.optInt("port", 0)
            if (address.isEmpty() || id.isEmpty() || port <= 0) continue

            val user = JSONObject()
            user.put("id", id)
            user.put("encryption", settings.optString("encryption", "none"))
            user.put("flow", settings.optString("flow", ""))
            user.put("level", settings.optInt("level", 8))

            val server = JSONObject()
            server.put("address", address)
            server.put("port", port)
            server.put("users", JSONArray().put(user))

            val normalizedSettings = JSONObject()
            normalizedSettings.put("vnext", JSONArray().put(server))
            outbound.put("settings", normalizedSettings)
            Log.d(TAG, "Normalized flat VLESS outbound settings for $address:$port")
        }
    }

    private fun sanitizeLogPaths(configJson: JSONObject, filesDir: File) {
        val log = configJson.optJSONObject("log") ?: return

        // The VPN client never needs destination/access logs. A server-provided
        // config must not silently enable browsing metadata collection locally.
        log.remove("access")
        try { File(filesDir, "access.log").delete() } catch (_: Exception) {}

        val errorPath = log.optString("error")
        if (errorPath.isNotEmpty()) {
            log.put("error", File(filesDir, "error.log").absolutePath)
        }
    }

    internal fun buildRuntimeConfigJson(config: XrayConfig, filesDir: File): JSONObject {
        val configJson = normalizeRuntimeConfig(
            JSONObject(config.V2RAY_FULL_JSON_CONFIG)
        ) as JSONObject
        normalizeVlessOutbounds(configJson)
        sanitizeLogPaths(configJson, filesDir)

        // ReVolt needs only its authenticated local SOCKS ingress.
        val inbounds = configJson.optJSONArray("inbounds") ?: JSONArray()
        var secureSocksEnabled = false
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("tag") != "revolt-secure-socks") continue

            val settings = inbound.optJSONObject("settings")
                ?: throw IllegalStateException("Secure SOCKS5 settings are missing")
            val users = settings.optJSONArray("users")
                ?: throw IllegalStateException("Secure SOCKS5 users are missing")
            val account = users.optJSONObject(0)
                ?: throw IllegalStateException("Secure SOCKS5 account is missing")
            val port = inbound.optInt("port", -1)

            if (inbound.optString("protocol") != "socks" ||
                inbound.optString("listen") != "127.0.0.1" ||
                port <= 1024 || port > 65535 ||
                settings.optString("auth") != "password" ||
                account.optString("user").isEmpty() ||
                account.optString("pass").isEmpty()
            ) {
                throw IllegalStateException("Secure SOCKS5 session is invalid")
            }

            config.LOCAL_SOCKS5_PORT = port
            config.LOCAL_HTTP_PORT = 0
            secureSocksEnabled = true
            break
        }

        val usedPorts = mutableSetOf<Int>()
        var hasSocksOnLocalPort = false
        var hasHttpOnLocalPort = false
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(i)
            val protocol = inbound.optString("protocol")
            val port = inbound.optInt("port", -1)
            if (port > 0) usedPorts.add(port)
            if (protocol == "socks" && port == config.LOCAL_SOCKS5_PORT) hasSocksOnLocalPort = true
            if (protocol == "http" && port == config.LOCAL_HTTP_PORT) hasHttpOnLocalPort = true
        }

        if (!hasSocksOnLocalPort) {
            if (usedPorts.contains(config.LOCAL_SOCKS5_PORT)) {
                config.LOCAL_SOCKS5_PORT = nextFreePort(config.LOCAL_SOCKS5_PORT, usedPorts)
            }
            val socksInbound = JSONObject()
            socksInbound.put("tag", uniqueInboundTag(inbounds, "socks"))
            socksInbound.put("port", config.LOCAL_SOCKS5_PORT)
            socksInbound.put("listen", "127.0.0.1")
            socksInbound.put("protocol", "socks")
            socksInbound.put("settings", JSONObject().put("auth", "noauth").put("udp", true))
            socksInbound.put(
                "sniffing",
                JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls"))
            )
            inbounds.put(socksInbound)
            usedPorts.add(config.LOCAL_SOCKS5_PORT)
            Log.d(TAG, "Injected SOCKS inbound on port ${config.LOCAL_SOCKS5_PORT}")
        }

        if (!secureSocksEnabled && !hasHttpOnLocalPort) {
            if (usedPorts.contains(config.LOCAL_HTTP_PORT)) {
                config.LOCAL_HTTP_PORT = nextFreePort(config.LOCAL_HTTP_PORT, usedPorts)
            }
            val httpInbound = JSONObject()
            httpInbound.put("tag", uniqueInboundTag(inbounds, "http"))
            httpInbound.put("port", config.LOCAL_HTTP_PORT)
            httpInbound.put("listen", "127.0.0.1")
            httpInbound.put("protocol", "http")
            inbounds.put(httpInbound)
            usedPorts.add(config.LOCAL_HTTP_PORT)
            Log.d(TAG, "Injected HTTP inbound on port ${config.LOCAL_HTTP_PORT}")
        }

        return configJson
    }

    internal fun buildDelayConfigJson(configJson: String, proxyPort: Int, filesDir: File): Pair<JSONObject, Int> {
        val delayConfig = XrayConfig(
            V2RAY_FULL_JSON_CONFIG = configJson,
            LOCAL_SOCKS5_PORT = proxyPort,
            LOCAL_HTTP_PORT = proxyPort + 1,
            LOCAL_API_PORT = proxyPort + 2
        )
        val runtimeJson = buildRuntimeConfigJson(delayConfig, filesDir)
        return Pair(runtimeJson, delayConfig.LOCAL_SOCKS5_PORT)
    }

    /**
     * Starts the Xray Core process.
     * 
     * @param context The service context (needed for file access and notifications).
     * @param config The configuration object containing the user's settings.
     * @return true if started successfully, false otherwise.
     */
    fun startCore(context: Service, config: XrayConfig): Boolean {
        AppConfigs.RUNTIME_READY = false
        AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTING
        AppConfigs.V2RAY_CONFIG = config

        // 1. Prepare the configuration file
        val configFilesDir = context.filesDir
        
        try {
            val configJson = buildRuntimeConfigJson(config, configFilesDir)
            val configFile = File(context.filesDir, "config.json")
            configFile.writeText(configJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write config file", e)
            deleteEphemeralConfigOnFailure(configFilesDir)
            return false
        }

        // 2. Find Xray executable (libxray.so)
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val xrayExecutable = File(nativeLibraryDir, "libxray.so")
        if (!xrayExecutable.exists()) {
            Log.e(TAG, "Xray executable not found at ${xrayExecutable.absolutePath}")
            deleteEphemeralConfigOnFailure(configFilesDir)
            return false
        }

        // 3. Prepare assets (geoip, geosite)
        try {
            Utilities.copyAssets(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare Xray assets", e)
            deleteEphemeralConfigOnFailure(configFilesDir)
            return false
        }

        // 4. Run Xray
        try {
            val cmd = listOf(
                xrayExecutable.absolutePath,
                "-config", File(configFilesDir, "config.json").absolutePath
            )
            val pb = ProcessBuilder(cmd)
            pb.directory(configFilesDir)
            pb.redirectErrorStream(true)
            
            // Set environment variables (XRAY_LOCATION_ASSET is crucial for finding geoip/geosite)
            val env = pb.environment()
            env["XRAY_LOCATION_ASSET"] = Utilities.getUserAssetsPath(context)

            xrayProcess = pb.start()
            Thread.sleep(300)
            if (xrayProcess?.isAlive != true) {
                val output = xrayProcess?.inputStream?.bufferedReader()?.readText().orEmpty()
                Log.e(TAG, "Xray process exited during startup. Output: $output")
                xrayProcess = null
                AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED
                deleteEphemeralConfigOnFailure(configFilesDir)
                return false
            }
            
            // config.json contains per-session SOCKS credentials. Xray has
            // consumed it after the startup liveness check, so remove it now.
            try {
                val ephemeralConfig = File(configFilesDir, "config.json")
                if (ephemeralConfig.exists() && !ephemeralConfig.delete()) {
                    Log.w(TAG, "Could not delete ephemeral Xray config")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete ephemeral Xray config", e)
            }

            AppConfigs.RUNTIME_READY = false
            AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTING
            startTimer(context)
            showNotification(context, config)
            sendStatusBroadcast(context, config)
            
            val process = xrayProcess ?: return false
            // Monitor process in a separate thread to detect crash
            Thread {
                try {
                    val startedAt = System.currentTimeMillis()
                    val startupLog = ArrayDeque<String>()
                    process.inputStream.bufferedReader().use { reader ->
                        reader.forEachLine { line ->
                            if (System.currentTimeMillis() - startedAt <= STARTUP_LOG_WINDOW_MS) {
                                if (startupLog.size >= STARTUP_LOG_MAX_LINES) startupLog.removeFirst()
                                startupLog.addLast(line)
                            } else if (startupLog.isNotEmpty()) {
                                startupLog.clear()
                            }
                        }
                    }

                    val exitCode = process.waitFor()
                    if (exitCode != 0 && startupLog.isNotEmpty()) {
                        Log.e(
                            TAG,
                            "Xray process exited with code $exitCode; startup output:\n" +
                                startupLog.joinToString("\n")
                        )
                    } else {
                        Log.e(TAG, "Xray process exited with code $exitCode")
                    }
                    if (xrayProcess === process &&
                        AppConfigs.V2RAY_CONFIG?.RUNTIME_TOKEN == config.RUNTIME_TOKEN &&
                        (AppConfigs.V2RAY_STATE == AppConfigs.V2RAY_STATES.V2RAY_CONNECTED ||
                         AppConfigs.V2RAY_STATE == AppConfigs.V2RAY_STATES.V2RAY_CONNECTING)
                    ) {
                        // Hold the Android TUN while the core is recovered. Killing
                        // the VPN service here would allow direct-network fallback.
                        xrayProcess = null
                        AppConfigs.RUNTIME_READY = false
                        AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTING
                        sendStatusBroadcast(context, config)
                        if (context is XrayVPNService) {
                            context.handleXrayCoreExit(config)
                        } else {
                            stopCore(context)
                        }
                    }
                } catch (e: java.io.InterruptedIOException) {
                    // Expected when stopping
                } catch (e: InterruptedException) {
                    // Expected when stopping
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading xray output", e)
                }
            }.start()

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Xray process", e)
            // The process may already be running: pb.start() succeeds before
            // anything below it can throw. Leaving it alive leaked one live
            // core per recovery attempt. Clear the field BEFORE destroying so
            // the monitor thread's `xrayProcess === process` guard fails and
            // recovery is not re-entered for a core we are killing here.
            val orphan = xrayProcess
            xrayProcess = null
            try {
                orphan?.destroy()
            } catch (destroyError: Exception) {
                Log.w(TAG, "Failed to destroy partially started Xray process", destroyError)
            }
            deleteEphemeralConfigOnFailure(configFilesDir)
            return false
        }
    }

    /**
     * Stops the Xray Core process and cleans up notifications.
     */
    fun stopCore(context: Service, confirmationToken: String? = AppConfigs.V2RAY_CONFIG?.RUNTIME_TOKEN) {
        try {
            xrayProcess?.destroy()
            xrayProcess = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy Xray process", e)
        }

        AppConfigs.RUNTIME_READY = false
        AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED
        stopTimer()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        sendDisconnectedBroadcast(context, confirmationToken.orEmpty())
        AppConfigs.V2RAY_CONFIG = null
    }

    fun markRuntimeConnecting(context: Context, config: XrayConfig) {
        if (AppConfigs.V2RAY_CONFIG?.RUNTIME_TOKEN != config.RUNTIME_TOKEN) return
        AppConfigs.RUNTIME_READY = false
        AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTING
        sendStatusBroadcast(context, config)
    }

    fun markRuntimeReady(context: Service, config: XrayConfig) {
        if (AppConfigs.V2RAY_CONFIG?.RUNTIME_TOKEN != config.RUNTIME_TOKEN) return
        AppConfigs.RUNTIME_READY = true
        AppConfigs.V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTED
        showNotification(context, config)
        sendStatusBroadcast(context, config)
    }

    fun isXrayRunning(): Boolean {
        // Check state instead of process because VPN runs in separate service process
        return AppConfigs.V2RAY_STATE == AppConfigs.V2RAY_STATES.V2RAY_CONNECTED ||
               AppConfigs.V2RAY_STATE == AppConfigs.V2RAY_STATES.V2RAY_CONNECTING
    }

    // CountDownTimer builds a Handler, so it only constructs on a looper thread.
    // Core recovery runs on a bare Thread (XrayVPNService.handleXrayCoreExit),
    // where this threw "Can't create handler inside thread ... that has not
    // called Looper.prepare()" and took the whole restart down with it.
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun startTimer(context: Context) {
        // Captured now: a stop that lands while the post is queued must not
        // leave a timer ticking for a generation that is already gone.
        val token = AppConfigs.V2RAY_CONFIG?.RUNTIME_TOKEN
        runOnMain { startTimerOnMain(context, token) }
    }

    private fun startTimerOnMain(context: Context, token: String?) {
        if (AppConfigs.V2RAY_CONFIG?.RUNTIME_TOKEN != token) return
        countDownTimer?.cancel()
        seconds = 0
        countDownTimer = object : CountDownTimer(Long.MAX_VALUE, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                seconds++
                val intent = Intent(AppConfigs.V2RAY_CONNECTION_INFO).setPackage(context.packageName)
                intent.putExtra("STATE", AppConfigs.V2RAY_STATE)
                intent.putExtra("RUNTIME_TOKEN", AppConfigs.V2RAY_CONFIG?.RUNTIME_TOKEN.orEmpty())
                intent.putExtra("RUNTIME_READY", AppConfigs.RUNTIME_READY)
                intent.putExtra("DURATION", seconds.toString())
                
                intent.putExtra("UPLOAD_SPEED", 0L)
                intent.putExtra("DOWNLOAD_SPEED", 0L)
                intent.putExtra("UPLOAD_TRAFFIC", 0L)
                intent.putExtra("DOWNLOAD_TRAFFIC", 0L)
                
                context.sendBroadcast(intent)
            }

            override fun onFinish() {}
        }.start()
    }

    private fun stopTimer() {
        runOnMain {
            countDownTimer?.cancel()
            countDownTimer = null
            seconds = 0
        }
    }

    fun sendStatusBroadcast(context: Context, config: XrayConfig) {
        val intent = Intent(AppConfigs.V2RAY_CONNECTION_INFO).setPackage(context.packageName)
        intent.putExtra("STATE", AppConfigs.V2RAY_STATE)
        intent.putExtra("RUNTIME_TOKEN", config.RUNTIME_TOKEN)
        intent.putExtra("RUNTIME_READY", AppConfigs.RUNTIME_READY)
        intent.putExtra("DURATION", seconds.toString())
        intent.putExtra("UPLOAD_SPEED", 0L)
        intent.putExtra("DOWNLOAD_SPEED", 0L)
        intent.putExtra("UPLOAD_TRAFFIC", 0L)
        intent.putExtra("DOWNLOAD_TRAFFIC", 0L)
        context.sendBroadcast(intent)
    }

    private fun sendDisconnectedBroadcast(context: Context, runtimeToken: String) {
        val intent = Intent(AppConfigs.V2RAY_CONNECTION_INFO).setPackage(context.packageName)
        intent.putExtra("STATE", AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED)
        intent.putExtra("RUNTIME_TOKEN", runtimeToken)
        intent.putExtra("RUNTIME_READY", false)
        intent.putExtra("DURATION", "0")
        intent.putExtra("UPLOAD_SPEED", 0L)
        intent.putExtra("DOWNLOAD_SPEED", 0L)
        intent.putExtra("UPLOAD_TRAFFIC", 0L)
        intent.putExtra("DOWNLOAD_TRAFFIC", 0L)
        context.sendBroadcast(intent)
    }

    private fun showNotification(context: Service, config: XrayConfig) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val channelId = createNotificationChannel(context, config.APPLICATION_NAME)
        
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launchIntent?.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val contentPendingIntent = PendingIntent.getActivity(context, 0, launchIntent, flags)

        val stopIntent = Intent(context, XrayVPNService::class.java)
        stopIntent.putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE)
        stopIntent.putExtra("RUNTIME_TOKEN", config.RUNTIME_TOKEN)
        val stopPendingIntent = PendingIntent.getService(context, 0, stopIntent, flags)
        val smallIcon = if (config.APPLICATION_ICON != 0) config.APPLICATION_ICON else android.R.drawable.ic_dialog_info

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(config.REMARK)
            .setContentText(if (AppConfigs.RUNTIME_READY) "Connected" else "Connecting")
            .addAction(0, config.NOTIFICATION_DISCONNECT_BUTTON_NAME, stopPendingIntent)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(true)

        context.startForeground(NOTIFICATION_ID, builder.build())
    }

    fun getConnectedV2rayServerDelay(context: Context, url: String): Long {
        // Use the configured SOCKS port (default 10807)
        val port = AppConfigs.V2RAY_CONFIG?.LOCAL_SOCKS5_PORT ?: 10807
        Log.d(TAG, "getConnectedV2rayServerDelay: Testing delay to $url via SOCKS port $port")
        
        return try {
            val start = System.currentTimeMillis()
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
            val connection = java.net.URL(url).openConnection(proxy) as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "HEAD"
            val responseCode = connection.responseCode
            val end = System.currentTimeMillis()
            val delay = end - start
            connection.disconnect()
            Log.d(TAG, "getConnectedV2rayServerDelay: Success! Response code: $responseCode, Delay: ${delay}ms")
            delay
        } catch (e: Exception) {
            Log.e(TAG, "getConnectedV2rayServerDelay: Failed to measure delay (VPN may not be running)", e)
            -1L
        }
    }

    /**
     * Measure delay for a server configuration (when not connected).
     * Temporarily starts Xray with the provided config, measures delay, then stops it.
     */
    fun getServerDelay(context: Context, configJson: String, url: String): Long {
        Log.d(TAG, "getServerDelay: Starting temporary Xray instance")
        
        var tempProcess: Process? = null
        try {
            // Find a random free port to avoid conflict with running VPN
            val freePort = try {
                val socket = java.net.ServerSocket(0)
                val port = socket.localPort
                socket.close()
                port
            } catch (e: Exception) {
                10806 // Fallback
            }
            
            val (json, socksPort) = buildDelayConfigJson(configJson, freePort, context.filesDir)
            Log.d(TAG, "getServerDelay: Using SOCKS port $socksPort")
            
            // Write temp config file
            val tempConfigFile = File(context.filesDir, "temp_delay_config.json")
            tempConfigFile.writeText(json.toString())
            
            // Copy assets
            Utilities.copyAssets(context)
            
            // Start Xray process
            val xrayExecutable = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            if (!xrayExecutable.exists()) {
                Log.e(TAG, "getServerDelay: Xray executable not found")
                return -1L
            }
            
            val cmd = listOf(
                xrayExecutable.absolutePath,
                "-config", tempConfigFile.absolutePath
            )
            
            val pb = ProcessBuilder(cmd)
            pb.directory(context.filesDir)
            val env = pb.environment()
            env["XRAY_LOCATION_ASSET"] = Utilities.getUserAssetsPath(context)
            
            tempProcess = pb.start()
            
            // Wait a bit for Xray to start
            Thread.sleep(1000)
            
            // Measure delay
            val delay = try {
                val start = System.currentTimeMillis()
                val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", socksPort))
                val connection = java.net.URL(url).openConnection(proxy) as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "HEAD"
                val responseCode = connection.responseCode
                val end = System.currentTimeMillis()
                connection.disconnect()
                val result = end - start
                Log.d(TAG, "getServerDelay: Success! Response code: $responseCode, Delay: ${result}ms")
                result
            } catch (e: Exception) {
                Log.e(TAG, "getServerDelay: Failed to measure delay", e)
                -1L
            }
            
            // Stop temp process
            tempProcess?.destroy()
            tempConfigFile.delete()
            
            return delay
            
        } catch (e: Exception) {
            Log.e(TAG, "getServerDelay: Error starting temp Xray", e)
            tempProcess?.destroy()
            return -1L
        }
    }

    private fun createNotificationChannel(context: Context, appName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "REVOLT_VPN_SERVICE"
            val channelName = "$appName Background Service"
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            return channelId
        }
        return ""
    }
}
