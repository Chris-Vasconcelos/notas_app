package mx.spen.lab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val ActiveTile = Color(0xff23393c)
private val Mint = Color(0xff91e1d5)
private val SheetActions = RoundedCornerShape(12.dp)
private val FloatingBoxShape = RoundedCornerShape(22.dp)

data class PenPreset(
    val tool: ToolMode,
    val colorArgb: Int,
    val widthDp: Float
)

@Composable
internal fun TopEditorBar(
    title: String,
    zoomPercent: Int,
    readingMode: Boolean,
    onToggleReadingMode: () -> Unit,
    focusMode: Boolean,
    onToggleFocus: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    revealAllTapes: Boolean,
    onToggleRevealTapes: () -> Unit,
    onFitContent: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenPaper: () -> Unit,
    onOpenToolSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onExportPng: () -> Unit,
    canClear: Boolean,
    onClear: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 4.dp)
                .clickable(onClick = onFitContent)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (readingMode) "${zoomPercent}% · Modo lectura" else "${zoomPercent}% · Toca para encajar",
                style = MaterialTheme.typography.labelMedium,
                color = if (readingMode) Mint else InkTextSecondary
            )
        }
        HeaderButton("Deshacer", EditorGlyph.Undo, enabled = canUndo, action = onUndo)
        HeaderButton("Rehacer", EditorGlyph.Redo, enabled = canRedo, action = onRedo)
        HeaderButton(
            label = if (readingMode) "Pasar a modo escritura" else "Modo lectura (desplazar con 1 dedo)",
            glyph = EditorGlyph.Reading,
            active = readingMode,
            action = onToggleReadingMode
        )
        HeaderButton(
            if (focusMode) "Mostrar herramientas" else "Modo enfoque",
            EditorGlyph.Focus,
            active = focusMode,
            action = onToggleFocus
        )
        Box {
            var expanded by remember { mutableStateOf(false) }
            HeaderButton("Más opciones", EditorGlyph.Menu) { expanded = true }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Ajustar hoja a pantalla") },
                    onClick = {
                        expanded = false
                        onFitContent()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Papel y plantilla") },
                    onClick = {
                        expanded = false
                        onOpenPaper()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Ajustes de lápiz, página y gestos") },
                    onClick = {
                        expanded = false
                        onOpenToolSettings()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (revealAllTapes) "Cubrir cintas de estudio" else "Revelar cintas de estudio") },
                    onClick = {
                        expanded = false
                        onToggleRevealTapes()
                    }
                )
                HorizontalDivider(color = GraphiteBorder)
                DropdownMenuItem(
                    text = { Text("Diagnóstico S Pen (Lab)") },
                    onClick = {
                        expanded = false
                        onOpenDiagnostics()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Exportar PNG") },
                    onClick = {
                        expanded = false
                        onExportPng()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Limpiar hoja") },
                    enabled = canClear,
                    onClick = {
                        expanded = false
                        onClear()
                    }
                )
            }
        }
    }
}

@Composable
private fun HeaderButton(
    label: String,
    glyph: EditorGlyph,
    enabled: Boolean = true,
    active: Boolean = false,
    action: () -> Unit
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(SheetActions)
            .background(if (active) ActiveTile else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = action)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        DrawGlyph(glyph, if (active) Mint else InkTextPrimary.copy(alpha = if (enabled) 1f else .38f))
    }
}

/**
 * Notein-inspired movable floating pencil box ("笔盒"):
 * - Draggable across the entire canvas via the grip handle
 * - Automatically switches to Vertical orientation when docked near Left/Right edges, or Horizontal near Top/Bottom
 * - Collapses into a floating pen bubble when minimized or dragged to a corner
 * - Includes 1-tap favorite presets and quick thickness dots
 */
@Composable
internal fun MovablePenBox(
    containerSize: IntSize,
    activeTool: ToolMode,
    activeColor: Color,
    currentWidthDp: Float,
    autoShapeEnabled: Boolean,
    strokePanelOpen: Boolean,
    favorites: List<PenPreset>,
    onSelectTool: (ToolMode) -> Unit,
    onToggleStrokePanel: () -> Unit,
    onToggleAutoShape: () -> Unit,
    onQuickWidthSelect: (Float) -> Unit,
    onApplyFavorite: (PenPreset) -> Unit
) {
    val density = LocalDensity.current
    val defaultMarginPx = with(density) { 12.dp.toPx() }
    val defaultBottomOffsetPx = with(density) { 78.dp.toPx() }

    var offsetX by remember { mutableFloatStateOf(defaultMarginPx) }
    var offsetY by remember(containerSize.height) {
        mutableFloatStateOf(
            if (containerSize.height > 0) {
                (containerSize.height - defaultBottomOffsetPx).coerceAtLeast(defaultMarginPx)
            } else {
                300f
            }
        )
    }
    var isVertical by remember { mutableStateOf(false) }
    var isCollapsed by remember { mutableStateOf(false) }

    val maxW = containerSize.width.toFloat().coerceAtLeast(320f)
    val maxH = containerSize.height.toFloat().coerceAtLeast(480f)

    fun clampAndSnap( snapEdges: Boolean) {
        val boxW = with(density) {
            when {
                isCollapsed -> 54.dp.toPx()
                isVertical -> 68.dp.toPx()
                else -> 340.dp.toPx().coerceAtMost(maxW - 16f)
            }
        }
        val boxH = with(density) {
            when {
                isCollapsed -> 54.dp.toPx()
                isVertical -> 370.dp.toPx().coerceAtMost(maxH - 24f)
                else -> 76.dp.toPx()
            }
        }
        offsetX = offsetX.coerceIn(8f, (maxW - boxW - 8f).coerceAtLeast(8f))
        offsetY = offsetY.coerceIn(8f, (maxH - boxH - 8f).coerceAtLeast(8f))

        if (snapEdges && !isCollapsed) {
            val sideThreshold = with(density) { 44.dp.toPx() }
            val cornerThreshold = with(density) { 40.dp.toPx() }
            val nearLeft = offsetX <= sideThreshold
            val nearRight = offsetX >= maxW - boxW - sideThreshold
            val nearTop = offsetY <= cornerThreshold
            val nearBottom = offsetY >= maxH - boxH - cornerThreshold

            // Auto-switch orientation when dragged to left/right sides vs top/bottom
            if ((nearLeft || nearRight) && !nearTop && !nearBottom) {
                isVertical = true
                offsetX = if (nearLeft) 10f else (maxW - with(density) { 68.dp.toPx() } - 10f).coerceAtLeast(10f)
            } else if (nearTop || nearBottom) {
                isVertical = false
            }
        }
    }

    val dragModifier = Modifier.pointerInput(containerSize, isVertical, isCollapsed) {
        detectDragGestures(
            onDragEnd = { clampAndSnap(snapEdges = true) },
            onDragCancel = { clampAndSnap(snapEdges = true) }
        ) { change, dragAmount ->
            change.consume()
            offsetX += dragAmount.x
            offsetY += dragAmount.y
            clampAndSnap(snapEdges = false)
        }
    }

    Box(
        modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
    ) {
        if (isCollapsed) {
            // Compact floating pen bubble (draggable & expandable)
            Surface(
                color = GraphiteSurface.copy(alpha = 0.95f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, Mint.copy(alpha = 0.7f)),
                shadowElevation = 10.dp,
                modifier = dragModifier
                    .size(52.dp)
                    .clickable {
                        isCollapsed = false
                        clampAndSnap(snapEdges = false)
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    DrawGlyph(glyphForTool(activeTool), Mint, activeColor)
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(5.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(activeColor)
                            .border(1.dp, InkTextPrimary, CircleShape)
                    )
                }
            }
        } else if (isVertical) {
            // Vertical Floating Pencil Case (like Notein left/right dock)
            Surface(
                color = GraphiteSurface.copy(alpha = 0.96f),
                shape = FloatingBoxShape,
                border = BorderStroke(1.dp, GraphiteBorder),
                shadowElevation = 12.dp,
                modifier = Modifier.width(68.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Drag handle + orientation/collapse row
                    DragHandlePill(
                        isVertical = true,
                        dragModifier = dragModifier,
                        onRotate = {
                            isVertical = false
                            clampAndSnap(snapEdges = false)
                        },
                        onCollapse = { isCollapsed = true }
                    )

                    ToolMode.entries.forEach { tool ->
                        val selected = activeTool == tool
                        DockTile(
                            label = tool.label,
                            glyph = glyphForTool(tool),
                            active = selected,
                            interaction = Modifier
                                .fillMaxWidth()
                                .semantics { this.selected = selected }
                                .clickable(role = Role.Button) {
                                    if (selected && tool != ToolMode.LASSO && tool != ToolMode.LASER) {
                                        onToggleStrokePanel()
                                    } else {
                                        onSelectTool(tool)
                                    }
                                }
                        )
                    }

                    HorizontalDivider(color = GraphiteBorder, modifier = Modifier.padding(vertical = 2.dp))

                    // Auto-Shape toggle
                    DockTile(
                        label = "Formas",
                        glyph = EditorGlyph.Shapes,
                        active = autoShapeEnabled,
                        interaction = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Switch) { onToggleAutoShape() }
                    )

                    // Active Color & Width trigger
                    DockTile(
                        label = String.format(Locale.getDefault(), "%.0fdp", currentWidthDp),
                        glyph = EditorGlyph.Swatch,
                        active = strokePanelOpen,
                        interaction = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = activeTool != ToolMode.LASSO && activeTool != ToolMode.LASER,
                                role = Role.Button,
                                onClick = onToggleStrokePanel
                            ),
                        swatch = activeColor
                    )

                    // Quick 3 thickness dots (Fine / Medium / Bold)
                    QuickWidthDots(
                        isVertical = true,
                        activeTool = activeTool,
                        currentWidthDp = currentWidthDp,
                        onSelectWidth = onQuickWidthSelect
                    )

                    // Favorite presets
                    if (favorites.isNotEmpty()) {
                        HorizontalDivider(color = GraphiteBorder, modifier = Modifier.padding(vertical = 2.dp))
                        favorites.forEach { preset ->
                            FavoriteChip(preset = preset, onClick = { onApplyFavorite(preset) })
                        }
                    }
                }
            }
        } else {
            // Horizontal Floating Pencil Case
            Surface(
                color = GraphiteSurface.copy(alpha = 0.96f),
                shape = FloatingBoxShape,
                border = BorderStroke(1.dp, GraphiteBorder),
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DragHandlePill(
                        isVertical = false,
                        dragModifier = dragModifier,
                        onRotate = {
                            isVertical = true
                            clampAndSnap(snapEdges = false)
                        },
                        onCollapse = { isCollapsed = true }
                    )

                    ToolMode.entries.forEach { tool ->
                        val selected = activeTool == tool
                        DockTile(
                            label = tool.label,
                            glyph = glyphForTool(tool),
                            active = selected,
                            interaction = Modifier
                                .width(52.dp)
                                .semantics { this.selected = selected }
                                .clickable(role = Role.Button) {
                                    if (selected && tool != ToolMode.LASSO && tool != ToolMode.LASER) {
                                        onToggleStrokePanel()
                                    } else {
                                        onSelectTool(tool)
                                    }
                                }
                        )
                    }

                    // Auto-Shape toggle
                    DockTile(
                        label = "Formas",
                        glyph = EditorGlyph.Shapes,
                        active = autoShapeEnabled,
                        interaction = Modifier
                            .width(52.dp)
                            .clickable(role = Role.Switch) { onToggleAutoShape() }
                    )

                    // Color & Width Swatch
                    DockTile(
                        label = String.format(Locale.getDefault(), "%.0fdp", currentWidthDp),
                        glyph = EditorGlyph.Swatch,
                        active = strokePanelOpen,
                        interaction = Modifier
                            .width(52.dp)
                            .clickable(
                                enabled = activeTool != ToolMode.LASSO && activeTool != ToolMode.LASER,
                                role = Role.Button,
                                onClick = onToggleStrokePanel
                            ),
                        swatch = activeColor
                    )

                    // Quick 3 thickness dots
                    QuickWidthDots(
                        isVertical = false,
                        activeTool = activeTool,
                        currentWidthDp = currentWidthDp,
                        onSelectWidth = onQuickWidthSelect
                    )

                    // Favorite presets
                    favorites.forEach { preset ->
                        FavoriteChip(preset = preset, onClick = { onApplyFavorite(preset) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DragHandlePill(
    isVertical: Boolean,
    dragModifier: Modifier,
    onRotate: () -> Unit,
    onCollapse: () -> Unit
) {
    if (isVertical) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Drag grip area
            Box(
                modifier = dragModifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(GraphiteBg),
                contentAlignment = Alignment.Center
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(4) {
                        Box(
                            Modifier
                                .size(3.5.dp)
                                .clip(CircleShape)
                                .background(InkTextSecondary)
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onRotate),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⇄", style = MaterialTheme.typography.labelSmall, color = InkTextSecondary)
                }
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onCollapse),
                    contentAlignment = Alignment.Center
                ) {
                    Text("−", style = MaterialTheme.typography.labelMedium, color = Mint)
                }
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = dragModifier
                    .width(22.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(GraphiteBg),
                contentAlignment = Alignment.Center
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(4) {
                        Box(
                            Modifier
                                .size(3.5.dp)
                                .clip(CircleShape)
                                .background(InkTextSecondary)
                        )
                    }
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onRotate),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⇅", style = MaterialTheme.typography.labelSmall, color = InkTextSecondary)
                }
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onCollapse),
                    contentAlignment = Alignment.Center
                ) {
                    Text("−", style = MaterialTheme.typography.labelMedium, color = Mint)
                }
            }
        }
    }
}

@Composable
private fun QuickWidthDots(
    isVertical: Boolean,
    activeTool: ToolMode,
    currentWidthDp: Float,
    onSelectWidth: (Float) -> Unit
) {
    if (activeTool == ToolMode.LASSO || activeTool == ToolMode.LASER) return
    val presets = when (activeTool) {
        ToolMode.HIGHLIGHTER -> listOf(12f, 18f, 26f)
        ToolMode.TAPE -> listOf(16f, 22f, 30f)
        ToolMode.ERASER -> listOf(8f, 14f, 22f)
        else -> listOf(2.5f, 4.2f, 7.0f)
    }
    val dotSizes = listOf(6.dp, 9.dp, 12.dp)

    if (isVertical) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 2.dp)
        ) {
            presets.forEachIndexed { idx, w ->
                val isSelected = kotlin.math.abs(currentWidthDp - w) < 0.8f
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) ActiveTile else Color.Transparent)
                        .clickable { onSelectWidth(w) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(dotSizes[idx])
                            .clip(CircleShape)
                            .background(if (isSelected) Mint else InkTextSecondary)
                    )
                }
            }
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 2.dp)
        ) {
            presets.forEachIndexed { idx, w ->
                val isSelected = kotlin.math.abs(currentWidthDp - w) < 0.8f
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) ActiveTile else GraphiteBg)
                        .clickable { onSelectWidth(w) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(dotSizes[idx])
                            .clip(CircleShape)
                            .background(if (isSelected) Mint else InkTextSecondary)
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteChip(
    preset: PenPreset,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GraphiteBg)
            .border(1.dp, Color(preset.colorArgb), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        DrawGlyph(glyphForTool(preset.tool), Color(preset.colorArgb))
    }
}

@Composable
private fun DockTile(
    label: String,
    glyph: EditorGlyph,
    active: Boolean,
    interaction: Modifier,
    swatch: Color = Color.Transparent
) {
    Column(
        modifier = interaction.padding(horizontal = 1.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(width = 38.dp, height = 28.dp)
                .background(if (active) ActiveTile else Color.Transparent, SheetActions),
            contentAlignment = Alignment.Center
        ) {
            DrawGlyph(glyph, if (active) Mint else InkTextPrimary, swatch)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) Mint else InkTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun glyphForTool(tool: ToolMode): EditorGlyph = when (tool) {
    ToolMode.PEN -> EditorGlyph.Pen
    ToolMode.BALLPOINT -> EditorGlyph.Ballpoint
    ToolMode.DASHED -> EditorGlyph.Dashed
    ToolMode.HIGHLIGHTER -> EditorGlyph.Marker
    ToolMode.TAPE -> EditorGlyph.Tape
    ToolMode.LASER -> EditorGlyph.Laser
    ToolMode.ERASER -> EditorGlyph.Eraser
    ToolMode.LASSO -> EditorGlyph.Lasso
}

private enum class EditorGlyph {
    Pen, Ballpoint, Dashed, Marker, Tape, Laser, Eraser, Lasso, Shapes, Swatch, Undo, Redo, Reading, Focus, Menu
}

@Composable
private fun DrawGlyph(glyph: EditorGlyph, tint: Color, swatch: Color = Color.Transparent) {
    Canvas(Modifier.size(21.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val outline = Stroke(width = 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun lines(points: List<Offset>, close: Boolean = false) {
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                    if (close) close()
                }
                drawPath(path, tint, style = outline)
            }
            when (glyph) {
                EditorGlyph.Pen -> {
                    lines(listOf(Offset(4f, 20f), Offset(5f, 15f), Offset(16f, 4f), Offset(20f, 8f), Offset(9f, 19f)), close = true)
                    lines(listOf(Offset(13.5f, 6.5f), Offset(17.5f, 10.5f)))
                }
                EditorGlyph.Ballpoint -> {
                    lines(listOf(Offset(4f, 20f), Offset(7f, 14f), Offset(17f, 4f), Offset(20f, 7f), Offset(10f, 17f)), close = true)
                    drawCircle(tint, radius = 1.4f, center = Offset(4.5f, 19.5f))
                }
                EditorGlyph.Dashed -> {
                    lines(listOf(Offset(4f, 18f), Offset(8f, 14f)))
                    lines(listOf(Offset(10.5f, 11.5f), Offset(14.5f, 7.5f)))
                    lines(listOf(Offset(17f, 5f), Offset(20f, 2f)))
                }
                EditorGlyph.Marker -> {
                    lines(listOf(Offset(5f, 16f), Offset(15f, 5f), Offset(20f, 10f), Offset(10f, 20f)), close = true)
                    lines(listOf(Offset(8f, 13f), Offset(13f, 18f)))
                    lines(listOf(Offset(4f, 20f), Offset(6f, 20f)))
                }
                EditorGlyph.Tape -> {
                    drawRoundRect(tint, Offset(3f, 7f), Size(18f, 10f), CornerRadius(3f), style = outline)
                    lines(listOf(Offset(8f, 7f), Offset(6f, 17f)))
                    lines(listOf(Offset(16f, 7f), Offset(14f, 17f)))
                }
                EditorGlyph.Laser -> {
                    drawCircle(tint, radius = 3.2f, center = Offset(12f, 12f))
                    lines(listOf(Offset(12f, 3f), Offset(12f, 6f)))
                    lines(listOf(Offset(12f, 18f), Offset(12f, 21f)))
                    lines(listOf(Offset(3f, 12f), Offset(6f, 12f)))
                    lines(listOf(Offset(18f, 12f), Offset(21f, 12f)))
                }
                EditorGlyph.Eraser -> {
                    lines(listOf(Offset(3f, 15f), Offset(13f, 5f), Offset(20f, 12f), Offset(13f, 19f), Offset(7f, 19f)), close = true)
                    lines(listOf(Offset(8f, 10f), Offset(16f, 17f)))
                }
                EditorGlyph.Lasso -> {
                    drawOval(tint, Offset(3f, 5f), Size(17f, 12f), style = outline)
                    lines(listOf(Offset(13f, 17f), Offset(11f, 21f), Offset(7f, 20f)))
                }
                EditorGlyph.Shapes -> {
                    drawRoundRect(tint, Offset(4f, 10f), Size(10f, 10f), CornerRadius(2f), style = outline)
                    drawCircle(tint, radius = 4f, center = Offset(16f, 7.5f), style = outline)
                }
                EditorGlyph.Swatch -> {
                    drawCircle(swatch, 6.5f, Offset(12f, 12f))
                    drawCircle(tint, 8.5f, Offset(12f, 12f), style = outline)
                }
                EditorGlyph.Undo, EditorGlyph.Redo -> {
                    fun x(p: Float) = if (glyph == EditorGlyph.Redo) 24f - p else p
                    lines(listOf(Offset(x(9f), 5f), Offset(x(4f), 10f), Offset(x(9f), 15f)))
                    val curve = Path().apply {
                        moveTo(x(4f), 10f)
                        lineTo(x(14f), 10f)
                        cubicTo(x(18f), 10f, x(20f), 13f, x(20f), 18f)
                    }
                    drawPath(curve, tint, style = outline)
                }
                EditorGlyph.Reading -> {
                    drawOval(tint, Offset(3f, 7f), Size(18f, 10f), style = outline)
                    drawCircle(tint, radius = 2.4f, center = Offset(12f, 12f))
                }
                EditorGlyph.Focus -> {
                    lines(listOf(Offset(9f, 4f), Offset(4f, 4f), Offset(4f, 9f)))
                    lines(listOf(Offset(15f, 4f), Offset(20f, 4f), Offset(20f, 9f)))
                    lines(listOf(Offset(4f, 15f), Offset(4f, 20f), Offset(9f, 20f)))
                    lines(listOf(Offset(20f, 15f), Offset(20f, 20f), Offset(15f, 20f)))
                }
                EditorGlyph.Menu -> {
                    for (x in listOf(6f, 12f, 18f)) drawCircle(tint, 1.35f, Offset(x, 12f))
                }
            }
        }
    }
}
