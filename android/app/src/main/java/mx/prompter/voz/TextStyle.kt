package mx.prompter.voz

import android.content.SharedPreferences
import android.graphics.Typeface

/** Color y tipo de letra del cuadrito (los mismos que la app web). No toca el seguimiento de voz. */
object TextStyle {
    const val COLOR = "textColor"
    const val FONT_FACE = "fontFace"

    data class Col(val key: String, val name: String, val argb: Int, val highlight: Int)
    data class Face(val key: String, val name: String, val family: String, val style: Int)

    private const val YELLOW = 0xFFFFD34D.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    val colors = listOf(
        Col("white", "Blanco", WHITE, YELLOW),
        Col("yellow", "Amarillo", YELLOW, WHITE),
        Col("green", "Verde", 0xFF78E68C.toInt(), YELLOW),
        Col("cyan", "Azul", 0xFF6ECDFF.toInt(), YELLOW),
        Col("pink", "Rosa", 0xFFFF8CC8.toInt(), YELLOW),
        Col("orange", "Naranja", 0xFFFFAA5A.toInt(), WHITE),
    )

    val faces = listOf(
        Face("normal", "Normal", "sans-serif-medium", Typeface.NORMAL),
        Face("bold", "Negrita", "sans-serif", Typeface.BOLD),
        Face("rounded", "Redondeada", "casual", Typeface.NORMAL),
        Face("serif", "Clásica", "serif", Typeface.NORMAL),
        Face("narrow", "Angosta", "sans-serif-condensed", Typeface.NORMAL),
        Face("mono", "Máquina", "monospace", Typeface.NORMAL),
    )

    fun color(p: SharedPreferences): Col = colors.firstOrNull { it.key == p.getString(COLOR, "white") } ?: colors[0]
    fun face(p: SharedPreferences): Face = faces.firstOrNull { it.key == p.getString(FONT_FACE, "normal") } ?: faces[0]
    fun typeface(f: Face): Typeface = Typeface.create(f.family, f.style)

    /** El mismo color pero tenue, para lo que ya dijiste. */
    fun dim(argb: Int): Int = (argb and 0x00FFFFFF) or 0x55000000
}
