package bytecart

import java.io.File
import kotlin.test.Test

class PriorsExperiment {
    @Test fun leaveOneOut() {
        val files = File("../games").listFiles { f -> f.extension == "bcs" }!!.sortedBy { it.name }
        val progs = files.associate { it.name to Compiler.compile(it.readText()).program }
        for (conf in listOf(2, 4, 6, 10, 20)) {
            var tot = 0; var totU = 0
            for ((name, p) in progs) {
                val pri = Codec.train(progs.filterKeys { it != name }.values.toList(), conf)
                tot += Codec.encode(p, pri).size + 1
                totU += Codec.encode(p, null).size + 1
            }
            println("conf=$conf leave-one-out total=$tot  uniform total=$totU")
        }
    }
}
