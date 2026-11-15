package dev.bytecart

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.nfc.tech.Ndef
import android.util.Log
import java.io.IOException

/**
 * Raw cartridge I/O on NFC tags.
 *
 * NTAG21x / Ultralight tags (NFC Forum Type 2, e.g. your NTAG213) are used without NDEF: the cart
 * image goes straight into the user pages (4 .. 4 + size/4 - 1), which gives all 144 bytes to the game.
 * Pages 0-3 (UID, lock bytes, capability container) and everything after the user area
 * (dynamic lock, config, password) are never written, and the tag is never made read-only.
 *
 * We talk to the tag with raw Type 2 commands over [NfcA] (READ 0x30, WRITE 0xA2, GET_VERSION 0x60)
 * instead of android.nfc.tech.MifareUltralight, because some phones (Samsung Galaxy S24 tested)
 * report these tags only as NfcA + Ndef and never offer the MifareUltralight class.
 *
 * Other NDEF tags fall back to one MIME record "application/x-bytecart".
 */
object TagIo {
    const val MIME = "application/x-bytecart"
    private const val FIRST_USER_PAGE = 4
    private const val TAG = "ByteCart"

    class TagInfo(val kind: String, val capacity: Int)
    class ReadResult(val info: TagInfo, val image: ByteArray)

    /** Type 2 tags answer ISO 14443-3A with SAK 0x00 (bank cards and MIFARE Classic do not). */
    private fun type2(tag: Tag): NfcA? = NfcA.get(tag)?.takeIf { (it.sak.toInt() and 0xFF) == 0x00 }

    /** READ returns 4 pages (16 bytes) starting at [page]. Retries once after a reconnect. */
    private fun readPages(a: NfcA, page: Int): ByteArray {
        for (attempt in 0..1) {
            try {
                val r = a.transceive(byteArrayOf(0x30, page.toByte()))
                if (r.size >= 16) return r
                Log.w(TAG, "READ $page returned ${r.size} bytes")
            } catch (e: IOException) {
                Log.w(TAG, "READ $page attempt $attempt: $e")
                if (attempt == 1) throw e
            }
            reconnect(a)
        }
        throw IOException("Tag gave a short answer to READ $page.")
    }

    private fun reconnect(a: NfcA) {
        try { a.close() } catch (_: IOException) {}
        a.connect()
    }

    private fun writePage(a: NfcA, page: Int, data: ByteArray) {
        require(data.size == 4)
        val r = a.transceive(byteArrayOf(0xA2.toByte(), page.toByte(), data[0], data[1], data[2], data[3]))
        // ACK is the 4-bit value 0xA. Some stacks return it, others return nothing; the verify pass catches the rest.
        if (r.isNotEmpty() && (r[0].toInt() and 0x0F) != 0x0A) throw IOException("Tag refused page $page (NAK ${r[0]}).")
    }

    /**
     * Chip name and size of the user area in bytes. The capability container (page 3) gives the size
     * on any NDEF-formatted tag; GET_VERSION is only sent when it doesn't, because a NAK halts the tag
     * and some phones (S24) handle the reconnect badly.
     */
    private fun userBytes(a: NfcA): Pair<String, Int> {
        val head = readPages(a, 3) // pages 3..6, page 3 = CC
        val cc0 = head[0].toInt() and 0xFF
        val cc2 = head[2].toInt() and 0xFF
        Log.i(TAG, "cc=${head.copyOf(4).hex()}")
        if (cc0 == 0xE1 && cc2 > 0) {
            val size = cc2 * 8
            return when (size) { 144 -> "NTAG213"; 496, 504 -> "NTAG215"; 872, 888 -> "NTAG216"; else -> "NFC Type 2 tag" } to size
        }
        val version = try { a.transceive(byteArrayOf(0x60)) } catch (e: IOException) {
            Log.w(TAG, "GET_VERSION: $e"); reconnect(a); null
        }
        val name = when (version?.takeIf { it.size >= 8 }?.get(6)?.toInt()?.and(0xFF)) {
            0x0F -> "NTAG213"; 0x11 -> "NTAG215"; 0x13 -> "NTAG216"; 0x0B, 0x0E -> "Ultralight EV1"
            else -> "NFC Type 2 tag"
        }
        Log.i(TAG, "unformatted tag $name version=${version?.hex()}")
        val size = when (name) { "NTAG213" -> 144; "NTAG215" -> 504; "NTAG216" -> 888; else -> 48 }
        return name to size
    }

    private fun readUser(a: NfcA, size: Int): ByteArray {
        val out = ByteArray(size)
        var off = 0
        while (off < size) {
            val chunk = readPages(a, FIRST_USER_PAGE + off / 4)
            System.arraycopy(chunk, 0, out, off, minOf(16, size - off))
            off += 16
        }
        return out
    }

    fun read(tag: Tag): ReadResult {
        type2(tag)?.let { a ->
            a.use {
                it.connect()
                it.timeout = 1000
                val (name, size) = userBytes(it)
                return ReadResult(TagInfo(name, size), readUser(it, size))
            }
        }
        Ndef.get(tag)?.let { ndef ->
            ndef.use {
                it.connect()
                val rec = it.ndefMessage?.records?.firstOrNull { r -> r.toMimeType() == MIME }
                return ReadResult(TagInfo(ndef.type ?: "NDEF", maxOf(0, it.maxSize - 26)), rec?.payload ?: ByteArray(0))
            }
        }
        throw IOException("This tag type is not supported (needs an NTAG/Ultralight or NDEF tag). Techs: ${tag.techList.joinToString { t -> t.substringAfterLast('.') }}")
    }

    /**
     * Write [image] to the tag, then read it back and compare. Only pages whose content changes are
     * written, which is faster and saves tag wear. Returns the tag info on success.
     */
    fun write(tag: Tag, image: ByteArray): TagInfo {
        type2(tag)?.let { a ->
            a.use {
                it.connect()
                it.timeout = 1000
                val (name, size) = userBytes(it)
                if (image.size > size) throw IOException("Game is ${image.size} bytes but this $name holds $size.")
                val padded = image.copyOf(size)
                val current = readUser(it, size)
                val lastPage = FIRST_USER_PAGE + size / 4 - 1
                var written = 0
                for (i in 0 until size / 4) {
                    val p = FIRST_USER_PAGE + i
                    check(p in FIRST_USER_PAGE..lastPage) // never outside the user area
                    val data = padded.copyOfRange(i * 4, i * 4 + 4)
                    if (!data.contentEquals(current.copyOfRange(i * 4, i * 4 + 4))) {
                        try { writePage(it, p, data); written++ } catch (e: IOException) {
                            throw IOException("Write failed at page $p. Keep the tag still, or it may be locked.", e)
                        }
                    }
                }
                val check = readUser(it, size)
                for (k in 0 until size) if (check[k] != padded[k]) throw IOException("Verify failed at byte $k. Try again.")
                Log.i(TAG, "wrote ${image.size} B ($written pages changed) to $name, verified")
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
        throw IOException("This tag type is not supported (needs an NTAG/Ultralight or NDEF tag). Techs: ${tag.techList.joinToString { t -> t.substringAfterLast('.') }}")
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
