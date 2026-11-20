package com.hanscreen.translator.ocr

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrLine(
    val text: String,
    val box: RectF
)

/**
 * 韩文 OCR。使用 bundled 模型，不依赖 Google Play 服务，
 * 国行 ColorOS 上也能离线识别。
 */
class OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(bitmap: Bitmap): List<OcrLine> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val out = ArrayList<OcrLine>(32)
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val t = line.text.trim()
                        if (t.isEmpty()) continue
                        // 韩文识别器给出的 line.boundingBox 有时只覆盖行首一段，
                        // 用 element 框取并集才能拿到整行真实宽度
                        var box: RectF? = line.boundingBox?.let { RectF(it) }
                        for (el in line.elements) {
                            val eb = el.boundingBox ?: continue
                            if (box == null) box = RectF(eb) else box.union(RectF(eb))
                        }
                        if (box != null) out.add(OcrLine(t, box))
                    }
                }
                if (cont.isActive) cont.resume(out)
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resumeWithException(e)
            }
    }

    fun close() {
        runCatching { recognizer.close() }
    }
}
