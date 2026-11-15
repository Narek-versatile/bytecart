package dev.bytecart

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import java.io.IOException

/**
 * Raw cartridge I/O on NFC tags.
 *
 * NTAG21x / Ultralight tags (your NTAG213) are used without NDEF: the cart image goes straight
 * into the user pages (4 .. 4 + size/4 - 1), which gives all 144 bytes to the game.
 * Pages 0-3 (UID, lock bytes, capability container) and everything after the user area
 * (dynamic lock, config, password) are never written, and the tag is never made read-only.
 *
 * Other NDEF tags fall back to one MIME record "application/x-bytecart".
 */
object TagIo {
    const val MIME = "application/x-bytecart"
    private const val FIRST_USER_PAGE = 4

    class TagInfo(val kind: String, val capacity: Int)
    class ReadResult(val info: TagInfo, val image: ByteArray)

    /** Size of the user area in bytes, from the capability container or GET_VERSION. */
    private fun userBytes(mu: MifareUltralight, tag: Tag): Pair<String, Int> {
        // GET_VERSION first: tags that don't support it NAK and must be reconnected
        val version = try { mu.transceive(byteArrayOf(0x60)) } catch (e: IOException) {
            try { mu.close(); mu.connect() } catch (_: IOException) {}
            null
        }
        val head = mu.readPages(3) // pages 3..6, page 3 = CC
        val cc0 = head[0].toInt() and 0xFF
        val cc2 = head[2].toInt() and 0xFF
        val name = when (version?.getOrNull(6)?.toInt()?.and(0xFF)) {
            0x0F -> "NTAG213"; 0x11 -> "NTAG215"; 0x13 -> "NTAG216"; 0x0B -> "Ultralight EV1"; 0x0E -> "Ultralight EV1"
            else -> if (mu.type == MifareUltralight.TYPE_ULTRALIGHT_C) "Ultralight C" else "NFC Type 2"
        }
        if (cc0 == 0xE1 && cc2 > 0) return name to cc2 * 8
        // not NDEF-formatted: fall back to the known sizes
        val size = when (name) { "NTAG213" -> 144; "NTAG215" -> 504; "NTAG216" -> 888; else -> 48 }
        return name to size
    }

    fun read(tag: Tag): ReadResult {
        MifareUltralight.get(tag)?.let { mu ->
            mu.use {
                it.connect()
                val (name, size) = userBytes(it, tag)
                val out = ByteArray(size)
                var page = FIRST_USER_PAGE
                while ((page - FIRST_USER_PAGE) * 4 < size) {
                    val chunk = it.readPages(page) // 16 bytes
                    val off = (page - FIRST_USER_PAGE) * 4
                    System.arraycopy(chunk, 0, out, off, minOf(16, size - off))
                    page += 4
                }
                return ReadResult(TagInfo(name, size), out)
            }
        }
        Ndef.get(tag)?.let { ndef ->
            ndef.use {
                it.connect()
                val rec = it.ndefMessage?.records?.firstOrNull { r -> r.toMimeType() == MIME }
                return ReadResult(TagInfo(ndef.type ?: "NDEF", maxOf(0, it.maxSize - 26)), rec?.payload ?: ByteArray(0))
            }
        }
        throw IOException("This tag type is not supported (needs NTAG/Ultralight or NDEF).")
    }

    /**
     * Write [image] to the tag, then read it back and compare. Only pages whose content changes are
     * written, which is faster and saves tag wear. Returns the tag info on success.
     */
    fun write(tag: Tag, image: ByteArray): TagInfo {
        MifareUltralight.get(tag)?.let { mu ->
            mu.use {
                it.connect()
                val (name, size) = userBytes(it, tag)
                if (image.size > size) throw IOException("Game is ${image.size} bytes but this $name holds $size.")
                val padded = image.copyOf(size)
                val current = ByteArray(size)
                var page = FIRST_USER_PAGE
                while ((page - FIRST_USER_PAGE) * 4 < size) {
                    val chunk = it.readPages(page); val off = (page - FIRST_USER_PAGE) * 4
                    System.arraycopy(chunk, 0, current, off, minOf(16, size - off)); page += 4
                }
                val lastPage = FIRST_USER_PAGE + size / 4 - 1
                for (i in 0 until size / 4) {
                    val p = FIRST_USER_PAGE + i
                    check(p in FIRST_USER_PAGE..lastPage) // never outside the user area
                    val data = padded.copyOfRange(i * 4, i * 4 + 4)
                    if (!data.contentEquals(current.copyOfRange(i * 4, i * 4 + 4))) {
                        try { it.writePage(p, data) } catch (e: IOException) {
                            throw IOException("Write failed at page $p. Keep the tag still, or it may be locked.", e)
                        }
                    }
                }
                // verify
                var vp = FIRST_USER_PAGE
                while ((vp - FIRST_USER_PAGE) * 4 < size) {
                    val chunk = it.readPages(vp); val off = (vp - FIRST_USER_PAGE) * 4
                    for (k in 0 until minOf(16, size - off)) if (chunk[k] != padded[off + k]) throw IOException("Verify failed at byte ${off + k}. Try again.")
                    vp += 4
                }
                return TagInfo(name, size)
            }
        }
        Ndef.get(tag)?.let { ndef ->
            ndef.use {
                it.connect()
                if (!it.isWritable) throw IOException("This tag is read-only.")
                val msg = NdefMessage(NdefRecord.createMime(MIME, image))
                if (msg.byteArrayLength > it.maxSize) throw IOException("Game is too big for this tag (${it.maxSize} bytes NDEF).")
                it.writeNdefMessage(msg)
                return TagInfo(ndef.type ?: "NDEF", it.maxSize)
            }
        }
        throw IOException("This tag type is not supported (needs NTAG/Ultralight or NDEF).")
    }
}
