package dev.bytecart

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Camera QR scanner. Calls [onResult] once with the raw bytes of the first code it reads. */
@Composable
fun ScanScreen(onResult: (ByteArray) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(16.dp)) {
        TopBar("Scan a QR cartridge", onBack)
        if (!granted) {
            Text("Camera permission is needed to scan QR games.", color = Color.White, modifier = Modifier.padding(top = 24.dp))
            return@Column
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp)) {
            CameraPreview(onResult)
            Box(Modifier.align(Alignment.Center).fillMaxWidth(0.85f).aspectRatio(1f).border(3.dp, Accent, RoundedCornerShape(16.dp)))
        }
        Text("Hold the code inside the frame. Large codes (up to 177×177 modules) read best from a screen or a sharp print, filling most of the frame.",
            color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun CameraPreview(onResult: (ByteArray) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(factory = { c ->
        val view = PreviewView(c)
        val future = ProcessCameraProvider.getInstance(c)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(android.util.Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { img ->
                try {
                    if (!done.get()) {
                        val plane = img.planes[0]
                        val buf = plane.buffer
                        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        val result = Qr.decode(bytes, plane.rowStride, img.width, img.height)
                        if (result != null && done.compareAndSet(false, true)) {
                            ContextCompat.getMainExecutor(c).execute { onResult(result) }
                        }
                    }
                } finally { img.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(c))
        view
    }, modifier = Modifier.fillMaxSize())
}
