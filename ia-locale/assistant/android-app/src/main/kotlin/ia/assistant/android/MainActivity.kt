package ia.assistant.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ia.assistant.android.ui.ButtonStyle
import ia.assistant.android.ui.Palette
import ia.assistant.android.ui.Ui
import ia.assistant.android.ui.remote
import ia.assistant.android.ui.setChip
import ia.assistant.core.androidtv.AndroidTvKeys
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
 * Écran principal, en trois onglets :
 * - TV : une seule télécommande, par le Wi-Fi ou par l'infrarouge (interrupteur) ;
 * - Décodeur : télécommande du décodeur Orange ;
 * - Réglages : pont avec Claude, sécurité, journal de bord.
 */
class MainActivity : Activity() {
    private val app get() = application as AssistantApp
    private val runtime get() = app.runtime
    private val prefs get() = getSharedPreferences("interface", Context.MODE_PRIVATE)
    private lateinit var ui: Ui

    private lateinit var bridgeChip: TextView
    private lateinit var irChip: TextView
    private lateinit var safetyChip: TextView
    private lateinit var resultBanner: TextView
    private lateinit var pages: List<View>
    private lateinit var scroll: ScrollView

    private lateinit var tvStatus: TextView
    private lateinit var tvWifiActions: LinearLayout
    private lateinit var tvRemoteHolder: FrameLayout
    private lateinit var decoderStatus: TextView
    private lateinit var decoderNetworkActions: LinearLayout
    private lateinit var decoderRemoteHolder: FrameLayout
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

    // ------------------------------------------------------------------ Structure

    private fun buildUi(): View {
        val column = ui.vertical().apply { setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(32)) }

        // En-tête et pastilles d'état.
        column.addView(ui.text("Assistant", Palette.TEXT, 28f, bold = true))
        column.addView(ui.text("Votre IA personnelle · ${Build.MODEL}", Palette.MUTED, 13f))
        bridgeChip = ui.chip("Pont", Palette.MUTED)
        irChip = ui.chip("Infrarouge", Palette.MUTED)
        safetyChip = ui.chip("Sécurité", Palette.MUTED)
        column.addView(ui.horizontal().apply {
            setPadding(0, ui.dp(12), 0, ui.dp(14))
            listOf(bridgeChip, irChip, safetyChip).forEachIndexed { i, chip ->
                addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) marginStart = ui.dp(8)
                })
            }
        })

        // Onglets.
        val selectedTab = prefs.getInt("onglet", 0).coerceIn(0, 2)
        column.addView(ui.segmented(listOf("TV", "Décodeur", "Réglages"), selectedTab) { showPage(it) })

        // Résultat de la dernière action.
        resultBanner = ui.text("", Palette.TEXT, 14f).apply {
            visibility = View.GONE
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ui.dp(12) }
        }
        column.addView(resultBanner)

        pages = listOf(buildTvPage(), buildDecoderPage(), buildSettingsPage())
        pages.forEach { page ->
            column.addView(page, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ui.dp(14) })
        }
        showPage(selectedTab)

        scroll = ScrollView(this).apply {
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
        return scroll
    }

    private fun showPage(index: Int) {
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        prefs.edit().putInt("onglet", index).apply()
        if (::scroll.isInitialized) scroll.scrollTo(0, 0)
    }

    /** Carte sans titre qui contient une télécommande. */
    private fun remoteCard(content: View) = ui.vertical().apply {
        background = ui.rounded(Palette.CARD, 28)
        setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(18))
        addView(content)
    }

    // ------------------------------------------------------------------ Onglet TV

    private fun buildTvPage(): View = ui.vertical().apply {
        val wifi = prefs.getString("mode_tv", "wifi") != "ir"
        addView(ui.segmented(listOf("Wi-Fi", "Infrarouge"), if (wifi) 0 else 1) { i ->
            prefs.edit().putString("mode_tv", if (i == 0) "wifi" else "ir").apply()
            renderTvRemote()
        })
        tvStatus = ui.text("", Palette.MUTED, 13f).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), 0) }
        addView(tvStatus)
        tvWifiActions = ui.row(listOf(
            ui.button("Rechercher", ButtonStyle.SECONDARY, heightDp = 44) { runTool("tv_rechercher", buildJsonObject {}) },
            ui.button("Appairer", ButtonStyle.SECONDARY, heightDp = 44) { startTvPairing() },
        ))
        addView(tvWifiActions)
        tvRemoteHolder = FrameLayout(context)
        addView(tvRemoteHolder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = ui.dp(12) })
        renderTvRemote()
    }

    private fun tvUsesWifi() = prefs.getString("mode_tv", "wifi") != "ir"

    private fun renderTvRemote() {
        tvRemoteHolder.removeAllViews()
        val remote = if (tvUsesWifi()) {
            ui.remote(AndroidTvKeys.CODES.keys + listOf("netflix", "youtube", "prime_video")) { key ->
                if (key in AndroidTvKeys.APPS) {
                    runTool("tv_ouvrir_app", buildJsonObject { put("app", key) })
                } else {
                    runTool("tv_touche", buildJsonObject { put("touche", key) })
                }
            }
        } else {
            val device = runCatching { app.loadIrLibrary().let { it.get("tv_salon") ?: it.devices.firstOrNull() } }.getOrNull()
            if (device == null) {
                ui.text("Aucune TV infrarouge configurée.", Palette.MUTED, 14f)
            } else {
                ui.remote(device.buttons.keys) { key ->
                    runTool("ir_envoyer_touche", buildJsonObject {
                        put("appareil", device.id)
                        put("touche", key)
                    })
                }
            }
        }
        tvRemoteHolder.addView(remoteCard(remote))
        if (::journalList.isInitialized) refresh()
    }

    /** Appairage : la TV affiche un code, que l'on saisit ici. */
    private fun startTvPairing() {
        runTool("tv_appairer", buildJsonObject {}) { started ->
            if (started.isError) return@runTool
            val input = EditText(this).apply {
                hint = "Ex. 4F2A9C"
                isSingleLine = true
                filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(6))
                setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16))
            }
            AlertDialog.Builder(this)
                .setTitle("Code affiché sur la TV")
                .setMessage("Saisissez les 6 caractères qui s'affichent sur l'écran de la TV.")
                .setView(input)
                .setPositiveButton("Valider") { _, _ ->
                    runTool("tv_code", buildJsonObject { put("code", input.text.toString()) })
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    // ------------------------------------------------------------------ Onglet Décodeur

    private fun buildDecoderPage(): View = ui.vertical().apply {
        addView(ui.segmented(listOf("Réseau", "Infrarouge"), if (decoderUsesNetwork()) 0 else 1) { i ->
            prefs.edit().putString("mode_decodeur", if (i == 0) "reseau" else "ir").apply()
            renderDecoderRemote()
        })
        decoderStatus = ui.text("", Palette.MUTED, 13f).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), 0) }
        addView(decoderStatus)
        decoderNetworkActions = ui.row(listOf(
            ui.button("Rechercher le décodeur", ButtonStyle.SECONDARY, heightDp = 44) { runTool("decodeur_rechercher", buildJsonObject {}) },
        ))
        addView(decoderNetworkActions)
        decoderRemoteHolder = FrameLayout(context)
        addView(decoderRemoteHolder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = ui.dp(12) })
        renderDecoderRemote()
    }

    private fun decoderUsesNetwork() = prefs.getString("mode_decodeur", "reseau") != "ir"

    /** Réseau : API locale (rapide, mais le décodeur doit être allumé). Infrarouge : marche même en veille. */
    private fun renderDecoderRemote() {
        decoderRemoteHolder.removeAllViews()
        val remote = if (decoderUsesNetwork()) {
            ui.remote(OrangeKeys.CODES.keys) { key -> runTool("decodeur_touche", buildJsonObject { put("touche", key) }) }
        } else {
            val device = runCatching { app.loadIrLibrary().get("decodeur_orange") }.getOrNull()
            if (device == null) {
                ui.text("Aucun code infrarouge pour le décodeur.", Palette.MUTED, 14f)
            } else {
                ui.remote(device.buttons.keys) { key ->
                    runTool("ir_envoyer_touche", buildJsonObject {
                        put("appareil", device.id)
                        put("touche", key)
                    })
                }
            }
        }
        decoderRemoteHolder.addView(remoteCard(remote))
        if (::journalList.isInitialized) refresh()
    }

    // ------------------------------------------------------------------ Onglet Réglages

    private fun buildSettingsPage(): View = ui.vertical().apply {
        addView(ui.card("Pont avec Claude", "Claude sur l'ordinateur pilote le téléphone").apply {
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
                ui.button("Nouveau jeton") { confirmNewToken() },
            )))
        })

        addView(ui.card("Sécurité", "Fige immédiatement toutes les actions de l'IA").apply {
            emergencyButton = ui.button("", ButtonStyle.DANGER, heightDp = 56) { toggleEmergency() }
            addView(emergencyButton)
        })

        addView(ui.card("Téléphone", "Infrarouge, réseau, batterie").apply {
            addView(ui.button("Diagnostic du téléphone", ButtonStyle.SECONDARY) { showDiagnostic() })
        })

        addView(ui.card("Journal de bord", "Les dernières actions et leur résultat").apply {
            journalList = ui.vertical()
            addView(journalList)
        })
    }

    // ------------------------------------------------------------------ Actions

    /** Exécute un outil en tant que propriétaire, hors du fil de l'interface (réseau, infrarouge). */
    private fun runTool(name: String, args: JsonObject, onDone: (ToolResult) -> Unit = {}) {
        Thread {
            val result = runtime.executor.call(AssistantApp.LOCAL_OWNER, name, args)
            runOnUiThread {
                showResult(result)
                refresh()
                onDone(result)
            }
        }.start()
    }

    private fun showResult(result: ToolResult) {
        resultBanner.visibility = View.VISIBLE
        resultBanner.text = result.text
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

    // ------------------------------------------------------------------ Mise à jour de l'affichage

    private fun refresh() {
        val running = runtime.isBridgeRunning
        val engaged = runtime.emergencyStop.engaged
        val irOk = AndroidIrTransmitter(this).isAvailable

        bridgeChip.setChip(if (running) "Pont actif" else "Pont arrêté", if (running) Palette.SUCCESS else Palette.MUTED)
        irChip.setChip(if (irOk) "Infrarouge" else "Pas d'infrarouge", if (irOk) Palette.SUCCESS else Palette.WARNING)
        safetyChip.setChip(if (engaged) "Arrêt d'urgence" else "Sécurité OK", if (engaged) Palette.DANGER else Palette.SUCCESS)

        // TV
        val tv = runtime.androidTv
        if (tvUsesWifi()) {
            tvWifiActions.visibility = View.VISIBLE
            tvStatus.text = when {
                tv.address == null -> "TV pas encore trouvée : touchez Rechercher (la TV doit être allumée)."
                !tv.isPaired -> "TV trouvée (${tv.address}). Touchez Appairer et saisissez le code affiché sur la TV."
                else -> "Connectée par le Wi-Fi · ${tv.address}"
            }
        } else {
            tvWifiActions.visibility = View.GONE
            tvStatus.text = if (irOk) "Infrarouge · pointez le haut du téléphone vers la TV" else "Ce téléphone n'a pas d'émetteur infrarouge."
        }

        // Décodeur
        val decoder = app.decoderAddress()
        if (decoderUsesNetwork()) {
            decoderNetworkActions.visibility = View.VISIBLE
            decoderStatus.text = if (decoder == null) {
                "Décodeur pas encore trouvé : allumez-le puis touchez Rechercher."
            } else {
                "Décodeur Orange · $decoder (réseau de la maison). S'il est en veille profonde, passez en Infrarouge."
            }
        } else {
            decoderNetworkActions.visibility = View.GONE
            decoderStatus.text = "Infrarouge · fonctionne même en veille. Pointez le haut du téléphone vers le décodeur."
        }

        // Réglages
        val addresses = DeviceStatus.localAddresses()
        bridgeStatus.text = if (running) {
            "Actif sur " + addresses.joinToString { "${it.second}:${McpHttpServer.DEFAULT_PORT}" }.ifEmpty { "(pas de réseau)" }
        } else {
            "Arrêté. Démarrez-le pour que Claude puisse joindre le téléphone."
        }
        bridgeButton.text = if (running) "Arrêter le pont" else "Démarrer le pont"
        emergencyButton.text = if (engaged) "Lever l'arrêt d'urgence" else "ARRÊT D'URGENCE"

        journalList.removeAllViews()
        val format = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
        val entries = runtime.audit.recent(12).reversed()
        if (entries.isEmpty()) journalList.addView(ui.text("Aucune action pour l'instant.", Palette.MUTED, 13f))
        entries.forEach { entry ->
            val ok = entry.outcome == AuditOutcome.EXECUTED
            journalList.addView(ui.horizontal().apply {
                gravity = Gravity.TOP
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                addView(ui.text("●", if (ok) Palette.SUCCESS else Palette.DANGER, 12f).apply { setPadding(0, ui.dp(2), ui.dp(10), 0) })
                addView(ui.vertical().apply {
                    addView(ui.text("${format.format(Date(entry.timestampMillis))}  ·  ${entry.tool}", Palette.TEXT, 13f, bold = true))
                    addView(ui.text(entry.detail.take(160), Palette.MUTED, 12.5f))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }
}
