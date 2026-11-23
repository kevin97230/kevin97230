package ia.assistant.android

import android.app.Application
import android.content.Context
import ia.assistant.core.AssistantRuntime
import ia.assistant.core.PlatformServices
import ia.assistant.core.audit.FileAuditLog
import ia.assistant.core.bridge.TokenAuthenticator
import ia.assistant.core.infrared.IrLibrary
import ia.assistant.core.network.SettingsStore
import ia.assistant.core.security.Principal
import ia.assistant.core.security.Role
import java.io.File

/** Point d'entrée : assemble l'IA une seule fois pour toute l'application. */
class AssistantApp : Application() {
    lateinit var runtime: AssistantRuntime
        private set
    lateinit var confirmations: NotificationConfirmationProvider
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        confirmations = NotificationConfirmationProvider(this)
        runtime = AssistantRuntime(
            PlatformServices(
                irTransmitter = AndroidIrTransmitter(this),
                notifier = { title, message -> Notifications.showMessage(this, title, message) },
                deviceStatus = { DeviceStatus.read(this, runtime) },
                confirmations = confirmations,
                audit = FileAuditLog(File(filesDir, "journal.jsonl")),
                irLibrary = ::loadIrLibrary,
                settings = PreferencesSettings(getSharedPreferences("reglages", Context.MODE_PRIVATE)),
            ),
        )
        runtime.pairOwnerComputer(ownerToken())
    }

    /** Adresse du décodeur Orange retenue par la recherche, ou null. */
    fun decoderAddress(): String? =
        getSharedPreferences("reglages", Context.MODE_PRIVATE).getString("decodeur_orange.adresse", null)

    /** Jeton secret de l'ordinateur du propriétaire, créé au premier lancement. */
    fun ownerToken(): String {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        return TokenAuthenticator.generateToken().also { prefs.edit().putString(KEY_TOKEN, it).apply() }
    }

    /** Révoque l'ancien jeton (ex. s'il a fuité) et en crée un nouveau. */
    fun regenerateToken(): String {
        runtime.authenticator.revokeAll()
        val token = TokenAuthenticator.generateToken()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, token).apply()
        runtime.pairOwnerComputer(token)
        return token
    }

    /**
     * Vos appareils : fichier `appareils.json` dans le dossier de l'appli s'il existe,
     * sinon la bibliothèque d'exemple.
     */
    fun loadIrLibrary(): IrLibrary {
        val custom = File(filesDir, "appareils.json")
        return if (custom.exists()) IrLibrary.parse(custom.readText()) else IrLibrary.loadDefault()
    }

    companion object {
        private const val PREFS = "assistant"
        private const val KEY_TOKEN = "jeton_proprietaire"

        /** Le propriétaire quand il agit directement sur son téléphone. */
        val LOCAL_OWNER = Principal(id = "proprietaire-local", displayName = "Propriétaire (téléphone)", role = Role.OWNER)
    }
}

/** Réglages conservés dans le téléphone. */
class PreferencesSettings(private val prefs: android.content.SharedPreferences) : SettingsStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
