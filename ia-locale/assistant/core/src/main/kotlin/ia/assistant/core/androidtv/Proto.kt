package ia.assistant.core.androidtv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Encodeur / décodeur Protocol Buffers minimal (varints et champs de longueur variable),
 * suffisant pour le protocole de télécommande Android TV. Évite une dépendance externe.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter = apply {
        writeVarint(out, (field.toLong() shl 3) or 0L)
        writeVarint(out, value)
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter = apply {
        writeVarint(out, (field.toLong() shl 3) or 2L)
        writeVarint(out, value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter = bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Message décodé : numéro de champ → valeurs (Long pour un varint, ByteArray sinon). */
class ProtoMessage(private val fields: Map<Int, List<Any>>) {
    fun has(field: Int): Boolean = fields.containsKey(field)
    fun long(field: Int): Long? = fields[field]?.firstOrNull() as? Long
    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray
    fun message(field: Int): ProtoMessage? = bytes(field)?.let(::parse)
    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
    val fieldNumbers: Set<Int> get() = fields.keys

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = linkedMapOf<Int, MutableList<Any>>()
            var pos = 0
            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IllegalArgumentException("Message tronqué.")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IllegalArgumentException("Varint trop long.")
                }
            }
            while (pos < data.size) {
                val key = readVarint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> fields.getOrPut(field) { mutableListOf() }.add(readVarint())
                    2 -> {
                        val len = readVarint().toInt()
                        require(len >= 0 && pos + len <= data.size) { "Longueur invalide." }
                        fields.getOrPut(field) { mutableListOf() }.add(data.copyOfRange(pos, pos + len))
                        pos += len
                    }
                    1 -> pos += 8
                    5 -> pos += 4
                    else -> throw IllegalArgumentException("Type de champ non pris en charge.")
                }
            }
            return ProtoMessage(fields)
        }
    }
}

internal fun writeVarint(out: OutputStream, value: Long) {
    var v = value
    while (true) {
        if (v and 0x7FL.inv() == 0L) {
            out.write(v.toInt())
            return
        }
        out.write(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
}

internal fun readVarint(input: InputStream): Long {
    var result = 0L
    var shift = 0
    while (true) {
        val b = input.read()
        if (b < 0) throw EOFException("Connexion fermée par la TV.")
        result = result or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) return result
        shift += 7
        if (shift > 63) throw IllegalArgumentException("Varint trop long.")
    }
}

/** Les messages sont précédés de leur longueur (varint). */
internal fun writeFrame(out: OutputStream, message: ByteArray) {
    val buffer = ByteArrayOutputStream()
    writeVarint(buffer, message.size.toLong())
    buffer.write(message)
    out.write(buffer.toByteArray())
    out.flush()
}

internal fun readFrame(input: InputStream, maxSize: Int = 64 * 1024): ByteArray {
    val size = readVarint(input).toInt()
    require(size in 0..maxSize) { "Message trop grand : $size octets." }
    val data = ByteArray(size)
    var read = 0
    while (read < size) {
        val n = input.read(data, read, size - read)
        if (n < 0) throw EOFException("Connexion fermée par la TV.")
        read += n
    }
    return data
}
