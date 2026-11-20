package com.hanscreen.translator.translate

import com.hanscreen.translator.App
import com.hanscreen.translator.Prefs

/**
 * 翻译引擎抽象。实现类负责把多段文本合并成尽量少的请求。
 */
interface TranslationEngine {
    val id: String
    val label: String
    fun isConfigured(): Boolean
    suspend fun translate(texts: List<String>, target: String): List<String>
    fun close() {}
}

object Engines {

    const val BAIDU = "baidu"
    const val YOUDAO = "youdao"
    const val GOOGLE = "google"
    const val MLKIT = "mlkit"

    val ids = listOf(BAIDU, YOUDAO, GOOGLE, MLKIT)
    val labels = listOf(
        "百度翻译（推荐 · 免费额度）",
        "有道智云（需应用 ID/密钥）",
        "Google 翻译（免费接口 · 需可访问）",
        "离线模型 ML Kit（需 GMS）"
    )

    fun create(id: String, prefs: Prefs): TranslationEngine = when (id) {
        YOUDAO -> YoudaoEngine(prefs.youdaoApp, prefs.youdaoSec)
        GOOGLE -> GoogleEngine()
        MLKIT -> MlKitEngine()
        else -> BaiduEngine(prefs.baiduAppId, prefs.baiduKey)
    }

    fun createCurrent(): TranslationEngine = create(App.prefs.provider, App.prefs)
}
