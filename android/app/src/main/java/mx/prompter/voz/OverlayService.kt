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
import android.content.res.Configuration
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
import android.widget.FrameLayout
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
        const val ACTION_RECREATE = "mx.prompter.voz.RECREATE"
        const val ACTION_STYLE = "mx.prompter.voz.STYLE"
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
    private var progressFill: View? = null   // barra de avance, igual que en la app web
    private var aiPanel: OverlayAiPanel? = null
    private var aiWasPlaying = false
    private var lastCommand = ""
    private var lastCommandAt = 0L

    private var follower = Follower("")
    private var fontSp = 22f
    private var voice = true
    private var playing = false
    private var wpm = 140
    private var recognizer: SpeechRecognizer? = null
    private var errors = 0
    private var a11yWindow = false

    // Detecta si Android le quitó el micrófono al cuadrito (otra app lo está usando)
    private var recCb: Any? = null
    private fun watchMic() {
        if (Build.VERSION.SDK_INT < 29 || recCb != null) return
        val am = getSystemService(android.media.AudioManager::class.java)
        val cb = object : android.media.AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                if (!playing || !voice || configs == null) return
                if (configs.any { it.isClientSilenced }) {
                    status.text = if (a11yWindow) "Android silenció el micrófono aquí · usa ⏱"
                                  else "Otra app usa el micrófono · activa Accesibilidad o usa ⏱"
                } else if (configs.isNotEmpty() && voskService != null) {
                    status.text = if (a11yWindow) "Escuchando… (accesibilidad)" else "Escuchando… (voz integrada)"
                }
            }
        }
        am.registerAudioRecordingCallback(cb, h)
        recCb = cb
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_STYLE) {
            // solo cambia color/letra/tamaño: el guion se queda donde ibas
            if (root != null) { applyTextStyle(); setFont(prefs.getInt(Prefs.FONT, 22).toFloat()) } else stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECREATE && root != null) {
            closeAiPanel()
            // pasar el cuadrito a ventana de accesibilidad (o regresarlo a normal)
            setPlaying(false)
            root?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
            root = null
        }
        startAsForeground()
        running = true
        try {
            if (root == null) createOverlay() else reload()
            prefs.edit().putString("lastError", "").putLong("shownAt", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            logError("Cuadrito: " + e.javaClass.simpleName + " " + (e.message ?: ""))
            android.widget.Toast.makeText(this, "No pude mostrar el cuadrito: " + (e.message ?: e.javaClass.simpleName), android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
        }
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
            // Si Android no deja usar el micrófono desde aquí, igual mostramos el cuadrito (modo ⏱ o voz al volver)
            logError("FGS mic: " + e.javaClass.simpleName + " " + (e.message ?: ""))
            try {
                if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(1, n)
            } catch (e2: Exception) {
                logError("FGS: " + e2.javaClass.simpleName + " " + (e2.message ?: ""))
            }
        }
    }

    private fun logError(msg: String) {
        prefs.edit().putString("lastError", msg.take(300)).apply()
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
        val a11y = AccessService.instance
        a11yWindow = a11y != null
        val w = (a11y ?: this).getSystemService(WINDOW_SERVICE) as WindowManager
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
        // Barra de avance delgada (como en la web): cuánto del guion llevas
        val track = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(2).toFloat() }
        }
        val fill = View(this).apply {
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(2).toFloat() }
        }
        track.addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        progressFill = fill
        box.addView(track, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(4); bottomMargin = dp(2) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val move = btn("✥") {}
        modeBtn = btn("🎙") { toggleMode() }
        playBtn = btn("▶") { togglePlay() }
        val ai = btn("🤖 IA") { toggleAiPanel() }
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
        listOf(modeBtn, playBtn, ai, restart, smaller, bigger, close).forEach { row.addView(it) }
        // ◢ cambiar tamaño: arrastra a los lados = ancho, arriba/abajo = líneas. Doble toque = tamaño normal
        val grip = btn("◢") {}
        row.addView(grip)
        box.addView(row)

        params = WindowManager.LayoutParams(
            prefs.getInt(Prefs.WIDTH, (dm.widthPixels * 0.92).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (a11yWindow) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
        var moved = false; var lastTapMove = 0L
        move.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - tx) > dp(3) || abs(e.rawY - ty) > dp(3)) moved = true
                    params.x = sx + (e.rawX - tx).toInt(); params.y = sy + (e.rawY - ty).toInt()
                    keepOnScreen(); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val now = System.currentTimeMillis()
                    if (!moved && e.action == MotionEvent.ACTION_UP && now - lastTapMove < 350) {
                        // doble toque en ✥: regresa arriba, donde estaba al principio
                        val (sw, _) = screenSize()
                        params.x = (sw * 0.04).toInt(); params.y = dp(40); lastTapMove = 0L
                        status.text = "Cuadrito regresó arriba"
                    } else lastTapMove = if (moved) 0L else now
                    keepOnScreen(save = true); true
                }
                else -> false
            }
        }
        var gw = 0; var gLines = 4; var gx = 0f; var gy = 0f
        var gMoved = false; var lastTapGrip = 0L
        grip.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    gw = if (params.width > 0) params.width else box.width
                    gLines = maxOf(3, prefs.getInt(Prefs.LINES, 4)); gx = e.rawX; gy = e.rawY; gMoved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - gx) > dp(3) || abs(e.rawY - gy) > dp(3)) gMoved = true
                    val (sw, _) = screenSize()
                    params.width = (gw + (e.rawX - gx).toInt()).coerceIn(dp(180), maxOf(dp(180), sw - params.x))
                    val lh = maxOf(1, tv.lineHeight)
                    val lines = (gLines + ((e.rawY - gy) / lh).toInt()).coerceIn(3, 10)
                    if (lines != prefs.getInt(Prefs.LINES, 4)) {
                        prefs.edit().putInt(Prefs.LINES, lines).apply()
                        setFont(fontSp)   // recalcula el alto con las líneas nuevas
                    }
                    keepOnScreen(); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val now = System.currentTimeMillis()
                    if (!gMoved && e.action == MotionEvent.ACTION_UP && now - lastTapGrip < 350) {
                        val (sw, _) = screenSize()
                        params.width = (sw * 0.92).toInt(); lastTapGrip = 0L
                        prefs.edit().putInt(Prefs.LINES, 4).apply(); setFont(fontSp)
                        status.text = "Tamaño normal"
                    } else {
                        lastTapGrip = if (gMoved) 0L else now
                        if (!gMoved) status.text = "Arrastra ◢ para cambiar el tamaño"
                    }
                    keepOnScreen(save = true); true
                }
                else -> false
            }
        }
        // Si el cuadrito crece (panel de IA, letra más grande), se acomoda para no salirse de la pantalla
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> keepOnScreen() }
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
        box.post { keepOnScreen() }
    }

    /** Tamaño de la pantalla actual (cambia al girar el cel). */
    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            try { wm?.currentWindowMetrics?.bounds?.let { return it.width() to it.height() } } catch (_: Exception) {}
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /** Mantiene el cuadrito completo dentro de la pantalla; con save=true guarda posición y tamaño. */
    private fun keepOnScreen(save: Boolean = false) {
        val box = root ?: return
        if (!::params.isInitialized) return
        val (sw, sh) = screenSize()
        val ow = params.width; val ox = params.x; val oy = params.y
        if (params.width > sw) params.width = sw
        val bw = if (params.width > 0) params.width else box.width
        val bh = if (box.height > 0) box.height else box.measuredHeight
        params.x = params.x.coerceIn(0, maxOf(0, sw - bw))
        params.y = params.y.coerceIn(0, maxOf(0, sh - bh))
        if (params.width != ow || params.x != ox || params.y != oy || save) {
            try { wm?.updateViewLayout(box, params) } catch (_: Exception) {}
        }
        if (save) prefs.edit().putInt(Prefs.X, params.x).putInt(Prefs.Y, params.y).putInt(Prefs.WIDTH, params.width).apply()
    }

    // Al girar el cel, el cuadrito se reacomoda dentro de la pantalla nueva
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root?.post { keepOnScreen() }
    }

    // El panel de IA es independiente del seguidor: no modifica follower.pos ni scrollY.
    private fun toggleAiPanel() {
        if (aiPanel != null) { closeAiPanel(); return }
        val container = root ?: return
        aiWasPlaying = playing
        if (playing) setPlaying(false) // libera el micrófono del seguimiento
        val panel = OverlayAiPanel(this) { closeAiPanel() }
        aiPanel = panel
        container.addView(panel.view)
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        wm?.updateViewLayout(container, params)
        status.text = "IA abierta · guion conservado"
    }

    private fun closeAiPanel() {
        val panel = aiPanel ?: return
        aiPanel = null
        panel.close()
        root?.removeView(panel.view)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        root?.let { wm?.updateViewLayout(it, params) }
        status.text = "Guion en pausa · toca ▶ para seguir"
        aiWasPlaying = false
    }

    private fun processVoiceCommand(raw: String): Boolean {
        val normalized = java.text.Normalizer.normalize(raw.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "")
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        val match = Regex("(?:texjahc|tex jahc|tex yac|tex yak) (pausa|sigue|atras|mas grande)").find(normalized) ?: return false
        val cmd = match.groupValues[1]
        val now = android.os.SystemClock.elapsedRealtime()
        if (cmd == lastCommand && now - lastCommandAt < 1800L) return true
        lastCommand = cmd; lastCommandAt = now
        when (cmd) {
            "pausa" -> setPlaying(false)
            "sigue" -> if (!playing) setPlaying(true)
            "atras" -> { follower.pos = (follower.pos - 8).coerceAtLeast(0); render(true) }
            "mas grande" -> setFont(fontSp + 2f)
        }
        return true
    }

    private fun reload() {
        follower = Follower(prefs.getString(Prefs.SCRIPT, Prefs.DEFAULT_SCRIPT) ?: Prefs.DEFAULT_SCRIPT)
        voice = prefs.getBoolean(Prefs.VOICE, true)
        wpm = prefs.getInt(Prefs.WPM, 140)
        applyTextStyle()
        setFont(prefs.getInt(Prefs.FONT, 22).toFloat())
        modeBtn.text = if (voice) "🎙" else "⏱"
        setPlaying(false)
        status.text = if (follower.count == 0) "Sin guion: escríbelo en TexJAHC"
            else if (voice) "Toca ▶ y habla" else "Toca ▶ para empezar"
    }

    private val EMPTY_HINT = "✏️ Escribe tu guion en la app TexJAHC y vuelve a abrir el cuadrito."

    // color y tipo de letra elegidos en la app
    private var txtColor = Color.WHITE
    private var hlColor = 0xFFFFD34D.toInt()
    private fun applyTextStyle() {
        val c = TextStyle.color(prefs)
        txtColor = c.argb; hlColor = c.highlight
        tv.setTextColor(txtColor)
        tv.typeface = TextStyle.typeface(TextStyle.face(prefs))
    }

    private fun setFont(sp: Float) {
        fontSp = sp.coerceIn(12f, 60f)
        prefs.edit().putInt(Prefs.FONT, fontSp.toInt()).apply()
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
        val lines = maxOf(3, prefs.getInt(Prefs.LINES, 4))
        scroll.layoutParams = scroll.layoutParams.apply { height = tv.lineHeight * (lines + 1) + dp(2) }
        root?.let { wm?.updateViewLayout(it, params) }
        render(true)
    }

    // ---------- pintar el texto ----------
    private fun render(jump: Boolean) {
        if (follower.count == 0 && follower.display.isBlank()) {   // sin guion: solo la indicación
            tv.text = EMPTY_HINT; scroll.scrollTo(0, 0)
            progressFill?.let { f -> f.layoutParams = f.layoutParams.apply { width = 0 }; f.requestLayout() }
            return
        }
        val sp = SpannableString(follower.display)
        for (r in follower.directions) {
            sp.setSpan(ForegroundColorSpan(0xFFFFD34D.toInt()), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(RelativeSizeSpan(0.72f), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (follower.pos > 0 && follower.words.isNotEmpty()) {
            val end = follower.words[minOf(follower.pos, follower.words.size) - 1].last + 1
            sp.setSpan(ForegroundColorSpan(TextStyle.dim(txtColor)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (follower.pos < follower.words.size) {
            val r = follower.words[follower.pos]
            sp.setSpan(ForegroundColorSpan(hlColor), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(UnderlineSpan(), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = sp
        progressFill?.let { f ->
            val parent = f.parent as? View
            parent?.post {
                val pct = if (follower.count > 0) follower.pos.toFloat() / follower.count else 0f
                f.layoutParams = f.layoutParams.apply { width = (parent.width * pct.coerceIn(0f, 1f)).toInt() }
                f.requestLayout()
            }
        }
        tv.post {
            val layout = tv.layout ?: return@post
            val line = layout.getLineForOffset(follower.currentOffset().coerceAtMost(follower.display.length))
            val keep = 0   // la línea actual hasta arriba: todo lo demás es lo que sigue
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
        if (on && follower.count == 0) {   // sin guion no hay nada que seguir
            playing = false; playBtn.text = "▶"; status.text = "Sin guion: escríbelo en TexJAHC"; return
        }
        if (on) {
            if (follower.pos >= follower.count) follower.pos = 0
            errors = 0
            if (voice) startListening() else h.postDelayed(tick, 60000L / wpm)
        } else {
            try { recognizer?.cancel() } catch (_: Exception) {}
            stopVosk()
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

    // ---------- voz integrada (Vosk): la misma que la app web, no depende de Google ----------
    private var voskModel: org.vosk.Model? = null
    private var voskService: org.vosk.android.SpeechService? = null
    private var voskLoading = false
    private var finalBuf = ""

    private fun grammarJson(): String {
        val set = LinkedHashSet<String>()
        val inDir = follower.directions
        Regex("\\S+").findAll(follower.display).forEach { m ->
            if (inDir.any { m.range.first >= it.first && m.range.first <= it.last }) return@forEach
            val w = m.value.lowercase().replace(Regex("[^a-z0-9áéíóúüñ]"), "")
            if (w.isNotEmpty()) set.add(w)
        }
        val arr = org.json.JSONArray()
        set.forEach { arr.put(it) }
        listOf("texjahc", "tex", "jahc", "pausa", "sigue", "atras", "mas", "grande", "regresa", "precio", "fecha").forEach { arr.put(it) }
        arr.put("[unk]")
        return arr.toString()
    }

    private val voskListener = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val t = try { org.json.JSONObject(hypothesis ?: "{}").optString("partial") } catch (_: Exception) { "" }
            if (t.isNotBlank()) heardText((finalBuf + " " + t).trim())
        }
        override fun onResult(hypothesis: String?) {
            val t = try { org.json.JSONObject(hypothesis ?: "{}").optString("text") } catch (_: Exception) { "" }
            if (t.isNotBlank()) {
                finalBuf = (finalBuf + " " + t).trim().split(Regex("\\s+")).takeLast(12).joinToString(" ")
                heardText(finalBuf)
            }
        }
        override fun onFinalResult(hypothesis: String?) = onResult(hypothesis)
        override fun onError(exception: Exception?) {
            status.text = "Voz: " + (exception?.message ?: "error") + " · usa ⏱"
        }
        override fun onTimeout() {}
    }

    private fun startVosk() {
        val model = voskModel
        if (model == null) {
            if (voskLoading) return
            voskLoading = true
            status.text = "Preparando voz…"
            Thread {
                try {
                    val m = org.vosk.Model(ModelStore.dir(this).absolutePath)
                    h.post { voskModel = m; voskLoading = false; if (playing && voice) startVosk() }
                } catch (e: Throwable) {
                    h.post {
                        voskLoading = false
                        prefs.edit().putString("lastError", "Voz integrada: " + (e.message ?: e.javaClass.simpleName)).apply()
                        status.text = "No cargó la voz integrada (" + (e.message ?: e.javaClass.simpleName).take(40) + ")"
                        h.postDelayed({ if (playing && voice) startGoogle() }, 3000)
                    }
                }
            }.start()
            return
        }
        try {
            stopVosk()
            finalBuf = ""
            val rec = org.vosk.Recognizer(model, 16000f, grammarJson())
            val svc = org.vosk.android.SpeechService(rec, 16000f)
            svc.startListening(voskListener)
            voskService = svc
            watchMic()
            status.text = if (a11yWindow) "Escuchando… (accesibilidad)" else "Escuchando… (voz integrada)"
        } catch (e: Exception) {
            status.text = "El micrófono está ocupado: usa ⏱"
        }
    }

    private fun stopVosk() {
        try { voskService?.stop(); voskService?.shutdown() } catch (_: Exception) {}
        voskService = null
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Falta permiso de micrófono: Ajustes › TexJAHC › Permisos"
            return
        }
        if (ModelStore.ready(this)) startVosk() else {
            status.text = "Usando Google · descarga la voz integrada en la app"
            startGoogle()
        }
    }

    private fun startGoogle() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Sin voz de Google: usa ⏱ (automático)"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Falta permiso de micrófono (abre la app)"
            return
        }
        val r = recognizer ?: createRecognizer().also {
            it.setRecognitionListener(this); recognizer = it
        }
        try { r.startListening(recIntent) } catch (_: Exception) { restartSoon(600) }
    }

    /** Usa el reconocedor de Google si está instalado (algunos celulares traen otro que no funciona bien). */
    private fun createRecognizer(): SpeechRecognizer {
        val google = android.content.ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        )
        val hasGoogle = try { packageManager.getPackageInfo(google.packageName, 0); true } catch (_: Exception) { false }
        if (hasGoogle && prefs.getBoolean("useGoogle", true)) {
            try { return SpeechRecognizer.createSpeechRecognizer(this, google) } catch (_: Exception) {}
        }
        return SpeechRecognizer.createSpeechRecognizer(this)
    }

    private fun restartSoon(ms: Long) {
        if (!playing || !voice) return
        h.postDelayed({ if (playing && voice) startListening() }, ms)
    }

    private fun heard(b: Bundle?) {
        val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        heardText(t)
    }

    private fun heardText(t: String) {
        if (t.isBlank()) return
        errors = 0
        status.text = "🎙 " + t.trim().split(Regex("\\s+")).takeLast(2).joinToString(" ")
        if (processVoiceCommand(t)) return
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
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                status.text = "Falta permiso de micrófono: Ajustes › TexJAHC › Permisos"; setPlaying(false); return
            }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> status.text = "Sin internet para la voz"
            SpeechRecognizer.ERROR_AUDIO -> status.text = "Otra app está usando el micrófono: usa ⏱"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> status.text = "Escuchando… (habla)"
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER -> {
                // reconocedor trabado: lo recreamos
                try { recognizer?.destroy() } catch (_: Exception) {}
        stopVosk()
        try { voskModel?.close() } catch (_: Exception) {}
                recognizer = null
                status.text = "Reiniciando voz…"
            }
            12, 13 -> {   // idioma no disponible en este reconocedor: probamos el otro
                prefs.edit().putBoolean("useGoogle", !prefs.getBoolean("useGoogle", true)).apply()
                try { recognizer?.destroy() } catch (_: Exception) {}
                recognizer = null
                status.text = "Cambiando de reconocedor de voz…"
            }
            10 -> {   // Google dice "demasiadas solicitudes": esperamos antes de reintentar
                status.text = if (ModelStore.ready(this)) "Google saturado; reintentando…" else "Google saturado · descarga la voz integrada en la app"
                restartSoon(5000L); return
            }
            else -> status.text = "Voz Google: error $error, reintentando…"
        }
        if (errors >= 6 && error == SpeechRecognizer.ERROR_AUDIO) {
            status.text = "El micrófono lo ocupa otra app: usa ⏱"
        }
        restartSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 600L)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 29) {
            (recCb as? android.media.AudioManager.AudioRecordingCallback)?.let {
                try { getSystemService(android.media.AudioManager::class.java).unregisterAudioRecordingCallback(it) } catch (_: Exception) {}
            }
        }
        aiPanel?.close(); aiPanel = null
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
