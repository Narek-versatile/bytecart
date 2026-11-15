package bytecart

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class DecodeError(msg: String) : Exception(msg)

/**
 * Cartridge codec: an LZMA-style binary range coder driven by adaptive context models.
 *
 * Nothing is stored at a fixed width. Every opcode is coded as a 6-bit binary tree whose
 * probabilities depend on the previous opcode; every operand goes through an adaptive
 * Elias-gamma style integer model owned by its (opcode, operand) slot; data bytes use an
 * order-1 model. All probabilities start from priors learned from the bundled games
 * ([Priors]) and keep adapting while coding, so unusual programs still compress.
 *
 * Encoder, decoder and trainer share one traversal ([Traversal]) so they cannot drift apart.
 */
object Codec {
    // ---- model layout (indices into one probability table)
    private const val OP_CTX = 65                       // previous opcode (64 = start of stream)
    private const val OP_BASE = 0
    private const val OP_SIZE = OP_CTX * 64
    internal const val SLOTS = 64 * 2 + 2               // (opcode, arg) slots + data count + data length
    internal const val SLOT_DATA_COUNT = 128
    internal const val SLOT_DATA_LEN = 129
    private const val INT_SIGN = 0
    private const val INT_NB = 1                         // 33 unary contexts
    private const val INT_TREE = 34                      // mantissa trees for bit-length 2..9 (offset table below)
    private const val INT_HIGH = 34 + 512                // mantissa bits for bit-length >= 10, by bit position
    private const val INT_SIZE = INT_HIGH + 32
    private const val INT_BASE = OP_BASE + OP_SIZE
    private const val DATA0_BASE = INT_BASE + SLOTS * INT_SIZE   // order-0 data byte tree (prior source)
    internal const val MODEL_SIZE = DATA0_BASE + 256
    private const val DATA1_SIZE = 257 * 256                    // order-1 data model, built from DATA0 at start

    private val treeOffset = IntArray(10).also { var o = 0; for (nb in 2..9) { it[nb] = o; o += 1 shl (nb - 1) } }

    const val MAX_INSTRUCTIONS = 50_000
    const val MAX_DATA = 64 * 1024

    // ---- probabilities: 12-bit P(bit = 0) plus a confidence counter

    private const val PROB_INIT = 2048
    private const val COUNT_LIMIT = 30
    private val RATE = IntArray(COUNT_LIMIT + 1) { (65536 * 2 / (2 * it + 3)) }

    class Model(priors: IntArray?) {
        val p = IntArray(MODEL_SIZE + DATA1_SIZE) { PROB_INIT }
        val n = IntArray(MODEL_SIZE + DATA1_SIZE)

        init {
            if (priors != null) {
                var i = 0
                while (i < priors.size) {
                    val idx = priors[i]; val v = priors[i + 1]
                    p[idx] = v shr 4; n[idx] = v and 15; i += 2
                }
            }
            // order-1 data contexts start as copies of the order-0 data model
            for (ctx in 0 until 257) for (node in 0 until 256) {
                p[MODEL_SIZE + ctx * 256 + node] = p[DATA0_BASE + node]
                n[MODEL_SIZE + ctx * 256 + node] = minOf(n[DATA0_BASE + node], 2)
            }
        }

        fun update(i: Int, bit: Int) {
            val c = n[i]
            val target = if (bit == 0) 4096 else 0
            var v = p[i] + (((target - p[i]) * RATE[c]) shr 16)
            if (v < 32) v = 32; if (v > 4064) v = 4064
            p[i] = v
            if (c < COUNT_LIMIT) n[i] = c + 1
        }
    }

    internal interface BitIo { fun bit(m: Model, i: Int, b: Int): Int }

    // ---- range coder

    private class Encoder : BitIo {
        var low = 0L; var range = 0xFFFFFFFFL; var cache = 0; var cacheSize = 1L
        val out = ArrayList<Byte>()
        var first = true

        override fun bit(m: Model, i: Int, b: Int): Int {
            val bound = (range ushr 12) * m.p[i]
            if (b == 0) range = bound else { low += bound; range -= bound }
            m.update(i, b)
            while (range < (1L shl 24)) { range = range shl 8; shiftLow() }
            return b
        }

        private fun emit(v: Int) { if (first) first = false else out.add(v.toByte()) } // first byte is always 0

        fun shiftLow() {
            if (low < 0xFF000000L || low > 0xFFFFFFFFL) {
                val carry = (low ushr 32).toInt()
                var temp = cache
                do { emit((temp + carry) and 0xFF); temp = 0xFF } while (--cacheSize != 0L)
                cache = ((low ushr 24) and 0xFF).toInt()
            }
            cacheSize++
            low = (low and 0x00FFFFFFL) shl 8
        }

        /** Pick the value in [low, low+range) with the most trailing zero bits, flush, drop zero tail. */
        fun finish(): ByteArray {
            for (bits in 32 downTo 0) {
                val mask = (1L shl bits) - 1
                val v = (low + mask) and mask.inv()
                if (v >= low && v < low + range) { low = v; break }
            }
            repeat(5) { shiftLow() }
            var end = out.size
            while (end > 0 && out[end - 1] == 0.toByte()) end--
            return ByteArray(end) { out[it] }
        }
    }

    private class Decoder(val input: ByteArray) : BitIo {
        var pos = 0
        var range = 0xFFFFFFFFL
        var code = 0L
        fun next(): Int = if (pos < input.size) input[pos++].toInt() and 0xFF else { pos++; 0 }
        init { repeat(4) { code = (code shl 8) or next().toLong() } }

        override fun bit(m: Model, i: Int, b: Int): Int {
            val bound = (range ushr 12) * m.p[i]
            val r: Int
            if (code < bound) { range = bound; r = 0 } else { code -= bound; range -= bound; r = 1 }
            m.update(i, r)
            while (range < (1L shl 24)) { range = range shl 8; code = ((code shl 8) or next().toLong()) and 0xFFFFFFFFL }
            return r
        }
    }

    /** Counts outcomes per context for training priors. */
    internal class Counter : BitIo {
        val zeros = LongArray(MODEL_SIZE + DATA1_SIZE)
        val ones = LongArray(MODEL_SIZE + DATA1_SIZE)
        override fun bit(m: Model, i: Int, b: Int): Int {
            if (i >= MODEL_SIZE) { // order-1 data: also train the shared order-0 tree
                val node = (i - MODEL_SIZE) % 256
                if (b == 0) zeros[DATA0_BASE + node]++ else ones[DATA0_BASE + node]++
            }
            if (b == 0) zeros[i]++ else ones[i]++
            m.update(i, b); return b
        }
    }

    // ---- shared traversal

    internal class Traversal(val io: BitIo, val m: Model) {
        fun tree(base: Int, bits: Int, value: Int): Int {
            var node = 1
            for (k in bits - 1 downTo 0) node = (node shl 1) or io.bit(m, base + node, (value shr k) and 1)
            return node - (1 shl bits)
        }

        fun int(slot: Int, value: Int): Int {
            val base = INT_BASE + slot * INT_SIZE
            val mag = if (value < 0) -(value.toLong()) else value.toLong()
            var nb = 0; while (nb < 33 && (mag ushr nb) != 0L) nb++
            // bit length, unary
            var dnb = 0
            while (dnb < 32) {
                val one = if (dnb < nb) 1 else 0
                if (io.bit(m, base + INT_NB + dnb, one) == 0) break
                dnb++
            }
            if (dnb == 0) return 0
            var r = 1L
            val rest = dnb - 1
            if (dnb <= 9) {
                val mant = tree(base + INT_TREE + treeOffset.getOrElse(dnb) { 0 }, rest, (mag and ((1L shl rest) - 1)).toInt())
                r = (1L shl rest) or mant.toLong()
            } else {
                for (k in rest - 1 downTo 0) r = (r shl 1) or io.bit(m, base + INT_HIGH + k, ((mag ushr k) and 1L).toInt()).toLong()
            }
            val neg = io.bit(m, base + INT_SIGN, if (value < 0) 1 else 0) == 1
            return (if (neg) -r else r).toInt()
        }

        fun dataByte(prev: Int, value: Int): Int = tree(MODEL_SIZE + prev * 256, 8, value)

        fun opcode(prev: Int, op: Int): Int = tree(OP_BASE + prev * 64, Op.BITS, op)
    }

    private fun traverse(t: Traversal, prog: Program?): Program {
        val enc = prog != null
        // data blocks
        val nData = t.int(SLOT_DATA_COUNT, prog?.data?.size ?: 0)
        if (nData < 0 || nData > 255) throw DecodeError("bad data count")
        var total = 0
        val blocks = ArrayList<ByteArray>()
        for (b in 0 until nData) {
            val src = prog?.data?.get(b)
            val len = t.int(SLOT_DATA_LEN, src?.size ?: 0)
            total += len
            if (len < 0 || total > MAX_DATA) throw DecodeError("bad data length")
            val bytes = ByteArray(len)
            var prev = 256
            for (k in 0 until len) {
                val v = t.dataByte(prev, if (enc) src!![k].toInt() and 0xFF else 0)
                bytes[k] = v.toByte(); prev = v
            }
            blocks.add(bytes)
        }
        // instructions
        val code = ArrayList<Int>()
        var prev = 64
        var idx = 0
        val starts = prog?.starts
        while (true) {
            val opId = if (enc) (if (idx < starts!!.size) prog.code[starts[idx]] else Op.END.ordinal) else 0
            val got = t.opcode(prev, opId)
            val op = Op.all.getOrNull(got) ?: throw DecodeError("bad opcode $got")
            if (op == Op.END) break
            if (idx >= MAX_INSTRUCTIONS) throw DecodeError("program too long")
            code.add(got)
            for (k in op.args.indices) {
                val raw = if (enc) prog.code[starts!![idx] + 1 + k] else 0
                val slot = got * 2 + minOf(k, 1)
                val v = if (op.args[k] == Arg.JUMP) t.int(slot, raw - idx) + idx else t.int(slot, raw)
                code.add(v)
            }
            prev = got; idx++
        }
        return Program(code.toIntArray(), blocks)
    }

    fun encode(prog: Program, priors: IntArray? = Priors.table): ByteArray {
        val e = Encoder()
        traverse(Traversal(e, Model(priors)), prog)
        return e.finish()
    }

    fun decode(stream: ByteArray, priors: IntArray? = Priors.table): Program =
        traverse(Traversal(Decoder(stream), Model(priors)), null)

    // ---- training

    /** Learn priors from a corpus. Result is the sparse (index, p<<4|confidence) table. */
    fun train(corpus: List<Program>, maxConfidence: Int = 6): IntArray {
        val c = Counter()
        for (prog in corpus) traverse(Traversal(c, Model(null)), prog)
        val out = ArrayList<Int>()
        for (i in 0 until MODEL_SIZE) {
            val z = c.zeros[i]; val o = c.ones[i]; val tot = z + o
            if (tot == 0L) continue
            val p = (((z + 0.4) / (tot + 0.8)) * 4096).toInt().coerceIn(32, 4064)
            val conf = minOf(tot, maxConfidence.toLong()).toInt()
            out.add(i); out.add((p shl 4) or conf)
        }
        return out.toIntArray()
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun priorsToString(t: IntArray): String {
        val bytes = ArrayList<Byte>()
        fun varint(v0: Int) { var v = v0; while (v >= 0x80) { bytes.add(((v and 0x7F) or 0x80).toByte()); v = v ushr 7 }; bytes.add(v.toByte()) }
        var last = 0
        var i = 0
        while (i < t.size) { varint(t[i] - last); last = t[i]; varint(t[i + 1]); i += 2 }
        return Base64.encode(bytes.toByteArray())
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun priorsFromString(s: String): IntArray {
        if (s.isEmpty()) return IntArray(0)
        val b = Base64.decode(s)
        val out = ArrayList<Int>()
        var pos = 0
        fun varint(): Int { var r = 0; var sh = 0; while (true) { val x = b[pos++].toInt() and 0xFF; r = r or ((x and 0x7F) shl sh); if (x < 0x80) return r; sh += 7 } }
        var last = 0
        while (pos < b.size) { last += varint(); out.add(last); out.add(varint()) }
        return out.toIntArray()
    }
}
