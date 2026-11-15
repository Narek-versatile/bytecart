package bytecart

/**
 * Minimal QR Code encoder: byte mode, error correction level L, versions 1..40, best mask by
 * the standard penalty rules. Used by the web playground; the Android app and CLI use ZXing.
 * Follows ISO/IEC 18004 (structure after Project Nayuki's reference implementation).
 */
class QrCode private constructor(val version: Int, val size: Int, private val modules: BooleanArray) {
    fun dark(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y * size + x]

    companion object {
        private val ECC_PER_BLOCK = intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30)
        private val NUM_BLOCKS = intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25)

        fun rawModules(ver: Int): Int {
            var r = (16 * ver + 128) * ver + 64
            if (ver >= 2) { val na = ver / 7 + 2; r -= (25 * na - 10) * na - 55; if (ver >= 7) r -= 36 }
            return r
        }

        fun dataCodewords(ver: Int) = rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver]

        /** Bytes that fit in byte mode at level L for [ver]. */
        fun capacity(ver: Int): Int = (dataCodewords(ver) * 8 - 4 - (if (ver < 10) 8 else 16)) / 8

        fun encodeBytes(data: ByteArray): QrCode {
            val ver = (1..40).firstOrNull { capacity(it) >= data.size } ?: throw IllegalArgumentException("${data.size} bytes is too much for one QR code")
            // bit stream
            val bits = ArrayList<Boolean>()
            fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((v ushr i) and 1 != 0) }
            put(4, 4); put(data.size, if (ver < 10) 8 else 16)
            for (b in data) put(b.toInt() and 0xFF, 8)
            val cap = dataCodewords(ver) * 8
            put(0, minOf(4, cap - bits.size))
            put(0, (8 - bits.size % 8) % 8)
            val words = ByteArray(dataCodewords(ver))
            for (i in 0 until bits.size / 8) { var v = 0; for (k in 0 until 8) v = (v shl 1) or (if (bits[i * 8 + k]) 1 else 0); words[i] = v.toByte() }
            var pad = 0xEC
            for (i in bits.size / 8 until words.size) { words[i] = pad.toByte(); pad = pad xor 0xEC xor 0x11 }
            val all = interleave(words, ver)
            val size = ver * 4 + 17
            var best: QrCode? = null; var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                val q = build(ver, size, all, mask)
                val p = q.penalty()
                if (p < bestPenalty) { best = q; bestPenalty = p }
            }
            return best!!
        }

        private fun mul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) { z = (z shl 1) xor ((z ushr 7) * 0x11D); z = z xor (((y ushr i) and 1) * x) }
            return z
        }

        private fun divisor(degree: Int): IntArray {
            val r = IntArray(degree); r[degree - 1] = 1; var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) { r[j] = mul(r[j], root); if (j + 1 < degree) r[j] = r[j] xor r[j + 1] }
                root = mul(root, 2)
            }
            return r
        }

        private fun remainder(data: IntArray, div: IntArray): IntArray {
            val r = IntArray(div.size)
            for (b in data) {
                val f = b xor r[0]
                for (i in 0 until r.size - 1) r[i] = r[i + 1]
                r[r.size - 1] = 0
                for (i in r.indices) r[i] = r[i] xor mul(div[i], f)
            }
            return r
        }

        private fun interleave(data: ByteArray, ver: Int): IntArray {
            val nb = NUM_BLOCKS[ver]; val ecc = ECC_PER_BLOCK[ver]; val raw = rawModules(ver) / 8
            val nShort = nb - raw % nb; val shortLen = raw / nb
            val div = divisor(ecc)
            val blocks = ArrayList<IntArray>()
            var k = 0
            for (i in 0 until nb) {
                val len = shortLen - ecc + (if (i < nShort) 0 else 1)
                val dat = IntArray(len) { data[k + it].toInt() and 0xFF }; k += len
                val e = remainder(dat, div)
                val block = IntArray(shortLen + 1)
                dat.copyInto(block, 0)
                e.copyInto(block, shortLen + 1 - ecc)
                blocks.add(block)
            }
            val out = ArrayList<Int>()
            for (i in 0 until shortLen + 1) for (j in 0 until nb) if (i != shortLen - ecc || j >= nShort) out.add(blocks[j][i])
            return out.toIntArray()
        }

        private fun build(ver: Int, size: Int, words: IntArray, mask: Int): QrCode {
            val m = BooleanArray(size * size)
            val fn = BooleanArray(size * size)
            fun set(x: Int, y: Int, d: Boolean) { m[y * size + x] = d; fn[y * size + x] = true }
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
                for (dy in -4..4) for (dx in -4..4) {
                    val x = cx + dx; val y = cy + dy
                    if (x in 0 until size && y in 0 until size) { val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)); set(x, y, d != 2 && d != 4) }
                }
            }
            val al = alignment(ver, size)
            for (i in al.indices) for (j in al.indices) {
                if (i == 0 && j == 0 || i == 0 && j == al.size - 1 || i == al.size - 1 && j == 0) continue
                for (dy in -2..2) for (dx in -2..2) set(al[i] + dx, al[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
            fun format(msk: Int) {
                val data = (1 shl 3) or msk // ECC L = 01
                var rem = data
                repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
                val bits = ((data shl 10) or rem) xor 0x5412
                fun b(i: Int) = (bits ushr i) and 1 != 0
                for (i in 0..5) set(8, i, b(i))
                set(8, 7, b(6)); set(8, 8, b(7)); set(7, 8, b(8))
                for (i in 9 until 15) set(14 - i, 8, b(i))
                for (i in 0 until 8) set(size - 1 - i, 8, b(i))
                for (i in 8 until 15) set(8, size - 15 + i, b(i))
                set(8, size - 8, true)
            }
            format(0)
            if (ver >= 7) {
                var rem = ver
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (ver shl 12) or rem
                for (i in 0 until 18) {
                    val d = (bits ushr i) and 1 != 0
                    val a = size - 11 + i % 3; val b = i / 3
                    set(a, b, d); set(b, a, d)
                }
            }
            // data, zigzag
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val up = ((right + 1) and 2) == 0
                    val y = if (up) size - 1 - vert else vert
                    if (!fn[y * size + x] && i < words.size * 8) {
                        m[y * size + x] = (words[i ushr 3] ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
                right -= 2
            }
            for (y in 0 until size) for (x in 0 until size) {
                if (fn[y * size + x]) continue
                val inv = when (mask) {
                    0 -> (x + y) % 2 == 0; 1 -> y % 2 == 0; 2 -> x % 3 == 0; 3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0; 5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0; else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (inv) m[y * size + x] = !m[y * size + x]
            }
            format(mask)
            return QrCode(ver, size, m)
        }

        private fun alignment(ver: Int, size: Int): IntArray {
            if (ver == 1) return IntArray(0)
            val n = ver / 7 + 2
            val step = (ver * 8 + n * 3 + 5) / (n * 4 - 4) * 2
            val r = IntArray(n); r[0] = 6
            var pos = size - 7
            for (i in n - 1 downTo 1) { r[i] = pos; pos -= step }
            return r
        }
    }

    private fun penalty(): Int {
        var p = 0
        fun runs(get: (Int, Int) -> Boolean) {
            for (a in 0 until size) {
                var run = 1
                for (b in 1 until size) {
                    if (get(a, b) == get(a, b - 1)) { run++; if (run == 5) p += 3 else if (run > 5) p++ } else run = 1
                }
                // finder-like 1:1:3:1:1 with 4 light modules on a side
                for (b in 0..size - 7) {
                    if (get(a, b) && !get(a, b + 1) && get(a, b + 2) && get(a, b + 3) && get(a, b + 4) && !get(a, b + 5) && get(a, b + 6)) {
                        val before = (1..4).all { b - it < 0 || !get(a, b - it) }
                        val after = (1..4).all { b + 6 + it >= size || !get(a, b + 6 + it) }
                        if (before || after) p += 40
                    }
                }
            }
        }
        runs { a, b -> modules[a * size + b] }
        runs { a, b -> modules[b * size + a] }
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = modules[y * size + x]
            if (c == modules[y * size + x + 1] && c == modules[(y + 1) * size + x] && c == modules[(y + 1) * size + x + 1]) p += 3
        }
        val dark = modules.count { it }; val total = size * size
        val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        return p + k * 10
    }
}
