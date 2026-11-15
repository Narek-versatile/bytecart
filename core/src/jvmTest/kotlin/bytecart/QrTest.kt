package bytecart

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class QrTest {
    private fun decode(q: QrCode): ByteArray {
        val scale = 3; val quiet = 4
        val n = (q.size + quiet * 2) * scale
        val px = IntArray(n * n) { i ->
            val x = i % n / scale - quiet; val y = i / n / scale - quiet
            if (q.dark(x, y)) 0xFF000000.toInt() else -1
        }
        val r = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(n, n, px))),
            mapOf(DecodeHintType.PURE_BARCODE to true, DecodeHintType.CHARACTER_SET to "ISO-8859-1"))
        @Suppress("UNCHECKED_CAST")
        val segs = r.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<ByteArray>
        return segs.reduce { a, b -> a + b }
    }

    @Test fun capacityMatchesStandard() {
        assertEquals(17, QrCode.capacity(1))
        assertEquals(2953, QrCode.capacity(40))
        assertEquals(Cart.QR_MAX, QrCode.capacity(40))
    }

    @Test fun zxingReadsEveryVersion() {
        val r = Random(5)
        for (ver in 1..40) {
            val n = QrCode.capacity(ver)
            val data = ByteArray(n) { r.nextInt().toByte() }
            val q = QrCode.encodeBytes(data)
            assertEquals(ver, q.version)
            assertContentEquals(data, decode(q), "version $ver")
        }
    }

    @Test fun zxingReadsGameCarts() {
        for (f in java.io.File("../games").listFiles { f -> f.extension == "bcs" }!!) {
            val img = Cart.build(f.readText())
            assertContentEquals(img, decode(QrCode.encodeBytes(img)), f.name)
        }
    }
}
