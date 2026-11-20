package com.hanscreen.translator.core

import kotlin.math.abs

object Hangul {

    fun isHangul(c: Char): Boolean =
        c in '\uAC00'..'\uD7A3' ||          // 音节
        c in '\u1100'..'\u11FF' ||          // 字母
        c in '\u3130'..'\u318F'             // 兼容字母

    fun count(text: String): Int {
        var n = 0
        for (c in text) if (isHangul(c)) n++
        return n
    }

    /**
     * 韩文字符在"有效字符"中的占比。用于过滤纯英文、数字、版本号这类噪声块。
     */
    fun ratio(text: String): Float {
        var letters = 0
        var hangul = 0
        for (c in text) {
            when {
                c.isWhitespace() -> Unit
                c.isLetter() -> {
                    letters++
                    if (isHangul(c)) hangul++
                }
                c.isDigit() -> letters++
            }
        }
        return if (letters == 0) 0f else hangul.toFloat() / letters
    }

    fun hasHangul(text: String): Boolean {
        for (c in text) if (isHangul(c)) return true
        return false
    }

    /** 去掉首尾的符号噪声，保留可用文本 */
    fun tidy(text: String): String =
        text.replace(Regex("\\s+"), " ").trim().trim('·', '-', '|', '_', ':', '/')
}
