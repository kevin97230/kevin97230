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
}

object IrEncoder {
    private const val CARRIER_HZ = 38_000

    fun encode(protocol: IrProtocol, address: Int, command: Int): IrSignal {
        require(command in 0..0xFF) { "Commande hors limites : $command" }
        return when (protocol) {
            IrProtocol.NEC -> nec(address, command)
            IrProtocol.SAMSUNG32 -> samsung32(address, command)
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
        return pulseDistance(header = intArrayOf(9000, 4500), data = data, bitOn = 562, zeroOff = 562, oneOff = 1687)
    }

    /** Samsung32 : en-tête 4,5 ms / 4,5 ms, puis adresse, adresse, commande, inverse de la commande. */
    private fun samsung32(address: Int, command: Int): IrSignal {
        require(address in 0..0xFF) { "Adresse Samsung hors limites : $address" }
        val data = address.toLong() or (address.toLong() shl 8) or (command.toLong() shl 16) or
            ((command.inv() and 0xFF).toLong() shl 24)
        return pulseDistance(header = intArrayOf(4500, 4500), data = data, bitOn = 560, zeroOff = 560, oneOff = 1690)
    }

    private fun pulseDistance(header: IntArray, data: Long, bitOn: Int, zeroOff: Int, oneOff: Int): IrSignal {
        val pattern = IntArray(header.size + 32 * 2 + 1)
        header.copyInto(pattern)
        var i = header.size
        for (bit in 0 until 32) {
            pattern[i++] = bitOn
            pattern[i++] = if ((data shr bit) and 1L == 1L) oneOff else zeroOff
        }
        pattern[i] = bitOn // impulsion de fin
        return IrSignal(CARRIER_HZ, pattern)
    }
}
