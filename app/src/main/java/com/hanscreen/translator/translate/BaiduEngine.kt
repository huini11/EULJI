package com.hanscreen.translator.translate

import com.hanscreen.translator.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * 百度翻译开放平台（标准版）。免费额度足够个人使用。
 * 多行用 \n 合并成一次请求，返回按行对应，避免 QPS=1 的限制。
 */
class BaiduEngine(
    private val appId: String,
    private val key: String
) : TranslationEngine {

    override val id = "baidu"
    override val label = "百度翻译"

    override fun isConfigured(): Boolean = appId.isNotBlank() && key.isNotBlank()

    override suspend fun translate(texts: List<String>, target: String): List<String> =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) throw IOException("请先填写百度翻译的 AppID 和密钥")
            if (texts.isEmpty()) return@withContext emptyList()

            val to = if (target.startsWith("zh")) "zh" else target
            val result = ArrayList<String>(texts.size)

            for (chunk in texts.chunked(12)) {
                val q = chunk.joinToString("\n") { it.replace('\n', ' ') }
                val salt = System.currentTimeMillis().toString()
                val sign = Http.md5(appId + q + salt + key)

                val raw = Http.postForm(
                    "https://fanyi-api.baidu.com/api/trans/vip/translate",
                    linkedMapOf(
                        "q" to q,
                        "from" to "kor",
                        "to" to to,
                        "appid" to appId,
                        "salt" to salt,
                        "sign" to sign
                    )
                )

                val json = JSONObject(raw)
                if (json.has("error_code")) {
                    val code = json.optString("error_code")
                    throw IOException("百度翻译错误 $code：${json.optString("error_msg")}${hint(code)}")
                }

                val arr = json.optJSONArray("trans_result")
                    ?: throw IOException("百度翻译返回格式异常")

                val lines = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    lines.add(arr.getJSONObject(i).optString("dst"))
                }

                when {
                    lines.size == chunk.size -> result.addAll(lines)
                    lines.size == 1 -> result.addAll(List(chunk.size) { lines[0] })
                    else -> chunk.indices.forEach { i ->
                        result.add(lines.getOrElse(i) { lines.lastOrNull() ?: "" })
                    }
                }
            }
            result
        }

    private fun hint(code: String): String = when (code) {
        "52003", "54001" -> "（AppID 或密钥不正确）"
        "54003" -> "（调用频率超限，稍后重试）"
        "54004" -> "（账户余额不足）"
        "58001" -> "（语言方向不支持）"
        "54005" -> "（长文本请求过于频繁）"
        else -> ""
    }
}
