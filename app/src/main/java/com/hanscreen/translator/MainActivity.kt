package com.hanscreen.translator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.hanscreen.translator.core.toast
import com.hanscreen.translator.databinding.ActivityMainBinding
import com.hanscreen.translator.service.TranslatorService
import com.hanscreen.translator.translate.EngineCache
import com.hanscreen.translator.translate.Engines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REQUEST_PROJECTION = "request_projection"
    }

    private lateinit var b: ActivityMainBinding
    private val prefs get() = App.prefs

    private var suppressSwitch = false
    private var pendingStart = false

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (pendingStart && Settings.canDrawOverlays(this)) {
                pendingStart = false
                startServiceAndCapture()
            }
            refresh()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK && res.data != null) {
                val i = Intent(this, TranslatorService::class.java).apply {
                    action = TranslatorService.ACTION_SET_PROJECTION
                    putExtra(TranslatorService.EXTRA_RESULT_CODE, res.resultCode)
                    putExtra(TranslatorService.EXTRA_RESULT_DATA, res.data)
                }
                ContextCompat.startForegroundService(this, i)
                b.tvStatus.text = "正在连接屏幕捕获…"
            } else {
                b.tvStatus.text = "未授权屏幕捕获；点悬浮球可再次申请"
            }
            refresh()
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        bindUi()
        loadValues()
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_PROJECTION, false) == true) {
            requestProjection()
        }
    }

    // ---------------- UI 绑定 ----------------

    private fun bindUi() {
        b.spProvider.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Engines.labels
        )
        b.spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val pid = Engines.ids.getOrElse(position) { Engines.BAIDU }
                if (prefs.provider != pid) {
                    prefs.provider = pid
                    EngineCache.close()
                }
                applyProviderUi(pid)
                refresh()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        b.etAppId.doAfterTextChanged { prefs.baiduAppId = it?.toString()?.trim().orEmpty() }
        b.etBaiduKey.doAfterTextChanged { prefs.baiduKey = it?.toString()?.trim().orEmpty() }
        b.etYoudaoApp.doAfterTextChanged { prefs.youdaoApp = it?.toString()?.trim().orEmpty() }
        b.etYoudaoSec.doAfterTextChanged { prefs.youdaoSec = it?.toString()?.trim().orEmpty() }

        b.sbFont.setOnSeekBarChangeListener(seekListener { p ->
            val v = 10 + p
            prefs.fontSp = v
            b.tvFont.text = "${v}sp"
        })

        b.sbBall.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = 34 + p * 2
                prefs.ballDp = v
                b.tvBall.text = "${v}dp"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit

            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (TranslatorService.running) {
                    sendService(TranslatorService.ACTION_REFRESH_BALL)
                }
            }
        })

        b.sbInterval.setOnSeekBarChangeListener(seekListener { p ->
            val v = 400 + p * 200
            prefs.intervalMs = v
            b.tvInterval.text = "${v}ms"
        })

        b.rgMode.setOnCheckedChangeListener { _, checkedId ->
            prefs.cardMode = checkedId == R.id.rbCard
        }

        b.swContinuous.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            prefs.continuous = isChecked
            if (TranslatorService.running) {
                val i = Intent(this, TranslatorService::class.java).apply {
                    action = TranslatorService.ACTION_SET_CONTINUOUS
                    putExtra(TranslatorService.EXTRA_ENABLE, isChecked)
                }
                ContextCompat.startForegroundService(this, i)
            } else if (isChecked) {
                toast("请先启动悬浮球")
                suppressSwitch = true
                b.swContinuous.isChecked = false
                suppressSwitch = false
            }
        }

        b.btnStart.setOnClickListener { startServiceAndCapture() }
        b.btnStop.setOnClickListener {
            startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_STOP))
            b.tvStatus.text = "已停止"
        }
        b.btnTest.setOnClickListener { runTest() }
        b.btnGlossary.setOnClickListener {
            startActivity(Intent(this, GlossaryActivity::class.java))
        }

        b.tvPermOverlay.setOnClickListener { requestOverlayPermission() }
        b.tvPermProjection.setOnClickListener { requestProjection() }
        b.tvBattery.setOnClickListener { requestBatteryWhitelist() }
    }

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
        override fun onStartTrackingTouch(sb: SeekBar?) = Unit
        override fun onStopTrackingTouch(sb: SeekBar?) = Unit
    }

    private fun applyProviderUi(pid: String) {
        b.llBaidu.visibility = if (pid == Engines.BAIDU) View.VISIBLE else View.GONE
        b.llYoudao.visibility = if (pid == Engines.YOUDAO) View.VISIBLE else View.GONE
    }

    private fun loadValues() {
        b.spProvider.setSelection(Engines.ids.indexOf(prefs.provider).coerceAtLeast(0))
        b.etAppId.setText(prefs.baiduAppId)
        b.etBaiduKey.setText(prefs.baiduKey)
        b.etYoudaoApp.setText(prefs.youdaoApp)
        b.etYoudaoSec.setText(prefs.youdaoSec)

        b.sbFont.progress = (prefs.fontSp - 10).coerceIn(0, 18)
        b.sbBall.progress = ((prefs.ballDp - 34) / 2).coerceIn(0, 16)
        b.sbInterval.progress = ((prefs.intervalMs - 400) / 200).coerceIn(0, 15)

        b.tvFont.text = "${prefs.fontSp}sp"
        b.tvBall.text = "${prefs.ballDp}dp"
        b.tvInterval.text = "${prefs.intervalMs}ms"

        b.rbCard.isChecked = prefs.cardMode
        b.rbOverlay.isChecked = !prefs.cardMode

        b.swContinuous.isChecked = prefs.continuous
        applyProviderUi(prefs.provider)

        b.tvGuide.text = """
            1. 点「启动悬浮球」→ 允许悬浮窗 → 允许屏幕录制（系统每次会话都会询问一次）。
            2. 打开任意韩文 App，点一下悬浮球，译文会直接盖在原文上方；点空白处关闭。
            3. 长按悬浮球开启连续翻译：画面变化才重新识别，适合刷列表、看聊天。
            4. 一加 ACE 5 建议：设置 → 电池 → 应用耗电管理 → 韩屏译 → 允许后台活动；并在「自启动」里放行，否则锁屏或后台清理后悬浮球会消失。
            5. 翻译引擎先去百度翻译开放平台（fanyi-api.baidu.com）注册，拿到 AppID 和密钥填入上方，每月有免费额度。
            6. 韩文识别在你的手机上离线完成，截图不会上传；只有识别出的文字会发给翻译接口。
        """.trimIndent()
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val running = TranslatorService.running
        val projectionOk = TranslatorService.hasProjection

        b.tvPermOverlay.text = "悬浮窗权限：" + if (overlayOk) "已授权" else "未授权（点此授权）"
        b.tvPermOverlay.setTextColor(
            Color.parseColor(if (overlayOk) "#6B7280" else "#F79009")
        )

        b.tvPermProjection.text = "屏幕捕获：" + if (projectionOk) "已授权" else "未授权"
        b.tvPermProjection.setTextColor(
            Color.parseColor(if (projectionOk) "#6B7280" else "#9AA3B2")
        )

        b.tvStatus.text = when {
            running && projectionOk -> "运行中 · 点悬浮球翻译"
            running -> "悬浮球已就绪 · 等待屏幕捕获授权"
            else -> "未启动"
        }
        b.tvDot.setTextColor(Color.parseColor(if (running) "#12B76A" else "#9AA3B2"))
        b.btnStop.isEnabled = running
        b.btnStart.text = if (running) "重启悬浮球" else "启动悬浮球"

        b.tvProviderHint.text = if (EngineCache.current().isConfigured()) "引擎就绪" else "引擎未配置"
        b.tvGlossaryCount.text = "已启用 ${App.glossary.size()} 条"

        suppressSwitch = true
        b.swContinuous.isChecked = TranslatorService.continuousOn
        suppressSwitch = false
    }

    // ---------------- 动作 ----------------

    private fun startServiceAndCapture() {
        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true
            requestOverlayPermission()
            return
        }
        sendService(TranslatorService.ACTION_START)
        if (!TranslatorService.hasProjection) requestProjection()
        refresh()
    }

    private fun sendService(action: String) {
        val i = Intent(this, TranslatorService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, i)
    }

    private fun requestOverlayPermission() {
        runCatching {
            overlayLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure { toast("请手动到「设置 → 应用 → 特殊权限」中开启悬浮窗") }
    }

    private fun requestProjection() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限")
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        runCatching {
            // Android 14+ 默认让用户选"单个应用"，那样悬浮球抓不到其他 App，
            // 这里强制整屏共享。
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                mpm.createScreenCaptureIntent()
            }
            projectionLauncher.launch(intent)
        }.onFailure { toast("无法发起屏幕录制授权：${it.message}") }
    }

    private fun requestBatteryWhitelist() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("已在省电白名单")
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            toast("请手动把本应用设为「不限制」")
        }
    }

    private fun runTest() {
        val e = EngineCache.current()
        if (!e.isConfigured()) {
            b.tvTest.text = "请先填写密钥"
            return
        }
        b.tvTest.text = "测试中…"
        lifecycleScope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    e.translate(listOf("안녕하세요. 오늘 날씨가 좋네요."), prefs.targetLang)
                }
            }
            res.onSuccess { b.tvTest.text = "→ " + (it.firstOrNull() ?: "(空)") }
                .onFailure { b.tvTest.text = "失败：" + (it.message ?: "未知错误").take(80) }
        }
    }
}
