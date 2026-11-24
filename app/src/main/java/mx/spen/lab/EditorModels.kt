package mx.spen.lab

import android.graphics.Color

enum class ToolMode(val label: String) {
    PEN("Pluma"),
    HIGHLIGHTER("Resaltador"),
    ERASER("Borrador"),
    LASSO("Lazo")
}

enum class PaperStyle(
    val label: String,
    val bgColor: Int,
    val lineColor: Int,
    val defaultInkColor: Int,
    val isDark: Boolean
) {
    LIGHT(
        label = "Claro",
        bgColor = Color.rgb(252, 253, 255),
        lineColor = Color.rgb(220, 229, 244),
        defaultInkColor = Color.rgb(24, 46, 80),
        isDark = false
    ),
    CREAM(
        label = "Crema",
        bgColor = Color.rgb(250, 246, 235),
        lineColor = Color.rgb(229, 220, 198),
        defaultInkColor = Color.rgb(34, 40, 52),
        isDark = false
    ),
    DARK(
        label = "Oscuro",
        bgColor = Color.rgb(22, 25, 31),
        lineColor = Color.rgb(42, 48, 60),
        defaultInkColor = Color.rgb(235, 240, 248),
        isDark = true
    )
}

enum class PaperTemplate(val label: String) {
    BLANK("Blanca"),
    GRID("Cuadrícula"),
    RULED("Rayada"),
    DOTTED("Punteada"),
    ISOMETRIC("Isométrica")
}

data class InkSwatch(
    val name: String,
    val argb: Int
)

object EditorPalettes {
    val penColors = listOf(
        InkSwatch("Grafito", 0xFF1A2230.toInt()),
        InkSwatch("Marino", 0xFF182E50.toInt()),
        InkSwatch("Petróleo", 0xFF1D7A8C.toInt()),
        InkSwatch("Azul", 0xFF1E58CC.toInt()),
        InkSwatch("Carmesí", 0xFFB9324B.toInt()),
        InkSwatch("Esmeralda", 0xFF1E7A56.toInt()),
        InkSwatch("Ámbar", 0xFFC77818.toInt()),
        InkSwatch("Tiza", 0xFFECEFF5.toInt())
    )

    val highlighterColors = listOf(
        InkSwatch("Amarillo", 0x66F5D142),
        InkSwatch("Cian", 0x552AA1B7),
        InkSwatch("Verde", 0x5538B87C),
        InkSwatch("Coral", 0x55E05A73),
        InkSwatch("Lavanda", 0x559B72E8)
    )
}

data class PenTelemetry(
    val pressure: Float = 0f,
    val tiltDegrees: Int = 0,
    val sideButtonPressed: Boolean = false,
    val fingerContacts: Int = 0,
    val cancellations: Int = 0,
    val zoomPercent: Int = 100,
    val lastShapeDetected: String? = null
)
