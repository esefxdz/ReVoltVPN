// Copyright (c) 2024-2026 13FOX Studio / tfox.dev.
// SPDX-License-Identifier: MIT

package com.github.tfox.flutter_vless

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.tfox.flutter_vless.xray.dto.XrayConfig
import com.github.tfox.flutter_vless.xray.service.XrayVPNService
import com.github.tfox.flutter_vless.xray.utils.AppConfigs
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Main entry point for the Flutter Vless plugin on Android.
 *
 * ReVolt keeps this bridge deliberately narrow: start/stop, session deadline,
 * permission handling, core version and private runtime status only.
 */
class FlutterVlessPlugin : FlutterPlugin, ActivityAware, PluginRegistry.ActivityResultListener, MethodChannel.MethodCallHandler {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var vpnControlMethod: MethodChannel
    private lateinit var vpnStatusEvent: EventChannel
    private var vpnStatusSink: EventChannel.EventSink? = null
    private var activity: Activity? = null
    private var xrayReceiver: BroadcastReceiver? = null
    private var pendingResult: MethodChannel.Result? = null
    private lateinit var context: Context
    private var expectedRuntimeToken: String? = null
    private var pendingStopResult: MethodChannel.Result? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopTimeoutRunnable = Runnable {
        val pending = pendingStopResult ?: return@Runnable
        pendingStopResult = null
        pending.error("STOP_TIMEOUT", "VPN service did not confirm shutdown", null)
    }

    companion object {
        private const val TAG = "FlutterVlessPlugin"
        private const val REQUEST_CODE_VPN_PERMISSION = 24
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 1
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        vpnControlMethod = MethodChannel(binding.binaryMessenger, "flutter_vless")
        vpnStatusEvent = EventChannel(binding.binaryMessenger, "flutter_vless/status")

        vpnControlMethod.setMethodCallHandler(this)
        vpnStatusEvent.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                vpnStatusSink = events
                registerReceiver()
            }

            override fun onCancel(arguments: Any?) {
                vpnStatusSink = null
                unregisterReceiver()
            }
        })
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startVless" -> {
                if (pendingStopResult != null) {
                    result.error("STOP_IN_PROGRESS", "VPN shutdown is still in progress", null)
                    return
                }

                val config = XrayConfig()
                val runtimeToken = UUID.randomUUID().toString()
                expectedRuntimeToken = runtimeToken
                config.RUNTIME_TOKEN = runtimeToken
                config.REMARK = call.argument("remark") ?: ""
                config.V2RAY_FULL_JSON_CONFIG = call.argument("config") ?: ""
                config.BLOCKED_APPS = call.argument<ArrayList<String>>("blocked_apps") ?: ArrayList()
                config.BYPASS_SUBNETS = call.argument<ArrayList<String>>("bypass_subnets") ?: ArrayList()
                config.NOTIFICATION_DISCONNECT_BUTTON_NAME = call.argument("notificationDisconnectButtonName") ?: "Disconnect"

                if (AppConfigs.NOTIFICATION_ICON_RESOURCE_NAME.isNotEmpty() && AppConfigs.NOTIFICATION_ICON_RESOURCE_TYPE.isNotEmpty()) {
                    config.NOTIFICATION_ICON_RESOURCE_NAME = AppConfigs.NOTIFICATION_ICON_RESOURCE_NAME
                    config.NOTIFICATION_ICON_RESOURCE_TYPE = AppConfigs.NOTIFICATION_ICON_RESOURCE_TYPE
                    val resId = context.resources.getIdentifier(
                        AppConfigs.NOTIFICATION_ICON_RESOURCE_NAME,
                        AppConfigs.NOTIFICATION_ICON_RESOURCE_TYPE,
                        context.packageName
                    )
                    config.APPLICATION_ICON = resId
                }

                if (call.argument<Boolean>("proxy_only") == true) {
                    AppConfigs.V2RAY_CONNECTION_MODE = AppConfigs.V2RAY_CONNECTION_MODES.PROXY_ONLY
                } else {
                    AppConfigs.V2RAY_CONNECTION_MODE = AppConfigs.V2RAY_CONNECTION_MODES.VPN_TUN
                }

                try {
                    val jsonConfig = org.json.JSONObject(config.V2RAY_FULL_JSON_CONFIG)
                    val outbounds = jsonConfig.optJSONArray("outbounds")
                    if (outbounds != null && outbounds.length() > 0) {
                        val firstOutbound = outbounds.getJSONObject(0)
                        val settings = firstOutbound.optJSONObject("settings")
                        val vnext = settings?.optJSONArray("vnext")
                        if (vnext != null && vnext.length() > 0) {
                            val server = vnext.getJSONObject(0)
                            config.CONNECTED_V2RAY_SERVER_ADDRESS = server.optString("address", "")
                            config.CONNECTED_V2RAY_SERVER_PORT = server.optInt("port", 0).toString()
                        } else if (settings != null) {
                            config.CONNECTED_V2RAY_SERVER_ADDRESS = settings.optString("address", "")
                            config.CONNECTED_V2RAY_SERVER_PORT = settings.optInt("port", 0).toString()
                        }
                    }
                } catch (_: Exception) {
                    // Optional metadata only; runtime config validation happens downstream.
                }

                val intent = Intent(context, XrayVPNService::class.java)
                intent.putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE)
                intent.putExtra("V2RAY_CONFIG", config)
                intent.putExtra("PROXY_ONLY", call.argument<Boolean>("proxy_only") ?: false)

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    result.success(null)
                } catch (e: Exception) {
                    if (expectedRuntimeToken == runtimeToken) expectedRuntimeToken = null
                    result.error("START_FAILED", e.message ?: "Could not start VPN service", null)
                }
            }

            "stopVless" -> {
                if (pendingStopResult != null) {
                    result.error("STOP_IN_PROGRESS", "VPN shutdown is already in progress", null)
                    return
                }

                // A STOP without a generation token can kill a newer runtime after
                // UI/process churn. No token means this bridge owns nothing to stop.
                val token = expectedRuntimeToken
                if (token == null) {
                    result.success(null)
                    return
                }

                val intent = Intent(context, XrayVPNService::class.java)
                intent.putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE)
                intent.putExtra("RUNTIME_TOKEN", token)

                try {
                    context.startService(intent)
                    pendingStopResult = result
                    mainHandler.removeCallbacks(stopTimeoutRunnable)
                    mainHandler.postDelayed(stopTimeoutRunnable, 6_000L)
                } catch (e: Exception) {
                    pendingStopResult = null
                    mainHandler.removeCallbacks(stopTimeoutRunnable)
                    result.error("STOP_FAILED", e.message ?: "Could not stop VPN service", null)
                }
            }

            "setSessionDeadline" -> {
                val token = expectedRuntimeToken
                val remainingSeconds = call.argument<Number>("remainingSeconds")?.toLong()
                if (token == null) {
                    result.error("NO_RUNTIME", "No active runtime token", null)
                    return
                }
                if (remainingSeconds == null || remainingSeconds < 0) {
                    result.error("INVALID_DEADLINE", "remainingSeconds must be non-negative", null)
                    return
                }

                val intent = Intent(context, XrayVPNService::class.java)
                intent.putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.UPDATE_SESSION_DEADLINE)
                intent.putExtra("RUNTIME_TOKEN", token)
                intent.putExtra("REMAINING_SECONDS", remainingSeconds)
                try {
                    context.startService(intent)
                    result.success(null)
                } catch (e: Exception) {
                    result.error("DEADLINE_FAILED", e.message ?: "Could not update session deadline", null)
                }
            }

            "initializeVless" -> {
                val iconResourceName = call.argument<String>("notificationIconResourceName")
                val iconResourceType = call.argument<String>("notificationIconResourceType")
                if (iconResourceName != null && iconResourceType != null) {
                    AppConfigs.NOTIFICATION_ICON_RESOURCE_NAME = iconResourceName
                    AppConfigs.NOTIFICATION_ICON_RESOURCE_TYPE = iconResourceType
                }
                result.success(null)
            }

            "getCoreVersion" -> {
                executor.submit {
                    try {
                        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
                        val xrayExecutable = File(nativeLibraryDir, "libxray.so")
                        if (xrayExecutable.exists()) {
                            val p = Runtime.getRuntime().exec(arrayOf(xrayExecutable.absolutePath, "-version"))
                            val reader = BufferedReader(InputStreamReader(p.inputStream))
                            val version = reader.readLine()
                            result.success(version)
                        } else {
                            result.success("Xray not found")
                        }
                    } catch (e: Exception) {
                        result.success("Error: ${e.message}")
                    }
                }
            }

            // POST_NOTIFICATIONS without VpnService.prepare, for proxy mode.
            "requestNotificationPermission" -> {
                val currentActivity = activity
                if (currentActivity == null) {
                    result.error("NO_ACTIVITY", "Activity is not attached", null)
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ActivityCompat.checkSelfPermission(
                        currentActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        currentActivity,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_CODE_POST_NOTIFICATIONS,
                    )
                }
                result.success(null)
            }

            "requestPermission" -> {
                val currentActivity = activity
                if (currentActivity == null) {
                    result.error("NO_ACTIVITY", "Activity is not attached", null)
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ActivityCompat.checkSelfPermission(currentActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(currentActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_POST_NOTIFICATIONS)
                    }
                }
                val request = VpnService.prepare(currentActivity)
                if (request != null) {
                    pendingResult = result
                    currentActivity.startActivityForResult(request, REQUEST_CODE_VPN_PERMISSION)
                } else {
                    result.success(true)
                }
            }

            else -> result.notImplemented()
        }
    }

    private fun registerReceiver() {
        if (activity == null) return
        if (xrayReceiver == null) {
            xrayReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null || vpnStatusSink == null) return
                    val state = intent.getSerializableExtra("STATE") as? AppConfigs.V2RAY_STATES
                    val runtimeToken = intent.getStringExtra("RUNTIME_TOKEN").orEmpty()
                    val runtimeReady = intent.getBooleanExtra("RUNTIME_READY", false)
                    val duration = intent.getStringExtra("DURATION")
                    val uploadSpeed = intent.getLongExtra("UPLOAD_SPEED", 0)
                    val downloadSpeed = intent.getLongExtra("DOWNLOAD_SPEED", 0)
                    val uploadTraffic = intent.getLongExtra("UPLOAD_TRAFFIC", 0)
                    val downloadTraffic = intent.getLongExtra("DOWNLOAD_TRAFFIC", 0)

                    val currentToken = expectedRuntimeToken
                    if (currentToken == null) {
                        if (runtimeToken.isNotEmpty() &&
                            (state == AppConfigs.V2RAY_STATES.V2RAY_CONNECTED ||
                             state == AppConfigs.V2RAY_STATES.V2RAY_CONNECTING)
                        ) {
                            expectedRuntimeToken = runtimeToken
                        } else {
                            return
                        }
                    } else if (runtimeToken != currentToken) {
                        return
                    }

                    val stateName = when (state) {
                        AppConfigs.V2RAY_STATES.V2RAY_CONNECTED -> if (runtimeReady) "CONNECTED" else "CONNECTING"
                        AppConfigs.V2RAY_STATES.V2RAY_CONNECTING -> "CONNECTING"
                        else -> "DISCONNECTED"
                    }

                    val data = ArrayList<String>()
                    data.add(duration ?: "0")
                    data.add(uploadSpeed.toString())
                    data.add(downloadSpeed.toString())
                    data.add(uploadTraffic.toString())
                    data.add(downloadTraffic.toString())
                    data.add(stateName)

                    if (state == AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED &&
                        runtimeToken.isNotEmpty() && runtimeToken == expectedRuntimeToken
                    ) {
                        expectedRuntimeToken = null
                        mainHandler.removeCallbacks(stopTimeoutRunnable)
                        pendingStopResult?.success(null)
                        pendingStopResult = null
                    }

                    vpnStatusSink?.success(data)
                }
            }
        }

        val filter = IntentFilter(AppConfigs.V2RAY_CONNECTION_INFO)
        val currentActivity = activity ?: return
        ContextCompat.registerReceiver(
            currentActivity,
            xrayReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterReceiver() {
        val currentActivity = activity
        val receiver = xrayReceiver
        if (currentActivity != null && receiver != null) {
            try {
                currentActivity.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "Receiver was already detached during lifecycle cleanup", e)
            }
        }
        xrayReceiver = null
    }

    private fun failPendingPermission(code: String, message: String) {
        pendingResult?.error(code, message, null)
        pendingResult = null
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        mainHandler.removeCallbacks(stopTimeoutRunnable)
        pendingStopResult?.error("ENGINE_DETACHED", "Flutter engine detached during VPN shutdown", null)
        pendingStopResult = null
        failPendingPermission("ENGINE_DETACHED", "Flutter engine detached during VPN permission request")
        unregisterReceiver()
        vpnControlMethod.setMethodCallHandler(null)
        vpnStatusEvent.setStreamHandler(null)
        executor.shutdown()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
        if (vpnStatusSink != null) {
            registerReceiver()
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        unregisterReceiver()
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
        if (vpnStatusSink != null) {
            registerReceiver()
        }
    }

    override fun onDetachedFromActivity() {
        failPendingPermission("NO_ACTIVITY", "Activity detached during VPN permission request")
        unregisterReceiver()
        activity = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_CODE_VPN_PERMISSION) {
            if (resultCode == Activity.RESULT_OK) {
                pendingResult?.success(true)
            } else {
                pendingResult?.success(false)
            }
            pendingResult = null
            return true
        }
        return false
    }
}
