package ir.jrweb.avangvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

class AvangVpnService : VpnService(), CoreCallbackHandler {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null

    override fun onCreate() {
        super.onCreate()
        Libv2ray.initCoreEnv(filesDir.absolutePath, "")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_VPN") {
            stopVpn()
            return START_NOT_STICKY
        }
        val configName = intent?.getStringExtra("CONFIG_NAME") ?: "Avang Server"
        val jsonConfig = intent?.getStringExtra("JSON_CONFIG") ?: return START_NOT_STICKY

        startForegroundNotification(configName)
        Thread {
            startCoreTunnel(configName, jsonConfig)
        }.start()
        return START_STICKY
    }

    private fun startForegroundNotification(serverName: String) {
        val channelId = "avang_vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "آونگ وی‌پی‌ان", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("آونگ وی‌پی‌ان متصل است")
            .setContentText("سرور فعال: $serverName")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notification)
        }
    }

    private fun startCoreTunnel(serverName: String, jsonConfig: String) {
        try {
            if (vpnInterface != null) {
                vpnInterface?.close()
                vpnInterface = null
            }

            val builder = Builder()
                .setSession("AvangVPN - $serverName")
                .setMtu(1500)
                .addAddress("26.26.26.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }

            vpnInterface = builder.establish()
            val fd = vpnInterface?.fd ?: return

            coreController?.stopLoop()
            coreController = Libv2ray.newCoreController(this)
            coreController?.startLoop(jsonConfig, fd)
        } catch (e: Exception) {
            e.printStackTrace()
            stopVpn()
        }
    }

    override fun shutdown(): Long {
        stopVpn()
        return 0
    }

    override fun onEmitStatus(l: Long, s: String?): Long = 0

    override fun startup(): Long = 0

    private fun stopVpn() {
        try {
            coreController?.stopLoop()
            coreController = null
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
