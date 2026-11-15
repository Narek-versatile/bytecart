package bytecart

/**
 * Cartridge image: the bytes that go on an NFC tag, into a QR code or into a .bcart file.
 *
 *   byte 0      CRC-8 of the stream XOR [FORMAT_TAG] (a corrupt or foreign tag fails this check)
 *   byte 1..    range-coded program ([Codec]); trailing zero bytes are implied and never stored
 *
 * Because trailing zeros are implied, the same image works padded (NFC pages) or exact (QR).
 */
object Cart {
    const val FORMAT_TAG = 0xB1

    /** NTAG213 user memory (pages 4..39). */
    const val NFC_NTAG213 = 144
    /** QR code version 40, error correction L, byte mode. */
    const val QR_MAX = 2953

    class TooBig(val size: Int, val capacity: Int) : Exception("cart is $size bytes, capacity is $capacity")

    fun crc8(b: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (b[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    fun pack(program: Program): ByteArray {
        val stream = Codec.encode(program)
        val out = ByteArray(stream.size + 1)
        stream.copyInto(out, 1)
        out[0] = (crc8(stream, 0, stream.size) xor FORMAT_TAG).toByte()
        return out
    }

    /** Length without the implied zero tail. */
    fun trimmedLength(image: ByteArray): Int {
        var end = image.size
        while (end > 1 && image[end - 1] == 0.toByte()) end--
        return end
    }

    fun isCart(image: ByteArray): Boolean {
        if (image.isEmpty()) return false
        val end = trimmedLength(image)
        return (crc8(image, 1, end) xor FORMAT_TAG) == (image[0].toInt() and 0xFF)
    }

    fun unpack(image: ByteArray): Program {
        if (!isCart(image)) throw DecodeError("not a ByteCart cartridge (checksum mismatch)")
        val end = trimmedLength(image)
        return Codec.decode(image.copyOfRange(1, end))
    }

    fun padTo(image: ByteArray, capacity: Int): ByteArray {
        if (image.size > capacity) throw TooBig(image.size, capacity)
        return image.copyOf(capacity)
    }

    /** Convenience: source text to cart image. */
    fun build(source: String): ByteArray = pack(Compiler.compile(source).program)
}
