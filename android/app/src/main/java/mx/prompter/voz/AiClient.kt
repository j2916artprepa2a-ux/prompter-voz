package mx.prompter.voz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Tu IA favorita con tu propia clave (Claude, ChatGPT, Gemini o compatible con OpenAI).
 * La configuración la guarda la pantalla de IA de la app (asistente.html › 🔑) y se queda
 * solo en este teléfono. La clave se manda directo a tu IA, sin pasar por servidores de TexJAHC.
 * El modo «servidor de TexJAHC con código de acceso» sigue en OverlayAiPanel como siempre.
 */
object AiClient {
    const val PREF = "aiConfig"

    data class Config(val prov: String, val key: String, val model: String, val base: String, val code: String) {
        val own get() = prov != "server"
        val ready get() = if (own) key.isNotBlank() && (prov != "compat" || (base.isNotBlank() && model.isNotBlank())) else code.isNotBlank()
        val name get() = when (prov) { "claude" -> "Claude"; "openai" -> "ChatGPT"; "gemini" -> "Gemini"; "compat" -> "Tu IA"; else -> "TexJAHC" }
    }

    private val DEFAULT_MODEL = mapOf("claude" to "claude-sonnet-5-5", "openai" to "gpt-6.1-sol", "gemini" to "gemini-3.8-flash")

    private const val SYSTEM = "Eres el asistente de TexJAHC, una app de teleprompter para creadores de contenido en México.\n" +
        "Responde en español de México, claro, útil y completo, en tono cercano.\n" +
        "Usa texto plano: sin markdown, sin asteriscos ni almohadillas (puedes usar guiones para listas).\n" +
        "Si usas información de internet, básate en las fuentes que encontraste."

    fun config(ctx: Context): Config? {
        val raw = ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).getString(PREF, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val prov = o.optString("prov").ifBlank { return null }
            Config(prov, o.optString("key"), o.optString("model").ifBlank { DEFAULT_MODEL[prov] ?: "" },
                o.optString("base").trimEnd('/'), o.optString("code"))
        } catch (_: Exception) { null }
    }

    class Answer(val text: String, val sources: List<Pair<String, String>>)   // (título, url)

    /** Llama a la IA elegida. Corre en un hilo aparte (hace red). Lanza IllegalStateException con mensaje claro. */
    fun ask(c: Config, messages: JSONArray, search: Boolean, maxTokens: Int = 1500): Answer {
        val sources = ArrayList<Pair<String, String>>()
        fun addSrc(url: String, title: String) { if (url.startsWith("https://") && sources.none { it.second == url }) sources.add(title.ifBlank { url } to url) }
        when (c.prov) {
            "claude" -> {
                val body = JSONObject().put("model", c.model).put("max_tokens", maxTokens).put("system", SYSTEM).put("messages", messages)
                if (search) body.put("tools", JSONArray().put(JSONObject().put("type", "web_search_20260318").put("name", "web_search").put("max_uses", 5)))
                val j = post(c.name, "https://api.anthropic.com/v1/messages", body,
                    mapOf("x-api-key" to c.key, "anthropic-version" to "2023-06-01"))
                val content = j.optJSONArray("content") ?: JSONArray()
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val b = content.optJSONObject(i) ?: continue
                    if (b.optString("type") == "text") sb.append(b.optString("text"))
                    val cits = b.optJSONArray("citations") ?: continue
                    for (k in 0 until cits.length()) cits.optJSONObject(k)?.let { addSrc(it.optString("url"), it.optString("title")) }
                }
                return Answer(sb.toString().trim(), sources)
            }
            "openai" -> {
                val input = JSONArray()
                for (i in 0 until messages.length()) messages.optJSONObject(i)?.let { input.put(JSONObject().put("role", it.optString("role")).put("content", it.optString("content"))) }
                val body = JSONObject().put("model", c.model).put("instructions", SYSTEM).put("max_output_tokens", maxTokens).put("input", input)
                if (search) body.put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
                val j = post(c.name, "https://api.openai.com/v1/responses", body, mapOf("authorization" to "Bearer ${c.key}"))
                val sb = StringBuilder()
                val out = j.optJSONArray("output") ?: JSONArray()
                for (i in 0 until out.length()) {
                    val o = out.optJSONObject(i) ?: continue
                    if (o.optString("type") != "message") continue
                    val parts = o.optJSONArray("content") ?: continue
                    for (k in 0 until parts.length()) {
                        val p = parts.optJSONObject(k) ?: continue
                        if (p.optString("type") != "output_text") continue
                        sb.append(p.optString("text"))
                        val ann = p.optJSONArray("annotations") ?: continue
                        for (a in 0 until ann.length()) ann.optJSONObject(a)?.let { addSrc(it.optString("url"), it.optString("title")) }
                    }
                }
                return Answer(sb.toString().trim(), sources)
            }
            "gemini" -> {
                val contents = JSONArray()
                for (i in 0 until messages.length()) messages.optJSONObject(i)?.let {
                    contents.put(JSONObject().put("role", if (it.optString("role") == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", it.optString("content")))))
                }
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                    .put("contents", contents)
                    .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
                if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                val url = "https://generativelanguage.googleapis.com/v1beta/models/" + URLEncoder.encode(c.model, "UTF-8") + ":generateContent"
                val j = post(c.name, url, body, mapOf("x-goog-api-key" to c.key))
                val cand = j.optJSONArray("candidates")?.optJSONObject(0) ?: JSONObject()
                val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
                val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks") ?: JSONArray()
                for (i in 0 until chunks.length()) chunks.optJSONObject(i)?.optJSONObject("web")?.let { addSrc(it.optString("uri"), it.optString("title")) }
                return Answer(sb.toString().trim(), sources)
            }
            else -> {   // compatible con OpenAI (Chat Completions)
                val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM))
                for (i in 0 until messages.length()) msgs.put(messages.getJSONObject(i))
                val body = JSONObject().put("model", c.model).put("max_tokens", maxTokens).put("messages", msgs)
                val j = post(c.name, c.base + "/chat/completions", body, mapOf("authorization" to "Bearer ${c.key}"))
                val text = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                return Answer(text.trim(), sources)
            }
        }
    }

    private fun post(name: String, url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        if (!url.startsWith("https://")) throw IllegalStateException("La dirección de la API debe empezar con https://")
        val conn = try { URL(url).openConnection() as HttpURLConnection } catch (_: Exception) { throw IllegalStateException("La dirección de la API no es válida.") }
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 12000; conn.readTimeout = 60000; conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try { conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            catch (_: Exception) { throw IllegalStateException("No me pude conectar con $name. Revisa tu internet.") }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val j = try { JSONObject(raw.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
            if (code !in 200..299) {
                val err = j.opt("error")
                val msg = (if (err is JSONObject) err.optString("message") else err?.toString()).orEmpty().ifBlank { j.optString("message") }
                throw IllegalStateException(friendly(name, code, msg))
            }
            return j
        } finally { conn.disconnect() }
    }

    private fun friendly(name: String, code: Int, msg: String): String {
        val m = msg.lowercase()
        return when {
            code == 401 || code == 403 || Regex("invalid.*(key|api)|api key not valid|incorrect api key").containsMatchIn(m) ->
                "Tu clave de $name no es válida. Revísala en 🔑 Elegir mi IA."
            Regex("credit|balance|quota|billing|insufficient").containsMatchIn(m) -> "Tu cuenta de $name no tiene saldo o llegó a su límite."
            code == 429 -> "Demasiadas preguntas seguidas a $name. Espera unos segundos."
            code == 404 || (m.contains("model") && Regex("not found|does not exist|unknown").containsMatchIn(m)) ->
                "Ese modelo no existe en $name. Deja «Modelo» vacío o escribe otro."
            else -> "$name no pudo responder: " + msg.ifBlank { "error $code" }
        }
    }
}
