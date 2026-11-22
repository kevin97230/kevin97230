package ia.assistant.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ia.assistant.android.ui.ButtonStyle
import ia.assistant.android.ui.Palette
import ia.assistant.android.ui.Ui
import ia.assistant.android.ui.remote
import ia.assistant.android.ui.setChip
import ia.assistant.core.audit.AuditOutcome
import ia.assistant.core.bridge.McpHttpServer
import ia.assistant.core.network.OrangeKeys
import ia.assistant.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Écran principal : état, télécommandes (TV, décodeur), pont avec Claude, sécurité et journal.
 */
class MainActivity : Activity() {
    private val app get() = application as AssistantApp
    private val runtime get() = app.runtime
    private lateinit var ui: Ui

    private lateinit var bridgeChip: TextView
    private lateinit var irChip: TextView
    private lateinit var safetyChip: TextView
    private lateinit var resultBanner: TextView
    private lateinit var bridgeStatus: TextView
    private lateinit var bridgeButton: TextView
    private lateinit var emergencyButton: TextView
    private lateinit var journalList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ui = Ui(this)
        window.decorView.setBackgroundColor(Palette.BACKGROUND)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi(): View {
        val column = ui.vertical().apply { setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(32)) }

        // --- En-tête ---
        column.addView(ui.text("Assistant", Palette.TEXT, 30f, bold = true))
        column.addView(ui.text("Votre IA personnelle · ${Build.MODEL}", Palette.MUTED, 13f))
        bridgeChip = ui.chip("Pont", Palette.MUTED)
        irChip = ui.chip("Infrarouge", Palette.MUTED)
        safetyChip = ui.chip("Sécurité", Palette.MUTED)
        column.addView(ui.horizontal().apply {
            setPadding(0, ui.dp(14), 0, ui.dp(14))
            listOf(bridgeChip, irChip, safetyChip).forEachIndexed { i, chip ->
                addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) marginStart = ui.dp(8)
                })
            }
        })

        // Résultat de la dernière action.
        resultBanner = ui.text("", Palette.TEXT, 14f).apply {
            visibility = View.GONE
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = ui.dp(14) }
        }
        column.addView(resultBanner)

        // --- Télécommandes infrarouges ---
        val devices = runCatching { app.loadIrLibrary().devices }.getOrElse { e ->
            column.addView(ui.card("Télécommande", "⚠️ Fichier appareils.json invalide : ${e.message}"))
            emptyList()
        }
        devices.forEach { device ->
            column.addView(ui.card(device.name, "Infrarouge · pointez le haut du téléphone vers l'appareil").apply {
                addView(ui.remote(device.buttons.keys) { key ->
                    runTool("ir_envoyer_touche", buildJsonObject {
                        put("appareil", device.id)
                        put("touche", key)
                    })
                })
            })
        }

        // --- Décodeur Orange ---
        column.addView(ui.card("Décodeur TV Orange", "Wi-Fi · pas besoin de viser").apply {
            addView(ui.button("🔍  Rechercher le décodeur", ButtonStyle.SOFT) {
                runTool("decodeur_rechercher", buildJsonObject {})
            })
            addView(ui.remote(OrangeKeys.CODES.keys) { key ->
                runTool("decodeur_touche", buildJsonObject { put("touche", key) })
            })
        })

        // --- Pont avec Claude ---
        column.addView(ui.card("Pont avec Claude", "Claude sur l'ordinateur pilote le téléphone").apply {
            bridgeStatus = ui.text("", Palette.MUTED, 13f).apply { setPadding(0, 0, 0, ui.dp(10)) }
            addView(bridgeStatus)
            bridgeButton = ui.button("", ButtonStyle.PRIMARY) {
                val action = if (runtime.isBridgeRunning) BridgeService.ACTION_STOP else BridgeService.ACTION_START
                startForegroundService(BridgeService.intent(this@MainActivity, action))
                bridgeButton.postDelayed({ refresh() }, 800)
            }
            addView(bridgeButton)
            addView(ui.row(listOf(
                ui.button("Copier la commande") { copyClaudeCommand() },
                ui.button("Diagnostic") { showDiagnostic() },
            )))
            addView(ui.row(listOf(ui.button("Nouveau jeton", ButtonStyle.SECONDARY, heightDp = 44) { confirmNewToken() })))
        })

        // --- Sécurité ---
        column.addView(ui.card("Sécurité", "Fige immédiatement toutes les actions de l'IA").apply {
            emergencyButton = ui.button("", ButtonStyle.DANGER, heightDp = 56) { toggleEmergency() }
            addView(emergencyButton)
        })

        // --- Journal ---
        column.addView(ui.card("Journal de bord", "Les dernières actions et leur résultat").apply {
            journalList = ui.vertical()
            addView(journalList)
        })

        return ScrollView(this).apply {
            setBackgroundColor(Palette.BACKGROUND)
            isVerticalScrollBarEnabled = false
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

    /** Exécute un outil en tant que propriétaire, hors du fil de l'interface (réseau, infrarouge). */
    private fun runTool(name: String, args: JsonObject) {
        Thread {
            val result = runtime.executor.call(AssistantApp.LOCAL_OWNER, name, args)
            runOnUiThread {
                showResult(result)
                refresh()
            }
        }.start()
    }

    private fun showResult(result: ToolResult) {
        resultBanner.visibility = View.VISIBLE
        resultBanner.text = (if (result.isError) "⚠️  " else "✓  ") + result.text
        resultBanner.setTextColor(if (result.isError) Palette.DANGER else Palette.TEXT)
        resultBanner.background = ui.rounded(
            if (result.isError) Palette.DANGER_SOFT else Palette.SURFACE,
            16,
            strokeColor = if (result.isError) Palette.DANGER else Palette.SUCCESS,
        )
    }

    private fun showDiagnostic() {
        val text = DeviceStatus.read(this, runtime).entries.joinToString("\n") { (k, v) -> "$k : $v" }
        AlertDialog.Builder(this).setTitle("Diagnostic").setMessage(text).setPositiveButton("OK", null).show()
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
            showResult(ToolResult.error("Pas de réseau : connectez le téléphone au Wi-Fi."))
            return
        }
        val command = "claude mcp add --transport http assistant-telephone " +
            "http://$address:${McpHttpServer.DEFAULT_PORT}${McpHttpServer.PATH} " +
            "--header \"Authorization: Bearer ${app.ownerToken()}\""
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Commande Claude", command))
        showResult(ToolResult.ok("Commande copiée. Collez-la dans PowerShell sur l'ordinateur."))
    }

    private fun confirmNewToken() {
        AlertDialog.Builder(this)
            .setTitle("Créer un nouveau jeton ?")
            .setMessage("L'ordinateur actuellement connecté perdra l'accès. Il faudra recopier la commande.")
            .setPositiveButton("Oui") { _, _ ->
                app.regenerateToken()
                showResult(ToolResult.ok("Nouveau jeton créé."))
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun refresh() {
        val running = runtime.isBridgeRunning
        val engaged = runtime.emergencyStop.engaged
        val irOk = AndroidIrTransmitter(this).isAvailable

        bridgeChip.setChip(if (running) "Pont actif" else "Pont arrêté", if (running) Palette.SUCCESS else Palette.MUTED)
        irChip.setChip(if (irOk) "Infrarouge" else "Pas d'infrarouge", if (irOk) Palette.SUCCESS else Palette.WARNING)
        safetyChip.setChip(if (engaged) "Arrêt d'urgence" else "Sécurité OK", if (engaged) Palette.DANGER else Palette.SUCCESS)

        val addresses = DeviceStatus.localAddresses()
        bridgeStatus.text = if (running) {
            "Actif sur " + addresses.joinToString { "${it.second}:${McpHttpServer.DEFAULT_PORT}" }.ifEmpty { "(pas de réseau)" }
        } else {
            "Arrêté. Démarrez-le pour que Claude puisse joindre le téléphone."
        }
        bridgeButton.text = if (running) "Arrêter le pont" else "Démarrer le pont"

        emergencyButton.text = if (engaged) "Lever l'arrêt d'urgence" else "⛔  ARRÊT D'URGENCE"

        journalList.removeAllViews()
        val format = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
        val entries = runtime.audit.recent(12).reversed()
        if (entries.isEmpty()) {
            journalList.addView(ui.text("Aucune action pour l'instant.", Palette.MUTED, 13f))
        }
        entries.forEach { entry ->
            val ok = entry.outcome == AuditOutcome.EXECUTED
            journalList.addView(ui.horizontal().apply {
                gravity = Gravity.TOP
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                addView(ui.text("●", if (ok) Palette.SUCCESS else Palette.DANGER, 12f).apply {
                    setPadding(0, ui.dp(2), ui.dp(10), 0)
                })
                addView(ui.vertical().apply {
                    addView(ui.text("${format.format(Date(entry.timestampMillis))}  ·  ${entry.tool}", Palette.TEXT, 13f, bold = true))
                    addView(ui.text(entry.detail.take(160), Palette.MUTED, 12.5f))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }
}
