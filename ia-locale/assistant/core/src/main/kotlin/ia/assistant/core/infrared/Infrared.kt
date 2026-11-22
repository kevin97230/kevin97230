package ia.assistant.core.infrared

/**
 * Un signal infrarouge prêt à émettre : fréquence porteuse et durées en microsecondes,
 * alternant émission / silence (en commençant par une émission). C'est exactement
 * le format attendu par `ConsumerIrManager.transmit()` sur Android.
 */
class IrSignal(val carrierFrequencyHz: Int, val pattern: IntArray) {
    val totalDurationMicros: Int get() = pattern.sum()
}

/** Émetteur infrarouge de la plateforme (Android : ConsumerIrManager). */
interface IrTransmitter {
    val isAvailable: Boolean
    fun transmit(signal: IrSignal)
}

enum class IrProtocol {
    /** Protocole NEC (LG, beaucoup de décodeurs, ventilateurs, TV génériques…). */
    NEC,

    /** Protocole Samsung 32 bits (TV Samsung). */
    SAMSUNG32,

    /**
     * Kaseikyo 48 bits (Panasonic). Adresse sur 32 bits au format de la base Flipper-IRDB :
     * identifiant fabricant (0x2002 pour Panasonic), appareil, sous-appareil.
     */
    KASEIKYO,
}

object IrEncoder {
    private const val CARRIER_HZ = 38_000
    private const val PANASONIC_CARRIER_HZ = 37_000

    fun encode(protocol: IrProtocol, address: Int, command: Int): IrSignal {
        val maxCommand = if (protocol == IrProtocol.KASEIKYO) 0x3FF else 0xFF
        require(command in 0..maxCommand) { "Commande hors limites : $command" }
        return when (protocol) {
            IrProtocol.NEC -> nec(address, command)
            IrProtocol.SAMSUNG32 -> samsung32(address, command)
            IrProtocol.KASEIKYO -> kaseikyo(address, command)
        }
    }

    /**
     * NEC : en-tête 9 ms / 4,5 ms, puis 32 bits (poids faible en premier) :
     * adresse, inverse de l'adresse (ou adresse étendue 16 bits), commande, inverse de la commande.
     */
    private fun nec(address: Int, command: Int): IrSignal {
        require(address in 0..0xFFFF) { "Adresse NEC hors limites : $address" }
        val addressBits = if (address <= 0xFF) address or ((address.inv() and 0xFF) shl 8) else address
        val data = addressBits.toLong() or (command.toLong() shl 16) or ((command.inv() and 0xFF).toLong() shl 24)
        return pulseDistance(CARRIER_HZ, intArrayOf(9000, 4500), data, 32, bitOn = 562, zeroOff = 562, oneOff = 1687)
    }

    /** Samsung32 : en-tête 4,5 ms / 4,5 ms, puis adresse, adresse, commande, inverse de la commande. */
    private fun samsung32(address: Int, command: Int): IrSignal {
        require(address in 0..0xFF) { "Adresse Samsung hors limites : $address" }
        val data = address.toLong() or (address.toLong() shl 8) or (command.toLong() shl 16) or
            ((command.inv() and 0xFF).toLong() shl 24)
        return pulseDistance(CARRIER_HZ, intArrayOf(4500, 4500), data, 32, bitOn = 560, zeroOff = 560, oneOff = 1690)
    }

    /**
     * Kaseikyo (Panasonic) : en-tête 3,5 ms / 1,7 ms, puis 6 octets (poids faible en premier) :
     * fabricant (2 octets), parité + genre, genre + commande, identifiant + commande, contrôle (XOR).
     */
    private fun kaseikyo(address: Int, command: Int): IrSignal {
        val id = (address ushr 24) and 0x3
        val vendor = (address ushr 8) and 0xFFFF
        val genre1 = (address ushr 4) and 0xF
        val genre2 = address and 0xF

        val bytes = IntArray(6)
        bytes[0] = vendor and 0xFF
        bytes[1] = vendor ushr 8
        val vendorParity = (bytes[0] xor bytes[1]).let { (it and 0xF) xor (it ushr 4) }
        bytes[2] = vendorParity or (genre1 shl 4)
        bytes[3] = genre2 or ((command and 0xF) shl 4)
        bytes[4] = (id shl 6) or (command ushr 4)
        bytes[5] = bytes[2] xor bytes[3] xor bytes[4]

        val data = bytes.foldIndexed(0L) { i, acc, b -> acc or (b.toLong() shl (8 * i)) }
        return pulseDistance(PANASONIC_CARRIER_HZ, intArrayOf(3456, 1728), data, 48, bitOn = 432, zeroOff = 432, oneOff = 1296)
    }

    private fun pulseDistance(
        carrierHz: Int,
        header: IntArray,
        data: Long,
        bitCount: Int,
        bitOn: Int,
        zeroOff: Int,
        oneOff: Int,
    ): IrSignal {
        val pattern = IntArray(header.size + bitCount * 2 + 1)
        header.copyInto(pattern)
        var i = header.size
        for (bit in 0 until bitCount) {
            pattern[i++] = bitOn
            pattern[i++] = if ((data shr bit) and 1L == 1L) oneOff else zeroOff
        }
        pattern[i] = bitOn // impulsion de fin
        return IrSignal(carrierHz, pattern)
    }
}
