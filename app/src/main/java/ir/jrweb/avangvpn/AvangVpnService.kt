package ir.jrweb.avangvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet

class AvangVpnService : VpnService(), V2RayVPNServiceSupportsSet {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var v2rayPoint: V2RayPoint? = null

    override fun onCreate() {
        super.onCreate()
        Libv2ray.initV2Env(filesDir.absolutePath, "")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_VPN") {
            stopVpn()
            return START_NOT_STICKY
        }
        val configName = intent?.getStringExtra("CONFIG_NAME") ?: "Avang Server"
        val jsonConfig = intent?.getStringExtra("JSON_CONFIG") ?: return START_NOT_STICKY
        startForegroundNotification(configName)
        startCoreTunnel(configName, jsonConfig)
        return START_STICKY
    }

    private fun startForegroundNotification(serverName: String) {
        val channelId = "avang_vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Ø¢ÙˆÙ†Ú¯ ÙˆÛŒâ€ŒÙ¾ÛŒâ€ŒØ§Ù†", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channelId) else Notification.Builder(this)
        val notification = builder.setContentTitle("Ø¢ÙˆÙ†Ú¯ ÙˆÛŒâ€ŒÙ¾ÛŒâ€ŒØ§Ù† Ù…ØªØµÙ„ Ø§Ø³Øª").setContentText("Ø³Ø±ÙˆØ± ÙØ¹Ø§Ù„: $serverName").setSmallIcon(android.R.drawable.ic_lock_lock).build()
        startForeground(101, notification)
    }

    private fun startCoreTunnel(serverName: String, jsonConfig: String) {
        try {
            if (vpnInterface == null) {
                val builder = Builder().setSession("AvangVPN - $serverName").setMtu(1500).addAddress("10.0.0.2", 30).addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1")
                try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
                vpnInterface = builder.establish()
            }
            v2rayPoint?.stopLoop()
            v2rayPoint = Libv2ray.newV2RayPoint(this, false)
            v2rayPoint?.configureFileContent = jsonConfig
            v2rayPoint?.domainName = serverName
            v2rayPoint?.runLoop(false)
        } catch (e: Exception) {
            e.printStackTrace()
            stopVpn()
        }
    }

    override fun shutdown(): Long { stopVpn(); return 0 }
    override fun prepare(): Long = 0
    override fun protect(l: Long): Boolean = protect(l.toInt())
    override fun onEmitStatus(l: Long, s: String?): Long = 0
    override fun setup(s: String?): Long = 0

    private fun stopVpn() {
        try { v2rayPoint?.stopLoop(); v2rayPoint = null; vpnInterface?.close(); vpnInterface = null } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }
}