package com.hanscreen.translator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.hanscreen.translator.App
import com.hanscreen.translator.MainActivity
import com.hanscreen.translator.R
import com.hanscreen.translator.capture.ScreenCapture
import com.hanscreen.translator.core.Hangul
import com.hanscreen.translator.core.Screen
import com.hanscreen.translator.core.dp
import com.hanscreen.translator.core.toast
import com.hanscreen.translator.ocr.OcrEngine
import com.hanscreen.translator.ocr.TextGrouper
import com.hanscreen.translator.overlay.FloatingBallView
import com.hanscreen.translator.overlay.OverlayController
import com.hanscreen.translator.overlay.OverlayItem
import com.hanscreen.translator.translate.EngineCache
import com.hanscreen.translator.translate.Glossary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class TranslatorService : Service() {

    companion object {
        private const val TAG = "HanScreen"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "hanscreen_service"

        const val ACTION_START = "com.hanscreen.action.START"
        const val ACTION_STOP = "com.hanscreen.action.STOP"
        const val ACTION_SET_PROJECTION = "com.hanscreen.action.SET_PROJECTION"
        const val ACTION_TRANSLATE = "com.hanscreen.action.TRANSLATE"
        const val ACTION_SET_CONTINUOUS = "com.hanscreen.action.SET_CONTINUOUS"
        const val ACTION_REFRESH_BALL = "com.hanscreen.action.REFRESH_BALL"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ENABLE = "enable"

        @Volatile
        var running = false

        @Volatile
        var hasProjection = false

        @Volatile
        var continuousOn = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val busy = AtomicBoolean(false)

    private var wm: WindowManager? = null
    private var ball: FloatingBallView? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private var overlay: OverlayController? = null
    private var ocr: OcrEngine? = null
    private var capture: ScreenCapture? = null
    private var projection: MediaProjection? = null

    private var loopJob: Job? = null
    private var lastThumb: ByteArray? = null
    private var engine: com.hanscreen.translator.translate.TranslationEngine? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && continuousOn) {
                stopContinuous(quiet = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlay = OverlayController(this)
        ocr = OcrEngine()
        createChannel()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        running = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            startForegroundCompat(withProjection = false)
            ensureBall()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_SET_PROJECTION -> {
                startForegroundCompat(withProjection = true)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (data != null && code != 0) setupProjection(code, data)
                ensureBall()
                App.prefs.serviceWanted = true
                return START_STICKY
            }

            ACTION_TRANSLATE -> {
                startForegroundCompat(withProjection = hasProjection)
                ensureBall()
                requestTranslate()
                return START_STICKY
            }

            ACTION_SET_CONTINUOUS -> {
                startForegroundCompat(withProjection = hasProjection)
                ensureBall()
                if (intent.getBooleanExtra(EXTRA_ENABLE, false)) startContinuous()
                else stopContinuous(quiet = true)
                return START_STICKY
            }

            ACTION_REFRESH_BALL -> {
                startForegroundCompat(withProjection = hasProjection)
                removeBall()
                ensureBall()
                return START_STICKY
            }

            else -> {
                startForegroundCompat(withProjection = false)
                ensureBall()
                App.prefs.serviceWanted = true
                return START_STICKY
            }
        }
    }

    // ---------------- 前台通知 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "悬浮翻译服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持悬浮球与屏幕捕获运行"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 11,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 12,
            Intent(this, TranslatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("韩屏译运行中")
            .setContentText("点击悬浮球翻译当前屏幕，长按切换连续翻译")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun startForegroundCompat(withProjection: Boolean) {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = 0
            if (Build.VERSION.SDK_INT >= 34) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            if (withProjection) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
                return
            } catch (e: Exception) {
                Log.w(TAG, "startForeground(type=$type) 失败: ${e.message}")
            }
        }
        runCatching { startForeground(NOTIF_ID, notif) }
    }

    // ---------------- 屏幕捕获 ----------------

    private fun setupProjection(resultCode: Int, data: Intent) {
        runCatching {
            capture?.stop()
            capture = null
            runCatching { projection?.stop() }
            projection = null

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(resultCode, data) ?: return
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post {
                        hasProjection = false
                        capture?.stop()
                        capture = null
                        projection = null
                        if (continuousOn) stopContinuous(quiet = true)
                    }
                }
            }, mainHandler)

            projection = p
            val size: Point = Screen.size(this)
            capture = ScreenCapture(
                p,
                size.x,
                size.y,
                resources.displayMetrics.densityDpi
            ).also { it.start() }
            hasProjection = true
        }.onFailure {
            hasProjection = false
            toast("屏幕捕获启动失败：${it.message}")
        }
    }

    private suspend fun captureFrame(): Bitmap? = withContext(Dispatchers.IO) {
        repeat(8) {
            val b = capture?.capture()
            if (b != null) return@withContext b
            delay(50)
        }
        null
    }

    // ---------------- 悬浮球 ----------------

    private fun ensureBall() {
        if (ball != null) return
        val size = dp(App.prefs.ballDp)
        val screen: Point = Screen.size(this)

        val v = FloatingBallView(this).apply {
            onTap = { requestTranslate() }
            onLongPress = { if (continuousOn) stopContinuous() else startContinuous() }
            onDrag = { dx, dy -> moveBall(dx, dy) }
            onDragEnd = { saveBallPosition() }
            continuous = continuousOn
        }

        val x = App.prefs.ballX
        val y = App.prefs.ballY
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = if (x >= 0) x.coerceIn(0, screen.x - size) else screen.x - size - dp(10)
            this.y = if (y >= 0) y.coerceIn(0, screen.y - size) else (screen.y * 0.45f).toInt()
        }

        runCatching { wm?.addView(v, lp) }
        ball = v
        ballParams = lp
    }

    private fun moveBall(dx: Float, dy: Float) {
        val v = ball ?: return
        val lp = ballParams ?: return
        val screen: Point = Screen.size(this)
        val size = v.width.takeIf { it > 0 } ?: dp(App.prefs.ballDp)
        lp.x = (lp.x + dx.toInt()).coerceIn(0, (screen.x - size).coerceAtLeast(0))
        lp.y = (lp.y + dy.toInt()).coerceIn(0, (screen.y - size).coerceAtLeast(0))
        runCatching { wm?.updateViewLayout(v, lp) }
    }

    private fun saveBallPosition() {
        ballParams?.let {
            App.prefs.ballX = it.x
            App.prefs.ballY = it.y
        }
    }

    private fun removeBall() {
        val v = ball ?: return
        saveBallPosition()
        runCatching { wm?.removeViewImmediate(v) }
        ball = null
        ballParams = null
    }

    // ---------------- 翻译主流程 ----------------

    private fun requestTranslate() {
        if (capture == null) {
            toast("需要先授权屏幕捕获")
            promptProjection()
            return
        }
        if (!busy.compareAndSet(false, true)) return
        ball?.busy = true
        scope.launch {
            try {
                hideOverlayBriefly()
                val bmp = captureFrame()
                if (bmp == null) {
                    toast("还没有拿到画面，请重试")
                    return@launch
                }
                processFrame(bmp)
            } catch (e: Exception) {
                toast("翻译失败：${e.message}")
            } finally {
                busy.set(false)
                ball?.busy = false
            }
        }
    }

    /**
     * 覆盖层会盖住原文，若直接截屏会把我们自己的译文截进去。
     * 抓帧前短暂隐藏，抓完由 processFrame 重新显示。
     */
    private suspend fun hideOverlayBriefly(): Boolean {
        val showing = overlay?.isShowing == true
        if (showing) {
            overlay?.setVisible(false)
            delay(45)
        }
        return showing
    }

    private suspend fun processFrame(src: Bitmap) {
        var work: Bitmap? = null
        val t0 = System.currentTimeMillis()
        try {
            val scale = if (src.width > 1280) 1280f / src.width else 1f
            work = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    src,
                    (src.width * scale).toInt(),
                    (src.height * scale).toInt(),
                    true
                )
            } else src

            val raw = ocr?.recognize(work) ?: emptyList()
            val lines = raw.filter { Hangul.count(it.text) >= 2 && Hangul.ratio(it.text) >= 0.15f }
            Log.i(TAG, "capture=${src.width}x${src.height} ocrLines=${raw.size} hangulLines=${lines.size}")

            if (lines.isEmpty()) {
                overlay?.hide()
                toast("当前画面没有检测到韩文")
                return
            }

            val paras = TextGrouper.group(lines)
            val texts = paras.map { it.text }
            val translated = translateAll(texts)
            Log.i(TAG, "paras=${paras.size} translated=${translated.count { it.isNotBlank() }} cost=${System.currentTimeMillis() - t0}ms")

            val inv = 1f / scale
            val items = paras.mapIndexedNotNull { i, p ->
                val t = translated.getOrNull(i)?.trim().orEmpty()
                if (t.isBlank()) return@mapIndexedNotNull null
                OverlayItem(
                    t,
                    p.box.left * inv,
                    p.box.top * inv,
                    p.box.right * inv,
                    p.box.bottom * inv
                )
            }
            if (items.isEmpty()) return

            overlay?.show(
                items,
                App.prefs.cardMode,
                App.prefs.fontSp * resources.displayMetrics.scaledDensity
            )
        } finally {
            // src 由 ScreenCapture 持有并复用，不能在此回收
            if (work !== src) runCatching { work?.recycle() }
        }
    }

    private suspend fun translateAll(texts: List<String>): List<String> {
        val e = engine ?: EngineCache.current().also { engine = it }
        val target = App.prefs.targetLang
        val g = App.glossary
        val rev = g.revision

        val out = arrayOfNulls<String>(texts.size)
        val miss = ArrayList<Int>()
        val prepared = arrayOfNulls<Glossary.Prepared>(texts.size)

        texts.forEachIndexed { i, raw ->
            // 1) 整段命中术语表：直接给译文，连请求都省了
            val exact = g.exact(raw)
            if (exact != null) {
                out[i] = exact
                return@forEachIndexed
            }
            // 2) 段内术语先用占位符保护，避免被机器翻译改写
            val p = g.prepare(raw)
            prepared[i] = p
            val k = App.cache.key(e.id, target, rev, p.masked)
            val hit = App.cache.get(k)
            if (hit != null) out[i] = hit else miss.add(i)
        }

        if (miss.isNotEmpty()) {
            val req = miss.map { prepared[it]?.masked ?: texts[it] }
            val res = e.translate(req, target)
            res.forEachIndexed { j, s ->
                val idx = miss.getOrNull(j) ?: return@forEachIndexed
                val p = prepared[idx]
                val restored = if (p != null) g.restore(s, p.applied) else s
                out[idx] = restored
                // 译文与原文相同说明这次没翻成功（或被接口原样回吐），不入缓存，
                // 否则下次会一直命中错误结果。
                if (restored.isNotBlank() && restored.trim() != texts[idx].trim()) {
                    App.cache.put(App.cache.key(e.id, target, rev, p?.masked ?: texts[idx]), restored)
                }
            }
        }
        return out.mapIndexed { i, s -> s ?: texts[i] }
    }

    // ---------------- 连续翻译 ----------------

    private fun startContinuous() {
        if (capture == null) {
            toast("需要先授权屏幕捕获")
            promptProjection()
            return
        }
        if (continuousOn) return
        continuousOn = true
        App.prefs.continuous = true
        ball?.continuous = true
        lastThumb = null
        toast("连续翻译：开")

        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive && continuousOn) {
                if (busy.compareAndSet(false, true)) {
                    ball?.busy = true
                    try {
                        val hidden = hideOverlayBriefly()
                        val bmp = captureFrame()
                        if (bmp == null) {
                            if (hidden) overlay?.setVisible(true)
                        } else {
                            val thumb = thumbOf(bmp)
                            val changed = lastThumb?.let { diff(it, thumb) > 2.2f } ?: true
                            lastThumb = thumb
                            if (changed) {
                                processFrame(bmp)
                            } else if (hidden) {
                                overlay?.setVisible(true)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "连续翻译失败: ${e.message}")
                    } finally {
                        busy.set(false)
                        ball?.busy = false
                    }
                }
                delay(App.prefs.intervalMs.toLong().coerceIn(400, 4000))
            }
        }
    }

    private fun stopContinuous(quiet: Boolean = false) {
        continuousOn = false
        App.prefs.continuous = false
        loopJob?.cancel()
        loopJob = null
        lastThumb = null
        ball?.continuous = false
        if (!quiet) toast("连续翻译：关")
    }

    private fun thumbOf(bmp: Bitmap): ByteArray {
        val w = 24
        val h = 48
        val small = Bitmap.createScaledBitmap(bmp, w, h, false)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        runCatching { small.recycle() }
        val out = ByteArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val lum = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            out[i] = lum.toByte()
        }
        return out
    }

    private fun diff(a: ByteArray, b: ByteArray): Float {
        if (a.size != b.size) return 99f
        var sum = 0L
        for (i in a.indices) sum += abs(a[i].toInt() - b[i].toInt())
        return sum.toFloat() / a.size
    }

    // ---------------- 其他 ----------------

    private fun promptProjection() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限")
        }
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_REQUEST_PROJECTION, true)
        }
        runCatching { startActivity(i) }
    }

    private fun stopEverything() {
        stopContinuous(quiet = true)
        overlay?.hide()
        removeBall()
        capture?.stop()
        capture = null
        runCatching { projection?.stop() }
        projection = null
        hasProjection = false
        running = false
        App.prefs.serviceWanted = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        continuousOn = false
        loopJob?.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        overlay?.hide()
        removeBall()
        capture?.stop()
        runCatching { projection?.stop() }
        ocr?.close()
        EngineCache.close()
        scope.cancel()
        super.onDestroy()
    }
}
