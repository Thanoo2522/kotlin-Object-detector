package com.cenixai.AIObjectDetection.model

import android.content.Context
import org.json.JSONArray
import java.io.IOException

/**
 * ✅ พอร์ตจาก ModelManager (Desktop) มาอ่านจาก Android AssetManager แทน File I/O ตรงๆ
 * โครงสร้างเหมือนเดิม: assets/models/<ชื่อโมเดล>/best.onnx + classes.json
 *
 * ใช้ org.json (มากับ Android SDK อยู่แล้ว) แทน kotlinx.serialization
 * เพื่อไม่ต้องเพิ่ม Gradle plugin ใหม่ให้โปรเจกต์
 */
object ModelManager {
    private const val MODELS_DIR = "models"

    fun listAvailableModels(context: Context): List<String> {
        return try {
            context.assets.list(MODELS_DIR)?.toList()?.sorted() ?: emptyList()
        } catch (e: IOException) {
            emptyList()
        }
    }

    fun loadModelBytes(context: Context, modelName: String): ByteArray {
        context.assets.open("$MODELS_DIR/$modelName/best.onnx").use { return it.readBytes() }
    }

    fun loadClassNames(context: Context, modelName: String): List<String>? {
        return try {
            context.assets.open("$MODELS_DIR/$modelName/classes.json").use { input ->
                val text = input.readBytes().decodeToString()
                val arr = JSONArray(text)
                List(arr.length()) { i -> arr.getString(i) }
            }
        } catch (e: Exception) {
            null
        }
    }
}
