package mx.prompter.voz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Panel de IA independiente del seguimiento del guion. No contiene credenciales de servidor. */
class OverlayAiPanel(
    private val context: Context,
    private val onClose: () -> Unit
) {
    companion object {
        private const val ENDPOINT = "https://veznsmmyylsqrodqzcul.supabase.co/functions/v1/chat"
    }

    private val history = JSONArray()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var busy = false
    private var alive = true
    private val code = EditText(context)
    private val question = EditText(context)
    private val answer = TextView(context)
    private val state = TextView(context)
    private val mic = Button(context)
    private val speak = CheckBox(context)
    val view: LinearLayout = LinearLayout(context)

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setPadding(12, 8, 12, 8)
        view.setBackgroundColor(0xFF202025.toInt())

        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(context).apply {
            text = "🤖 Pregúntale a TexJAHC"; setTextColor(Color.WHITE); textSize = 17f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Button(context).apply { text = "↩ Guion"; setOnClickListener { onClose() } })
        view.addView(head)

        code.hint = "Código de acceso a tu IA (no es clave API)"
        code.setSingleLine(true)
        code.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        code.setTextColor(Color.WHITE); code.setHintTextColor(0xFFAAAAAA.toInt())
        view.addView(code)

        question.hint = "Escribe tu pregunta o activa el micrófono"
        question.minLines = 2; question.maxLines = 3
        question.setTextColor(Color.WHITE); question.setHintTextColor(0xFFAAAAAA.toInt())
        view.addView(question)

        val options = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        mic.text = "🎙 Activar micrófono IA"
        mic.setOnClickListener { if (listening) stopListening() else startListening() }
        options.addView(mic)
        speak.text = "🔊 Activar voz de IA (apagada por defecto)"
        speak.setTextColor(Color.WHITE)
        speak.isChecked = false
        speak.setOnCheckedChangeListener { _, checked -> if (!checked) tts?.stop() }
        options.addView(speak)
        options.addView(Button(context).apply { text = "Preguntar a la IA"; setOnClickListener { ask() } })
        view.addView(options)

        state.setTextColor(0xFFFFD34D.toInt()); state.textSize = 13f
        state.text = "El micrófono está apagado. Puedes preguntar escribiendo."
        view.addView(state)
        answer.setTextColor(Color.WHITE); answer.setTextIsSelectable(true)
        answer.textSize = 23f; answer.typeface = Typeface.DEFAULT_BOLD
        val scroll = ScrollView(context).apply { addView(answer) }
        view.addView(scroll, LinearLayout.LayoutParams(-1, (context.resources.displayMetrics.density * 180).toInt()))
        val sizes = LinearLayout(context).apply { gravity = Gravity.END }
        sizes.addView(Button(context).apply { text = "A−"; setOnClickListener { answer.textSize = (answer.textSize / context.resources.displayMetrics.scaledDensity - 2f).coerceAtLeast(14f) } })
        sizes.addView(Button(context).apply { text = "A+"; setOnClickListener { answer.textSize = (answer.textSize / context.resources.displayMetrics.scaledDensity + 2f).coerceAtMost(44f) } })
        view.addView(sizes)
    }

    private fun stopListening() {
        listening = false
        mic.text = "🎙 Activar micrófono IA"
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun startListening() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state.text = "Falta permiso de micrófono: habilítalo en Ajustes de Android."
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            state.text = "Reconocimiento de voz no disponible en este teléfono. Puedes escribir."
            return
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { r ->
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        stopListening()
                        if (result.isNotBlank()) { question.setText(result); state.text = "Pregunta reconocida. Pulsa Preguntar a la IA." }
                    }
                    override fun onError(error: Int) { stopListening(); state.text = "No se pudo reconocer tu voz ($error). Intenta otra vez o escribe." }
                    override fun onReadyForSpeech(params: Bundle?) { state.text = "Escuchando tu pregunta…" }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
                })
            }
            listening = true; mic.text = "⏹ Detener micrófono IA"
        } catch (e: Exception) { stopListening(); state.text = "No fue posible activar el micrófono: ${e.message}" }
    }

    private fun ask() {
        val q = question.text.toString().trim()
        val access = code.text.toString().trim()
        if (q.isBlank()) { state.text = "Escribe una pregunta primero."; return }
        if (access.isBlank()) { state.text = "Introduce tu código de acceso a TexJAHC."; return }
        if (busy) return
        stopListening()
        busy = true; state.text = "Consultando IA…"; answer.text = ""
        val sent = JSONArray().also { a ->
            for (i in 0 until history.length()) a.put(history.getJSONObject(i))
            a.put(JSONObject().put("role", "user").put("content", q))
        }
        Thread {
            var result = ""
            var sources = ""
            var error: String? = null
            try {
                val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000; readTimeout = 45000
                    setRequestProperty("content-type", "application/json")
                    setRequestProperty("x-texjahc-code", access)
                    doOutput = true
                }
                try {
                    val payload = JSONObject().put("messages", sent).put("search", true).put("maxTokens", 1500)
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    val success = connection.responseCode in 200..299
                    val raw = (if (success) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    val json = JSONObject(raw.ifBlank { "{}" })
                    if (!success) throw IllegalStateException(json.optString("error", "Error HTTP ${connection.responseCode}"))
                    result = json.optString("text", "")
                    if (result.isBlank()) throw IllegalStateException("La IA no devolvió una respuesta.")
                    val refs = json.optJSONArray("sources") ?: JSONArray()
                    sources = (0 until minOf(refs.length(), 6)).mapNotNull { i ->
                        val o = refs.optJSONObject(i) ?: return@mapNotNull null
                        o.optString("url").takeIf { it.startsWith("https://") }?.let { "• ${o.optString("title", "Fuente")}: $it" }
                    }.joinToString("\n")
                } finally { connection.disconnect() }
            } catch (e: Exception) { error = e.message ?: "No fue posible consultar la IA. Revisa tu conexión." }
            val output = result; val citations = sources; val failure = error
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!alive) return@post
                busy = false
                if (failure != null) { state.text = "Error: $failure"; return@post }
                history.put(JSONObject().put("role", "user").put("content", q))
                history.put(JSONObject().put("role", "assistant").put("content", output))
                answer.text = if (citations.isEmpty()) output else "$output\n\nFuentes:\n$citations"
                state.text = "Respuesta recibida."
                if (speak.isChecked) readAloud(output)
            }
        }.start()
    }

    private fun readAloud(text: String) {
        if (tts == null) {
            tts = TextToSpeech(context) { ready ->
                if (ready == TextToSpeech.SUCCESS && alive && speak.isChecked) {
                    tts?.language = Locale.forLanguageTag("es-MX")
                    tts?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "texjahc-ai")
                } else if (ready != TextToSpeech.SUCCESS) state.text = "No está disponible la voz del sistema. La respuesta sigue escrita."
            }
        } else {
            tts?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "texjahc-ai")
        }
    }

    fun close() {
        alive = false
        stopListening()
        tts?.stop(); tts?.shutdown(); tts = null
        code.setText("") // Evita mantener el código en el campo al cerrar.
    }
}
