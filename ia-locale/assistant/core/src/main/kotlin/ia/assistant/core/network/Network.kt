package ia.assistant.core.network

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Petits réglages persistants (adresse du décodeur…). Android : SharedPreferences. */
interface SettingsStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

class InMemorySettings : SettingsStore {
    private val values = ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = values[key]
    override fun set(key: String, value: String) {
        values[key] = value
    }
}

object LocalNetwork {
    /**
     * N'autorise que les adresses du réseau local (ou la machine elle-même) :
     * l'IA ne doit pas pouvoir être détournée vers Internet.
     */
    fun isLocalIpv4(host: String): Boolean {
        if (!host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return false
        val parts = host.split('.').map { it.toInt() }
        if (parts.any { it > 255 }) return false
        val (a, b) = parts
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || a == 127
    }

    /** Toutes les adresses du (des) réseau(x) Wi-Fi / Ethernet local(aux) en /24, sauf la nôtre. */
    fun neighbourHosts(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.name.startsWith("tun") }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.address as? Inet4Address }
            .map { it.hostAddress.orEmpty() }
            .filter { isLocalIpv4(it) && !it.startsWith("127.") }
            .flatMap { own ->
                val prefix = own.substringBeforeLast('.')
                (1..254).map { "$prefix.$it" }.filter { it != own }
            }
            .distinct()
    }.getOrDefault(emptyList())

    /** Teste les adresses en parallèle et renvoie celles pour lesquelles [probe] répond vrai. */
    fun scan(hosts: List<String>, threads: Int = 48, probe: (String) -> Boolean): List<String> {
        if (hosts.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(threads.coerceAtMost(hosts.size))
        try {
            val futures = hosts.map { host -> host to pool.submit<Boolean> { runCatching { probe(host) }.getOrDefault(false) } }
            return futures.filter { (_, f) -> runCatching { f.get(10, TimeUnit.SECONDS) }.getOrDefault(false) }.map { it.first }
        } finally {
            pool.shutdownNow()
        }
    }

    internal fun requireLocal(host: String) {
        require(isLocalIpv4(host)) { "Adresse « $host » refusée : seules les adresses du réseau local sont autorisées." }
        InetAddress.getByName(host) // adresse littérale : aucune résolution DNS
    }
}

/** Réglages enregistrés dans un fichier (pour le simulateur sur ordinateur). */
class FileSettings(private val file: java.io.File) : SettingsStore {
    private val props = java.util.Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    @Synchronized
    override fun get(key: String): String? = props.getProperty(key)

    @Synchronized
    override fun set(key: String, value: String) {
        props.setProperty(key, value)
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "Réglages de l'assistant") }
    }
}
