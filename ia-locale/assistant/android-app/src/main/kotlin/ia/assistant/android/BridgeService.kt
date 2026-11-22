package ia.assistant.android

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import ia.assistant.core.bridge.McpHttpServer

/**
 * Service de premier plan qui garde le pont avec Claude ouvert (serveur MCP sur le port 8765),
 * même écran éteint. Une notification permanente l'indique, avec un bouton d'arrêt d'urgence.
 */
class BridgeService : Service() {
    private val app get() = application as AssistantApp
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EMERGENCY -> app.runtime.emergencyStop.engage()
        }

        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        if (!app.runtime.isBridgeRunning) {
            Thread {
                try {
                    app.runtime.startBridge(McpHttpServer.DEFAULT_PORT)
                    acquireWifiLock()
                } catch (e: Exception) {
                    Notifications.showMessage(this, "Pont avec Claude", "Impossible de démarrer : ${e.message}")
                    stopSelf()
                }
            }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        app.runtime.stopBridge()
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    /** Évite que le Wi-Fi s'endorme écran éteint, pour que Claude puisse joindre le téléphone. */
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "assistant:pont").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun buildNotification(): Notification {
        val emergency = app.runtime.emergencyStop.engaged
        val address = DeviceStatus.localAddresses().firstOrNull()?.second ?: "?"
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, Notifications.CHANNEL_BRIDGE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(if (emergency) "⛔ Arrêt d'urgence engagé" else "Pont avec Claude actif")
            .setContentText("http://$address:${McpHttpServer.DEFAULT_PORT}${McpHttpServer.PATH}")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Arrêt d'urgence", servicePendingIntent(this, ACTION_EMERGENCY, 1)).build())
            .addAction(Notification.Action.Builder(null, "Couper le pont", servicePendingIntent(this, ACTION_STOP, 2)).build())
            .build()
    }

    companion object {
        const val ACTION_START = "ia.assistant.android.PONT_DEMARRER"
        const val ACTION_STOP = "ia.assistant.android.PONT_ARRETER"
        const val ACTION_EMERGENCY = "ia.assistant.android.ARRET_URGENCE"
        private const val NOTIFICATION_ID = 1

        fun intent(context: Context, action: String) = Intent(context, BridgeService::class.java).setAction(action)

        private fun servicePendingIntent(context: Context, action: String, code: Int) =
            PendingIntent.getService(context, code, intent(context, action), PendingIntent.FLAG_IMMUTABLE)
    }
}
