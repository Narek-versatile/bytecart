package dev.bytecart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import bytecart.Program
import bytecart.Tiles
import bytecart.Vm
import bytecart.VmState
import kotlinx.coroutines.isActive
import kotlin.math.abs

val Bg = Color(0xFF0B0D17)
val Panel = Color(0xFF161A2B)
val Accent = Color(0xFFF7D038)
val Dim = Color(0xFF8A90A6)

/** Caches 8x8 tile bitmaps; custom tiles are keyed by their current pixels. */
private class TileCache {
    private val map = HashMap<Long, ImageBitmap>()
    fun get(tile: Int, vm: Vm): ImageBitmap? {
        val t = tile and 0x7F
        var key = t.toLong()
        if (t in Tiles.FIRST_CUSTOM until Tiles.FIRST_GLYPH) {
            val k = t - Tiles.FIRST_CUSTOM
            var h = vm.customColor[k].toLong()
            for (r in 0 until 8) h = h * 257 + vm.customRows[k * 8 + r]
            key = (h shl 8) or t.toLong()
        }
        return map.getOrPut(key) {
            val px = Tiles.bitmap(t, vm.customRows, vm.customColor) ?: return null
            Bitmap.createBitmap(px, 8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
}

private val glyphPaint = Paint().apply {
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER; isAntiAlias = true
    color = 0xFFF4F4F4.toInt()
}

private fun DrawScope.drawVm(vm: Vm, cache: TileCache, cell: Float, ox: Float, oy: Float) {
    drawRect(Color(Tiles.palette[0]), Offset(ox, oy), Size(cell * vm.w, cell * vm.h))
    // faint grid dots help reading positions
    for (y in 0 until vm.h) for (x in 0 until vm.w) {
        val t = vm.cell(x, y)
        val dx = ox + x * cell; val dy = oy + y * cell
        if (t == 0) { drawRect(Color(0xFF121626), Offset(dx + cell / 2 - 1, dy + cell / 2 - 1), Size(2f, 2f)); continue }
        drawTile(t, vm, cache, dx, dy, cell)
    }
    for (i in 0 until 16) {
        val t = vm.spriteTile[i]
        if (t != 0) drawTile(t, vm, cache, ox + vm.spriteX[i] * cell / 8f, oy + vm.spriteY[i] * cell / 8f, cell)
    }
}

private fun DrawScope.drawTile(t: Int, vm: Vm, cache: TileCache, x: Float, y: Float, cell: Float) {
    val alpha = if (Tiles.isDim(t)) 0.35f else 1f
    val ch = Tiles.glyph(t)
    if (ch != null) {
        glyphPaint.textSize = cell * 0.82f
        glyphPaint.alpha = (alpha * 255).toInt()
        drawContext.canvas.nativeCanvas.drawText(ch.toString(), x + cell / 2, y + cell * 0.80f, glyphPaint)
        return
    }
    val bmp = cache.get(t, vm) ?: return
    drawImage(bmp, IntOffset.Zero, IntSize(8, 8), IntOffset(x.toInt(), y.toInt()), IntSize(cell.toInt() + 1, cell.toInt() + 1), alpha = alpha, filterQuality = FilterQuality.None)
}

private fun hiKey(image: ByteArray) = "hi_" + image.contentHashCode().toUInt().toString(16)

@Composable
fun GameScreen(program: Program, image: ByteArray, title: String, sound: Sound, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("scores", Context.MODE_PRIVATE) }
    val vm = remember(program) { Vm(program, System.nanoTime().toInt()).also { it.hiscore = prefs.getInt(hiKey(image), 0) } }
    val cache = remember { TileCache() }
    var frameNo by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(VmState.RUNNING) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(vm) {
        var last = 0L; var acc = 0L
        val step = 1_000_000_000L / Vm.FPS
        while (isActive) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                acc += now - last; last = now
                var n = 0
                while (acc >= step && n < 4) { vm.frame(); acc -= step; n++ }
                if (acc > step * 4) acc = 0
                if (vm.sounds.isNotEmpty()) { sound.play(vm.sounds); vm.sounds.clear() }
                if (vm.state != state) {
                    state = vm.state
                    if (state != VmState.RUNNING) {
                        prefs.edit().putInt(hiKey(image), maxOf(vm.hiscore, vm.score)).apply()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
                frameNo++
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(horizontal = 12.dp)) {
        // HUD
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Dim, fontSize = 30.sp, modifier = Modifier.clip(CircleShape).pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) onExit() }
            }.padding(horizontal = 10.dp))
            Text(title.uppercase(), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            frameNo.let { }
            Column(horizontalAlignment = Alignment.End) {
                Text("SCORE ${vm.score}", color = Accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("BEST ${maxOf(vm.hiscore, vm.score)}", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        // Screen
        val density = LocalDensity.current
        val swipe = with(density) { 36.dp.toPx() }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(vm) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun cellAt(o: Offset): Pair<Int, Int> {
                            val cell = minOf(size.width.toFloat() / vm.w, size.height.toFloat() / vm.h)
                            val ox = (size.width - cell * vm.w) / 2; val oy = (size.height - cell * vm.h) / 2
                            return ((o.x - ox) / cell).toInt() to ((o.y - oy) / cell).toInt()
                        }
                        if (vm.state != VmState.RUNNING) {
                            if (waitForUpOrCancellation() != null) { vm.reset(System.nanoTime().toInt()); state = VmState.RUNNING }
                            return@awaitEachGesture
                        }
                        var anchor = down.position
                        var moved = false
                        cellAt(down.position).let { (x, y) -> vm.touch(x, y) }
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            cellAt(ch.position).let { (x, y) -> vm.touch(x, y) }
                            val d = ch.position - anchor
                            if (abs(d.x) > swipe || abs(d.y) > swipe) {
                                val dir = if (abs(d.x) > abs(d.y)) (if (d.x > 0) 0 else 2) else (if (d.y > 0) 1 else 3)
                                vm.press(dir); vm.release(dir)
                                anchor = ch.position; moved = true
                            }
                            if (ch.positionChange() != Offset.Zero) ch.consume()
                        }
                        vm.touchEnd()
                        if (!moved) cellAt(down.position).let { (x, y) -> vm.tap(x, y) }
                    }
                }
            ) {
                frameNo // redraw every frame
                val cell = minOf(size.width / vm.w, size.height / vm.h).let { kotlin.math.floor(it) }
                val ox = (size.width - cell * vm.w) / 2; val oy = (size.height - cell * vm.h) / 2
                drawVm(vm, cache, cell, ox, oy)
                vm.message?.let { banner(it, size.width / 2, size.height / 2, cell) }
                when (vm.state) {
                    VmState.OVER -> overlay("GAME OVER", "score ${vm.score} · tap to retry")
                    VmState.WON -> overlay("YOU WIN!", "score ${vm.score} · tap to play again")
                    VmState.HALTED -> overlay("THE END", "tap to restart")
                    VmState.CRASHED -> overlay("CRASHED", vm.error ?: "")
                    else -> {}
                }
            }
        }
        Controls(vm)
    }
}

private val bannerPaint = Paint().apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER; isAntiAlias = true; color = 0xFF0B0D17.toInt() }
private val bannerBg = Paint().apply { color = 0xFFF7D038.toInt(); isAntiAlias = true }

private fun DrawScope.banner(text: String, cx: Float, cy: Float, cell: Float) {
    bannerPaint.textSize = maxOf(cell * 0.9f, 40f)
    val w = bannerPaint.measureText(text) + bannerPaint.textSize
    val h = bannerPaint.textSize * 1.8f
    val c = drawContext.canvas.nativeCanvas
    c.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, 18f, 18f, bannerBg)
    c.drawText(text, cx, cy + bannerPaint.textSize * 0.35f, bannerPaint)
}

private val overlayTitle = Paint().apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER; isAntiAlias = true; color = 0xFFF7D038.toInt(); textSize = 84f }
private val overlaySub = Paint().apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER; isAntiAlias = true; color = 0xFFF4F4F4.toInt(); textSize = 40f }

private fun DrawScope.overlay(title: String, sub: String) {
    drawRect(Color(0xCC0B0D17))
    val c = drawContext.canvas.nativeCanvas
    c.drawText(title, size.width / 2, size.height / 2, overlayTitle)
    c.drawText(sub.take(48), size.width / 2, size.height / 2 + 70f, overlaySub)
}

@Composable
private fun Controls(vm: Vm) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        // D-pad
        Box(Modifier.size(150.dp)) {
            PadButton(vm, 3, "▲", Modifier.align(Alignment.TopCenter))
            PadButton(vm, 2, "◀", Modifier.align(Alignment.CenterStart))
            PadButton(vm, 0, "▶", Modifier.align(Alignment.CenterEnd))
            PadButton(vm, 1, "▼", Modifier.align(Alignment.BottomCenter))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PadButton(vm, 5, "B", Modifier.padding(top = 36.dp), round = true)
            Spacer(Modifier.width(14.dp))
            PadButton(vm, 4, "A", Modifier.padding(bottom = 36.dp), round = true)
        }
    }
}

@Composable
private fun PadButton(vm: Vm, key: Int, label: String, modifier: Modifier, round: Boolean = false) {
    var down by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.size(if (round) 64.dp else 50.dp).clip(if (round) CircleShape else RoundedCornerShape(12.dp))
            .background(if (down) Accent else Panel)
            .pointerInput(vm) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    down = true; vm.press(key); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    waitForUpOrCancellation()
                    down = false; vm.release(key)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (down) Bg else Color.White, fontSize = if (round) 22.sp else 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}
