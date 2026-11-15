package bytecart

import kotlin.random.Random
import kotlin.test.*

class CoreTest {
    private fun runMain(src: String, frames: Int = 5): Vm {
        val vm = Vm(Compiler.compile(src).program)
        repeat(frames) { vm.frame() }
        return vm
    }

    @Test fun arithmeticMatchesFolder() {
        // the same expression evaluated at compile time (consts) and at run time (vars) must agree
        val exprs = listOf("a + b * 3", "a / b", "a % b", "-a % b", "a << 3 >> 1", "a ^ b | 5 & 3", "a < b && b != 0", "a || b", "!a", "~a", "a > b ? a : b")
        for (e in exprs) for ((a, b) in listOf(7 to 3, -7 to 3, 7 to -3, 0 to 5, 5 to 0)) {
            val folded = runMain("const a = $a, b = $b\nscore = $e\nhalt_now()".replace("\nhalt_now()", ""))
            val runtime = runMain("var a = $a, b = $b\nscore = $e")
            assertEquals(folded.score, runtime.score, "$e with a=$a b=$b")
        }
    }

    @Test fun controlFlowAndFunctions() {
        val vm = runMain(
            """
            fn fib(n) { if n < 2 { return n } return fib(n - 1) + fib(n - 2) }
            var s = 0
            for i in 0..10 { if i % 2 == 0 { continue } s += i }
            var k = 0
            while true { k++; if k == 7 { break } }
            score = fib(15) * 1000 + s * 10 + k
            """.trimIndent()
        )
        assertEquals(VmState.HALTED, vm.state)
        assertEquals(610 * 1000 + 25 * 10 + 7, vm.score)
    }

    @Test fun gridBuiltins() {
        val vm = runMain(
            """
            grid(8, 4)
            fill(0, 0, 8, 1, RED)
            set(3, 2, APPLE)
            score = count(RED) * 100 + find(APPLE)
            """.trimIndent()
        )
        assertEquals(8 * 100 + (2 * 8 + 3), vm.score)
        assertEquals(Tiles.constants["APPLE"], vm.cell(3, 2))
        assertEquals(16, Tiles.constants["WALL"])
    }

    @Test fun compileErrorsHaveLocations() {
        val e = assertFailsWith<CompileError> { Compiler.compile("var x = 1\nx = y + 2") }
        assertEquals(2, e.line)
        assertFailsWith<CompileError> { Compiler.compile("set(1, 2)") }
        assertFailsWith<CompileError> { Compiler.compile("if 1 { ") }
    }

    @Test fun cartRoundTrip() {
        val src = """
            data L = "HELLO WORLD"
            var a = 1000000, b = -5
            loop { a += b; text(0, 0, L); frame() }
        """.trimIndent()
        val prog = Compiler.compile(src).program
        val img = Cart.pack(prog)
        assertTrue(Cart.isCart(img))
        assertEquals(prog, Cart.unpack(img))
        // zero padding (NFC pages) decodes to the same program
        assertEquals(prog, Cart.unpack(Cart.padTo(img, Cart.NFC_NTAG213)))
        // a flipped bit is detected
        val bad = img.copyOf(); bad[2] = (bad[2].toInt() xor 0x10).toByte()
        assertFalse(Cart.isCart(bad))
    }

    @Test fun randomProgramsRoundTrip() {
        val r = Random(1)
        repeat(300) {
            val n = r.nextInt(1, 60)
            val code = ArrayList<Int>()
            for (i in 0 until n) {
                val op = Op.all[r.nextInt(1, Op.all.size)]
                code.add(op.ordinal)
                for (a in op.args) code.add(
                    when (a) { Arg.JUMP -> r.nextInt(0, n); Arg.UINT -> r.nextInt(0, 300); Arg.INT -> if (r.nextBoolean()) r.nextInt() else r.nextInt(-40, 40) }
                )
            }
            val data = List(r.nextInt(0, 4)) { ByteArray(r.nextInt(0, 30)) { r.nextInt().toByte() } }
            val p = Program(code.toIntArray(), data)
            assertEquals(p, Cart.unpack(Cart.pack(p)))
        }
    }

    @Test fun garbageNeverCrashesTheHost() {
        val r = Random(2)
        var decoded = 0
        repeat(2000) {
            val img = ByteArray(r.nextInt(1, 144)) { r.nextInt().toByte() }
            val prog = try { Codec.decode(img) } catch (e: DecodeError) { null }
            if (prog != null) {
                decoded++
                val vm = Vm(prog)
                repeat(20) { vm.press(r.nextInt(6)); vm.tap(r.nextInt(16), r.nextInt(16)); vm.frame() }
            }
        }
        assertTrue(decoded > 0)
    }
}
