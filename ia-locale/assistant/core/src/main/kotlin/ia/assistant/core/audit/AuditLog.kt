package ia.assistant.core.audit

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

enum class AuditOutcome {
    EXECUTED,
    DENIED,
    REJECTED_BY_OWNER,
    FAILED,
}

/** Une ligne du journal de bord : qui a demandé quoi, quand, et ce qui s'est passé. */
data class AuditEntry(
    val timestampMillis: Long,
    val principalId: String,
    val principalName: String,
    val tool: String,
    val arguments: String,
    val outcome: AuditOutcome,
    val detail: String,
)

interface AuditLog {
    fun record(entry: AuditEntry)

    /** Les entrées les plus récentes, de la plus ancienne à la plus récente. */
    fun recent(limit: Int = 50): List<AuditEntry>
}

class InMemoryAuditLog(private val capacity: Int = 1000) : AuditLog {
    private val entries = ArrayDeque<AuditEntry>()

    @Synchronized
    override fun record(entry: AuditEntry) {
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    override fun recent(limit: Int): List<AuditEntry> = entries.toList().takeLast(limit)
}

/** Journal conservé en mémoire et ajouté à un fichier (une ligne JSON par action). */
class FileAuditLog(
    private val file: File,
    private val memory: InMemoryAuditLog = InMemoryAuditLog(),
) : AuditLog {
    @Synchronized
    override fun record(entry: AuditEntry) {
        memory.record(entry)
        val line = buildJsonObject {
            put("horodatage", JsonPrimitive(entry.timestampMillis))
            put("qui", JsonPrimitive(entry.principalId))
            put("nom", JsonPrimitive(entry.principalName))
            put("outil", JsonPrimitive(entry.tool))
            put("arguments", JsonPrimitive(entry.arguments))
            put("resultat", JsonPrimitive(entry.outcome.name))
            put("detail", JsonPrimitive(entry.detail))
        }.toString()
        file.parentFile?.mkdirs()
        file.appendText(line + "\n", Charsets.UTF_8)
    }

    override fun recent(limit: Int): List<AuditEntry> = memory.recent(limit)
}
