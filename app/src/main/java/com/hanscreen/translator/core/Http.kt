package com.hanscreen.translator.core

import android.content.Context
import android.widget.Toast
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.roundToInt

object Http {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    fun postForm(url: String, params: Map<String, String>, timeoutMs: Int = 8000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", UA)
        }
        val body = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(120)}")
            text
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    fun get(url: String, timeoutMs: Int = 8000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", UA)
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(120)}")
            text
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun md5(s: String): String = digest("MD5", s)

    fun sha256(s: String): String = digest("SHA-256", s)

    private fun digest(alg: String, s: String): String =
        MessageDigest.getInstance(alg)
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

fun Context.dp(v: Int): Int = dp(v.toFloat())

fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
