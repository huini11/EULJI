package com.hanscreen.translator.translate

import com.hanscreen.translator.App

/**
 * 当前引擎单例。密钥改动后自动重建。
 */
object EngineCache {

    private var engine: TranslationEngine? = null
    private var signature: String = ""

    @Synchronized
    fun current(): TranslationEngine {
        val p = App.prefs
        val sig = listOf(
            p.provider, p.baiduAppId, p.baiduKey, p.youdaoApp, p.youdaoSec
        ).joinToString("|")
        val e = engine
        if (e != null && sig == signature) return e

        close()
        val fresh = Engines.create(p.provider, p)
        engine = fresh
        signature = sig
        return fresh
    }

    @Synchronized
    fun close() {
        runCatching { engine?.close() }
        engine = null
        signature = ""
    }
}
