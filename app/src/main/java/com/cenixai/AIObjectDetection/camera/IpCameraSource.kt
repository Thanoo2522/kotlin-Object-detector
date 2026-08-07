package com.cenixai.AIObjectDetection.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * ✅ พอร์ตจาก mjpeg_frame_generator() ฝั่ง Python/Desktop มาใช้ OkHttp แทน
 * java.net.http.HttpClient (OkHttp เป็นไลบรารีมาตรฐานฝั่ง Android มากกว่า)
 * ตรรกะการหา JPEG marker (SOI = FF D8 / EOI = FF D9) เหมือนเดิมทุกประการ
 */
class IpCameraSource(url: String) : AutoCloseable {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // stream ไม่มีวันจบ ปิด read timeout ไว้
        .build()

    private val inputStream: InputStream

    // เก็บ byte ที่ยังหา marker ไม่ครบ (เทียบเท่า byte_buffer ฝั่ง Python)
    private var pending: ByteArray = ByteArray(0)
    @Volatile private var closed = false

    init {
        val fullUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "http://$url"
        val request = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept-Encoding", "identity")
            .header("Connection", "keep-alive")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("❌ เชื่อมต่อกล้อง Wi-Fi ไม่สำเร็จ: HTTP ${response.code}")
        }
        inputStream = response.body?.byteStream()
            ?: throw RuntimeException("❌ เชื่อมต่อกล้อง Wi-Fi ไม่สำเร็จ: response body ว่างเปล่า")
    }

    /** คืน Bitmap เฟรมถัดไป หรือ null ถ้าอ่านไม่ได้/จบสตรีม */
    fun nextFrame(): Bitmap? {
        val chunk = ByteArray(4096)
        while (!closed) {
            tryExtractJpeg()?.let { return it }

            val n = try {
                inputStream.read(chunk)
            } catch (e: Exception) {
                return null
            }
            if (n <= 0) return null
            pending += chunk.copyOfRange(0, n)
        }
        return null
    }

    private fun tryExtractJpeg(): Bitmap? {
        val start = indexOf(pending, byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        val end = indexOf(pending, byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        if (start == -1 || end == -1 || end <= start) return null

        val jpgBytes = pending.copyOfRange(start, end + 2)
        // เอา byte ที่ใช้ไปแล้วออกจาก buffer (เทียบเท่า byte_buffer = byte_buffer[end+2:])
        pending = pending.copyOfRange(end + 2, pending.size)

        return BitmapFactory.decodeByteArray(jpgBytes, 0, jpgBytes.size)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    override fun close() {
        closed = true
        runCatching { inputStream.close() }
    }
}
