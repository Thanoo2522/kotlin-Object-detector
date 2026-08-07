package com.cenixai.AIObjectDetection.detector

/** ผลลัพธ์ 1 กล่องตรวจจับ พิกัดอ้างอิงภาพต้นฉบับ (หลังย้อน letterbox กลับแล้ว) */
data class Detection(
    val x1: Int, val y1: Int, val x2: Int, val y2: Int,
    val score: Float, val classId: Int
)
