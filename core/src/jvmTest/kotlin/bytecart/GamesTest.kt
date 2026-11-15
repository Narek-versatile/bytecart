package bytecart

import java.io.File
import kotlin.random.Random
import kotlin.test.*

class GamesTest {
    private val dir = File("../games")

    private fun games() = dir.listFiles { f -> f.extension == "bcs" }!!.sortedBy { it.name }

    @Test fun everyGameBuildsFitsAndRuns() {
        val rows = ArrayList<String>()
        for (f in games()) {
            val src = f.readText()
            val res = Compiler.compile(src)
            res.warnings.forEach { println("${f.name}: warning $it") }
            val img = Cart.pack(res.program)
            assertEquals(res.program, Cart.unpack(img), f.name)
            val medium = Regex("@medium (\\w+)").find(src)?.groupValues?.get(1) ?: "qr"
            val cap = if (medium == "nfc") Cart.NFC_NTAG213 else Cart.QR_MAX
            rows.add("%-12s %-4s instr=%4d naive=%5d cart=%5d cap=%5d".format(f.name, medium, res.program.instructionCount, res.program.naiveSize(), img.size, cap))
            assertTrue(img.size <= cap, "${f.name} is ${img.size} bytes, capacity $cap")
            // smoke run with random input
            val r = Random(f.name.hashCode())
            for (round in 0 until 3) {
                val vm = Vm(res.program, r.nextInt())
                repeat(3000) {
                    if (r.nextInt(4) == 0) vm.press(r.nextInt(6)) else vm.release(r.nextInt(6))
                    if (r.nextInt(6) == 0) vm.tap(r.nextInt(vm.w), r.nextInt(vm.h))
                    vm.frame()
                }
                assertNotEquals(VmState.CRASHED, vm.state, "${f.name}: ${vm.error}")
            }
        }
        println(rows.joinToString("\n"))
    }
}
