package dev.bytecart

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import bytecart.Cart
import bytecart.DecodeError
import bytecart.Program
import java.io.IOException

sealed interface Screen {
    data object Home : Screen
    data object Library : Screen
    data object Scan : Screen
    class Play(val program: Program, val image: ByteArray, val title: String) : Screen
    class Qr(val image: ByteArray, val title: String) : Screen
    class Editor(val source: String, val id: String?) : Screen
}

class WriteJob(val title: String, val image: ByteArray) {
    var result by mutableStateOf<String?>(null)
}

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Home)
    private var back = ArrayList<Screen>()
    private var writeJob by mutableStateOf<WriteJob?>(null)
    private var status by mutableStateOf<String?>(null)
    private var games by mutableStateOf<List<GameEntry>>(emptyList())
    private var nfc: NfcAdapter? = null
    private lateinit var sound: Sound

    private fun go(s: Screen) { back.add(screen); screen = s }
    private fun goBack() { screen = back.removeLastOrNull() ?: Screen.Home }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfc = NfcAdapter.getDefaultAdapter(this)
        sound = Sound()
        games = Library.all(this)
        handleIntent(intent)
        setContent {
            val s = screen
            BackHandler(enabled = s != Screen.Home) { goBack() }
            when (s) {
                Screen.Home -> HomeScreen(nfcStatus(), status, onLibrary = { go(Screen.Library) }, onScan = { go(Screen.Scan) },
                    onEditor = { go(Screen.Editor(Library.TEMPLATE, null)) })
                Screen.Library -> LibraryScreen(games,
                    onPlay = { g -> g.cart.getOrNull()?.let { play(it, g.name) } },
                    onWrite = { g -> g.cart.getOrNull()?.let { writeJob = WriteJob(g.name, it) } },
                    onQr = { g -> g.cart.getOrNull()?.let { go(Screen.Qr(it, g.name)) } },
                    onEdit = { g -> go(Screen.Editor(g.source, if (g.builtIn) null else g.id)) },
                    onBack = ::goBack)
                Screen.Scan -> ScanScreen(onResult = { bytes -> back.removeLastOrNull(); screen = back.removeLastOrNull() ?: Screen.Home; openImage(bytes, "QR") }, onBack = ::goBack)
                is Screen.Play -> GameScreen(s.program, s.image, s.title, sound, onExit = ::goBack)
                is Screen.Qr -> QrScreen(s.image, s.title, onBack = ::goBack)
                is Screen.Editor -> EditorScreen(s.source, s.id,
                    onRun = { play(it, "Test run") },
                    onWrite = { name, img -> writeJob = WriteJob(name, img) },
                    onQr = { name, img -> go(Screen.Qr(img, name)) },
                    onSaved = { games = Library.all(this) },
                    onBack = ::goBack)
            }
            writeJob?.let { job -> WriteDialog(job.title, job.image.size, job.result) { writeJob = null } }
        }
    }

    private fun nfcStatus() = when {
        nfc == null -> "This phone has no NFC. You can still play QR and library games."
        nfc?.isEnabled == false -> "NFC is off. Turn it on in Settings."
        else -> "NFC ready"
    }

    override fun onResume() {
        super.onResume()
        // Reader mode: this app gets every tag while it is open, and Android skips its own NDEF
        // parsing (our tags are raw, not NDEF).
        nfc?.enableReaderMode(this, ::onTag,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            "dev.bytecart.PLAY" -> intent.getStringExtra("cart")?.let { hex ->
                val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                openImage(bytes, "pushed")
            }
            NfcAdapter.ACTION_TECH_DISCOVERED -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG))?.let { tag -> Thread { onTag(tag) }.start() }
            }
        }
    }

    /** Called on a binder thread for every tag that comes near the phone. */
    private fun onTag(tag: Tag) {
        val job = writeJob
        if (job != null && job.result == null) {
            val msg = try {
                val info = TagIo.write(tag, job.image)
                "Done. \"${job.title}\" is on your ${info.kind}: ${job.image.size} of ${info.capacity} bytes used, verified."
            } catch (e: IOException) {
                Log.w("ByteCart", "write failed", e)
                e.message ?: "Write failed. Hold the tag still and try again."
            } catch (e: Exception) {
                Log.w("ByteCart", "write failed", e)
                "Write failed: ${e.message}"
            }
            runOnUiThread { job.result = msg }
            return
        }
        try {
            val r = TagIo.read(tag)
            runOnUiThread { openImage(r.image, r.info.kind) }
        } catch (e: Exception) {
            Log.w("ByteCart", "read failed", e)
            runOnUiThread { status = "Could not read the tag: ${e.message}" }
        }
    }

    private fun openImage(image: ByteArray, source: String) {
        if (!Cart.isCart(image)) {
            status = "This $source does not hold a ByteCart game (blank or foreign). Pick a game in the Library and tap \"Write tag\"."
            screen = Screen.Home; back.clear()
            return
        }
        val title = Library.match(this, image)?.name ?: "Cartridge"
        try {
            val prog = Cart.unpack(image)
            status = null
            if (screen is Screen.Play) back.clear().also { screen = Screen.Home }
            go(Screen.Play(prog, image.copyOf(Cart.trimmedLength(image)), title))
        } catch (e: DecodeError) {
            status = "The game data is damaged (${e.message}). Rewrite the tag."
        }
    }

    private fun play(image: ByteArray, title: String) {
        go(Screen.Play(Cart.unpack(image), image, title))
    }
}
