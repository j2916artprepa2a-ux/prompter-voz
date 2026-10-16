package mx.prompter.voz

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Modo Accesibilidad (experimental): Android deja que un servicio de accesibilidad con su
 * ventana encima escuche el micrófono al mismo tiempo que la app que está grabando.
 * No lee la pantalla: solo sirve para mostrar el cuadrito como ventana de accesibilidad.
 */
class AccessService : AccessibilityService() {
    companion object {
        @Volatile var instance: AccessService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Si el cuadrito ya estaba abierto, se vuelve a crear como ventana de accesibilidad
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_RECREATE))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
}
