package com.cenixai.AIObjectDetection

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cenixai.AIObjectDetection.detector.Detection
import com.cenixai.AIObjectDetection.detector.Detector
import com.cenixai.AIObjectDetection.detector.OnnxDetector
import com.cenixai.AIObjectDetection.model.ModelManager
import com.cenixai.AIObjectDetection.ui.DetectionOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * ✅ พอร์ตจาก Gradio Blocks UI + run_dashboard() ของ dashboard.py (ผ่านเวอร์ชัน
 * Desktop/Kotlin มาแล้วก่อนหน้านี้) มาเป็นแอป Android จริง ใช้ CameraX แทนกล้อง PC
 * และ Jetpack Compose แทน Gradio/Compose Desktop
 */
class MainActivity : ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (hasCameraPermission) {
                        DetectionScreen(modifier = Modifier.padding(innerPadding))
                    } else {
                        PermissionRequestScreen(modifier = Modifier.padding(innerPadding)) {
                            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionRequestScreen(modifier: Modifier = Modifier, onRequestPermission: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("แอปนี้ต้องใช้สิทธิ์เข้าถึงกล้องเพื่อตรวจจับวัตถุ")
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRequestPermission) { Text("อนุญาตใช้กล้อง") }
    }
}

/**
 * ✅ Layout แนวตั้ง (Column): กล่องกล้อง+overlay อยู่บน (weight(1f))
 * แผงควบคุมอยู่ล่าง (wrapContent + verticalScroll กันล้นจอ)
 */
@Composable
fun DetectionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var models by remember { mutableStateOf(ModelManager.listAvailableModels(context)) }
    var selectedModel by remember { mutableStateOf(models.firstOrNull()) }
    var confThreshold by remember { mutableFloatStateOf(0.5f) }
    var iouThreshold by remember { mutableFloatStateOf(0.45f) }
    // ✅ ขนาดภาพที่ใช้ตอนเทรนโมเดล (imgsz) — ต้องตรงกับตอน export เป๊ะๆ
    var inputSizeText by remember { mutableStateOf("640") }

    var detector by remember { mutableStateOf<Detector?>(null) }
    var classNames by remember { mutableStateOf<List<String>?>(null) }
    var detections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var imgSize by remember { mutableStateOf(0 to 0) }
    var statusText by remember { mutableStateOf("เลือกโมเดลแล้วกด Start เพื่อเริ่มตรวจจับ") }
    var isRunning by remember { mutableStateOf(false) }

    // โหลด/ปลดโมเดลตามที่เลือก — ปิดของเก่าเสมอก่อนเปิดใหม่ กัน memory leak
    LaunchedEffect(isRunning, selectedModel) {
        if (isRunning && selectedModel != null) {
            withContext(Dispatchers.IO) {
                try {
                    val modelName = selectedModel!!
                    val bytes = ModelManager.loadModelBytes(context, modelName)
                    val names = ModelManager.loadClassNames(context, modelName)
                    val imgSizeValue = inputSizeText.toIntOrNull() ?: 640
                    detector?.close()
                    detector = OnnxDetector(bytes, inputSize = imgSizeValue, numClassesHint = names?.size)
                    classNames = names
                    statusText = "🟢 กำลังตรวจจับด้วยโมเดล '$modelName' (imgsz=$imgSizeValue)"
                } catch (e: Exception) {
                    statusText = "❌ โหลดโมเดลไม่สำเร็จ: ${e.message}"
                    isRunning = false
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { detector?.close() }
    }

    Column(modifier = modifier.fillMaxSize()) {

        // ----------------- กล่องกล้องด้านบน -----------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (isRunning) {
                CameraPreviewWithAnalysis(
                    lifecycleOwner = lifecycleOwner,
                    onFrame = { bitmap ->
                        // ✅ inference เกิดบน background thread ของ CameraX analyzer อยู่แล้ว
                        // การเขียน Compose State จากเธรดนี้ตรงๆ ปลอดภัย เพราะ Compose ใช้
                        // Snapshot system ที่ thread-safe ในตัว ไม่ต้อง post กลับ main thread เอง
                        val d = detector
                        if (d != null) {
                            detections = d.infer(bitmap, confThreshold, iouThreshold)
                            imgSize = bitmap.width to bitmap.height
                        }
                    }
                )
                DetectionOverlay(
                    detections = detections,
                    classNames = classNames,
                    imageWidth = imgSize.first,
                    imageHeight = imgSize.second,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("กด Start เพื่อเริ่มกล้อง")
                }
            }
        }

        // ----------------- แผงควบคุมด้านล่าง -----------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text("🎯 CenixAI Object Detection", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                var expanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedModel ?: "-- เลือกโมเดล --")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        models.forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = {
                                selectedModel = m; expanded = false
                            })
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { models = ModelManager.listAvailableModels(context) }) {
                    Text("🔄")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Confidence: ${"%.2f".format(confThreshold)}")
            Slider(value = confThreshold, onValueChange = { confThreshold = it }, valueRange = 0f..1f)

            Text("IoU (NMS): ${"%.2f".format(iouThreshold)}")
            Slider(value = iouThreshold, onValueChange = { iouThreshold = it }, valueRange = 0.1f..0.9f)

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = inputSizeText,
                onValueChange = { inputSizeText = it },
                label = { Text("ONNX Input Size (imgsz)") },
                enabled = !isRunning, // ล็อกตอนกำลังตรวจจับ กันค่าไม่ sync กับโมเดลที่โหลดไว้แล้ว
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    enabled = !isRunning && selectedModel != null,
                    onClick = { isRunning = true },
                    modifier = Modifier.weight(1f)
                ) { Text("▶️ Start") }

                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = isRunning,
                    onClick = {
                        isRunning = false
                        detector?.close(); detector = null
                        detections = emptyList()
                        statusText = "⏹️ หยุดแล้ว"
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("⏹️ Stop") }
            }

            Spacer(Modifier.height(8.dp))
            Text(statusText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * ✅ CameraX Preview + ImageAnalysis
 *
 * รวม 3 fix สำคัญที่ทำให้กล่องตรวจจับตรงกับตำแหน่งวัตถุจริงบนจอ:
 *
 * 1) หมุนภาพตาม imageProxy.imageInfo.rotationDegrees ก่อน inference —
 *    ImageProxy.toBitmap() คืนภาพตามแนวเซนเซอร์ (มักเป็นแนวนอน) ไม่ใช่แนวจอ
 *
 * 2) implementationMode = COMPATIBLE + scaleType = FIT_CENTER —
 *    กัน SurfaceView (z-order พิเศษ) ทับ DetectionOverlay และกันภาพยืด/บิด
 *
 * 3) UseCaseGroup + ViewPort — บังคับให้ Preview กับ ImageAnalysis ใช้ crop rect
 *    เดียวกันจากเซนเซอร์ (WYSIWYG) กันปัญหา CameraX เลือก FOV ให้แต่ละ use case
 *    ไม่ตรงกันเป๊ะ แม้ aspect ratio จะตั้งไว้เหมือนกันแล้วก็ตาม — เป็นสาเหตุที่พบ
 *    บ่อยที่สุดของอาการ "label ถูกแต่กล่องเพี้ยน"
 *
 * STRATEGY_KEEP_ONLY_LATEST เทียบเท่ากับ queue.Queue(maxsize=2) ของ Python เดิม —
 * กันเฟรมสะสมค้างถ้า inference ช้ากว่าที่กล้องส่งเฟรมมา
 */
@Composable
fun CameraPreviewWithAnalysis(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onFrame: (Bitmap) -> Unit
) {
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3) // ✅ ตรงกับเซนเซอร์ OV2640 ที่ใช้เทรน
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val analysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysisUseCase ->
                        analysisUseCase.setAnalyzer(analysisExecutor) { imageProxy ->
                            try {
                                // ✅ Fix 1: หมุนภาพให้ตรงแนวจอก่อนส่งเข้า inference
                                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                var bitmap = imageProxy.toBitmap()
                                if (rotationDegrees != 0) {
                                    val matrix = android.graphics.Matrix().apply {
                                        postRotate(rotationDegrees.toFloat())
                                    }
                                    bitmap = Bitmap.createBitmap(
                                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                                    )
                                }
                                onFrame(bitmap)
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }

                // ✅ Fix 3: รอ previewView layout เสร็จก่อน (viewPort เป็น null จนกว่าจะ
                // measure/layout เรียบร้อย) ใช้ post {} เพื่อรอรอบ layout ถัดไป
                previewView.post {
                    val viewPort = previewView.viewPort ?: return@post

                    val useCaseGroup = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(analysis)
                        .setViewPort(viewPort)
                        .build()

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}