package dev.bytecart

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import bytecart.SoundEvent
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.pow

/** One-voice chiptune synth: square waves and noise, fed by the VM's beep()/sfx() events. */
class Sound {
    private val rate = 22050
    private val queue = LinkedBlockingQueue<ShortArray>()
    @Volatile private var running = true
    var enabled = true

    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private val thread = Thread {
        track.play()
        while (running) {
            val buf = try { queue.take() } catch (e: InterruptedException) { break }
            if (buf.isNotEmpty()) track.write(buf, 0, buf.size)
        }
    }.apply { isDaemon = true; start() }

    private fun freq(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** Each step: midi note (or -1 for noise), length in 1/30 s frames. */
    private fun render(steps: List<Pair<Int, Int>>, volume: Double = 0.18): ShortArray {
        val total = steps.sumOf { it.second } * rate / 30
        val out = ShortArray(total)
        var i = 0
        var noise = 0x1234
        for ((note, frames) in steps) {
            val n = frames * rate / 30
            val period = if (note >= 0) rate / freq(note) else 0.0
            for (k in 0 until n) {
                val env = 1.0 - k.toDouble() / n * 0.7
                val v = if (note < 0) {
                    noise = noise xor (noise shl 7); noise = noise xor (noise ushr 9); noise = noise xor (noise shl 8)
                    if (noise and 1 == 0) 1.0 else -1.0
                } else if ((k % period) < period / 2) 1.0 else -1.0
                out[i++] = (v * env * volume * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    private val sfx: Array<ShortArray> = arrayOf(
        render(listOf(84 to 2, 91 to 4)),                       // 0 coin
        render(listOf(-1 to 3)),                                 // 1 hit
        render(listOf(60 to 1, 64 to 1, 67 to 1, 72 to 2)),      // 2 jump
        render(listOf(-1 to 4, -1 to 6), 0.22),                  // 3 explode
        render(listOf(60 to 2, 64 to 2, 67 to 2, 72 to 4)),      // 4 power-up
        render(listOf(67 to 4, 63 to 4, 60 to 4, 55 to 8)),      // 5 lose
        render(listOf(60 to 3, 64 to 3, 67 to 3, 72 to 3, 76 to 8)), // 6 win
        render(listOf(88 to 1), 0.12),                           // 7 blip
    )

    fun play(events: List<SoundEvent>) {
        if (!enabled) return
        // one voice: the newest sound wins, queued audio is dropped so sounds stay in sync
        val e = events.lastOrNull() ?: return
        queue.clear()
        queue.offer(if (e.kind == 1) sfx[e.a and 7] else render(listOf(48 + e.a to e.b)))
    }

    fun release() {
        running = false; thread.interrupt()
        try { track.stop() } catch (_: Exception) {}
        track.release()
    }
}
