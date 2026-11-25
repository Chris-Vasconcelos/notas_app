package mx.spen.lab

import android.graphics.Color

enum class ToolMode(val label: String) {
    PEN("Pluma"),
    BALLPOINT("Bolígrafo"),
    DASHED("Punteada"),
    HIGHLIGHTER("Resaltar"),
    TAPE("Cinta"),
    LASER("Láser"),
    ERASER("Borrar"),
    LASSO("Lazo")
}

/** Stable on-disk brush codes. Never renumber existing codes (1=PEN, 2=PENCIL, 3=HIGHLIGHTER, 4=BALLPOINT, 5=DASHED, 6=TAPE). */
enum class BrushType(val code: Int) {
    PEN(1),
    PENCIL(2),
    HIGHLIGHTER(3),
    BALLPOINT(4),
    DASHED(5),
    TAPE(6);

    companion object {
        fun fromCode(code: Int): BrushType = entries.firstOrNull { it.code == code } ?: PEN
    }
}

data class StrokePoint(
    val x: Float,
    val y: Float,
    val elapsedTimeMillis: Long,
    val pressure: Float,
    val tiltRadians: Float,
    val orientationRadians: Float,
    val strokeUnitLengthCm: Float
)

data class StrokeData(
    val brushType: BrushType,
    val color: Int,
    val sizeWorld: Float,
    val epsilonWorld: Float,
    val isHighlighter: Boolean,
    val toolTypeInt: Int,
    val noiseSeed: Int,
    val points: List<StrokePoint>
) {
    fun translated(dx: Float, dy: Float): StrokeData = copy(
        points = points.map { pt -> pt.copy(x = pt.x + dx, y = pt.y + dy) }
    )
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

data class InkColorSet(
    val title: String,
    val swatches: List<InkSwatch>
)

object EditorPalettes {
    val basicColors = listOf(
        InkSwatch("Grafito", 0xFF1A2230.toInt()),
        InkSwatch("Marino", 0xFF182E50.toInt()),
        InkSwatch("Petróleo", 0xFF1D7A8C.toInt()),
        InkSwatch("Azul", 0xFF1E58CC.toInt()),
        InkSwatch("Carmesí", 0xFFB9324B.toInt()),
        InkSwatch("Esmeralda", 0xFF1E7A56.toInt()),
        InkSwatch("Ámbar", 0xFFC77818.toInt()),
        InkSwatch("Tiza", 0xFFECEFF5.toInt())
    )

    val morandiColors = listOf(
        InkSwatch("Pizarra", 0xFF4A586E.toInt()),
        InkSwatch("Salvia", 0xFF5E7E72.toInt()),
        InkSwatch("Terracota", 0xFFA46358.toInt()),
        InkSwatch("Malva", 0xFF7B6888.toInt()),
        InkSwatch("Ocre", 0xFFA6895B.toInt()),
        InkSwatch("Musgo", 0xFF6B7B5C.toInt()),
        InkSwatch("Acero", 0xFF567687.toInt()),
        InkSwatch("Marfil", 0xFFE5E0D5.toInt())
    )

    val vividColors = listOf(
        InkSwatch("Negro", 0xFF0D1017.toInt()),
        InkSwatch("Azul Real", 0xFF0052FF.toInt()),
        InkSwatch("Rojo Vivo", 0xFFE52535.toInt()),
        InkSwatch("Cian", 0xFF00A3C4.toInt()),
        InkSwatch("Verde", 0xFF0E9F6E.toInt()),
        InkSwatch("Violeta", 0xFF7E3AF2.toInt()),
        InkSwatch("Naranja", 0xFFFF5A1F.toInt()),
        InkSwatch("Blanco", 0xFFFFFFFF.toInt())
    )

    val colorSets = listOf(
        InkColorSet("Clásicos", basicColors),
        InkColorSet("Morandi", morandiColors),
        InkColorSet("Intensos", vividColors)
    )

    val penColors: List<InkSwatch> get() = basicColors

    val highlighterColors = listOf(
        InkSwatch("Amarillo", 0x66F5D142),
        InkSwatch("Cian", 0x552AA1B7),
        InkSwatch("Verde", 0x5538B87C),
        InkSwatch("Coral", 0x55E05A73),
        InkSwatch("Lavanda", 0x559B72E8)
    )

    val tapeColors = listOf(
        InkSwatch("Menta", 0xFF2B9085.toInt()),
        InkSwatch("Ámbar", 0xFFD9822B.toInt()),
        InkSwatch("Coral", 0xFFD6536D.toInt()),
        InkSwatch("Índigo", 0xFF5C6AC4.toInt())
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
