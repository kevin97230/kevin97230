package ia.assistant.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ia.assistant.core.bridge.McpHttpServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Écran principal : pont avec Claude, arrêt d'urgence, télécommande infrarouge et journal de bord.
 * Interface volontairement simple (vues Android standard) ; elle sera enrichie plus tard.
 */
class MainActivity : Activity() {
    private val app get() = application as AssistantApp
    private val runtime get() = app.runtime

    private lateinit var statusView: TextView
    private lateinit var bridgeButton: Button
    private lateinit var emergencyButton: Button
    private lateinit var journalView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        column.addView(title("Assistant — IA personnelle", 24f))

        // --- Pont avec Claude ---
        column.addView(title("Pont avec Claude", 18f))
        statusView = TextView(this).apply { textSize = 14f }
        column.addView(statusView)
        bridgeButton = button("") {
            val action = if (runtime.isBridgeRunning) BridgeService.ACTION_STOP else BridgeService.ACTION_START
            startForegroundService(BridgeService.intent(this, action))
            statusView.postDelayed({ refresh() }, 800)
        }
        column.addView(bridgeButton)
        column.addView(button("Copier la commande pour Claude (ordinateur)") { copyClaudeCommand() })
        column.addView(button("Nouveau jeton (révoque l'ancien)") { confirmNewToken() })

        // --- Sécurité ---
        column.addView(title("Sécurité", 18f))
        emergencyButton = button("") { toggleEmergency() }
        column.addView(emergencyButton)

        // --- Télécommande infrarouge ---
        column.addView(title("Télécommande infrarouge", 18f))
        if (!AndroidIrTransmitter(this).isAvailable) {
            column.addView(TextView(this).apply { text = "⚠️ Aucun émetteur infrarouge détecté sur ce téléphone." })
        }
        val devices = runCatching { app.loadIrLibrary().devices }.getOrElse { e ->
            column.addView(TextView(this).apply { text = "⚠️ Fichier appareils.json invalide : ${e.message}" })
            emptyList()
        }
        devices.forEach { device ->
            column.addView(TextView(this).apply {
                text = "${device.name} (${device.brand})"
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(8), 0, 0)
            })
            device.buttons.keys.chunked(3).forEach { row ->
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.forEach { key ->
                    line.addView(
                        button(key.replace('_', ' ')) { sendIr(device.id, key) },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                    )
                }
                column.addView(line)
            }
        }

        // --- Journal ---
        column.addView(title("Journal de bord", 18f))
        journalView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        column.addView(journalView)

        return ScrollView(this).apply {
            addView(column)
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                }
                insets
            }
            fitsSystemWindows = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        }
    }

    private fun sendIr(deviceId: String, button: String) {
        val args = buildJsonObject {
            put("appareil", deviceId)
            put("touche", button)
        }
        Thread {
            val result = runtime.executor.call(AssistantApp.LOCAL_OWNER, "ir_envoyer_touche", args)
            runOnUiThread {
                Toast.makeText(this, result.text, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }.start()
    }

    private fun toggleEmergency() {
        if (runtime.emergencyStop.engaged) {
            // Lever l'arrêt d'urgence n'est possible qu'ici, sur le téléphone.
            AlertDialog.Builder(this)
                .setTitle("Lever l'arrêt d'urgence ?")
                .setMessage("L'IA pourra de nouveau agir.")
                .setPositiveButton("Lever") { _, _ ->
                    runtime.emergencyStop.release()
                    refresh()
                }
                .setNegativeButton("Annuler", null)
                .show()
        } else {
            runtime.emergencyStop.engage()
            refresh()
        }
    }

    private fun copyClaudeCommand() {
        val address = DeviceStatus.localAddresses().firstOrNull()?.second
        if (address == null) {
            Toast.makeText(this, "Pas de réseau : connectez le téléphone au Wi-Fi.", Toast.LENGTH_LONG).show()
            return
        }
        val command = "claude mcp add --transport http assistant-telephone " +
            "http://$address:${McpHttpServer.DEFAULT_PORT}${McpHttpServer.PATH} " +
            "--header \"Authorization: Bearer ${app.ownerToken()}\""
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Commande Claude", command))
        Toast.makeText(this, "Commande copiée. Collez-la dans un terminal de l'ordinateur.", Toast.LENGTH_LONG).show()
    }

    private fun confirmNewToken() {
        AlertDialog.Builder(this)
            .setTitle("Créer un nouveau jeton ?")
            .setMessage("L'ordinateur actuellement connecté perdra l'accès. Il faudra recopier la commande.")
            .setPositiveButton("Oui") { _, _ ->
                app.regenerateToken()
                Toast.makeText(this, "Nouveau jeton créé.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun refresh() {
        val running = runtime.isBridgeRunning
        val addresses = DeviceStatus.localAddresses()
        statusView.text = if (running) {
            "✅ Actif. Adresse(s) : " + addresses.joinToString { "http://${it.second}:${McpHttpServer.DEFAULT_PORT}${McpHttpServer.PATH}" }
        } else {
            "⏹ Arrêté."
        }
        bridgeButton.text = if (running) "Arrêter le pont" else "Démarrer le pont"

        val engaged = runtime.emergencyStop.engaged
        emergencyButton.text = if (engaged) "Lever l'arrêt d'urgence" else "⛔ ARRÊT D'URGENCE"
        emergencyButton.setTextColor(if (engaged) Color.BLACK else Color.RED)

        val format = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)
        journalView.text = runtime.audit.recent(15).reversed().joinToString("\n") {
            "${format.format(Date(it.timestampMillis))} ${it.principalName} · ${it.tool} · ${it.outcome}"
        }.ifEmpty { "(vide)" }
    }

    private fun title(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
