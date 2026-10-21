package mx.prompter.voz

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.HorizontalScrollView
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

object Prefs {
    const val FILE = "prompter"
    const val SCRIPT = "script"
    const val VOICE = "voice"
    const val WPM = "wpm"
    const val FONT = "font"
    const val LINES = "lines"
    const val LANG = "lang"
    const val SECURE = "secure"
    const val X = "x"
    const val Y = "y"
    const val WIDTH = "width"
    // Sin guion precargado: el editor empieza vacío (los guiones que ya tengas guardados se respetan)
    const val DEFAULT_SCRIPT = ""
}

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences(Prefs.FILE, MODE_PRIVATE) }
    private lateinit var script: EditText
    private lateinit var modeVoice: Button
    private lateinit var modeAuto: Button
    private lateinit var wpmLabel: TextView
    private lateinit var fontLabel: TextView
    private lateinit var errorView: TextView
    private lateinit var voiceInfo: TextView
    private lateinit var voiceBtn: Button
    private var launchAfterDownload = false
    private lateinit var a11yInfo: TextView
    private lateinit var a11yBtn: Button
    private var waitingOverlay = false
    private var askedPerms = false
    private lateinit var setupBox: LinearLayout
    private lateinit var setupTitle: TextView
    private var wizard = false
    private var lastWizardStep = ""
    private lateinit var preview: TextView
    private val swatchViews = ArrayList<Pair<String, View>>()
    private val faceButtons = ArrayList<Pair<String, Button>>()

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun label(t: String, size: Float = 15f, bold: Boolean = false, color: Int = 0xCCFFFFFF.toInt()) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(6))
        }

    private fun pill(t: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 16f
        setTextColor(if (primary) 0xFF1A1400.toInt() else Color.WHITE)
        background = GradientDrawable().apply {
            setColor(if (primary) 0xFFFFD34D.toInt() else 0xFF26262A.toInt())
            cornerRadius = dp(26).toFloat()
        }
        setPadding(dp(18), dp(12), dp(18), dp(12))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        takeSharedText(intent)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        col.addView(label("TexJAHC", 26f, true, Color.WHITE))
        col.addView(label(
            "Tu cuadrito de texto flota encima de TikTok, Instagram, Facebook o la cámara. " +
            "Esas apps graban tu cámara, no tu pantalla: el cuadrito solo lo ves tú.", 14f))
        buildSetup(col)
        // Probar cámara, micrófono y una grabación corta (abre la guía de la app de grabación)
        col.addView(pill("🔍  Probar cámara, micrófono y grabación", false) {
            prefs.edit().putString(Prefs.SCRIPT, script.text.toString()).apply()
            startActivity(Intent(this, RecordActivity::class.java).putExtra(RecordActivity.EXTRA_PAGE, "#setup"))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        col.addView(pill("🎥  Grabar video aquí (te sigue la voz)", true) {
            prefs.edit().putString(Prefs.SCRIPT, script.text.toString().ifBlank { Prefs.DEFAULT_SCRIPT }).apply()
            startActivity(Intent(this, RecordActivity::class.java))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        col.addView(pill("▶  Abrir cuadrito flotante (para TikTok, Insta, FB)", false) { launch() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        col.addView(pill("💬  Chat con IA (preguntar e investigar)", false) {
            prefs.edit().putString(Prefs.SCRIPT, script.text.toString().ifBlank { Prefs.DEFAULT_SCRIPT }).apply()
            startActivity(Intent(this, RecordActivity::class.java).putExtra(RecordActivity.EXTRA_PAGE, "asistente.html#chat"))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        errorView = TextView(this).apply {
            setTextColor(0xFFFF8A80.toInt()); textSize = 13f; setPadding(0, dp(8), 0, 0)
            text = prefs.getString("lastError", "").orEmpty().let { if (it.isEmpty()) "" else "⚠️ $it" }
        }
        col.addView(errorView)

        col.addView(label("Tu guion  ·  [entre corchetes] = indicaciones que no se dicen", 14f, true))
        script = EditText(this).apply {
            setText(prefs.getString(Prefs.SCRIPT, Prefs.DEFAULT_SCRIPT))
            setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF)
            hint = "Pega o escribe lo que vas a decir…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 8; gravity = Gravity.TOP or Gravity.START; textSize = 16f
            background = GradientDrawable().apply { setColor(0xFF141416.toInt()); cornerRadius = dp(14).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        col.addView(script)
        // Eliminar el guion guardado (con confirmación). Solo borra el texto de este cel.
        col.addView(pill("🗑️  Eliminar guion guardado", false) {
            AlertDialog.Builder(this)
                .setTitle("¿Eliminar tu guion guardado?")
                .setMessage("Se borra de este celular y no se puede recuperar. Tus videos no se tocan.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Sí, eliminar") { _, _ ->
                    prefs.edit().remove(Prefs.SCRIPT).apply()
                    script.setText("")
                    if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_RELOAD))
                    Toast.makeText(this, "Guion eliminado", Toast.LENGTH_SHORT).show()
                }
                .show()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        col.addView(label("¿Cómo avanza el texto?", 14f, true))
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeVoice = pill("🎙 Sigue mi voz", false) { setVoice(true) }
        modeAuto = pill("⏱ Automático", false) { setVoice(false) }
        modes.addView(modeVoice, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        modes.addView(modeAuto, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(modes)
        setVoice(prefs.getBoolean(Prefs.VOICE, true))
        voiceInfo = label("", 13f, color = 0xCCFFFFFF.toInt())
        col.addView(voiceInfo)
        voiceBtn = pill("⬇  Descargar voz para seguirte mejor (≈40 MB, una vez)", false) { downloadVoice(false) }
        col.addView(voiceBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        updateVoiceInfo()

        col.addView(label("🎥 Que te siga la voz mientras grabas o haces live (experimental)", 14f, true))
        a11yInfo = label("", 13f, color = 0xCCFFFFFF.toInt())
        col.addView(a11yInfo)
        a11yBtn = pill("Activar en Accesibilidad", false) {
            Toast.makeText(this, "Busca TexJAHC en la lista y actívalo", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        col.addView(a11yBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        updateA11yInfo()
        if (BuildConfig.FLAVOR != "live") {
            a11yInfo.text = "Esta es la versión normal. Para intentar que te siga la voz mientras grabas en otra app, " +
                "puedes probar la versión Live (experimental). Usa Accesibilidad, por eso Play Protect puede bloquear su instalación: " +
                "si pasa, sigue con esta versión normal. No apagues Play Protect."
            a11yBtn.text = "Descargar versión Live (experimental)"
            a11yBtn.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://j2916artprepa2a-ux.github.io/prompter-voz/descargas/TexJAHC-live.apk")))
            }
        }
        col.addView(label(
            "Si grabas dentro de TikTok o Instagram y el texto no te sigue, es porque esa app ocupa el micrófono: usa ⏱ Automático.", 12f,
            color = 0x99FFFFFF.toInt()))

        wpmLabel = label("", 14f, true)
        col.addView(wpmLabel)
        col.addView(SeekBar(this).apply {
            max = 180; progress = prefs.getInt(Prefs.WPM, 140) - 60
            setOnSeekBarChangeListener(simple { v -> prefs.edit().putInt(Prefs.WPM, v + 60).apply(); updateLabels() })
        })
        buildStyle(col)
        updateLabels()

        col.addView(CheckBox(this).apply {
            text = "Ocultarlo también en grabaciones de PANTALLA (ahí se vería negro)"
            setTextColor(0xCCFFFFFF.toInt()); textSize = 13f
            isChecked = prefs.getBoolean(Prefs.SECURE, false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(Prefs.SECURE, c).apply() }
            setPadding(0, dp(14), 0, dp(8))
        })

        col.addView(pill("▶  Abrir cuadrito flotante", true) { launch() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        // Regresa el cuadrito a su lugar y tamaño de inicio
        col.addView(pill("↺  Restaurar posición y tamaño del cuadrito", false) {
            prefs.edit().remove(Prefs.X).remove(Prefs.Y).remove(Prefs.WIDTH).putInt(Prefs.LINES, 4).apply()
            if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_RECREATE))
            Toast.makeText(this, "Cuadrito en su lugar de inicio", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        col.addView(pill("✕  Cerrar cuadrito", false) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        col.addView(label(
            "Cómo usarlo:\n" +
            "1. Toca “Abrir cuadrito flotante” y da los permisos.\n" +
            "2. Abre TikTok, Instagram, Facebook o tu cámara.\n" +
            "3. Arrastra el cuadrito con ✥ a donde quieras (cerca de la cámara se ve natural).\n" +
            "4. Toca ▶ y habla. 🎙/⏱ cambia el modo, A− A+ el tamaño, ↺ vuelve al inicio.\n" +
            "Tip: desde la app web usa “Compartir” y elige TexJAHC para mandar tu guion aquí.",
            13f, color = 0x99FFFFFF.toInt()))

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
    }

    private fun simple(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) onChange(v) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    // ---------- 🎨 estilo del texto: color, tipo de letra y tamaño ----------
    private fun buildStyle(col: LinearLayout) {
        col.addView(label("🎨 Estilo del texto", 16f, true, Color.WHITE))
        preview = TextView(this).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12)); setLineSpacing(0f, 1.12f)
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF2A2A2E.toInt()) }
        }
        col.addView(preview)
        fontLabel = label("", 14f, true)
        col.addView(fontLabel)
        col.addView(SeekBar(this).apply {
            max = 40; progress = prefs.getInt(Prefs.FONT, 22) - 12
            setOnSeekBarChangeListener(simple { v -> prefs.edit().putInt(Prefs.FONT, v + 12).apply(); updateLabels(); styleChanged() })
        })
        col.addView(label("Color del texto", 14f, true))
        val sw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in TextStyle.colors) {
            val v = View(this).apply {
                contentDescription = c.name
                setOnClickListener { prefs.edit().putString(TextStyle.COLOR, c.key).apply(); styleChanged() }
            }
            swatchViews.add(c.key to v)
            sw.addView(v, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) })
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sw) })
        col.addView(label("Tipo de letra", 14f, true))
        val fr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (f in TextStyle.faces) {
            val b = pill(f.name, false) { prefs.edit().putString(TextStyle.FONT_FACE, f.key).apply(); styleChanged() }
            b.typeface = TextStyle.typeface(f); b.textSize = 15f
            faceButtons.add(f.key to b)
            fr.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(fr) })
        col.addView(pill("Como estaba", false) {
            prefs.edit().putString(TextStyle.COLOR, "white").putString(TextStyle.FONT_FACE, "normal").putInt(Prefs.FONT, 22).apply()
            recreate()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        paintStyle()
    }

    private fun paintStyle() {
        val c = TextStyle.color(prefs); val f = TextStyle.face(prefs)
        val t = "Así se ve lo que ya dijiste, esta es la palabra que sigue y lo demás de tu guion."
        val sp = SpannableString(t)
        val cur = t.indexOf("esta")
        sp.setSpan(ForegroundColorSpan(TextStyle.dim(c.argb)), 0, cur, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(ForegroundColorSpan(c.highlight), cur, cur + 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(UnderlineSpan(), cur, cur + 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        preview.text = sp
        preview.setTextColor(c.argb)
        preview.typeface = TextStyle.typeface(f)
        preview.textSize = prefs.getInt(Prefs.FONT, 22).toFloat().coerceAtMost(34f)
        for ((k, v) in swatchViews) {
            val col = TextStyle.colors.first { it.key == k }
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(col.argb)
                if (k == c.key) setStroke(dp(4), Color.WHITE) else setStroke(dp(1), 0x33FFFFFF)
            }
        }
        for ((k, b) in faceButtons) {
            val on = k == f.key
            (b.background as GradientDrawable).setColor(if (on) 0xFFFFD34D.toInt() else 0xFF26262A.toInt())
            b.setTextColor(if (on) 0xFF1A1400.toInt() else Color.WHITE)
        }
    }

    /** Aplica el estilo al instante, también al cuadrito si ya está abierto (sin perder dónde ibas). */
    private fun styleChanged() {
        paintStyle()
        if (OverlayService.running) {
            try { startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STYLE)) } catch (_: Exception) {}
        }
    }

    // ---------- ✅ configuración guiada: todo lo que antes había que activar a mano ----------
    private data class Step(val title: String, val hint: String, val done: () -> Boolean, val fix: () -> Unit)

    private fun steps(): List<Step> {
        val list = ArrayList<Step>()
        list.add(Step("Mostrar sobre otras apps", "Para que el cuadrito flote encima de TikTok, Insta o FB.",
            { Settings.canDrawOverlays(this) }) {
            Toast.makeText(this, "Activa TexJAHC y regresa con ◀", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        list.add(Step("Micrófono", "Para seguir tu voz palabra por palabra.",
            { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) || !prefs.getBoolean("askedMic", false)) {
                prefs.edit().putBoolean("askedMic", true).apply()
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 8)
            } else openAppInfo("Permisos › Micrófono › Permitir")
        })
        list.add(Step("Cámara", "Para grabar tu video dentro de TexJAHC con el texto encima.",
            { checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) || !prefs.getBoolean("askedCam", false)) {
                prefs.edit().putBoolean("askedCam", true).apply()
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 8)
            } else openAppInfo("Permisos › Cámara › Permitir")
        })
        if (Build.VERSION.SDK_INT >= 33) list.add(Step("Notificaciones", "Android la pide para que el cuadrito no se cierre solo.",
            { checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }) {
            if (!prefs.getBoolean("askedNotif", false)) {
                prefs.edit().putBoolean("askedNotif", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
            } else openAppInfo("Notificaciones › Permitir")
        })
        list.add(Step("Voz integrada (≈40 MB, una vez)", "Te sigue sin internet. Mejor con WiFi.",
            { ModelStore.ready(this) }) { downloadVoice(false) })
        list.add(Step("Sin límite de batería", "Para que el cel no cierre el cuadrito a media grabación.",
            { (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) }) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { openAppInfo("Batería › Sin restricciones") }
            }
        })
        if (BuildConfig.FLAVOR == "live") list.add(Step("Accesibilidad (versión Live)",
            "Si sale “Ajuste restringido”: en la siguiente pantalla toca ⋮ › “Permitir ajustes restringidos”, regresa y toca otra vez.",
            { a11yEnabled() }) {
            if (!prefs.getBoolean("a11yTried", false) && Build.VERSION.SDK_INT >= 33) {
                prefs.edit().putBoolean("a11yTried", true).apply()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Busca TexJAHC › Activar. Si está gris, regresa y vuelve a tocar.", Toast.LENGTH_LONG).show()
            } else if (Build.VERSION.SDK_INT >= 33 && prefs.getBoolean("a11yTried", false) && !prefs.getBoolean("restrictedShown", false)) {
                prefs.edit().putBoolean("restrictedShown", true).apply()
                openAppInfo("⋮ (arriba a la derecha) › Permitir ajustes restringidos")
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Busca TexJAHC › Activar", Toast.LENGTH_LONG).show()
            }
        })
        return list
    }

    private fun openAppInfo(where: String) {
        Toast.makeText(this, "Ahí entra a: $where", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun buildSetup(col: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(0xFF141416.toInt()); cornerRadius = dp(16).toFloat() }
        }
        setupTitle = label("", 15f, true, Color.WHITE)
        card.addView(setupTitle)
        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(setupBox)
        card.addView(pill("⚡  Configurar todo paso a paso", true) { wizard = true; nextStep() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        col.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(6) })
        refreshSetup()
    }

    private fun refreshSetup() {
        if (!::setupBox.isInitialized) return
        val st = steps()
        val ok = st.count { it.done() }
        setupTitle.text = if (ok == st.size) "✅ Tu cel ya está listo ($ok de ${st.size})" else "⚙️ Prepara tu cel: $ok de ${st.size} listos"
        setupBox.removeAllViews()
        for (s in st) {
            val done = s.done()
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val txt = TextView(this).apply {
                text = (if (done) "✓  " else "○  ") + s.title + if (done) "" else "\n" + s.hint
                setTextColor(if (done) 0xFF8BE59B.toInt() else 0xDDFFFFFF.toInt()); textSize = 13.5f
            }
            row.addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!done) row.addView(pill("Activar", false) { s.fix() }.apply { textSize = 13f; setPadding(dp(14), dp(6), dp(14), dp(6)) })
            setupBox.addView(row)
        }
    }

    /** En modo “Configurar todo”: al regresar de cada ajuste, abre el siguiente que falte. */
    private fun nextStep() {
        refreshSetup()
        val pending = steps().firstOrNull { !it.done() }
        if (pending == null) {
            if (wizard) Toast.makeText(this, "¡Listo! Tu cel ya está configurado ✓", Toast.LENGTH_LONG).show()
            wizard = false; lastWizardStep = ""; return
        }
        // si regresaste sin activarlo, no te lo vuelvo a abrir en bucle
        if (pending.title == lastWizardStep) {
            wizard = false; lastWizardStep = ""
            Toast.makeText(this, "Falta: ${pending.title}. Toca su botón “Activar” cuando quieras.", Toast.LENGTH_LONG).show()
            return
        }
        lastWizardStep = pending.title
        pending.fix()
    }

    private fun updateLabels() {
        wpmLabel.text = "Velocidad en automático: ${prefs.getInt(Prefs.WPM, 140)} palabras por minuto"
        fontLabel.text = "Tamaño de letra: ${prefs.getInt(Prefs.FONT, 22)}"
    }

    private fun setVoice(v: Boolean) {
        prefs.edit().putBoolean(Prefs.VOICE, v).apply()
        (modeVoice.background as GradientDrawable).setColor(if (v) 0xFFFFD34D.toInt() else 0xFF26262A.toInt())
        modeVoice.setTextColor(if (v) 0xFF1A1400.toInt() else Color.WHITE)
        (modeAuto.background as GradientDrawable).setColor(if (!v) 0xFFFFD34D.toInt() else 0xFF26262A.toInt())
        modeAuto.setTextColor(if (!v) 0xFF1A1400.toInt() else Color.WHITE)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null && takeSharedText(intent)) script.setText(prefs.getString(Prefs.SCRIPT, ""))
    }

    /** Guion recibido con “Compartir” desde otra app (por ejemplo la app web). */
    private fun takeSharedText(i: Intent?): Boolean {
        if (i?.action != Intent.ACTION_SEND) return false
        val t = i.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (t.isEmpty()) return false
        prefs.edit().putString(Prefs.SCRIPT, t).apply()
        Toast.makeText(this, "Guion recibido ✓", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun a11yEnabled(): Boolean {
        if (AccessService.instance != null) return true
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.contains(packageName + "/")
    }

    private fun updateA11yInfo() {
        if (BuildConfig.FLAVOR != "live") return
        if (a11yEnabled()) {
            a11yInfo.text = "✓ Modo accesibilidad activo. Abre el cuadrito, toca ▶ y empieza a grabar o tu live. " +
                "Si abajo dice “Android silenció el micrófono”, tu cel no lo permite: usa ⏱ Automático."
            a11yBtn.text = "Ajustes de Accesibilidad"
        } else {
            a11yInfo.text = "Android solo deja escuchar mientras otra app graba a las apps de Accesibilidad. " +
                "Actívalo: Ajustes › Accesibilidad › Apps instaladas (o Servicios) › TexJAHC › Activar."
            a11yBtn.text = "Activar en Accesibilidad"
        }
    }

    private fun updateVoiceInfo() {
        if (ModelStore.ready(this)) {
            voiceInfo.text = "✓ Voz integrada lista: te sigue palabra por palabra, sin internet."
            voiceBtn.visibility = android.view.View.GONE
        } else if (!ModelStore.downloading) {
            voiceInfo.text = "Para que 🎙 te siga palabra por palabra, descarga la voz integrada (mejor con WiFi)."
            voiceBtn.visibility = android.view.View.VISIBLE
        }
    }

    private fun downloadVoice(thenLaunch: Boolean) {
        if (ModelStore.downloading) return
        launchAfterDownload = thenLaunch
        voiceBtn.isEnabled = false
        voiceInfo.text = "Descargando voz… 0%"
        Thread {
            val err = ModelStore.download(applicationContext) { p -> runOnUiThread { voiceInfo.text = "Descargando voz… $p%" } }
            runOnUiThread {
                voiceBtn.isEnabled = true
                if (err != null) {
                    voiceInfo.text = "⚠️ No se pudo descargar la voz: $err. Revisa tu internet e intenta otra vez."
                } else {
                    updateVoiceInfo()
                    refreshSetup()
                    Toast.makeText(this, "Voz lista ✓", Toast.LENGTH_SHORT).show()
                    if (wizard) { lastWizardStep = ""; nextStep() }
                    if (launchAfterDownload) launch()
                }
            }
        }.start()
    }

    private fun launch() {
        prefs.edit().putString(Prefs.SCRIPT, script.text.toString().ifBlank { Prefs.DEFAULT_SCRIPT }).apply()
        // En modo voz, primero baja la voz integrada (una sola vez)
        if (prefs.getBoolean(Prefs.VOICE, true) && !ModelStore.ready(this)) {
            if (!ModelStore.downloading) downloadVoice(true)
            Toast.makeText(this, "Primero descargo la voz (una sola vez)…", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this) && AccessService.instance == null) {
            waitingOverlay = true
            Toast.makeText(this, "Activa “Mostrar sobre otras apps” para TexJAHC y regresa", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty() && !askedPerms) {
            askedPerms = true
            requestPermissions(need.toTypedArray(), 7)
            return
        }
        val i = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_RELOAD)
        try { startForegroundService(i) } catch (e: Exception) {
            prefs.edit().putString("lastError", "Inicio: " + e.javaClass.simpleName + " " + (e.message ?: "")).apply()
        }
        // Esperamos a que aparezca antes de salir (si salimos al instante, Android bloquea el micrófono)
        window.decorView.postDelayed({
            val err = prefs.getString("lastError", "").orEmpty()
            if (err.isNotEmpty()) {
                errorView.text = "⚠️ $err"
                Toast.makeText(this, "Algo falló, revisa el mensaje rojo", Toast.LENGTH_LONG).show()
            } else if (OverlayService.running) {
                Toast.makeText(this, "Listo ✓ Ahora abre TikTok, Instagram o tu cámara", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else {
                errorView.text = "⚠️ El cuadrito no apareció. Revisa que TexJAHC tenga permiso de “Mostrar sobre otras apps” (y en Xiaomi/Redmi también “Mostrar ventanas emergentes”)."
            }
        }, 1500)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) launch()
        if (requestCode == 8) { refreshSetup(); if (wizard) window.decorView.postDelayed({ nextStep() }, 400) }
    }

    override fun onResume() {
        super.onResume()
        if (::a11yInfo.isInitialized) updateA11yInfo()
        refreshSetup()
        if (wizard && !waitingOverlay) window.decorView.postDelayed({ if (wizard) nextStep() }, 500)
        if (::script.isInitialized) {
            val t = prefs.getString(Prefs.SCRIPT, null)
            if (t != null && t != script.text.toString()) script.setText(t)
        }
        if (waitingOverlay && Settings.canDrawOverlays(this)) { waitingOverlay = false; launch() }
    }
}
