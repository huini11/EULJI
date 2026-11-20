package com.hanscreen.translator.translate

import com.hanscreen.translator.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * 有道智云文本翻译 v3。
 */
class YoudaoEngine(
    private val appKey: String,
    private val appSecret: String
) : TranslationEngine {

    override val id = "youdao"
    override val label = "有道智云"

    override fun isConfigured(): Boolean = appKey.isNotBlank() && appSecret.isNotBlank()

    override suspend fun translate(texts: List<String>, target: String): List<String> =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) throw IOException("请先填写有道的应用 ID 和密钥")
            if (texts.isEmpty()) return@withContext emptyList()

            val to = if (target.startsWith("zh")) "zh-CHS" else "en"
            val out = ArrayList<String>(texts.size)

            for (chunk in texts.chunked(10)) {
                val q = chunk.joinToString("\n") { it.replace('\n', ' ') }
                val salt = UUID.randomUUID().toString()
                val curtime = (System.currentTimeMillis() / 1000).toString()
                val input = if (q.length <= 20) q
                else q.substring(0, 10) + q.length + q.substring(q.length - 10)
                val sign = Http.sha256(appKey + input + salt + curtime + appSecret)

                val raw = Http.postForm(
                    "https://openapi.youdao.com/api",
                    linkedMapOf(
                        "q" to q,
                        "from" to "ko",
                        "to" to to,
                        "appKey" to appKey,
                        "salt" to salt,
                        "sign" to sign,
                        "signType" to "v3",
                        "curtime" to curtime
                    )
                )

                val json = JSONObject(raw)
                val code = json.optString("errorCode", "0")
                if (code != "0") throw IOException("有道翻译错误码 $code")

                val arr = json.optJSONArray("translation")
                    ?: throw IOException("有道翻译返回格式异常")
                val lines = (0 until arr.length()).map { arr.optString(it) }

                when {
                    lines.size == chunk.size -> out.addAll(lines)
                    lines.size == 1 -> {
                        val split = lines[0].split("\n")
                        if (split.size == chunk.size) out.addAll(split)
                        else repeat(chunk.size) { out.add(lines[0]) }
                    }
                    else -> chunk.indices.forEach { i ->
                        out.add(lines.getOrElse(i) { lines.firstOrNull() ?: "" })
                    }
                }
            }
            out
        }
}
