package ia.assistant.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.ConsumerIrManager
import android.os.BatteryManager
import android.os.Build
import ia.assistant.core.AssistantRuntime
import ia.assistant.core.infrared.IrSignal
import ia.assistant.core.infrared.IrTransmitter
import ia.assistant.core.tools.ConfirmationProvider
import ia.assistant.core.tools.ConfirmationRequest
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Émetteur infrarouge du téléphone (le petit hublot en haut du Redmi). */
class AndroidIrTransmitter(context: Context) : IrTransmitter {
    private val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    override val isAvailable: Boolean
        get() = manager?.hasIrEmitter() == true

    override fun transmit(signal: IrSignal) {
        val ir = manager?.takeIf { it.hasIrEmitter() } ?: error("Aucun émetteur infrarouge sur ce téléphone.")
        ir.transmit(signal.carrierFrequencyHz, signal.pattern)
    }

    /** « disponible (30–60 kHz) » : utile pour vérifier que la fréquence de la TV est acceptée. */
    fun describe(): String {
        val ir = manager ?: return "absent (service introuvable)"
        if (!ir.hasIrEmitter()) return "absent"
        val ranges = runCatching { ir.carrierFrequencies }.getOrNull()
            ?.joinToString { "${it.minFrequency / 1000}–${it.maxFrequency / 1000} kHz" }
        return "disponible" + (ranges?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
    }
}

object DeviceStatus {
    fun read(context: Context, runtime: AssistantRuntime): Map<String, String> {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = battery?.isCharging == true
        return linkedMapOf(
            "Appareil" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Batterie" to "${level ?: "?"} %" + if (charging) " (en charge)" else "",
            "Infrarouge" to AndroidIrTransmitter(context).describe(),
            "Adresses réseau" to localAddresses().joinToString { "${it.second} (${it.first})" }.ifEmpty { "aucune" },
            "Pont avec Claude" to if (runtime.isBridgeRunning) "actif" else "arrêté",
            "Arrêt d'urgence" to if (runtime.emergencyStop.engaged) "ENGAGÉ" else "non",
        )
    }

    /** (interface, adresse IPv4) : wlan0 pour le Wi-Fi, tun0 pour Tailscale / VPN. */
    fun localAddresses(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it.hostAddress.orEmpty() }
            }
            .sortedBy { if (it.first.startsWith("wlan")) 0 else 1 }
    }.getOrDefault(emptyList())
}

object Notifications {
    const val CHANNEL_BRIDGE = "pont"
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_CONFIRM = "validations"
    private val messageIds = AtomicInteger(2000)

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BRIDGE, "Pont avec Claude", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages de l'assistant", NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONFIRM, "Demandes de validation", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    fun showMessage(context: Context, title: String, message: String) {
        val notification = Notification.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(messageIds.incrementAndGet(), notification)
    }
}

/**
 * Demande au propriétaire de valider une action par une notification « Valider / Refuser ».
 * Le téléphone doit être déverrouillé pour répondre. Sans réponse sous 60 s : refus.
 */
class NotificationConfirmationProvider(private val context: Context) : ConfirmationProvider {
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Boolean>>()
    private val ids = AtomicInteger(1000)

    override fun requestConfirmation(request: ConfirmationRequest): Boolean {
        val id = ids.incrementAndGet()
        val answer = CompletableFuture<Boolean>()
        pending[id] = answer
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(id, buildNotification(id, request))
        return try {
            answer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            false
        } finally {
            pending.remove(id)
            nm.cancel(id)
        }
    }

    fun resolve(id: Int, approved: Boolean) {
        pending[id]?.complete(approved)
    }

    private fun buildNotification(id: Int, request: ConfirmationRequest): Notification {
        val text = "${request.principal.displayName} demande « ${request.toolName} » ${request.arguments}\n${request.reason}"
        return Notification.Builder(context, Notifications.CHANNEL_CONFIRM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Validation demandée")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setTimeoutAfter(TIMEOUT_SECONDS * 1000)
            .addAction(action(id, approved = true, "Valider"))
            .addAction(action(id, approved = false, "Refuser"))
            .build()
    }

    private fun action(id: Int, approved: Boolean, label: String): Notification.Action {
        val intent = Intent(context, ConfirmationReceiver::class.java)
            .setAction(ConfirmationReceiver.ACTION)
            .putExtra(ConfirmationReceiver.EXTRA_ID, id)
            .putExtra(ConfirmationReceiver.EXTRA_APPROVED, approved)
        val pi = PendingIntent.getBroadcast(
            context, id * 2 + if (approved) 1 else 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, label, pi).apply {
            // Exige un téléphone déverrouillé (empreinte, visage ou code) pour répondre.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAuthenticationRequired(true)
        }.build()
    }

    companion object {
        private const val TIMEOUT_SECONDS = 60L
    }
}

class ConfirmationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as AssistantApp
        app.confirmations.resolve(intent.getIntExtra(EXTRA_ID, -1), intent.getBooleanExtra(EXTRA_APPROVED, false))
    }

    companion object {
        const val ACTION = "ia.assistant.android.VALIDATION"
        const val EXTRA_ID = "id"
        const val EXTRA_APPROVED = "valide"
    }
}
