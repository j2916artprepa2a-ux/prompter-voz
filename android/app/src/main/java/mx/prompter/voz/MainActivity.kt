package mx.prompter.voz

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    const val DEFAULT_SCRIPT =
        "[Mira a la cámara y sonríe]\n" +
        "¿Sabías que puedes grabar tus videos sin aprenderte el guion? Hoy te enseño cómo.\n" +
        "[Muestra tu celular]\n" +
        "Este cuadrito flota encima de TikTok, Instagram o Facebook y me va siguiendo palabra por palabra.\n" +
        "[Señala hacia abajo]\n" +
        "Si te sirvió, sígueme para más tips. ¡Nos vemos!"
}

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences(Prefs.FILE, MODE_PRIVATE) }
    private lateinit var script: EditText
    private lateinit var modeVoice: Button
    private lateinit var modeAuto: Button
    private lateinit var wpmLabel: TextView
    private lateinit var fontLabel: TextView
    private var waitingOverlay = false
    private var askedPerms = false

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

        col.addView(label("¿Cómo avanza el texto?", 14f, true))
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeVoice = pill("🎙 Sigue mi voz", false) { setVoice(true) }
        modeAuto = pill("⏱ Automático", false) { setVoice(false) }
        modes.addView(modeVoice, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        modes.addView(modeAuto, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(modes)
        setVoice(prefs.getBoolean(Prefs.VOICE, true))
        col.addView(label(
            "Si grabas dentro de TikTok o Instagram y el texto no te sigue, es porque esa app ocupa el micrófono: usa ⏱ Automático.", 12f,
            color = 0x99FFFFFF.toInt()))

        wpmLabel = label("", 14f, true)
        col.addView(wpmLabel)
        col.addView(SeekBar(this).apply {
            max = 180; progress = prefs.getInt(Prefs.WPM, 140) - 60
            setOnSeekBarChangeListener(simple { v -> prefs.edit().putInt(Prefs.WPM, v + 60).apply(); updateLabels() })
        })
        fontLabel = label("", 14f, true)
        col.addView(fontLabel)
        col.addView(SeekBar(this).apply {
            max = 40; progress = prefs.getInt(Prefs.FONT, 22) - 12
            setOnSeekBarChangeListener(simple { v -> prefs.edit().putInt(Prefs.FONT, v + 12).apply(); updateLabels() })
        })
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

    private fun launch() {
        prefs.edit().putString(Prefs.SCRIPT, script.text.toString().ifBlank { Prefs.DEFAULT_SCRIPT }).apply()
        if (!Settings.canDrawOverlays(this)) {
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
        startForegroundService(i)
        Toast.makeText(this, "Listo: abre TikTok, Instagram o tu cámara", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) launch()
    }

    override fun onResume() {
        super.onResume()
        if (waitingOverlay && Settings.canDrawOverlays(this)) { waitingOverlay = false; launch() }
    }
}
