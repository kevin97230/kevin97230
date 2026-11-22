package ia.assistant.core

import ia.assistant.core.infrared.IrEncoder
import ia.assistant.core.infrared.IrLibrary
import ia.assistant.core.infrared.IrProtocol
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InfraredTest {
    /** Lit les 32 bits (poids faible en premier) à partir des durées de silence. */
    private fun decodeBits(pattern: IntArray, headerSize: Int = 2): Long {
        var value = 0L
        for (bit in 0 until 32) {
            val off = pattern[headerSize + bit * 2 + 1]
            if (off > 1000) value = value or (1L shl bit)
        }
        return value
    }

    @Test
    fun `NEC LG allumer`() {
        val signal = IrEncoder.encode(IrProtocol.NEC, address = 0x04, command = 0x08)
        assertEquals(38_000, signal.carrierFrequencyHz)
        assertEquals(67, signal.pattern.size)
        assertContentEquals(intArrayOf(9000, 4500), signal.pattern.copyOfRange(0, 2))
        // adresse 0x04, ~adresse 0xFB, commande 0x08, ~commande 0xF7
        assertEquals(0xF708FB04L, decodeBits(signal.pattern))
        assertEquals(562, signal.pattern.last())
    }

    @Test
    fun `NEC etendu sur 16 bits`() {
        val signal = IrEncoder.encode(IrProtocol.NEC, address = 0x1234, command = 0x01)
        assertEquals(0xFE011234L, decodeBits(signal.pattern))
    }

    @Test
    fun `Samsung32 allumer`() {
        val signal = IrEncoder.encode(IrProtocol.SAMSUNG32, address = 0x07, command = 0x02)
        assertContentEquals(intArrayOf(4500, 4500), signal.pattern.copyOfRange(0, 2))
        assertEquals(0xFD020707L, decodeBits(signal.pattern))
        assertTrue(signal.totalDurationMicros < 200_000)
    }

    @Test
    fun `bibliotheque par defaut et erreurs`() {
        val lib = IrLibrary.loadDefault()
        assertTrue(lib.devices.size >= 2)
        val lg = lib.get("tv_lg")!!
        assertEquals(IrProtocol.NEC, lg.protocol)
        assertEquals(0x08, lg.buttons["allumer"])
        assertFailsWith<IllegalArgumentException> { lg.signalFor("inexistante") }
        assertFailsWith<IllegalArgumentException> {
            IrLibrary.parse("""{"appareils":[{"id":"Mauvais Id","nom":"x","protocole":"NEC","adresse":"1","touches":{}}]}""")
        }
    }
}
