package com.hanscreen.translator

import android.content.Context

/**
 * 全部用户设置。键名集中在此，方便做设置导入/导出。
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("hanscreen", Context.MODE_PRIVATE)

    var provider: String
        get() = sp.getString("provider", "baidu") ?: "baidu"
        set(v) = sp.edit().putString("provider", v).apply()

    var baiduAppId: String
        get() = sp.getString("baidu_appid", "") ?: ""
        set(v) = sp.edit().putString("baidu_appid", v).apply()

    var baiduKey: String
        get() = sp.getString("baidu_key", "") ?: ""
        set(v) = sp.edit().putString("baidu_key", v).apply()

    var youdaoApp: String
        get() = sp.getString("youdao_app", "") ?: ""
        set(v) = sp.edit().putString("youdao_app", v).apply()

    var youdaoSec: String
        get() = sp.getString("youdao_sec", "") ?: ""
        set(v) = sp.edit().putString("youdao_sec", v).apply()

    var targetLang: String
        get() = sp.getString("target_lang", "zh-CN") ?: "zh-CN"
        set(v) = sp.edit().putString("target_lang", v).apply()

    var fontSp: Int
        get() = sp.getInt("font_sp", 15)
        set(v) = sp.edit().putInt("font_sp", v).apply()

    var ballDp: Int
        get() = sp.getInt("ball_dp", 48)
        set(v) = sp.edit().putInt("ball_dp", v).apply()

    var cardMode: Boolean
        get() = sp.getBoolean("card_mode", false)
        set(v) = sp.edit().putBoolean("card_mode", v).apply()

    var continuous: Boolean
        get() = sp.getBoolean("continuous", false)
        set(v) = sp.edit().putBoolean("continuous", v).apply()

    var intervalMs: Int
        get() = sp.getInt("interval_ms", 1000)
        set(v) = sp.edit().putInt("interval_ms", v).apply()

    var ballX: Int
        get() = sp.getInt("ball_x", -1)
        set(v) = sp.edit().putInt("ball_x", v).apply()

    var ballY: Int
        get() = sp.getInt("ball_y", -1)
        set(v) = sp.edit().putInt("ball_y", v).apply()

    /** 用户是否希望悬浮球常驻，用于进程被杀后恢复判断 */
    var serviceWanted: Boolean
        get() = sp.getBoolean("service_wanted", false)
        set(v) = sp.edit().putBoolean("service_wanted", v).apply()
}
