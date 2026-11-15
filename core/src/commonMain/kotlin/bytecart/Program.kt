package bytecart

/**
 * A decoded program: a flat list of instructions plus constant data blocks.
 * [code] holds opcode ids each followed by their operands. JUMP operands are absolute
 * *instruction numbers* (not array offsets) so the format does not depend on operand widths.
 */
class Program(val code: IntArray, val data: List<ByteArray>) {

    /** Array offset of every instruction, in order. */
    val starts: IntArray by lazy {
        val out = ArrayList<Int>()
        var pc = 0
        while (pc < code.size) {
            out.add(pc)
            val op = Op.all.getOrNull(code[pc]) ?: break
            pc += 1 + op.args.size
        }
        out.toIntArray()
    }

    val instructionCount get() = starts.size

    /** Naive size: 1 byte per opcode, LEB128 varints for operands and data, for comparison. */
    fun naiveSize(): Int {
        var n = 0
        fun v(x: Int) { var u = if (x < 0) (x.inv() shl 1) or 1 else x shl 1; do { n++; u = u ushr 7 } while (u != 0) }
        for (s in starts) {
            n++
            val op = Op.all[code[s]]
            for (i in op.args.indices) v(code[s + 1 + i])
        }
        v(data.size)
        for (d in data) { v(d.size); n += d.size }
        return n + 1
    }

    fun disassemble(): String = buildString {
        starts.forEachIndexed { idx, s ->
            val op = Op.all[code[s]]
            append(idx.toString().padStart(4)).append("  ").append(op.name)
            for (i in op.args.indices) {
                val v = code[s + 1 + i]
                append(' ')
                if (op == Op.SYS && i == 0) append(Builtin.all.getOrNull(v)?.name?.lowercase() ?: "?$v")
                else if (op.args[i] == Arg.JUMP) append("@").append(v)
                else append(v)
            }
            append('\n')
        }
        data.forEachIndexed { i, d ->
            append("data $i [${d.size}]: ")
            val printable = d.all { it in 32..126 }
            if (printable) append('"').append(d.decodeToString()).append('"')
            else append(d.joinToString(",") { (it.toInt() and 0xFF).toString() })
            append('\n')
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Program && other.code.contentEquals(code) &&
            other.data.size == data.size && data.indices.all { data[it].contentEquals(other.data[it]) }

    override fun hashCode(): Int = code.contentHashCode()
}
