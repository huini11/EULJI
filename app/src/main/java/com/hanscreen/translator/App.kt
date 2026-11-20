package com.hanscreen.translator

import android.app.Application
import com.hanscreen.translator.translate.Glossary
import com.hanscreen.translator.translate.TranslationCache

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 预热本地缓存的键索引
        cache
        glossary
    }

    companion object {
        lateinit var instance: App
            private set

        val prefs: Prefs by lazy { Prefs(instance) }
        val cache: TranslationCache by lazy { TranslationCache(instance) }
        val glossary: Glossary by lazy { Glossary(instance) }
    }
}
