package com.hanscreen.translator.translate

import com.hanscreen.translator.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Google 网页翻译免费接口。无需密钥，但需要网络可达。
 * 逐段请求（并发 4），因为该接口按句子切分、不保证行对齐。
 */
class GoogleEngine : TranslationEngine {

    override val id = "google"
    override val label = "Google 翻译"

    override fun isConfigured(): Boolean = true

    override suspend fun translate(texts: List<String>, target: String): List<String> =
        coroutineScope {
            if (texts.isEmpty()) return@coroutineScope emptyList()
            val sem = Semaphore(4)
            try {
                texts.map { t ->
                    async(Dispatchers.IO) {
                        sem.withPermit { translateOne(t, target) }
                    }
                }.awaitAll()
            } catch (e: Exception) {
                android.util.Log.w("GoogleEngine", "translate failed: ${e.message}")
                throw java.io.IOException("Google 翻译不可用：${e.message}")
            }
        }

    private fun translateOne(text: String, target: String): String {
        val tl = if (target.startsWith("zh")) "zh-CN" else target
        val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=ko&tl=$tl&dt=t&q=" + URLEncoder.encode(text, "UTF-8")
        val raw = Http.get(url)
        val root = JSONArray(raw)
        val segs = root.optJSONArray(0) ?: return text
        val sb = StringBuilder()
        for (i in 0 until segs.length()) {
            val seg = segs.optJSONArray(i) ?: continue
            sb.append(seg.optString(0))
        }
        return sb.toString().trim().ifEmpty { text }
    }
}
