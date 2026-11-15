package dev.bytecart

import android.content.Context
import bytecart.Cart
import bytecart.CompileError
import bytecart.Compiler
import java.io.File

/** A game the app knows about: bundled in the APK or saved from the editor. */
class GameEntry(val id: String, val source: String, val builtIn: Boolean) {
    private fun meta(key: String) = Regex("""//\s*@$key\s+(.+)""").find(source)?.groupValues?.get(1)?.trim()
    val name: String = meta("name") ?: id
    val desc: String = meta("desc") ?: ""
    val medium: String = meta("medium") ?: "qr"

    /** Compiled cart image, or the compile error. */
    val cart: Result<ByteArray> by lazy {
        try { Result.success(Cart.pack(Compiler.compile(source).program)) } catch (e: CompileError) { Result.failure(e) }
    }
}

object Library {
    private fun userDir(ctx: Context) = File(ctx.filesDir, "games").apply { mkdirs() }

    fun builtIn(ctx: Context): List<GameEntry> =
        (ctx.assets.list("games") ?: emptyArray()).filter { it.endsWith(".bcs") }.sorted().map { f ->
            GameEntry(f.removeSuffix(".bcs"), ctx.assets.open("games/$f").bufferedReader().readText(), true)
        }

    fun user(ctx: Context): List<GameEntry> =
        userDir(ctx).listFiles { f -> f.extension == "bcs" }.orEmpty().sortedBy { it.name }
            .map { GameEntry(it.nameWithoutExtension, it.readText(), false) }

    fun all(ctx: Context) = builtIn(ctx) + user(ctx)

    fun save(ctx: Context, id: String, source: String) {
        val safe = id.lowercase().replace(Regex("[^a-z0-9_-]"), "_").ifEmpty { "game" }
        File(userDir(ctx), "$safe.bcs").writeText(source)
    }

    fun delete(ctx: Context, id: String) { File(userDir(ctx), "$id.bcs").delete() }

    /** Find the library game whose cart matches an image read from a tag or QR code. */
    fun match(ctx: Context, image: ByteArray): GameEntry? {
        val n = Cart.trimmedLength(image)
        val key = image.copyOf(n)
        return all(ctx).firstOrNull { g -> g.cart.getOrNull()?.let { Cart.trimmedLength(it) == n && it.copyOf(n).contentEquals(key) } == true }
    }

    const val TEMPLATE = """// @name My Game
// @desc Describe your game here.
// @medium nfc
var x = 8, y = 8
loop {
  set(x, y, 0)
  x = (x + dx(dir)) % W
  y = (y + dy(dir)) % H
  set(x, y, PLAYER)
  wait(4)
}
"""
}
