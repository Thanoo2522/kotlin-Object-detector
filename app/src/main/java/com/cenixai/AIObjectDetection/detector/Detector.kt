package com.cenixai.AIObjectDetection.detector

import android.graphics.Bitmap

/**
 * Interface กลางสำหรับ detector ทุกแบบ (ONNX ตอนนี้ / อาจเพิ่ม NCNN ในอนาคตถ้าต้องการ)
 * ออกแบบไว้ให้ swap implementation ได้โดยไม่กระทบส่วนอื่นของแอป (UI, camera source)
 */
interface Detector : AutoCloseable {
    fun infer(bitmap: Bitmap, confThreshold: Float, iouThreshold: Float): List<Detection>
}
