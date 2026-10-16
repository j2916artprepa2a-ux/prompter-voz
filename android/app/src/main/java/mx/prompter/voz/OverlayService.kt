package mx.prompter.voz

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/**
 * El cuadrito flotante: queda encima de TikTok, Instagram, Facebook o la cámara.
 * Esas apps graban tu cámara (no tu pantalla), así que el cuadrito no sale en el video.
 */
class OverlayService : Service(), RecognitionListener {

    companion object {
        const val ACTION_STOP = "mx.prompter.voz.STOP"
        const val ACTION_RELOAD = "mx.prompter.voz.RELOAD"
        const val CHANNEL = "prompter"
        var running = false
    }

    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(Prefs.FILE, MODE_PRIVATE) }

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var scroll: ScrollView
    private lateinit var tv: TextView
    private lateinit var status: TextView
    private lateinit var playBtn: TextView
    private lateinit var modeBtn: TextView

    private var follower = Follower("")
    private var fontSp = 22f
    private var voice = true
    private var playing = false
    private var wpm = 140
    private var recognizer: SpeechRecognizer? = null
    private var errors = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startAsForeground()
        running = true
        if (root == null) createOverlay() else reload()
        return START_NOT_STICKY
    }

    // ---------- notificación obligatoria para seguir funcionando encima de otras apps ----------
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cuadrito flotante", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("TexJAHC está activo")
            .setContentText("Tu cuadrito de texto está flotando. Toca para editar el guion.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Cerrar cuadrito", stop).build())
            .setOngoing(true)
            .build()
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        try {
            when {
                Build.VERSION.SDK_INT >= 34 -> {
                    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    if (micOk) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    startForeground(1, n, type)
                }
                Build.VERSION.SDK_INT >= 29 && micOk ->
                    startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                else -> startForeground(1, n)
            }
        } catch (e: Exception) {
            startForeground(1, n)
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun btn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(0xDDFFFFFF.toInt())
        textSize = 17f
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(4), dp(9), dp(4))
        setOnClickListener { onClick() }
    }

    // ---------- crear el cuadrito ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = w
        val dm = resources.displayMetrics

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xE6000000.toInt())
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(14), dp(10), dp(10), dp(4))
        }

        tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.12f)
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(tv)
        }
        box.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(90)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val move = btn("✥") {}
        modeBtn = btn("🎙") { toggleMode() }
        playBtn = btn("▶") { togglePlay() }
        val restart = btn("↺") { follower.pos = 0; render(true) }
        val smaller = btn("A−") { setFont(fontSp - 2f) }
        val bigger = btn("A+") { setFont(fontSp + 2f) }
        val close = btn("✕") { stopSelf() }
        status = TextView(this).apply {
            setTextColor(0x99FFFFFF.toInt())
            textSize = 11f
            maxLines = 1
            setPadding(dp(4), 0, dp(4), 0)
        }
        row.addView(move)
        row.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        listOf(modeBtn, playBtn, restart, smaller, bigger, close).forEach { row.addView(it) }
        box.addView(row)

        params = WindowManager.LayoutParams(
            prefs.getInt(Prefs.WIDTH, (dm.widthPixels * 0.92).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(Prefs.X, (dm.widthPixels * 0.04).toInt())
            y = prefs.getInt(Prefs.Y, dp(40))
        }
        if (prefs.getBoolean(Prefs.SECURE, false)) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_SECURE
        }

        // Arrastrar con ✥ (también se puede arrastrar desde el texto con el dedo)
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        move.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = sx + (e.rawX - tx).toInt(); params.y = sy + (e.rawY - ty).toInt()
                    wm?.updateViewLayout(box, params); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt(Prefs.X, params.x).putInt(Prefs.Y, params.y).apply(); true
                }
                else -> false
            }
        }
        // Tocar una palabra = brincar ahí
        tv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val off = tv.getOffsetForPosition(e.x, e.y)
                if (off >= 0) { follower.jumpToOffset(off); render(true) }
            }
            true
        }

        w.addView(box, params)
        root = box
        reload()
    }

    private fun reload() {
        follower = Follower(prefs.getString(Prefs.SCRIPT, Prefs.DEFAULT_SCRIPT) ?: Prefs.DEFAULT_SCRIPT)
        voice = prefs.getBoolean(Prefs.VOICE, true)
        wpm = prefs.getInt(Prefs.WPM, 140)
        setFont(prefs.getInt(Prefs.FONT, 22).toFloat())
        modeBtn.text = if (voice) "🎙" else "⏱"
        setPlaying(false)
        status.text = if (voice) "Toca ▶ y habla" else "Toca ▶ para empezar"
    }

    private fun setFont(sp: Float) {
        fontSp = sp.coerceIn(12f, 60f)
        prefs.edit().putInt(Prefs.FONT, fontSp.toInt()).apply()
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
        val lines = prefs.getInt(Prefs.LINES, 4)
        scroll.layoutParams = scroll.layoutParams.apply { height = tv.lineHeight * lines + dp(2) }
        root?.let { wm?.updateViewLayout(it, params) }
        render(true)
    }

    // ---------- pintar el texto ----------
    private fun render(jump: Boolean) {
        val sp = SpannableString(follower.display)
        for (r in follower.directions) {
            sp.setSpan(ForegroundColorSpan(0xFFFFD34D.toInt()), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(RelativeSizeSpan(0.72f), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (follower.pos > 0 && follower.words.isNotEmpty()) {
            val end = follower.words[minOf(follower.pos, follower.words.size) - 1].last + 1
            sp.setSpan(ForegroundColorSpan(0x55FFFFFF), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (follower.pos < follower.words.size) {
            val r = follower.words[follower.pos]
            sp.setSpan(ForegroundColorSpan(0xFFFFD34D.toInt()), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(UnderlineSpan(), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = sp
        tv.post {
            val layout = tv.layout ?: return@post
            val line = layout.getLineForOffset(follower.currentOffset().coerceAtMost(follower.display.length))
            val keep = if (prefs.getInt(Prefs.LINES, 4) >= 4) 1 else 0   // 1 línea anterior y el resto lo que sigue
            val top = layout.getLineTop(max(0, line - keep))
            if (jump) scroll.scrollTo(0, top) else scroll.smoothScrollTo(0, top)
        }
        if (follower.pos >= follower.count && follower.count > 0) {
            status.text = "¡Terminaste! 🎉"
            if (playing) setPlaying(false)
        }
    }

    // ---------- reproducir / pausar ----------
    private fun toggleMode() {
        val was = playing
        setPlaying(false)
        voice = !voice
        prefs.edit().putBoolean(Prefs.VOICE, voice).apply()
        modeBtn.text = if (voice) "🎙" else "⏱"
        status.text = if (voice) "Modo voz: te sigue al hablar" else "Modo automático: avanza solo"
        if (was) setPlaying(true)
    }

    private fun togglePlay() = setPlaying(!playing)

    private fun setPlaying(on: Boolean) {
        playing = on
        playBtn.text = if (on) "⏸" else "▶"
        h.removeCallbacks(tick)
        if (on) {
            if (follower.pos >= follower.count) follower.pos = 0
            errors = 0
            if (voice) startListening() else h.postDelayed(tick, 60000L / wpm)
        } else {
            try { recognizer?.cancel() } catch (_: Exception) {}
            status.text = "En pausa"
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!playing || voice) return
            if (follower.pos < follower.count) { follower.pos++; render(false) }
            if (follower.pos < follower.count) h.postDelayed(this, 60000L / wpm)
        }
    }

    // ---------- seguir la voz (reconocimiento de voz del teléfono) ----------
    private val recIntent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.getString(Prefs.LANG, "es-MX"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Sin voz de Google: usa ⏱ (automático)"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Falta permiso de micrófono (abre la app)"
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this); recognizer = it
        }
        try { r.startListening(recIntent) } catch (_: Exception) { restartSoon(600) }
    }

    private fun restartSoon(ms: Long) {
        if (!playing || !voice) return
        h.postDelayed({ if (playing && voice) startListening() }, ms)
    }

    private fun heard(b: Bundle?) {
        val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        if (t.isBlank()) return
        errors = 0
        status.text = "🎙 " + t.trim().split(Regex("\\s+")).takeLast(2).joinToString(" ")
        if (follower.follow(t)) render(false)
    }

    override fun onReadyForSpeech(params: Bundle?) { if (playing) status.text = "Escuchando…" }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {
        if (playing && voice) playBtn.alpha = (0.55f + (rmsdB.coerceIn(0f, 10f) / 22f))
    }
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) = heard(partialResults)
    override fun onEvent(eventType: Int, params: Bundle?) {}
    override fun onResults(results: Bundle?) { heard(results); restartSoon(150) }
    override fun onError(error: Int) {
        if (!playing) return
        errors++
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { status.text = "Falta permiso de micrófono"; setPlaying(false); return }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> status.text = "Sin internet para la voz"
            SpeechRecognizer.ERROR_AUDIO -> status.text = "Otra app está usando el micrófono"
        }
        if (errors >= 6 && error == SpeechRecognizer.ERROR_AUDIO) {
            status.text = "El micrófono lo ocupa otra app: usa ⏱"
        }
        restartSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 700L else 250L)
    }

    override fun onDestroy() {
        running = false
        playing = false
        h.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (_: Exception) {}
        root?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        root = null
        super.onDestroy()
    }

    @Suppress("unused")
    private fun near(a: Int, b: Int) = abs(a - b) < 3
}
