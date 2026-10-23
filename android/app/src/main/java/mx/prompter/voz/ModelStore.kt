package mx.prompter.voz

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Voz integrada (Vosk): el modelo en español se baja UNA vez (~40 MB) de tu propia página
 * y se queda en el cel. Después funciona sin internet y sin el reconocedor de Google.
 */
object ModelStore {
    val URL_MODEL: String = BuildConfig.WEB_URL + "models/es.tar.gz"   // sale de TEXJAHC_WEB_URL al compilar

    fun dir(c: Context) = File(c.filesDir, "vosk-es")
    fun ready(c: Context) = File(dir(c), ".ok").exists()

    @Volatile var downloading = false

    /** Descarga y descomprime. Bloquea: llamar desde un hilo. Devuelve null si salió bien, o el error. */
    fun download(c: Context, onProgress: (Int) -> Unit): String? {
        if (downloading) return "Ya se está descargando"
        downloading = true
        val tmp = File(c.filesDir, "vosk-es.tmp")
        try {
            tmp.deleteRecursively(); tmp.mkdirs()
            val conn = URL(URL_MODEL).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000; conn.readTimeout = 30000
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.connect()
            if (conn.responseCode != 200) return "No se pudo descargar (${conn.responseCode})"
            val total = conn.contentLengthLong
            var read = 0L
            var lastPct = -1
            val counting = object : FilterInputStream(conn.inputStream) {
                private fun bump(n: Int) {
                    if (n <= 0) return
                    read += n
                    if (total > 0) {
                        val p = (read * 100 / total).toInt()
                        if (p != lastPct) { lastPct = p; onProgress(p) }
                    }
                }
                override fun read(): Int { val b = super.read(); if (b >= 0) bump(1); return b }
                override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); bump(n); return n }
            }
            untar(GZIPInputStream(counting.buffered(65536), 65536), tmp)
            conn.disconnect()
            val d = dir(c)
            d.deleteRecursively()
            if (!tmp.renameTo(d)) return "No se pudo guardar la voz"
            File(d, ".ok").writeText("ok")
            onProgress(100)
            return null
        } catch (e: Exception) {
            return (e.message ?: e.javaClass.simpleName)
        } finally {
            downloading = false
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    /** Lector de .tar sencillo; quita la primera carpeta ("model/"). */
    private fun untar(input: InputStream, dest: File) {
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it.toInt() == 0 }) break
            var name = longName ?: cString(header, 0, 100)
            longName = null
            val prefix = cString(header, 345, 155)
            if (prefix.isNotEmpty() && !name.startsWith(prefix)) name = "$prefix/$name"
            val size = cString(header, 124, 12).trim().ifEmpty { "0" }.toLong(8)
            val type = header[156].toInt().toChar()
            val padded = ((size + 511) / 512) * 512
            if (type == 'L') {                       // nombre largo (GNU)
                val buf = ByteArray(padded.toInt()); readFully(input, buf)
                longName = String(buf, 0, size.toInt()).trimEnd('\u0000')
                continue
            }
            val rel = name.substringAfter('/', "")   // quita "model/"
            val safe = rel.isNotEmpty() && !rel.contains("..")
            if ((type == '0' || type == '\u0000') && safe) {
                val f = File(dest, rel); f.parentFile?.mkdirs()
                FileOutputStream(f).use { out -> copyN(input, out, size) }
                skip(input, padded - size)
            } else {
                if (type == '5' && safe) File(dest, rel).mkdirs()
                skip(input, padded)
            }
        }
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var got = 0
        while (got < buf.size) {
            val n = input.read(buf, got, buf.size - got)
            if (n < 0) return got > 0 && got == buf.size
            got += n
        }
        return true
    }

    private fun copyN(input: InputStream, out: FileOutputStream, n: Long) {
        val buf = ByteArray(65536)
        var left = n
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw java.io.EOFException("Archivo incompleto")
            out.write(buf, 0, r); left -= r
        }
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) return
            left -= r
        }
    }
}
