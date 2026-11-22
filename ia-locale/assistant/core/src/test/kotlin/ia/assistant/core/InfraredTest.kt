package ia.assistant.core

import ia.assistant.core.infrared.IrCode
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
    private fun decodeBits(pattern: IntArray, headerSize: Int = 2, bitCount: Int = 32): Long {
        var value = 0L
        for (bit in 0 until bitCount) {
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
    fun `Kaseikyo Panasonic allumer`() {
        // Trame Panasonic « marche/arrêt » de référence : 02 20 80 00 3D BD.
        val signal = IrEncoder.encode(IrProtocol.KASEIKYO, address = 0x00200280, command = 0x03D0)
        assertEquals(37_000, signal.carrierFrequencyHz)
        assertEquals(2 + 48 * 2 + 1, signal.pattern.size)
        assertContentEquals(intArrayOf(3456, 1728), signal.pattern.copyOfRange(0, 2))
        assertEquals(0xBD3D00802002L, decodeBits(signal.pattern, bitCount = 48))
    }

    @Test
    fun `bibliotheque Panasonic par defaut`() {
        val tv = IrLibrary.loadDefault().get("tv_salon")!!
        assertEquals(IrProtocol.KASEIKYO, tv.protocol)
        assertEquals(IrCode(0x00200280, 0x03D0), tv.buttons["allumer"])
        assertEquals(IrCode(0x01200280, 0x0090), tv.buttons["ok"])
        assertTrue(tv.buttons.size >= 30)
        tv.buttons.keys.forEach { tv.signalFor(it) } // toutes les touches s'encodent
    }

    @Test
    fun `formats de nombres et erreurs`() {
        val lib = IrLibrary.parse(
            """{"appareils":[{"id":"ventilo","nom":"Ventilateur","protocole":"NEC","adresse":"0x04",
               "touches":{"marche":8,"vitesse":"0x02","oscillation":{"commande":"03"}}}]}""",
        )
        val fan = lib.get("ventilo")!!
        assertEquals(IrCode(4, 8), fan.buttons["marche"])
        assertEquals(IrCode(4, 2), fan.buttons["vitesse"])
        assertEquals(IrCode(4, 3), fan.buttons["oscillation"])
        assertFailsWith<IllegalArgumentException> { fan.signalFor("inexistante") }
        assertFailsWith<IllegalArgumentException> {
            IrLibrary.parse("""{"appareils":[{"id":"Mauvais Id","nom":"x","protocole":"NEC","adresse":"1","touches":{}}]}""")
        }
        assertFailsWith<IllegalArgumentException> {
            IrLibrary.parse("""{"appareils":[{"id":"sans_adresse","nom":"x","protocole":"NEC","touches":{"a":"1"}}]}""")
        }
    }
}
