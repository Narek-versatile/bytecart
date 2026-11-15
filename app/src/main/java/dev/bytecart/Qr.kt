package dev.bytecart

import android.graphics.Bitmap
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR cartridges: the cart image is stored in byte mode at error-correction level L (the most data
 * per symbol; up to 2953 bytes in a version-40 code). Bytes are carried as ISO-8859-1 text,
 * which maps every byte 1:1 to a character, so nothing is lost or re-encoded.
 */
object Qr {
    fun encode(image: ByteArray, scale: Int = 6): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            EncodeHintType.MARGIN to 4,
        )
        val m = QRCodeWriter().encode(String(image, Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, 0, 0, hints)
        val px = IntArray(m.width * m.height) { if (m.get(it % m.width, it / m.width)) 0xFF000000.toInt() else -1 }
        val small = Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(small, m.width * scale, m.height * scale, false)
    }

    /** Modules per side of the symbol that holds [n] bytes. */
    fun modules(n: Int): Int {
        val caps = intArrayOf(17, 32, 53, 78, 106, 134, 154, 192, 230, 271, 321, 367, 425, 458, 520, 586, 644, 718, 792, 858,
            929, 1003, 1091, 1171, 1273, 1367, 1465, 1528, 1628, 1732, 1840, 1952, 2068, 2188, 2303, 2431, 2563, 2699, 2809, 2953)
        val v = caps.indexOfFirst { it >= n } + 1
        return if (v == 0) 0 else 17 + 4 * v
    }

    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "ISO-8859-1",
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    /** Decode a luminance plane (camera Y channel). Returns the raw bytes or null. */
    fun decode(y: ByteArray, rowStride: Int, width: Int, height: Int): ByteArray? {
        val src = PlanarYUVLuminanceSource(y, rowStride, height, 0, 0, width, height, false)
        for (bin in listOf(HybridBinarizer(src), HybridBinarizer(src.invert()))) {
            try {
                val r = reader.decode(BinaryBitmap(bin), hints)
                @Suppress("UNCHECKED_CAST")
                val segs = r.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>
                return if (!segs.isNullOrEmpty()) segs.reduce { a, b -> a + b } else r.text.toByteArray(Charsets.ISO_8859_1)
            } catch (e: ReaderException) {
            } finally { reader.reset() }
        }
        return null
    }
}
