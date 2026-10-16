package com.paladinvpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.tfox.flutter_vless.xray.utils.RevoltAlerts
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val installerChannel = "com.revoltvpn.app/installer"
    private val notificationChannel = "com.revoltvpn.app/notification"
    private val hapticsChannel = "com.revoltvpn.app/haptics"
    private val networkChannel = "com.revoltvpn.app/network"
    private val powerChannel = "com.revoltvpn.app/power"

    private var notificationChannelReady = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkEvents: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, installerChannel)
            .setMethodCallHandler { call, result ->
                if (call.method == "getInstallerPackage") {
                    result.success(installerPackage())
                } else {
                    result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, powerChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "isIgnoringBatteryOptimizations" ->
                        result.success(isIgnoringBatteryOptimizations())
                    "requestIgnoreBatteryOptimizations" ->
                        result.success(requestIgnoreBatteryOptimizations())
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, notificationChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "updateNotificationText" -> {
                        val text = call.argument<String>("text") ?: ""
                        val title = call.argument<String>("title") ?: "Revolt VPN"
                        val action = call.argument<String>("disconnectLabel") ?: "Disconnect"
                        updateVpnNotification(title, text, action)
                        result.success(null)
                    }
                    // Session ends only Dart sees; the service posts the rest.
                    "showDisconnectedAlert" -> {
                        RevoltAlerts.postDisconnected(this)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, hapticsChannel)
            .setMethodCallHandler { call, result ->
                if (call.method == "impact") {
                    val kind = call.argument<String>("kind") ?: "tap"
                    result.success(performHaptic(kind))
                } else {
                    result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, networkChannel)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    networkEvents = events
                    startNetworkMonitor()
                }

                override fun onCancel(arguments: Any?) {
                    networkEvents = null
                    stopNetworkMonitor()
                }
            })
    }

    override fun onDestroy() {
        stopNetworkMonitor()
        super.onDestroy()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun requestIgnoreBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (isIgnoringBatteryOptimizations()) return true
        return try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
            true
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun updateVpnNotification(title: String, text: String, actionLabel: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureNotificationChannel()

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        launchIntent?.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_NEW_TASK

        // The VPN service owns the authoritative Disconnect PendingIntent and
        // embeds the current runtime-generation token in it. Never recreate an
        // unscoped STOP intent from the UI process: a stale notification could
        // otherwise stop a newer tunnel. Reuse the live service action when the
        // platform exposes it; on older Android versions omit the action rather
        // than weaken generation scoping.
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val serviceStopAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.activeNotifications
                .firstOrNull { it.id == NOTIFICATION_ID }
                ?.notification
                ?.actions
                ?.firstOrNull()
                ?.actionIntent
        } else {
            null
        }

        val publicVersion = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle("Revolt VPN")
            .setContentText("VPN is active")
            .build()

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setColorized(true)
            .setColor(0xFF0D1117.toInt())
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)

        if (serviceStopAction != null) {
            builder.addAction(0, actionLabel, serviceStopAction)
        }

        if (launchIntent != null) {
            builder.setContentIntent(PendingIntent.getActivity(this, 0, launchIntent, flags))
        }

        try {
            val notification = builder.build()
            notification.flags = notification.flags or
                Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // The native foreground notification may not be ready yet.
        }
    }

    private fun ensureNotificationChannel() {
        if (notificationChannelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Revolt VPN Background Service",
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        notificationChannelReady = true
    }

    private fun startNetworkMonitor() {
        if (networkCallback != null) return

        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                emitBestPhysicalNetwork(manager, "available")
            }

            override fun onLost(network: Network) {
                emitBestPhysicalNetwork(manager, "lost")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                emitBestPhysicalNetwork(manager, "changed")
            }
        }

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            manager.registerNetworkCallback(request, callback)
            networkCallback = callback
            emitBestPhysicalNetwork(manager, "initial")
        } catch (e: Exception) {
            networkEvents?.error("NETWORK_MONITOR", e.message, null)
        }
    }

    private fun stopNetworkMonitor() {
        val callback = networkCallback ?: return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    private fun emitBestPhysicalNetwork(
        manager: ConnectivityManager,
        reason: String,
    ) {
        val best = manager.allNetworks
            .mapNotNull { network ->
                val capabilities = manager.getNetworkCapabilities(network)
                    ?: return@mapNotNull null
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return@mapNotNull null
                }
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    return@mapNotNull null
                }
                Triple(network, capabilities, physicalNetworkScore(capabilities))
            }
            .maxByOrNull { it.third }

        if (best == null) {
            emitNetworkPayload(
                mapOf<String, Any>(
                    "reason" to reason,
                    "transport" to "none",
                    "connected" to false,
                    "validated" to false,
                    "metered" to false,
                    "timestamp" to System.currentTimeMillis(),
                ),
            )
            return
        }

        emitNetworkState(best.first, best.second, reason)
    }

    private fun physicalNetworkScore(capabilities: NetworkCapabilities): Int {
        var score = 0
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            score += 100
        }
        score += when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 40
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 30
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 20
            else -> 10
        }
        return score
    }

    private fun emitNetworkState(
        network: Network,
        capabilities: NetworkCapabilities,
        reason: String,
    ) {
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        val validated = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        emitNetworkPayload(
            mapOf<String, Any>(
                "reason" to reason,
                "transport" to transport,
                "connected" to true,
                "validated" to validated,
                "metered" to metered,
                "timestamp" to System.currentTimeMillis(),
            ),
        )
    }

    private fun emitNetworkPayload(payload: Map<String, Any>) {
        runOnUiThread { networkEvents?.success(payload) }
    }

    private fun installerPackage(): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
    } catch (_: Exception) {
        null
    }

    private fun performHaptic(kind: String): Boolean {
        return try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager =
                    getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (!vibrator.hasVibrator()) {
                false
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val effect = when (kind) {
                        "selection" -> VibrationEffect.EFFECT_TICK
                        "success" -> VibrationEffect.EFFECT_HEAVY_CLICK
                        else -> VibrationEffect.EFFECT_CLICK
                    }
                    vibrator.vibrate(VibrationEffect.createPredefined(effect))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val durationMs = if (kind == "success") 24L else 14L
                    val amplitude = if (kind == "success") 110 else 70
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(if (kind == "success") 24L else 14L)
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "REVOLT_VPN_SERVICE"
    }
}
