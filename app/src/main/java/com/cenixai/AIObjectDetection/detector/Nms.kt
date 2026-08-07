package com.cenixai.AIObjectDetection.detector

import kotlin.math.max
import kotlin.math.min

/**
 * ✅ Non-Maximum Suppression แบบ pure Kotlin (ไม่พึ่ง OpenCV เพราะฝั่ง Android
 * ไม่พก OpenCV เต็มตัว — หนักเกินไปสำหรับมือถือ ~100MB+)
 *
 * เทียบเท่า cv2.dnn.NMSBoxes ฝั่ง Desktop เดิม แบบ class-agnostic (ตัดกล่องซ้อนทับ
 * ข้ามคลาสด้วย เพราะเจอปัญหานี้มาก่อนกับโมเดล helmet/no-helmet ที่ขึ้นทับตำแหน่งเดียวกัน)
 */
object Nms {
    fun run(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val kept = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { iou(best, it) > iouThreshold }
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val interX1 = max(a.x1, b.x1)
        val interY1 = max(a.y1, b.y1)
        val interX2 = min(a.x2, b.x2)
        val interY2 = min(a.y2, b.y2)

        val interArea = max(0, interX2 - interX1) * max(0, interY2 - interY1)
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val unionArea = areaA + areaB - interArea

        return if (unionArea <= 0) 0f else interArea.toFloat() / unionArea.toFloat()
    }
}
