package mx.spen.lab

import android.graphics.Color

enum class ToolMode(val label: String) {
    PEN("Pluma"),
    BALLPOINT("Bolígrafo"),
    DASHED("Punteada"),
    HIGHLIGHTER("Resaltar"),
    TAPE("Cinta"),
    SHAPES("Figuras"),
    TEXT("Texto"),
    LASER("Láser"),
    ERASER("Borrar"),
    LASSO("Lazo")
}

enum class EraserMode(val label: String) {
    STROKE("Trazo completo"),
    PIXEL("Por píxel (parcial)"),
    LASSO_ERASE("Por área (lazo)")
}

enum class LassoMode(val label: String) {
    FREEHAND("Lazo libre"),
    RECTANGLE("Rectangular")
}

enum class DirectShapeKind(val code: Int, val label: String, val is3D: Boolean = false) {
    AUTO(0, "Auto (1 trazo)"),
    LINE(1, "Recta"),
    WAVY(2, "Ondulada"),
    ARROW(3, "Flecha"),
    AXES(4, "Ejes X-Y"),
    PARABOLA(5, "Parábola"),
    RECTANGLE(6, "Rectángulo"),
    TRIANGLE(7, "Triángulo"),
    DIAMOND(8, "Rombo"),
    PENTAGON(9, "Pentágono"),
    STAR(10, "Estrella"),
    ELLIPSE(11, "Círculo / Elipse"),
    CUBE(12, "Cubo 3D", is3D = true),
    CYLINDER(13, "Cilindro 3D", is3D = true),
    CONE(14, "Cono 3D", is3D = true),
    SPHERE(15, "Esfera 3D", is3D = true);

    companion object {
        fun fromCode(code: Int): DirectShapeKind =
            entries.firstOrNull { it.code == code } ?: AUTO
    }
}

/** Canvas layout mode (Sections 01, 03 & Page Settings). */
enum class CanvasLayoutMode(val label: String) {
    PAGED("Paginado A4"),
    INFINITE("Lienzo infinito")
}

data class NoteLayer(
    val id: Int,
    val name: String,
    val visible: Boolean = true,
    val locked: Boolean = false
)

/** Outline / bookmark item in the Right Sidebar (Section 17 · 大纲). */
data class OutlineEntry(
    val id: Long,
    val title: String,
    val pageIndex: Int,
    val worldX: Float,
    val worldY: Float,
    val indentLevel: Int = 0
)

/** Annotation / comment item in the Right Sidebar (Section 17 · 备注). */
data class CommentEntry(
    val id: Long,
    val text: String,
    val pageIndex: Int,
    val worldX: Float,
    val worldY: Float,
    val createdAtMs: Long = System.currentTimeMillis()
)

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

    fun scaled(anchorX: Float, anchorY: Float, factor: Float): StrokeData = copy(
        sizeWorld = (sizeWorld * factor).coerceIn(0.5f, 120f),
        points = points.map { pt ->
            pt.copy(
                x = anchorX + (pt.x - anchorX) * factor,
                y = anchorY + (pt.y - anchorY) * factor
            )
        }
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
        lineColor = Color.rgb(210, 222, 240),
        defaultInkColor = Color.rgb(24, 46, 80),
        isDark = false
    ),
    CREAM(
        label = "Crema",
        bgColor = Color.rgb(250, 246, 235),
        lineColor = Color.rgb(220, 210, 186),
        defaultInkColor = Color.rgb(34, 40, 52),
        isDark = false
    ),
    DARK(
        label = "Oscuro",
        bgColor = Color.rgb(22, 25, 31),
        lineColor = Color.rgb(48, 56, 70),
        defaultInkColor = Color.rgb(235, 240, 248),
        isDark = true
    )
}

enum class PaperTemplate(val label: String) {
    BLANK("Blanca"),
    GRID("Cuadrícula"),
    RULED("Rayada"),
    DOTTED("Punteada"),
    ISOMETRIC("Isométrica"),
    CORNELL("Cornell")
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
    // 1. Básicos (10 colors)
    val basicColors = listOf(
        InkSwatch("Grafito", 0xFF1A2230.toInt()),
        InkSwatch("Marino", 0xFF182E50.toInt()),
        InkSwatch("Petróleo", 0xFF1D7A8C.toInt()),
        InkSwatch("Azul", 0xFF1E58CC.toInt()),
        InkSwatch("Cian", 0xFF00A3C4.toInt()),
        InkSwatch("Esmeralda", 0xFF1E7A56.toInt()),
        InkSwatch("Ámbar", 0xFFC77818.toInt()),
        InkSwatch("Carmesí", 0xFFB9324B.toInt()),
        InkSwatch("Púrpura", 0xFF6C4AB6.toInt()),
        InkSwatch("Tiza", 0xFFECEFF5.toInt())
    )

    // 2. Morandi (10 colors)
    val morandiColors = listOf(
        InkSwatch("Pizarra", 0xFF4A586E.toInt()),
        InkSwatch("Salvia", 0xFF5E7E72.toInt()),
        InkSwatch("Terracota", 0xFFA46358.toInt()),
        InkSwatch("Malva", 0xFF7B6888.toInt()),
        InkSwatch("Ocre", 0xFFA6895B.toInt()),
        InkSwatch("Musgo", 0xFF6B7B5C.toInt()),
        InkSwatch("Acero", 0xFF567687.toInt()),
        InkSwatch("Rosa Ceniza", 0xFFB5838D.toInt()),
        InkSwatch("Taupe", 0xFF6D6875.toInt()),
        InkSwatch("Marfil", 0xFFE5E0D5.toInt())
    )

    // 3. Retro (10 colors)
    val retroColors = listOf(
        InkSwatch("Carbón", 0xFF2B2D42.toInt()),
        InkSwatch("Vino", 0xFF8D0801.toInt()),
        InkSwatch("Mostaza", 0xFFD4A373.toInt()),
        InkSwatch("Oliva", 0xFF606C38.toInt()),
        InkSwatch("Bosque", 0xFF283618.toInt()),
        InkSwatch("Cobre", 0xFFBC6C25.toInt()),
        InkSwatch("Prusia", 0xFF003049.toInt()),
        InkSwatch("Ladrillo", 0xFFC1121F.toInt()),
        InkSwatch("Aciano", 0xFF669BBC.toInt()),
        InkSwatch("Pergamino", 0xFFFDF0D5.toInt())
    )

    // 4. Macaron / Pastel (10 colors)
    val macaronColors = listOf(
        InkSwatch("Arándano", 0xFF5E60CE.toInt()),
        InkSwatch("Cielo", 0xFF4EA8DE.toInt()),
        InkSwatch("Agua", 0xFF56CFE1.toInt()),
        InkSwatch("Menta", 0xFF72EFDD.toInt()),
        InkSwatch("Limón", 0xFFF9C74F.toInt()),
        InkSwatch("Durazno", 0xFFF8961E.toInt()),
        InkSwatch("Fresa", 0xFFF94144.toInt()),
        InkSwatch("Chicle", 0xFFFF85A1.toInt()),
        InkSwatch("Lila", 0xFFB5179E.toInt()),
        InkSwatch("Nube", 0xFFF8F9FA.toInt())
    )

    // 5. Estética Oriental (10 colors)
    val orientalColors = listOf(
        InkSwatch("Tinta China", 0xFF1F2421.toInt()),
        InkSwatch("Cinabrio", 0xFF9E2A2B.toInt()),
        InkSwatch("Jade", 0xFF335C67.toInt()),
        InkSwatch("Bambú", 0xFF52796F.toInt()),
        InkSwatch("Oro Viejo", 0xFFE09F3E.toInt()),
        InkSwatch("Burdeos", 0xFF540B0E.toInt()),
        InkSwatch("Porcelana", 0xFF5C80BC.toInt()),
        InkSwatch("Ciruela", 0xFF6D597A.toInt()),
        InkSwatch("Té Verde", 0xFF84A98C.toInt()),
        InkSwatch("Arroz", 0xFFFFF3B0.toInt())
    )

    // 6. Intensos (10 colors)
    val vividColors = listOf(
        InkSwatch("Negro", 0xFF0D1017.toInt()),
        InkSwatch("Azul Real", 0xFF0052FF.toInt()),
        InkSwatch("Rojo Vivo", 0xFFE52535.toInt()),
        InkSwatch("Cian Neón", 0xFF00B4D8.toInt()),
        InkSwatch("Verde Vivo", 0xFF0E9F6E.toInt()),
        InkSwatch("Violeta", 0xFF7E3AF2.toInt()),
        InkSwatch("Naranja", 0xFFFF5A1F.toInt()),
        InkSwatch("Magenta", 0xFFD90368.toInt()),
        InkSwatch("Lima", 0xFF70E000.toInt()),
        InkSwatch("Blanco", 0xFFFFFFFF.toInt())
    )

    val colorSets = listOf(
        InkColorSet("Clásicos", basicColors),
        InkColorSet("Morandi", morandiColors),
        InkColorSet("Retro", retroColors),
        InkColorSet("Macaron", macaronColors),
        InkColorSet("Oriental", orientalColors),
        InkColorSet("Intensos", vividColors)
    )

    val penColors: List<InkSwatch> get() = basicColors

    val highlighterColors = listOf(
        InkSwatch("Amarillo", 0x66F5D142),
        InkSwatch("Cian", 0x552AA1B7),
        InkSwatch("Verde", 0x5538B87C),
        InkSwatch("Coral", 0x55E05A73),
        InkSwatch("Lavanda", 0x559B72E8),
        InkSwatch("Durazno", 0x55F8961E),
        InkSwatch("Rosa", 0x55FF85A1),
        InkSwatch("Menta", 0x554CC9F0)
    )

    val tapeColors = listOf(
        InkSwatch("Menta", 0xFF2B9085.toInt()),
        InkSwatch("Ámbar", 0xFFD9822B.toInt()),
        InkSwatch("Coral", 0xFFD6536D.toInt()),
        InkSwatch("Índigo", 0xFF5C6AC4.toInt()),
        InkSwatch("Pizarra", 0xFF4A586E.toInt())
    )
}

data class PenTelemetry(
    val pressure: Float = 0f,
    val tiltDegrees: Int = 0,
    val sideButtonPressed: Boolean = false,
    val fingerContacts: Int = 0,
    val cancellations: Int = 0,
    val zoomPercent: Int = 100,
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val lastShapeDetected: String? = null
)
