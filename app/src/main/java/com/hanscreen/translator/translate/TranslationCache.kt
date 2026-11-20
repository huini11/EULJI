package com.hanscreen.translator.translate

import android.util.LruCache
import com.hanscreen.translator.core.Http
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 两级缓存：内存 LRU + 磁盘追加写。
 * 连续翻译时同一屏的重复文本不会重复请求接口。
 */
class TranslationCache(context: android.content.Context) {

    private val mem = LruCache<String, String>(4000)
    private val file = File(context.filesDir, "trans_cache.tsv")
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "cache-io").apply { isDaemon = true } }
    private val size = AtomicLong(0)

    init {
        io.execute { load() }
    }

    fun key(provider: String, target: String, glossaryRevision: Int, text: String): String =
        Http.md5("$provider|$target|g$glossaryRevision|$text")

    fun get(k: String): String? = mem.get(k)

    fun put(k: String, v: String) {
        if (v.isBlank()) return
        mem.put(k, v)
        io.execute {
            runCatching {
                if (file.length() > 4 * 1024 * 1024) {
                    file.delete()
                    size.set(0)
                }
                file.appendText("$k\t${v.replace('\t', ' ').replace('\n', ' ')}\n")
                size.addAndGet(1)
            }
        }
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            file.forEachLine { line ->
                val i = line.indexOf('\t')
                if (i > 0 && i < line.length - 1) {
                    mem.put(line.substring(0, i), line.substring(i + 1))
                }
            }
        }
    }

    fun clear() {
        mem.evictAll()
        io.execute { runCatching { file.delete() } }
    }
}
