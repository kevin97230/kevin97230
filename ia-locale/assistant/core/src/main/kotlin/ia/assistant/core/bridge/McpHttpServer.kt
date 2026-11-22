package ia.assistant.core.bridge

import fi.iki.elonen.NanoHTTPD
import ia.assistant.core.security.Principal
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Associe des jetons secrets (un par appareil appairé) à des personnes. */
class TokenAuthenticator {
    private val tokens = ConcurrentHashMap<String, Principal>()

    fun register(token: String, principal: Principal) {
        require(token.length >= 32) { "Jeton trop court." }
        tokens[token] = principal
    }

    fun revoke(token: String) {
        tokens.remove(token)
    }

    fun revokeAll() = tokens.clear()

    /** Comparaison à temps constant pour ne rien laisser deviner du jeton. */
    fun authenticate(candidate: String): Principal? {
        val candidateBytes = candidate.toByteArray(Charsets.UTF_8)
        var found: Principal? = null
        for ((token, principal) in tokens) {
            if (MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), candidateBytes)) found = principal
        }
        return found
    }

    companion object {
        fun generateToken(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

/**
 * Transport HTTP du serveur MCP (« Streamable HTTP », réponses JSON simples) sur `POST /mcp`.
 *
 * Sécurité :
 * - jeton obligatoire (`Authorization: Bearer …`) ;
 * - requêtes venant d'un navigateur (en-tête `Origin`) refusées, contre les attaques par rebond DNS ;
 * - taille des requêtes limitée.
 *
 * Le trafic n'est pas chiffré : utilisez un réseau privé chiffré (Tailscale) hors du Wi-Fi de la maison.
 */
class McpHttpServer(
    host: String,
    port: Int,
    private val mcp: McpServer,
    private val authenticator: TokenAuthenticator,
) : NanoHTTPD(host, port) {

    override fun serve(session: IHTTPSession): Response {
        // Le corps est toujours lu en entier avant de répondre, sinon ses restes
        // corrompraient la requête suivante sur la même connexion.
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY_BYTES) return refuse(Response.Status.BAD_REQUEST, "Taille de requête invalide.")
        val body = readExactly(session.inputStream, length).toString(Charsets.UTF_8)

        if (session.uri != PATH) return refuse(Response.Status.NOT_FOUND, "Introuvable. Point d'accès : $PATH")

        if (session.headers.containsKey("origin")) {
            return refuse(Response.Status.FORBIDDEN, "Requêtes de navigateur refusées.")
        }

        val principal = session.headers["authorization"]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()
            ?.let(authenticator::authenticate)
            ?: return refuse(Response.Status.UNAUTHORIZED, "Jeton manquant ou invalide.")

        if (session.method != Method.POST) {
            // Pas de flux d'événements serveur ni de sessions : GET et DELETE ne sont pas pris en charge.
            return refuse(Response.Status.METHOD_NOT_ALLOWED, "Méthode non prise en charge.").apply {
                addHeader("Allow", "POST")
            }
        }
        if (body.isEmpty()) return refuse(Response.Status.BAD_REQUEST, "Requête vide.")

        val reply = mcp.handle(body, principal)
            ?: return newFixedLengthResponse(Response.Status.ACCEPTED, MIME_PLAINTEXT, "")
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", reply)
    }


    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    /** Réponse d'erreur ; la connexion est fermée par prudence. */
    private fun refuse(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", message).apply { closeConnection(true) }

    companion object {
        const val PATH = "/mcp"
        const val DEFAULT_PORT = 8765
        private const val MAX_BODY_BYTES = 1_000_000
    }
}
