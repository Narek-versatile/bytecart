package bytecart.cli

import bytecart.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private const val USAGE = """bytecart: desktop compiler for ByteCart games

usage:
  bytecart compile <game.bcs> [-o out.bcart]   compile to a cartridge image
  bytecart info <game.bcs|game.bcart>          sizes and which media it fits
  bytecart dis <game.bcs|game.bcart>           disassemble
  bytecart qr <game.bcs|game.bcart> [-o out.png] [--scale 8]
                                               render a QR code (byte mode, ECC L)
  bytecart hex <game.bcs|game.bcart>           print the cart as hex
  bytecart push <game.bcs|game.bcart> [-s serial]
                                               open the game on a phone over adb
  bytecart train <games-dir> [-o Priors.kt]    learn codec priors from a corpus
  bytecart sizes <games-dir>                   size table, with leave-one-out priors
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) { print(USAGE); return }
    try {
        when (args[0]) {
            "compile" -> compile(args)
            "info" -> info(args)
            "dis" -> println(load(arg(args, 1)).let { Cart.unpack(it) }.disassemble())
            "qr" -> qr(args)
            "hex" -> println(load(arg(args, 1)).joinToString("") { "%02x".format(it) })
            "push" -> push(args)
            "train" -> train(args)
            "sizes" -> sizes(args)
            else -> fail("unknown command '${args[0]}'\n$USAGE")
        }
    } catch (e: CompileError) {
        fail("${args.getOrNull(1)}:${e.message}")
    } catch (e: DecodeError) {
        fail("not a valid cart: ${e.message}")
    }
}

private fun fail(msg: String): Nothing { System.err.println("error: $msg"); exitProcess(1) }
private fun arg(a: Array<String>, i: Int) = a.getOrNull(i) ?: fail("missing argument\n$USAGE")
private fun opt(a: Array<String>, name: String) = a.indexOf(name).takeIf { it >= 0 }?.let { a.getOrNull(it + 1) }

/** Cart image from a .bcs source or a .bcart file. */
private fun load(path: String): ByteArray {
    val f = File(path)
    if (!f.exists()) fail("no such file: $path")
    if (f.extension == "bcart") return f.readBytes()
    val res = Compiler.compile(f.readText())
    res.warnings.forEach { System.err.println("warning: $it") }
    return Cart.pack(res.program)
}

private fun media(size: Int) = buildString {
    append(if (size <= Cart.NFC_NTAG213) "fits NTAG213 (144 B)" else "too big for NTAG213 (144 B)")
    append(", ")
    append(if (size <= 504) "fits NTAG215 (504 B)" else "too big for NTAG215")
    append(", ")
    append(if (size <= Cart.QR_MAX) "fits a QR code (${qrVersion(size)})" else "too big for one QR code")
}

/** Smallest QR version holding n bytes in byte mode at ECC level L. */
fun qrVersion(n: Int): String {
    val caps = intArrayOf(17, 32, 53, 78, 106, 134, 154, 192, 230, 271, 321, 367, 425, 458, 520, 586, 644, 718, 792, 858,
        929, 1003, 1091, 1171, 1273, 1367, 1465, 1528, 1628, 1732, 1840, 1952, 2068, 2188, 2303, 2431, 2563, 2699, 2809, 2953)
    val v = caps.indexOfFirst { it >= n }
    return if (v < 0) "none" else "version ${v + 1}"
}

private fun compile(a: Array<String>) {
    val src = arg(a, 1)
    val img = load(src)
    val out = File(opt(a, "-o") ?: File(src).nameWithoutExtension + ".bcart")
    out.writeBytes(img)
    println("${out.path}: ${img.size} bytes, ${media(img.size)}")
}

private fun info(a: Array<String>) {
    val path = arg(a, 1)
    val img = load(path)
    val p = Cart.unpack(img)
    println("file:          $path")
    println("cart:          ${img.size} bytes (1 check byte + ${img.size - 1} coded)")
    println("naive bytecode ${p.naiveSize()} bytes (1 byte per opcode, varint operands)")
    println("instructions:  ${p.instructionCount}, data blocks: ${p.data.size} (${p.data.sumOf { it.size }} bytes)")
    println("media:         ${media(img.size)}")
}

private fun qr(a: Array<String>) {
    val path = arg(a, 1)
    val img = load(path)
    if (img.size > Cart.QR_MAX) fail("cart is ${img.size} bytes, a QR code holds at most ${Cart.QR_MAX}")
    val scale = opt(a, "--scale")?.toInt() ?: 8
    val text = String(img, Charsets.ISO_8859_1)
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.CHARACTER_SET to "ISO-8859-1", EncodeHintType.MARGIN to 4)
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val bi = BufferedImage(m.width * scale, m.height * scale, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until bi.height) for (x in 0 until bi.width) bi.setRGB(x, y, if (m.get(x / scale, y / scale)) 0 else 0xFFFFFF)
    val out = File(opt(a, "-o") ?: File(path).nameWithoutExtension + ".png")
    ImageIO.write(bi, "png", out)
    println("${out.path}: ${m.width - 8}x${m.height - 8} modules (${qrVersion(img.size)}), ${img.size} bytes")
}

private fun push(a: Array<String>) {
    val img = load(arg(a, 1))
    val hex = img.joinToString("") { "%02x".format(it) }
    val cmd = mutableListOf("adb")
    opt(a, "-s")?.let { cmd += listOf("-s", it) }
    cmd += listOf("shell", "am", "start", "-n", "dev.bytecart/.MainActivity", "-a", "dev.bytecart.PLAY", "--es", "cart", hex)
    val p = ProcessBuilder(cmd).inheritIO().start()
    exitProcess(p.waitFor())
}

private fun corpus(dir: String) = File(dir).listFiles { f -> f.extension == "bcs" }!!.sortedBy { it.name }
    .associate { it.nameWithoutExtension to Compiler.compile(it.readText()).program }

private fun train(a: Array<String>) {
    val progs = corpus(arg(a, 1))
    val table = Codec.train(progs.values.toList())
    val s = Codec.priorsToString(table)
    val chunks = s.chunked(16000).joinToString(" +\n        ") { "\"$it\"" }
    val out = File(opt(a, "-o") ?: "Priors.kt")
    out.writeText(
        """
        |package bytecart
        |
        |/** Learned starting probabilities for [Codec]. Generated by `bytecart train` from ${progs.size} games. Do not edit. */
        |object Priors {
        |    /** Format v1 priors. Changing this string changes the bitstream: bump Cart.FORMAT_TAG when you do. */
        |    internal const val V1 = $chunks
        |
        |    val table: IntArray by lazy { Codec.priorsFromString(V1) }
        |}
        |""".trimMargin()
    )
    println("${out.path}: ${table.size / 2} trained contexts from ${progs.size} games (${s.length} chars)")
}

private fun sizes(a: Array<String>) {
    val progs = corpus(arg(a, 1))
    println("%-10s %6s %9s %9s %8s".format("game", "naive", "untrained", "held-out", "shipped"))
    var tn = 0; var tu = 0; var tl = 0; var ts = 0
    for ((name, p) in progs) {
        val naive = p.naiveSize()
        val flat = Codec.encode(p, null).size + 1
        val loo = Codec.encode(p, Codec.train(progs.filterKeys { it != name }.values.toList())).size + 1
        val shipped = Cart.pack(p).size
        tn += naive; tu += flat; tl += loo; ts += shipped
        println("%-10s %6d %9d %9d %8d".format(name, naive, flat, loo, shipped))
    }
    println("%-10s %6d %9d %9d %8d".format("total", tn, tu, tl, ts))
    println("naive = 1 byte/opcode + varints; untrained = codec without priors;")
    println("held-out = priors learned from the other games only; shipped = priors from all games.")
}
