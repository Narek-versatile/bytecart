package dev.bytecart

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import bytecart.Builtin
import bytecart.Cart
import bytecart.CompileError
import bytecart.Compiler
import kotlinx.coroutines.delay
import java.io.File

private val Mono = FontFamily.Monospace

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) Text("‹", color = Dim, fontSize = 30.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onBack() }.padding(end = 12.dp))
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Btn(text: String, primary: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(if (!enabled) Panel else if (primary) Accent else Panel)
            .clickable(enabled = enabled) { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (!enabled) Dim else if (primary) Bg else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

/** Capacity bar: [used] of [cap] bytes. */
@Composable
fun SizeBar(label: String, used: Int, cap: Int) {
    val frac = (used.toFloat() / cap).coerceIn(0f, 1f)
    val over = used > cap
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (over) "$used / $cap B · too big" else "$used / $cap B", color = if (over) Color(0xFFE8434F) else Color.White, fontSize = 12.sp, fontFamily = Mono)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Panel)) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(if (over) Color(0xFFE8434F) else Accent))
        }
    }
}

@Composable
fun HomeScreen(nfcStatus: String, status: String?, onLibrary: () -> Unit, onScan: () -> Unit, onEditor: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Text("BYTECART", color = Accent, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 40.sp)
        Text("games that fit on a sticker", color = Dim, fontSize = 15.sp)
        Spacer(Modifier.height(48.dp))
        Box(Modifier.size(170.dp).clip(RoundedCornerShape(85.dp)).background(Panel), contentAlignment = Alignment.Center) {
            Text("((•))", color = Accent, fontSize = 40.sp, fontFamily = Mono)
        }
        Spacer(Modifier.height(18.dp))
        Text("Hold a game tag to the back of the phone", color = Color.White, fontSize = 17.sp)
        Text(nfcStatus, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        if (status != null) {
            Text(status, color = Color(0xFFF28C28), fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp).clip(RoundedCornerShape(10.dp)).background(Panel).padding(12.dp))
        }
        Spacer(Modifier.weight(1f))
        Btn("Scan a QR game", primary = true, modifier = Modifier.fillMaxWidth(), onClick = onScan)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Btn("Library", modifier = Modifier.weight(1f), onClick = onLibrary)
            Spacer(Modifier.width(10.dp))
            Btn("Editor", modifier = Modifier.weight(1f), onClick = onEditor)
        }
    }
}

@Composable
fun LibraryScreen(games: List<GameEntry>, onPlay: (GameEntry) -> Unit, onWrite: (GameEntry) -> Unit, onQr: (GameEntry) -> Unit, onEdit: (GameEntry) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(16.dp)) {
        TopBar("Library", onBack)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(games, key = { (if (it.builtIn) "b:" else "u:") + it.id }) { g ->
                val size = g.cart.getOrNull()?.size
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        val fits = size != null && size <= Cart.NFC_NTAG213
                        Text(
                            if (size == null) "error" else if (fits) "TAG · $size B" else "QR · $size B",
                            color = if (fits) Accent else Color(0xFF6FD0F2), fontFamily = Mono, fontSize = 12.sp,
                        )
                    }
                    if (!g.builtIn) Text("my game", color = Dim, fontSize = 11.sp)
                    Text(g.desc, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("Play", primary = true, enabled = size != null) { onPlay(g) }
                        Btn("Write tag", enabled = size != null) { onWrite(g) }
                        Btn("QR", enabled = size != null && size <= Cart.QR_MAX) { onQr(g) }
                        Btn("Code") { onEdit(g) }
                    }
                }
            }
        }
    }
}

@Composable
fun QrScreen(image: ByteArray, title: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val bmp = remember(image) { Qr.encode(image, 8) }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar(title, onBack)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(6.dp)) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
        }
        Text("${image.size} bytes · ${Qr.modules(image.size)}×${Qr.modules(image.size)} modules", color = Dim, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        Text("Scan it with ByteCart on another phone, or share or print it.", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Btn("Share image", primary = true) {
            val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
            val f = File(dir, title.lowercase().replace(Regex("[^a-z0-9]+"), "-") + ".png")
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(ctx, "dev.bytecart.files", f)
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share $title"))
        }
    }
}

@Composable
fun WriteDialog(title: String, size: Int, result: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(if (result == null) "Write \"$title\"" else "Tag", color = Color.White) },
        text = {
            Column {
                if (result == null) {
                    Text("Hold your tag to the back of the phone and keep it still until this says done.", color = Color.White)
                    Text("$size bytes · NTAG213 holds 144", color = Dim, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp), color = Accent, trackColor = Bg)
                } else Text(result, color = Color.White)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (result == null) "Cancel" else "OK", color = Accent) } },
    )
}

@Composable
fun EditorScreen(initial: String, initialId: String?, onRun: (ByteArray) -> Unit, onWrite: (String, ByteArray) -> Unit, onQr: (String, ByteArray) -> Unit, onSaved: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    var size by remember { mutableStateOf<Int?>(null) }
    var cart by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showRef by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    val name = Regex("""//\s*@name\s+(.+)""").find(text)?.groupValues?.get(1)?.trim() ?: "game"

    LaunchedEffect(text) {
        delay(250)
        try {
            val img = Cart.pack(Compiler.compile(text).program)
            cart = img; size = img.size; error = null
        } catch (e: CompileError) { error = e.message; cart = null }
    }

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TopBar("Editor", onBack) }
            Btn("Reference") { showRef = !showRef }
        }
        size?.let {
            SizeBar("NFC tag (NTAG213)", it, Cart.NFC_NTAG213)
            SizeBar("QR code (v40-L)", it, Cart.QR_MAX)
        }
        Text(error ?: (saved ?: "compiles OK"), color = if (error != null) Color(0xFFE8434F) else Dim, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        if (showRef) {
            Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)).background(Panel).verticalScroll(rememberScrollState()).padding(10.dp)) {
                Text(REFERENCE, color = Color.White, fontFamily = Mono, fontSize = 12.sp)
                for (b in Builtin.all) if (b.doc.isNotEmpty() && b != Builtin.SETSCORE && b != Builtin.SETDIR && b != Builtin.LEN.takeIf { false })
                    Text(b.doc, color = Accent, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                Text("\nTiles: " + bytecart.Tiles.constants.keys.joinToString(" "), color = Dim, fontFamily = Mono, fontSize = 12.sp)
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)).background(Panel).horizontalScroll(rememberScrollState())) {
                BasicTextField(
                    value = text, onValueChange = { text = it; saved = null },
                    textStyle = TextStyle(color = Color.White, fontFamily = Mono, fontSize = 13.sp, lineHeight = 18.sp),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.widthIn(min = 600.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(10.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Run", primary = true, enabled = cart != null) { cart?.let(onRun) }
            Btn("Write tag", enabled = cart != null) { cart?.let { onWrite(name, it) } }
            Btn("QR", enabled = (size ?: Int.MAX_VALUE) <= Cart.QR_MAX && cart != null) { cart?.let { onQr(name, it) } }
            Btn("Save") { Library.save(ctx, initialId ?: name, text); saved = "saved to My games"; onSaved() }
        }
    }
}

private const val REFERENCE = """LANGUAGE
var x = 1, y          globals (locals inside fn)
const N = 10          compile-time constant
data L = [1, 2, "AB"] byte table: L[i], len(L)
fn f(a, b) { return a + b }
if c { } else if d { } else { }
while c { }   loop { }   for i in 0..n { }
break continue return
x += 1  x++  mem[i] = v   (4096 ints)
ops: + - * / % & | ^ << >> == != < <= > >= && || ! ~ ?:
specials: score dir tick W H tapx tapy touchx touchy hiscore key
buttons: RIGHT DOWN LEFT UP A B   tile + DIM = dimmed

BUILTINS"""
