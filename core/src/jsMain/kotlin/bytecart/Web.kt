@file:OptIn(ExperimentalJsExport::class)

package bytecart

/** JavaScript API used by the web playground (web/play). */
@JsExport
class WebCompile(
    val ok: Boolean,
    val error: String?,
    val errorLine: Int,
    val image: ByteArray?,
    val size: Int,
    val naive: Int,
    val disasm: String,
    val warnings: Array<String>,
)

@JsExport
fun compileSource(src: String): WebCompile = try {
    val r = Compiler.compile(src)
    val img = Cart.pack(r.program)
    WebCompile(true, null, 0, img, img.size, r.program.naiveSize(), r.program.disassemble(), r.warnings.toTypedArray())
} catch (e: CompileError) {
    WebCompile(false, e.message, e.line, null, 0, 0, "", emptyArray())
}

@JsExport
fun isCart(image: ByteArray): Boolean = Cart.isCart(image)

@JsExport
val NFC_CAPACITY = Cart.NFC_NTAG213

@JsExport
val QR_CAPACITY = Cart.QR_MAX

@JsExport
fun palette(): IntArray = Tiles.palette

@JsExport
fun builtinDocs(): Array<String> = Builtin.all.map { it.doc }.toTypedArray()

@JsExport
fun tileNames(): Array<String> = Tiles.constants.keys.toTypedArray()

/** A running game. Throws if the image is not a valid cart. */
@JsExport
class WebGame(image: ByteArray, seed: Int) {
    private val vm = Vm(Cart.unpack(image), seed)
    val fps get() = Vm.FPS
    fun frame() = vm.frame()
    fun press(k: Int) = vm.press(k)
    fun release(k: Int) = vm.release(k)
    fun tap(x: Int, y: Int) = vm.tap(x, y)
    fun touch(x: Int, y: Int) = vm.touch(x, y)
    fun touchEnd() = vm.touchEnd()
    fun reset(seed: Int) = vm.reset(seed)
    val w get() = vm.w
    val h get() = vm.h
    fun cell(x: Int, y: Int) = vm.cell(x, y)
    fun spriteTile(i: Int) = vm.spriteTile[i]
    fun spriteX(i: Int) = vm.spriteX[i]
    fun spriteY(i: Int) = vm.spriteY[i]
    val message get() = vm.message
    val state get() = vm.state.name
    val score get() = vm.score
    var hiscore: Int
        get() = vm.hiscore
        set(v) { vm.hiscore = v }
    val error get() = vm.error
    val instructions get() = vm.instructionsLastFrame
    /** ARGB pixels for a tile (64 values, 0 = transparent), or null for empty and glyph tiles. */
    fun tilePixels(t: Int): IntArray? = Tiles.bitmap(t, vm.customRows, vm.customColor)
    fun glyph(t: Int): String? = Tiles.glyph(t)?.toString()
    fun isDim(t: Int) = Tiles.isDim(t)
    /** Key that changes when custom tile art changes (for caching). */
    fun customKey(): Int = vm.customRows.contentHashCode() * 31 + vm.customColor.contentHashCode()
    /** Pending sounds as flat triples (kind, a, b); clears the queue. */
    fun drainSounds(): IntArray {
        val out = IntArray(vm.sounds.size * 3)
        vm.sounds.forEachIndexed { i, s -> out[i * 3] = s.kind; out[i * 3 + 1] = s.a; out[i * 3 + 2] = s.b }
        vm.sounds.clear()
        return out
    }
}
