package com.cenixai.AIObjectDetection.detector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.nio.FloatBuffer

/**
 * ✅ พอร์ตจาก OnnxYoloDetector (Desktop/Kotlin) มาเป็นเวอร์ชัน Android
 * เปลี่ยน preprocessing จาก OpenCV Mat -> android.graphics.Bitmap/Canvas
 * (ไม่พก OpenCV Android เต็มตัวเพราะหนักเกินไปสำหรับมือถือ)
 *
 * คง 2 หลักการเดิมไว้ครบ:
 *   1) Letterbox: resize รักษาสัดส่วน + เติมขอบเทา (114,114,114) แบบเดียวกับตอนเทรน YOLO
 *   2) numClassesHint จาก classes.json แทนการเดาจาก (C - 4) กัน mask coefficients
 *      ของโมเดล -seg ปนเป็น class ปลอม
 * ส่วน NMS ย้ายไปเขียนเป็น pure Kotlin แยกไว้ที่ Nms.kt แทน cv2.dnn.NMSBoxes
 *
 * ✅ เพิ่มการเร่งความเร็ว inference พร้อม fallback 2 ชั้น:
 *   ชั้น 1: ลองสร้าง session ด้วย NNAPI (ใช้ GPU/NPU/DSP ของเครื่อง — เร็วที่สุด)
 *   ชั้น 2: ถ้า NNAPI ใช้กับโมเดลนี้ไม่ได้ (บาง op ไม่รองรับ) ถอยไปใช้ CPU
 *          + multi-thread โดยอัตโนมัติ แทนที่จะปล่อยให้แอป error
 */
class OnnxDetector(
    modelBytes: ByteArray,
    private val inputSize: Int = 640,
    private val numClassesHint: Int? = null
) : Detector {

    companion object {
        private const val TAG = "OnnxDetector"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = createSessionWithFallback(modelBytes)
    private val inputName: String = session.inputNames.iterator().next()

    /**
     * สร้าง session แบบมี fallback: ลอง NNAPI ก่อน ถ้า createSession() ล้มเหลว
     * (โมเดลมี op ที่ NNAPI ไม่รองรับ) ให้ถอยไปสร้างด้วย CPU EP ล้วนๆ แทนอัตโนมัติ
     */
    private fun createSessionWithFallback(bytes: ByteArray): OrtSession {
        return try {
            val optionsWithNnapi = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                addNnapi()
            }
            env.createSession(bytes, optionsWithNnapi).also {
                Log.d(TAG, "โหลดโมเดลสำเร็จด้วย NNAPI")
            }
        } catch (e: Exception) {
            Log.w(TAG, "สร้าง session ด้วย NNAPI ไม่สำเร็จ (${e.message}) — ถอยไปใช้ CPU แทน")
            val cpuOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env.createSession(bytes, cpuOptions).also {
                Log.d(TAG, "โหลดโมเดลสำเร็จด้วย CPU (fallback)")
            }
        }
    }

    private data class Letterbox(val bitmap: Bitmap, val scale: Float, val padX: Int, val padY: Int)

    /** เหมือน _letterbox() ฝั่ง Desktop แต่ใช้ Canvas วาดแทน OpenCV resize/copyMakeBorder */
    private fun letterbox(src: Bitmap): Letterbox {
        val w = src.width
        val h = src.height
        val scale = minOf(inputSize.toFloat() / w, inputSize.toFloat() / h)
        val newW = Math.round(w * scale)
        val newH = Math.round(h * scale)
        val padX = (inputSize - newW) / 2
        val padY = (inputSize - newH) / 2

        val canvasBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114)) // ขอบเทาเหมือนต้นฉบับ

        val resized = Bitmap.createScaledBitmap(src, newW, newH, true)
        canvas.drawBitmap(resized, padX.toFloat(), padY.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        if (resized !== src) resized.recycle()

        return Letterbox(canvasBitmap, scale, padX, padY)
    }

    /** Bitmap (ARGB_8888) -> CHW FloatArray normalize [0,1] เรียง R-plane, G-plane, B-plane */
    private fun bitmapToChw(bitmap: Bitmap): FloatArray {
        val size = bitmap.width // = bitmap.height = inputSize เสมอหลัง letterbox
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)

        val plane = size * size
        val chw = FloatArray(plane * 3)
        for (i in 0 until plane) {
            val p = pixels[i]
            chw[i] = ((p shr 16) and 0xFF) / 255f          // R plane
            chw[plane + i] = ((p shr 8) and 0xFF) / 255f    // G plane
            chw[2 * plane + i] = (p and 0xFF) / 255f        // B plane
        }
        return chw
    }

    /** เหมือน _parse_output() ฝั่ง Desktop: รองรับทั้ง output shape [1,C,N] และ [1,N,C] */
    private fun parseOutput(
        floats: FloatArray, shape: LongArray,
        scale: Float, padX: Int, padY: Int, confThreshold: Float
    ): List<Detection> {
        val dimA = shape[1].toInt()
        val dimB = shape[2].toInt()

        val transposed: Boolean
        val numChannelsRaw: Int
        val numBoxes: Int
        if (dimA < dimB) {
            numChannelsRaw = dimA; numBoxes = dimB; transposed = false
        } else {
            numChannelsRaw = dimB; numBoxes = dimA; transposed = true
        }

        val numClasses = (numClassesHint ?: (numChannelsRaw - 4)).coerceAtMost(numChannelsRaw - 4)

        fun value(channel: Int, box: Int): Float =
            if (!transposed) floats[channel * numBoxes + box] else floats[box * numChannelsRaw + channel]

        val results = ArrayList<Detection>()
        for (n in 0 until numBoxes) {
            var bestClass = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (c in 0 until numClasses) {
                val s = value(4 + c, n)
                if (s > bestScore) { bestScore = s; bestClass = c }
            }
            if (bestScore < confThreshold) continue

            val cx = value(0, n); val cy = value(1, n)
            val bw = value(2, n); val bh = value(3, n)

            val x1 = (cx - bw / 2 - padX) / scale
            val y1 = (cy - bh / 2 - padY) / scale
            val x2 = (cx + bw / 2 - padX) / scale
            val y2 = (cy + bh / 2 - padY) / scale

            results.add(Detection(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), bestScore, bestClass))
        }
        return results
    }

    /** รัน inference 1 เฟรม แล้วคืนกล่องที่ผ่าน NMS แล้ว (เทียบเท่า infer() ฝั่ง Desktop) */
    override fun infer(bitmap: Bitmap, confThreshold: Float, iouThreshold: Float): List<Detection> {
        val startTime = System.currentTimeMillis()

        val lb = letterbox(bitmap)
        val chw = bitmapToChw(lb.bitmap)
        lb.bitmap.recycle()

        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val outFloats: FloatArray
        val outShape: LongArray

        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), shape).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { output ->
                val outTensor = output[0] as OnnxTensor
                outShape = outTensor.info.shape
                val buffer = outTensor.floatBuffer
                outFloats = FloatArray(buffer.remaining())
                buffer.get(outFloats)
            }
        }

        val raw = parseOutput(outFloats, outShape, lb.scale, lb.padX, lb.padY, confThreshold)
        val result = if (raw.isEmpty()) emptyList() else Nms.run(raw, iouThreshold)

        // ✅ วัดเวลาจริงต่อเฟรม — ดูผลได้ที่ Logcat filter คำว่า "OnnxDetector"
        Log.d(TAG, "inference time: ${System.currentTimeMillis() - startTime} ms")

        return result
    }

    override fun close() {
        session.close()
    }
}