package bytecart

enum class VmState { RUNNING, OVER, WON, HALTED, CRASHED }

/** Sound request for the host: [kind] 0 = beep(note, frames), 1 = sfx(id). */
class SoundEvent(val kind: Int, val a: Int, val b: Int)

/**
 * The ByteCart virtual machine. The host calls [frame] 30 times per second, then draws
 * [cell], [sprites] and [message]. Input arrives through [press], [release] and [tap].
 * Any program, even random bytes, is safe to run: faults stop the VM with [VmState.CRASHED].
 */
class Vm(val program: Program, private var seed: Int = 0x2545F491) {
    companion object {
        const val MAX_DIM = 32
        const val FPS = 30
        const val STACK = 256
        const val GLOBALS = 256
        const val MEM = 4096
        const val LOCALS = 4096
        const val CALLS = 128
        const val BUDGET = 300_000
        const val WALL_OUTSIDE = 16
    }

    private val code: IntArray
    private val fnEntry: IntArray

    init {
        val starts = program.starts
        code = program.code.copyOf()
        // JUMP operands: instruction number -> array offset (out of range -> past the end = halt)
        for (s in starts) {
            val op = Op.all[code[s]]
            if (op.args.isNotEmpty() && op.args[0] == Arg.JUMP) {
                val t = code[s + 1]
                code[s + 1] = if (t in starts.indices) starts[t] else code.size
            }
        }
        fnEntry = starts.filter { code[it] == Op.FUNC.ordinal }.toIntArray()
    }

    // ---- observable state
    var state = VmState.RUNNING; private set
    var error: String? = null; private set
    var w = 16; private set
    var h = 16; private set
    private val grid = IntArray(MAX_DIM * MAX_DIM)
    val spriteTile = IntArray(16)
    val spriteX = IntArray(16)
    val spriteY = IntArray(16)
    val customRows = IntArray(16 * 8)
    val customColor = IntArray(16) { 1 }
    var message: String? = null; private set
    var score = 0; private set
    var hiscore = 0
    var tick = 0; private set
    var instructionsLastFrame = 0; private set
    val sounds = ArrayList<SoundEvent>()

    fun cell(x: Int, y: Int): Int = if (x in 0 until w && y in 0 until h) grid[y * MAX_DIM + x] else 0

    // ---- input
    private var keys = 0
    private var pressedBits = 0
    private var dir = 0
    private var tapX = -1
    private var tapY = -1
    private var touchX = -1
    private var touchY = -1

    /** Button k: 0 right, 1 down, 2 left, 3 up, 4 A, 5 B. */
    fun press(k: Int) {
        if (k !in 0..5) return
        keys = keys or (1 shl k); pressedBits = pressedBits or (1 shl k)
        if (k < 4) dir = k
    }

    fun release(k: Int) { if (k in 0..5) keys = keys and (1 shl k).inv() }
    /** A short touch released without moving (a "tap") on cell x, y. */
    fun tap(x: Int, y: Int) { if (x in 0 until w && y in 0 until h) { tapX = x; tapY = y } }

    /** Finger position while it is on the grid (drag), or touchEnd() when lifted. */
    fun touch(x: Int, y: Int) { touchX = x.coerceIn(0, w - 1); touchY = y.coerceIn(0, h - 1) }
    fun touchEnd() { touchX = -1; touchY = -1 }

    // ---- execution state
    private var pc = 0
    private val stack = IntArray(STACK)
    private var sp = 0
    private val globals = IntArray(GLOBALS)
    private val mem = IntArray(MEM)
    private val locals = IntArray(LOCALS)
    private var fp = 0
    private var localTop = 0
    private val callPc = IntArray(CALLS)
    private val callFp = IntArray(CALLS)
    private val callTop = IntArray(CALLS)
    private var depth = 0
    private var waitFrames = 0
    private var rng = seed

    fun reset(newSeed: Int = seed + 0x9E3779B9.toInt()) {
        seed = newSeed; rng = if (newSeed == 0) 1 else newSeed
        state = VmState.RUNNING; error = null; w = 16; h = 16
        grid.fill(0); spriteTile.fill(0); spriteX.fill(0); spriteY.fill(0)
        customRows.fill(0); customColor.fill(1); message = null; score = 0; tick = 0
        keys = 0; pressedBits = 0; dir = 0; tapX = -1; tapY = -1; touchX = -1; touchY = -1
        pc = 0; sp = 0; globals.fill(0); mem.fill(0); locals.fill(0); fp = 0; localTop = 0; depth = 0; waitFrames = 0
        sounds.clear()
    }

    init { reset(seed) }

    private fun random(n: Int): Int {
        if (n <= 0) return 0
        var x = rng
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        rng = x
        return ((x.toLong() and 0xFFFFFFFFL) % n).toInt()
    }

    private fun crash(msg: String) { state = VmState.CRASHED; error = "pc ${program.starts.indexOf(pc).let { if (it < 0) pc else it }}: $msg" }

    /** Advance one host frame. */
    fun frame() {
        if (state != VmState.RUNNING) return
        tick++
        if (waitFrames > 0) { waitFrames--; if (waitFrames > 0) return }
        run()
        pressedBits = 0; tapX = -1; tapY = -1
        if (score > hiscore && state != VmState.RUNNING) hiscore = score
    }

    private fun push(v: Int) { if (sp >= STACK) throw VmFault("stack overflow"); stack[sp++] = v }
    private fun pop(): Int { if (sp <= 0) throw VmFault("stack underflow"); return stack[--sp] }
    private fun gidx(i: Int) = i and (GLOBALS - 1)
    private fun lidx(i: Int): Int { val k = fp + i; if (i < 0 || k >= LOCALS) throw VmFault("bad local $i"); return k }
    private fun data(b: Int): ByteArray? = program.data.getOrNull(b)
    private fun str(b: Int): String = data(b)?.let { d -> CharArray(d.size) { (d[it].toInt() and 0xFF).toChar() }.concatToString() } ?: ""

    private fun inGrid(x: Int, y: Int) = x in 0 until w && y in 0 until h
    private fun get(x: Int, y: Int) = if (inGrid(x, y)) grid[y * MAX_DIM + x] else WALL_OUTSIDE
    private fun set(x: Int, y: Int, t: Int) { if (inGrid(x, y)) grid[y * MAX_DIM + x] = t and 0xFF }

    private fun run() {
        var budget = BUDGET
        try {
            while (budget-- > 0) {
                if (pc < 0 || pc >= code.size) { state = VmState.HALTED; break }
                val op = Op.all.getOrNull(code[pc]) ?: throw VmFault("bad opcode ${code[pc]}")
                if (pc + op.args.size >= code.size) { state = VmState.HALTED; break }
                val a = if (op.args.isNotEmpty()) code[pc + 1] else 0
                pc += 1 + op.args.size
                when (op) {
                    Op.END, Op.HALT, Op.FUNC -> { state = VmState.HALTED; break }
                    Op.LIT -> push(a)
                    Op.LD -> push(globals[gidx(a)])
                    Op.ST -> globals[gidx(a)] = pop()
                    Op.LDL -> push(locals[lidx(a)])
                    Op.STL -> locals[lidx(a)] = pop()
                    Op.ADDV -> { val g = gidx(a); globals[g] = globals[g] + pop() }
                    Op.INCV -> globals[gidx(a)]++
                    Op.DECV -> globals[gidx(a)]--
                    Op.ADDL -> { val l = lidx(a); locals[l] = locals[l] + pop() }
                    Op.INCL -> locals[lidx(a)]++
                    Op.DECL -> locals[lidx(a)]--
                    Op.DUP -> { val v = pop(); push(v); push(v) }
                    Op.DROP -> pop()
                    Op.SWAP -> { val y = pop(); val x = pop(); push(y); push(x) }
                    Op.ADD -> { val y = pop(); push(pop() + y) }
                    Op.SUB -> { val y = pop(); push(pop() - y) }
                    Op.MUL -> { val y = pop(); push(pop() * y) }
                    Op.DIV -> { val y = pop(); push(Alu.binary("/", pop(), y)) }
                    Op.MOD -> { val y = pop(); val x = pop(); push(if (y == 0) 0 else Math2.mod(x, y)) }
                    Op.NEG -> push(-pop())
                    Op.AND -> { val y = pop(); push(pop() and y) }
                    Op.OR -> { val y = pop(); push(pop() or y) }
                    Op.XOR -> { val y = pop(); push(pop() xor y) }
                    Op.SHL -> { val y = pop(); push(pop() shl (y and 31)) }
                    Op.SHR -> { val y = pop(); push(pop() shr (y and 31)) }
                    Op.BNOT -> push(pop().inv())
                    Op.LNOT -> push(if (pop() == 0) 1 else 0)
                    Op.EQ -> { val y = pop(); push(if (pop() == y) 1 else 0) }
                    Op.NE -> { val y = pop(); push(if (pop() != y) 1 else 0) }
                    Op.LT -> { val y = pop(); push(if (pop() < y) 1 else 0) }
                    Op.LE -> { val y = pop(); push(if (pop() <= y) 1 else 0) }
                    Op.GT -> { val y = pop(); push(if (pop() > y) 1 else 0) }
                    Op.GE -> { val y = pop(); push(if (pop() >= y) 1 else 0) }
                    Op.JMP -> pc = a
                    Op.JZ -> if (pop() == 0) pc = a
                    Op.JNZ -> if (pop() != 0) pc = a
                    Op.CALL -> {
                        val entry = fnEntry.getOrNull(a) ?: throw VmFault("no function $a")
                        if (depth >= CALLS) throw VmFault("call stack overflow")
                        val nargs = code[entry + 1]; val nloc = code[entry + 2]
                        if (nargs < 0 || nloc < 0 || localTop + nargs + nloc > LOCALS) throw VmFault("out of local memory")
                        callPc[depth] = pc; callFp[depth] = fp; callTop[depth] = localTop; depth++
                        val nfp = localTop
                        for (i in nargs - 1 downTo 0) locals[nfp + i] = pop()
                        for (i in 0 until nloc) locals[nfp + nargs + i] = 0
                        fp = nfp; localTop = nfp + nargs + nloc
                        pc = entry + 3
                    }
                    Op.RET, Op.RET0 -> {
                        val v = if (op == Op.RET) pop() else 0
                        if (depth == 0) throw VmFault("return outside a function")
                        depth--; pc = callPc[depth]; fp = callFp[depth]; localTop = callTop[depth]
                        push(v)
                    }
                    Op.LDM -> push(mem[pop() and (MEM - 1)])
                    Op.STM -> { val v = pop(); mem[pop() and (MEM - 1)] = v }
                    Op.LDD -> { val i = pop(); val d = data(a); push(if (d != null && i in d.indices) d[i].toInt() and 0xFF else 0) }
                    Op.SYS -> if (sys(a)) break
                }
            }
        } catch (f: VmFault) {
            crash(f.message ?: "fault")
        } catch (e: Exception) {
            crash("internal: $e")
        }
        instructionsLastFrame = BUDGET - budget
    }

    /** Returns true when the program yields the frame. */
    private fun sys(id: Int): Boolean {
        val b = Builtin.all.getOrNull(id) ?: throw VmFault("bad builtin $id")
        when (b) {
            Builtin.SET -> { val t = pop(); val y = pop(); set(pop(), y, t) }
            Builtin.GET -> { val y = pop(); push(get(pop(), y)) }
            Builtin.FRAME -> { waitFrames = 1; return true }
            Builtin.WAIT -> { waitFrames = pop().coerceIn(1, 3000); return true }
            Builtin.RND -> push(random(pop()))
            Builtin.SETI -> { val t = pop(); val i = pop(); if (w > 0 && i >= 0) set(i % w, i / w, t) }
            Builtin.GETI -> { val i = pop(); push(if (i < 0) WALL_OUTSIDE else get(i % w, i / w)) }
            Builtin.CLS -> { val t = pop() and 0xFF; for (y in 0 until h) for (x in 0 until w) grid[y * MAX_DIM + x] = t }
            Builtin.OVER -> { state = VmState.OVER; sounds.add(SoundEvent(1, 5, 0)); return true }
            Builtin.WIN -> { state = VmState.WON; sounds.add(SoundEvent(1, 6, 0)); return true }
            Builtin.SFX -> sounds.add(SoundEvent(1, pop() and 7, 0))
            Builtin.BEEP -> { val len = pop(); val note = pop(); sounds.add(SoundEvent(0, note.coerceIn(0, 63), len.coerceIn(1, 60))) }
            Builtin.HELD -> { val k = pop(); push(if (k in 0..5) (keys shr k) and 1 else 0) }
            Builtin.PRESSED -> { val k = pop(); push(if (k in 0..5) (pressedBits shr k) and 1 else 0) }
            Builtin.SCORE -> push(score)
            Builtin.SETSCORE -> score = pop()
            Builtin.DIR -> push(dir)
            Builtin.SETDIR -> dir = pop() and 3
            Builtin.TAPX -> push(tapX)
            Builtin.TAPY -> push(tapY)
            Builtin.TAPPED -> push(if (tapX >= 0) 1 else 0)
            Builtin.TICK -> push(tick)
            Builtin.W -> push(w)
            Builtin.H -> push(h)
            Builtin.HISCORE -> push(hiscore)
            Builtin.KEY -> push(keys)
            Builtin.FILL -> {
                val t = pop(); val hh = pop(); val ww = pop(); val y0 = pop(); val x0 = pop()
                for (y in maxOf(0, y0) until minOf(h, y0 + hh)) for (x in maxOf(0, x0) until minOf(w, x0 + ww)) set(x, y, t)
            }
            Builtin.TEXT -> { val s = str(pop()); val y = pop(); val x = pop(); drawText(x, y, s) }
            Builtin.NUM -> { val n = pop(); val y = pop(); val x = pop(); drawText(x, y, n.toString()) }
            Builtin.MSG -> { val id = pop(); message = if (id < 0) null else str(id) }
            Builtin.GRID -> {
                val hh = pop(); val ww = pop()
                w = ww.coerceIn(1, MAX_DIM); h = hh.coerceIn(1, MAX_DIM); grid.fill(0)
            }
            Builtin.RNDEMPTY -> {
                var n = 0
                for (y in 0 until h) for (x in 0 until w) if (grid[y * MAX_DIM + x] == 0) n++
                if (n == 0) push(-1) else {
                    var k = random(n); var found = -1
                    loop@ for (y in 0 until h) for (x in 0 until w) if (grid[y * MAX_DIM + x] == 0) { if (k-- == 0) { found = y * w + x; break@loop } }
                    push(found)
                }
            }
            Builtin.COUNT -> { val t = pop() and 0xFF; var n = 0; for (y in 0 until h) for (x in 0 until w) if (grid[y * MAX_DIM + x] == t) n++; push(n) }
            Builtin.FIND -> {
                val t = pop() and 0xFF; var found = -1
                loop@ for (y in 0 until h) for (x in 0 until w) if (grid[y * MAX_DIM + x] == t) { found = y * w + x; break@loop }
                push(found)
            }
            Builtin.REPLACE -> {
                val bt = pop() and 0xFF; val at = pop() and 0xFF; var n = 0
                for (y in 0 until h) for (x in 0 until w) if (grid[y * MAX_DIM + x] == at) { grid[y * MAX_DIM + x] = bt; n++ }
                push(n)
            }
            Builtin.SCROLL -> { val t = pop() and 0xFF; scroll(pop() and 3, t) }
            Builtin.TILE -> {
                val color = pop(); val d = data(pop()); val id = pop()
                val k = (if (id >= Tiles.FIRST_CUSTOM) id - Tiles.FIRST_CUSTOM else id) and 15
                for (r in 0 until 8) customRows[k * 8 + r] = if (d != null && r < d.size) d[r].toInt() and 0xFF else 0
                customColor[k] = color and 15
            }
            Builtin.SPR -> { val y = pop(); val x = pop(); val t = pop(); val i = pop() and 15; spriteTile[i] = t and 0xFF; spriteX[i] = x; spriteY[i] = y }
            Builtin.XY -> { val y = pop(); push(y * w + pop()) }
            Builtin.PX -> push(Math2.mod(pop(), w))
            Builtin.PY -> { val i = pop(); push(if (i < 0) -1 else i / w) }
            Builtin.ABS -> push(pop().let { if (it < 0) -it else it })
            Builtin.MIN -> { val y = pop(); push(minOf(pop(), y)) }
            Builtin.MAX -> { val y = pop(); push(maxOf(pop(), y)) }
            Builtin.SGN -> push(pop().let { if (it > 0) 1 else if (it < 0) -1 else 0 })
            Builtin.DX -> push(DXS[pop() and 3])
            Builtin.DY -> push(DYS[pop() and 3])
            Builtin.NBR -> {
                val t = pop() and 0xFF; val y = pop(); val x = pop(); var n = 0
                for (dy in -1..1) for (dx in -1..1) if ((dx != 0 || dy != 0) && inGrid(x + dx, y + dy) && grid[(y + dy) * MAX_DIM + x + dx] == t) n++
                push(n)
            }
            Builtin.INSIDE -> { val y = pop(); push(if (inGrid(pop(), y)) 1 else 0) }
            Builtin.ROWFULL -> { val y = pop(); push(if (y in 0 until h && (0 until w).all { grid[y * MAX_DIM + it] != 0 }) 1 else 0) }
            Builtin.DELROW -> {
                val y = pop()
                if (y in 0 until h) {
                    for (r in y downTo 1) for (x in 0 until w) grid[r * MAX_DIM + x] = grid[(r - 1) * MAX_DIM + x]
                    for (x in 0 until w) grid[x] = 0
                }
            }
            Builtin.CLAMP -> { val hi = pop(); val lo = pop(); val v = pop(); push(if (v < lo) lo else if (v > hi) hi else v) }
            Builtin.FLOOD -> { val t = pop() and 0xFF; val y = pop(); push(flood(pop(), y, t)) }
            Builtin.LEN -> push(data(pop())?.size ?: 0)
            Builtin.TOUCHX -> push(touchX)
            Builtin.TOUCHY -> push(touchY)
        }
        return false
    }

    private fun drawText(x: Int, y: Int, s: String) {
        for ((i, c0) in s.withIndex()) {
            var c = c0.code
            if (c in 'a'.code..'z'.code) c -= 32
            set(x + i, y, if (c in 32..95) c + 32 else Tiles.FIRST_GLYPH)
        }
    }

    private fun scroll(d: Int, t: Int) {
        val dx = DXS[d]; val dy = DYS[d]
        val xs = if (dx > 0) (w - 1 downTo 0) else (0 until w)
        val ys = if (dy > 0) (h - 1 downTo 0) else (0 until h)
        for (y in ys) for (x in xs) {
            val sx = x - dx; val sy = y - dy
            grid[y * MAX_DIM + x] = if (inGrid(sx, sy)) grid[sy * MAX_DIM + sx] else t
        }
    }

    private fun flood(x: Int, y: Int, t: Int): Int {
        if (!inGrid(x, y)) return 0
        val from = grid[y * MAX_DIM + x]
        if (from == t) return 0
        val queue = IntArray(w * h); var head = 0; var tail = 0; var n = 0
        grid[y * MAX_DIM + x] = t; queue[tail++] = y * MAX_DIM + x
        while (head < tail) {
            val p = queue[head++]; n++
            val px = p % MAX_DIM; val py = p / MAX_DIM
            for (d in 0..3) {
                val nx = px + DXS[d]; val ny = py + DYS[d]
                if (inGrid(nx, ny) && grid[ny * MAX_DIM + nx] == from) { grid[ny * MAX_DIM + nx] = t; queue[tail++] = ny * MAX_DIM + nx }
            }
        }
        return n
    }
}

private val DXS = intArrayOf(1, 0, -1, 0)
private val DYS = intArrayOf(0, 1, 0, -1)

internal class VmFault(msg: String) : Exception(msg)
