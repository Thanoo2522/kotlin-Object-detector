package com.cenixai.AIObjectDetection.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.cenixai.AIObjectDetection.detector.Detection
import kotlin.math.min

/**
 * วาดกรอบ + label ทับบน CameraX preview โดยแปลงพิกัดจากขนาดภาพต้นฉบับที่ใช้ตอน
 * inference (imageWidth x imageHeight) ไปเป็นตำแหน่งจริงบนจอ (canvas size ของ
 * Composable นี้) เทียบเท่า detect_and_draw()/draw_labels_pil() ฝั่ง Desktop เดิม
 *
 * ✅ คำนวณ offset/scale แบบเดียวกับ PreviewView.ScaleType.FIT_CENTER: ภาพจะถูกย่อ
 * ให้พอดีกรอบโดยรักษาสัดส่วน แล้ววางกึ่งกลาง (มีขอบว่างด้านใดด้านหนึ่งถ้าสัดส่วน
 * กรอบกับภาพไม่ตรงกันเป๊ะ) — ถ้าไม่หักขอบว่างนี้ออก กล่องจะเลื่อนเพี้ยนจากวัตถุจริง
 */
@Composable
fun DetectionOverlay(
    detections: List<Detection>,
    classNames: List<String>?,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (imageWidth == 0 || imageHeight == 0) return@Canvas

        // ✅ scale เดียวใช้ทั้ง 2 แกน (min ของทั้งคู่) เพื่อรักษาสัดส่วนภาพ
        // ตรงกับพฤติกรรมของ FIT_CENTER ที่ PreviewView ใช้แสดงกล้อง
        val scale = min(size.width / imageWidth, size.height / imageHeight)
        val displayedWidth = imageWidth * scale
        val displayedHeight = imageHeight * scale

        // ✅ ขอบว่างที่เกิดจากการย่อภาพให้พอดีกรอบแบบรักษาสัดส่วน (letterbox บนจอ)
        // ต้องบวกเข้าไปในทุกพิกัด ไม่งั้นกล่องจะเลื่อนเพี้ยนจากวัตถุจริง
        val offsetX = (size.width - displayedWidth) / 2f
        val offsetY = (size.height - displayedHeight) / 2f

        detections.forEach { d ->
            val left = offsetX + d.x1 * scale
            val top = offsetY + d.y1 * scale
            val right = offsetX + d.x2 * scale
            val bottom = offsetY + d.y2 * scale

            drawRect(
                color = Color.Green,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 4f)
            )

            val label = (classNames?.getOrNull(d.classId) ?: "class_${d.classId}") +
                    " ${"%.2f".format(d.score)}"

            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GREEN
                    textSize = 32f
                    isFakeBoldText = true
                }
                val bgPaint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
                val textWidth = paint.measureText(label)
                drawRect(left, (top - 36f).coerceAtLeast(0f), left + textWidth + 8f, top, bgPaint)
                drawText(label, left + 4f, (top - 8f).coerceAtLeast(24f), paint)
            }
        }
    }
}