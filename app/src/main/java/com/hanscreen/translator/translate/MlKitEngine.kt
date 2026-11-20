package com.hanscreen.translator.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { if (cont.isActive) cont.cancel() }
}

/**
 * ML Kit 端侧翻译。完全离线，但首次需要下载语言模型（依赖 Google Play 服务）。
 */
class MlKitEngine : TranslationEngine {

    override val id = "mlkit"
    override val label = "离线模型"

    override fun isConfigured(): Boolean = true

    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.KOREAN)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build()
        )
    }

    override suspend fun translate(texts: List<String>, target: String): List<String> =
        withContext(Dispatchers.Default) {
            if (texts.isEmpty()) return@withContext emptyList()
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()
            texts.map { t -> runCatching { translator.translate(t).awaitTask() }.getOrDefault(t) }
        }

    override fun close() {
        runCatching { translator.close() }
    }
}
