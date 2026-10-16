package mx.prompter.voz

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * La grabadora de TexJAHC (la misma de la página) dentro de la app:
 * cámara + voz palabra por palabra + clips, y los videos van directo a la Galería.
 */
class RecordActivity : Activity() {

    companion object { const val URL = "https://j2916artprepa2a-ux.github.io/prompter-voz/" }

    private lateinit var web: WebView
    private var pendingWebPerm: PermissionRequest? = null
    private val prefs by lazy { getSharedPreferences(Prefs.FILE, MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        setContentView(web)
        immersive()

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.userAgentString = s.userAgentString + " TexJAHCApp"
        web.addJavascriptInterface(Bridge(), "TexJAHCAndroid")
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val need = ArrayList<String>()
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA)
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
                    if (need.isEmpty()) request.grant(request.resources)
                    else { pendingWebPerm = request; requestPermissions(need.toTypedArray(), 21) }
                }
            }
        }
        web.loadUrl(URL)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val r = pendingWebPerm ?: return
        pendingWebPerm = null
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (ok) r.grant(r.resources) else {
            r.deny()
            Toast.makeText(this, "Necesito cámara y micrófono para grabar", Toast.LENGTH_LONG).show()
        }
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    /** Lo que la página puede pedirle a la app. */
    inner class Bridge {
        private var out: OutputStream? = null
        private var uri: Uri? = null
        private var legacyFile: File? = null

        @JavascriptInterface fun getScript(): String = prefs.getString(Prefs.SCRIPT, "") ?: ""
        @JavascriptInterface fun setScript(t: String) { prefs.edit().putString(Prefs.SCRIPT, t).apply() }

        /** Guardar el video en la Galería (en partes, porque puede ser grande). */
        @JavascriptInterface fun beginSave(name: String, mime: String): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, name)
                        put(MediaStore.Video.Media.MIME_TYPE, mime)
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/TexJAHC")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                    out = uri?.let { contentResolver.openOutputStream(it) }
                } else {
                    val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "TexJAHC").apply { mkdirs() }
                    legacyFile = File(dir, name)
                    out = FileOutputStream(legacyFile!!)
                }
                out != null
            } catch (e: Exception) { false }
        }

        @JavascriptInterface fun appendSave(b64: String): Boolean {
            return try { out?.write(Base64.decode(b64, Base64.DEFAULT)); true } catch (e: Exception) { false }
        }

        @JavascriptInterface fun endSave(): Boolean {
            return try {
                out?.close(); out = null
                if (Build.VERSION.SDK_INT >= 29) {
                    uri?.let { contentResolver.update(it, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) }
                } else {
                    legacyFile?.let { MediaScannerConnection.scanFile(this@RecordActivity, arrayOf(it.absolutePath), null, null) }
                }
                runOnUiThread { Toast.makeText(this@RecordActivity, "Video guardado en tu Galería (TexJAHC) ✓", Toast.LENGTH_LONG).show() }
                true
            } catch (e: Exception) { false }
        }

        @JavascriptInterface fun openFloating() {
            runOnUiThread {
                startActivity(android.content.Intent(this@RecordActivity, MainActivity::class.java))
                finish()
            }
        }
    }
}
