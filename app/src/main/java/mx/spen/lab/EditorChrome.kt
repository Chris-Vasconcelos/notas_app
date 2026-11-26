package mx.spen.lab

import android.graphics.RectF
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
import androidx.compose.material3.TextButton
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
import java.util.Locale
import kotlin.math.max
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
    currentPage: Int,
    totalPages: Int,
    layoutMode: CanvasLayoutMode,
    activeLayerName: String,
    leftDrawerOpen: Boolean,
    onToggleLeftDrawer: () -> Unit,
    onOpenLibrary: () -> Unit,
    onInsertImage: () -> Unit,
    rulerEnabled: Boolean,
    onToggleRuler: () -> Unit,
    minimapOpen: Boolean,
    onToggleMinimap: () -> Unit,
    gridViewOpen: Boolean,
    onToggleGridView: () -> Unit,
    rightSidebarOpen: Boolean,
    onToggleRightSidebar: () -> Unit,
    onOpenLayers: () -> Unit,
    readingMode: Boolean,
    onToggleReadingMode: () -> Unit,
    focusMode: Boolean,
    onToggleFocus: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    revealAllTapes: Boolean,
    onToggleRevealTapes: () -> Unit,
    fixedToolbarMode: Boolean,
    onToggleFixedToolbarMode: () -> Unit,
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
            .padding(start = 6.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderButton("Biblioteca y carpetas", EditorGlyph.Back, action = onOpenLibrary)
        HeaderButton("Cajón de documentos y pantalla dividida", EditorGlyph.LeftDrawer, active = leftDrawerOpen, action = onToggleLeftDrawer)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
                .clickable(onClick = onFitContent)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val pageBadge = if (layoutMode == CanvasLayoutMode.PAGED) "Pág $currentPage/$totalPages" else "Infinito"
            Text(
                if (readingMode) "${zoomPercent}% · $pageBadge · Lectura · $activeLayerName"
                else "${zoomPercent}% · $pageBadge · $activeLayerName",
                style = MaterialTheme.typography.labelSmall,
                color = if (readingMode) Mint else InkTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        HeaderButton("Cuadrícula de páginas", EditorGlyph.Grid, active = gridViewOpen, action = onToggleGridView)
        HeaderButton("Deshacer", EditorGlyph.Undo, enabled = canUndo, action = onUndo)
        HeaderButton("Rehacer", EditorGlyph.Redo, enabled = canRedo, action = onRedo)
        HeaderButton("Insertar imagen", EditorGlyph.Image, action = onInsertImage)
        HeaderButton("Regla virtual", EditorGlyph.Ruler, active = rulerEnabled, action = onToggleRuler)
        HeaderButton("Capas", EditorGlyph.Layers, action = onOpenLayers)
        HeaderButton("Minimapa de lienzo infinito", EditorGlyph.Minimap, active = minimapOpen, action = onToggleMinimap)
        HeaderButton("Panel lateral (Páginas / Esquema / Notas)", EditorGlyph.Sidebar, active = rightSidebarOpen, action = onToggleRightSidebar)
        HeaderButton(
            label = if (readingMode) "Pasar a modo escritura" else "Modo lectura",
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
                    text = { Text("Biblioteca de cuadernos y carpetas") },
                    onClick = {
                        expanded = false
                        onOpenLibrary()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Insertar imagen en la hoja") },
                    onClick = {
                        expanded = false
                        onInsertImage()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Ajustar hoja a pantalla (Doble toque)") },
                    onClick = {
                        expanded = false
                        onFitContent()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Papel, cuadrícula y opacidad") },
                    onClick = {
                        expanded = false
                        onOpenPaper()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Capas del documento") },
                    onClick = {
                        expanded = false
                        onOpenLayers()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (fixedToolbarMode) "Cambiar a estuche flotante movible"
                            else "Cambiar a barra fija tradicional"
                        )
                    },
                    onClick = {
                        expanded = false
                        onToggleFixedToolbarMode()
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
            .size(40.dp)
            .clip(SheetActions)
            .background(if (active) ActiveTile else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = action)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        DrawGlyph(glyph, if (active) Mint else InkTextPrimary.copy(alpha = if (enabled) 1f else .38f))
    }
}

/** Traditional Fixed Toolbar option (Section 14 · 常规工具栏). */
@Composable
internal fun FixedTopToolBar(
    activeTool: ToolMode,
    activeColor: Color,
    currentWidthDp: Float,
    strokePanelOpen: Boolean,
    favorites: List<PenPreset>,
    onSelectTool: (ToolMode) -> Unit,
    onToggleStrokePanel: () -> Unit,
    onQuickWidthSelect: (Float) -> Unit,
    onApplyFavorite: (PenPreset) -> Unit
) {
    Surface(
        color = GraphiteSurface,
        border = BorderStroke(0.5.dp, GraphiteBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ToolMode.entries.forEach { tool ->
                val selected = activeTool == tool
                DockTile(
                    label = tool.label,
                    glyph = glyphForTool(tool),
                    active = selected,
                    interaction = Modifier
                        .width(50.dp)
                        .semantics { this.selected = selected }
                        .clickable(role = Role.Button) {
                            if (selected) onToggleStrokePanel() else onSelectTool(tool)
                        }
                )
            }
            DockTile(
                label = String.format(Locale.getDefault(), "%.0fdp", currentWidthDp),
                glyph = EditorGlyph.Swatch,
                active = strokePanelOpen,
                interaction = Modifier
                    .width(50.dp)
                    .clickable(role = Role.Button, onClick = onToggleStrokePanel),
                swatch = activeColor
            )
            QuickWidthDots(
                isVertical = false,
                activeTool = activeTool,
                currentWidthDp = currentWidthDp,
                onSelectWidth = onQuickWidthSelect
            )
            favorites.forEach { preset ->
                FavoriteChip(preset = preset, onClick = { onApplyFavorite(preset) })
            }
        }
    }
}

/**
 * Notein-inspired movable floating pencil box ("笔盒", Section 08 & 11):
 * - Draggable across the entire canvas
 * - Dragging to Left/Right edges snaps Vertically; Top/Bottom snaps Horizontally
 * - Dragging to any of the 4 corners automatically collapses into the compact pen-nib badge!
 */
@Composable
internal fun MovablePenBox(
    containerSize: IntSize,
    activeTool: ToolMode,
    activeColor: Color,
    currentWidthDp: Float,
    strokePanelOpen: Boolean,
    favorites: List<PenPreset>,
    onSelectTool: (ToolMode) -> Unit,
    onToggleStrokePanel: () -> Unit,
    onQuickWidthSelect: (Float) -> Unit,
    onApplyFavorite: (PenPreset) -> Unit
) {
    val density = LocalDensity.current
    val defaultMarginPx = with(density) { 12.dp.toPx() }
    val defaultBottomOffsetPx = with(density) { 80.dp.toPx() }

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
    var showFavoritesDrawer by remember { mutableStateOf(true) }

    val maxW = containerSize.width.toFloat().coerceAtLeast(320f)
    val maxH = containerSize.height.toFloat().coerceAtLeast(480f)

    fun clampAndSnap(snapEdges: Boolean) {
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
                isVertical -> 380.dp.toPx().coerceAtMost(maxH - 24f)
                else -> 76.dp.toPx()
            }
        }
        offsetX = offsetX.coerceIn(6f, (maxW - boxW - 6f).coerceAtLeast(6f))
        offsetY = offsetY.coerceIn(6f, (maxH - boxH - 6f).coerceAtLeast(6f))

        if (snapEdges && !isCollapsed) {
            val sideThreshold = with(density) { 46.dp.toPx() }
            val cornerThreshold = with(density) { 46.dp.toPx() }
            val nearLeft = offsetX <= sideThreshold
            val nearRight = offsetX >= maxW - boxW - sideThreshold
            val nearTop = offsetY <= cornerThreshold
            val nearBottom = offsetY >= maxH - boxH - cornerThreshold

            // Section 08: Dragging into any of the 4 corners auto-collapses into the compact pen badge!
            if ((nearLeft || nearRight) && (nearTop || nearBottom)) {
                isCollapsed = true
                val badgePx = with(density) { 54.dp.toPx() }
                offsetX = if (nearLeft) 10f else (maxW - badgePx - 10f).coerceAtLeast(10f)
                offsetY = if (nearTop) 10f else (maxH - badgePx - 10f).coerceAtLeast(10f)
            } else if (nearLeft || nearRight) {
                isVertical = true
                offsetX = if (nearLeft) 10f else (maxW - with(density) { 68.dp.toPx() } - 10f).coerceAtLeast(10f)
            } else if (nearTop || nearBottom) {
                isVertical = false
                offsetY = if (nearTop) 10f else (maxH - with(density) { 76.dp.toPx() } - 10f).coerceAtLeast(10f)
            }
        }
    }

    val dragModifier = Modifier.pointerInput(containerSize) {
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
            // Compact dark rounded-square pen-nib badge (Notein corner pen icon)
            Surface(
                color = GraphiteSurface.copy(alpha = 0.96f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, Mint.copy(alpha = 0.75f)),
                shadowElevation = 10.dp,
                modifier = dragModifier
                    .size(54.dp)
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
            // Vertical Floating Pencil Case
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
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    DragHandlePill(
                        isVertical = true,
                        dragModifier = dragModifier,
                        showFavorites = showFavoritesDrawer,
                        onToggleFavorites = { showFavoritesDrawer = !showFavoritesDrawer },
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
                                    if (selected) onToggleStrokePanel() else onSelectTool(tool)
                                }
                        )
                    }

                    HorizontalDivider(color = GraphiteBorder, modifier = Modifier.padding(vertical = 2.dp))

                    DockTile(
                        label = String.format(Locale.getDefault(), "%.0fdp", currentWidthDp),
                        glyph = EditorGlyph.Swatch,
                        active = strokePanelOpen,
                        interaction = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClick = onToggleStrokePanel),
                        swatch = activeColor
                    )

                    QuickWidthDots(
                        isVertical = true,
                        activeTool = activeTool,
                        currentWidthDp = currentWidthDp,
                        onSelectWidth = onQuickWidthSelect
                    )

                    if (showFavoritesDrawer && favorites.isNotEmpty()) {
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
                        showFavorites = showFavoritesDrawer,
                        onToggleFavorites = { showFavoritesDrawer = !showFavoritesDrawer },
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
                                .width(50.dp)
                                .semantics { this.selected = selected }
                                .clickable(role = Role.Button) {
                                    if (selected) onToggleStrokePanel() else onSelectTool(tool)
                                }
                        )
                    }

                    DockTile(
                        label = String.format(Locale.getDefault(), "%.0fdp", currentWidthDp),
                        glyph = EditorGlyph.Swatch,
                        active = strokePanelOpen,
                        interaction = Modifier
                            .width(50.dp)
                            .clickable(role = Role.Button, onClick = onToggleStrokePanel),
                        swatch = activeColor
                    )

                    QuickWidthDots(
                        isVertical = false,
                        activeTool = activeTool,
                        currentWidthDp = currentWidthDp,
                        onSelectWidth = onQuickWidthSelect
                    )

                    if (showFavoritesDrawer) {
                        favorites.forEach { preset ->
                            FavoriteChip(preset = preset, onClick = { onApplyFavorite(preset) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Infinite Canvas Minimap with draggable Blue Viewport Box (Section 03 · 无限画布 "蓝色方框").
 */
@Composable
internal fun InfiniteCanvasMinimap(
    worldBounds: RectF,
    viewportBounds: RectF,
    itemSnapshots: List<Pair<RectF, Int>>,
    isDarkPaper: Boolean,
    onJumpToWorld: (worldCx: Float, worldCy: Float) -> Unit,
    onFitAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = GraphiteSurface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, GraphiteBorder),
        shadowElevation = 10.dp,
        modifier = modifier.width(196.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Minimapa · Lienzo",
                    style = MaterialTheme.typography.labelMedium,
                    color = InkTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "×",
                    style = MaterialTheme.typography.titleMedium,
                    color = InkTextSecondary,
                    modifier = Modifier
                        .clickable(onClick = onClose)
                        .padding(horizontal = 4.dp)
                )
            }

            val ww = max(1f, worldBounds.width())
            val wh = max(1f, worldBounds.height())

            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(126.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isDarkPaper) Color(0xFF16191F) else Color(0xFFF5F7FB))
                    .border(1.dp, GraphiteBorder, RoundedCornerShape(10.dp))
                    .pointerInput(worldBounds) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val nx = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val ny = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            onJumpToWorld(
                                worldBounds.left + nx * ww,
                                worldBounds.top + ny * wh
                            )
                        }
                    }
            ) {
                Canvas(Modifier.matchParentSize()) {
                    fun mapX(wx: Float): Float = ((wx - worldBounds.left) / ww) * size.width
                    fun mapY(wy: Float): Float = ((wy - worldBounds.top) / wh) * size.height

                    // Draw miniature items
                    for ((rect, argb) in itemSnapshots) {
                        val l = mapX(rect.left)
                        val t = mapY(rect.top)
                        val r = mapX(rect.right)
                        val b = mapY(rect.bottom)
                        drawRect(
                            color = Color(argb),
                            topLeft = Offset(l, t),
                            size = Size(max(2f, r - l), max(2f, b - t))
                        )
                    }

                    // Draw Blue Viewport Box ("蓝色方框")
                    val vl = mapX(viewportBounds.left).coerceIn(0f, size.width)
                    val vt = mapY(viewportBounds.top).coerceIn(0f, size.height)
                    val vr = mapX(viewportBounds.right).coerceIn(0f, size.width)
                    val vb = mapY(viewportBounds.bottom).coerceIn(0f, size.height)
                    val vw = max(8f, vr - vl)
                    val vh = max(8f, vb - vt)
                    drawRect(
                        color = Color(0x332AA1B7),
                        topLeft = Offset(vl, vt),
                        size = Size(vw, vh)
                    )
                    drawRect(
                        color = Color(0xFF2AA1B7),
                        topLeft = Offset(vl, vt),
                        size = Size(vw, vh),
                        style = Stroke(width = 2.2f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Arrastra el cuadro azul",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkTextSecondary
                )
                TextButton(onClick = onFitAll) {
                    Text("Encajar", style = MaterialTheme.typography.labelSmall, color = Mint)
                }
            }
        }
    }
}

@Composable
private fun DragHandlePill(
    isVertical: Boolean,
    dragModifier: Modifier,
    showFavorites: Boolean,
    onToggleFavorites: () -> Unit,
    onRotate: () -> Unit,
    onCollapse: () -> Unit
) {
    if (isVertical) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
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
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .clickable(onClick = onToggleFavorites),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "★",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (showFavorites) Mint else InkTextSecondary
                    )
                }
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .clickable(onClick = onRotate),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⇄", style = MaterialTheme.typography.labelSmall, color = InkTextSecondary)
                }
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(5.dp))
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
                    .height(50.dp)
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
                verticalArrangement = Arrangement.spacedBy(1.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .clickable(onClick = onToggleFavorites),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "★",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (showFavorites) Mint else InkTextSecondary
                    )
                }
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .clickable(onClick = onRotate),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⇅", style = MaterialTheme.typography.labelSmall, color = InkTextSecondary)
                }
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .clickable(onClick = onCollapse),
                    contentAlignment = Alignment.Center
                ) {
                    Text("−", style = MaterialTheme.typography.labelSmall, color = Mint)
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
    if (activeTool == ToolMode.LASSO || activeTool == ToolMode.LASER ||
        activeTool == ToolMode.TEXT || activeTool == ToolMode.LINK
    ) return
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
                .size(width = 38.dp, height = 27.dp)
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
    ToolMode.SHAPES -> EditorGlyph.Shapes
    ToolMode.TEXT -> EditorGlyph.Text
    ToolMode.LINK -> EditorGlyph.Link
    ToolMode.LASER -> EditorGlyph.Laser
    ToolMode.ERASER -> EditorGlyph.Eraser
    ToolMode.LASSO -> EditorGlyph.Lasso
}

private enum class EditorGlyph {
    Pen, Ballpoint, Dashed, Marker, Tape, Shapes, Text, Link, Laser, Eraser, Lasso,
    Back, LeftDrawer, Image, Ruler, Layers, Minimap, Grid, Sidebar, Swatch, Undo, Redo, Reading, Focus, Menu
}

@Composable
private fun DrawGlyph(glyph: EditorGlyph, tint: Color, swatch: Color = Color.Transparent) {
    Canvas(Modifier.size(20.dp)) {
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
                EditorGlyph.Shapes -> {
                    drawRoundRect(tint, Offset(4f, 10f), Size(10f, 10f), CornerRadius(2f), style = outline)
                    drawCircle(tint, radius = 4f, center = Offset(16f, 7.5f), style = outline)
                }
                EditorGlyph.Text -> {
                    drawRoundRect(tint, Offset(3f, 4f), Size(18f, 16f), CornerRadius(3f), style = outline)
                    lines(listOf(Offset(8f, 8f), Offset(16f, 8f)))
                    lines(listOf(Offset(12f, 8f), Offset(12f, 16f)))
                }
                EditorGlyph.Link -> {
                    drawRoundRect(tint, Offset(3f, 8f), Size(8f, 8f), CornerRadius(4f), style = outline)
                    drawRoundRect(tint, Offset(13f, 8f), Size(8f, 8f), CornerRadius(4f), style = outline)
                    lines(listOf(Offset(8.5f, 12f), Offset(15.5f, 12f)))
                }
                EditorGlyph.Back -> {
                    lines(listOf(Offset(15f, 5f), Offset(8f, 12f), Offset(15f, 19f)))
                }
                EditorGlyph.LeftDrawer -> {
                    drawRoundRect(tint, Offset(3f, 4f), Size(18f, 16f), CornerRadius(2f), style = outline)
                    lines(listOf(Offset(9f, 4f), Offset(9f, 20f)))
                }
                EditorGlyph.Image -> {
                    drawRoundRect(tint, Offset(3f, 4f), Size(18f, 16f), CornerRadius(2.5f), style = outline)
                    drawCircle(tint, radius = 1.8f, center = Offset(8.5f, 9f))
                    lines(listOf(Offset(4f, 18f), Offset(10f, 13f), Offset(14f, 16f), Offset(17.5f, 12f), Offset(20.5f, 16.5f)))
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
                EditorGlyph.Ruler -> {
                    drawRoundRect(tint, Offset(3f, 7f), Size(18f, 10f), CornerRadius(2f), style = outline)
                    lines(listOf(Offset(7f, 7f), Offset(7f, 11f)))
                    lines(listOf(Offset(12f, 7f), Offset(12f, 13f)))
                    lines(listOf(Offset(17f, 7f), Offset(17f, 11f)))
                }
                EditorGlyph.Layers -> {
                    lines(listOf(Offset(12f, 4f), Offset(20f, 9f), Offset(12f, 14f), Offset(4f, 9f)), close = true)
                    lines(listOf(Offset(4f, 14f), Offset(12f, 19f), Offset(20f, 14f)))
                }
                EditorGlyph.Minimap -> {
                    drawRoundRect(tint, Offset(3f, 4f), Size(18f, 16f), CornerRadius(2f), style = outline)
                    drawRoundRect(tint, Offset(11f, 10f), Size(7f, 6f), CornerRadius(1f), style = outline)
                }
                EditorGlyph.Grid -> {
                    drawRoundRect(tint, Offset(4f, 4f), Size(6.5f, 6.5f), CornerRadius(1.5f), style = outline)
                    drawRoundRect(tint, Offset(13.5f, 4f), Size(6.5f, 6.5f), CornerRadius(1.5f), style = outline)
                    drawRoundRect(tint, Offset(4f, 13.5f), Size(6.5f, 6.5f), CornerRadius(1.5f), style = outline)
                    drawRoundRect(tint, Offset(13.5f, 13.5f), Size(6.5f, 6.5f), CornerRadius(1.5f), style = outline)
                }
                EditorGlyph.Sidebar -> {
                    drawRoundRect(tint, Offset(3f, 4f), Size(18f, 16f), CornerRadius(2f), style = outline)
                    lines(listOf(Offset(15f, 4f), Offset(15f, 20f)))
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

/**
 * Right Sidebar Drawer (Section 17 · 右侧边栏):
 * - Tab 0: Overview / Pages ("页面概览")
 * - Tab 1: Outline / Bookmarks ("大纲")
 * - Tab 2: Comments / Annotations ("备注")
 */
@Composable
internal fun RightOverviewSidebar(
    currentPageIndex: Int,
    pageCount: Int,
    layoutMode: CanvasLayoutMode,
    isDarkPaper: Boolean,
    outlines: List<OutlineEntry>,
    comments: List<CommentEntry>,
    pageSnapshotProvider: (pageIdx: Int) -> Pair<RectF, List<Pair<RectF, Int>>>,
    onJumpToPage: (Int) -> Unit,
    onInsertPageAfter: (Int) -> Unit,
    onDuplicatePage: (Int) -> Unit,
    onDeletePage: (Int) -> Unit,
    onAddOutlineForCurrentPage: () -> Unit,
    onJumpToOutline: (OutlineEntry) -> Unit,
    onIndentOutline: (OutlineEntry, Int) -> Unit,
    onDeleteOutline: (OutlineEntry) -> Unit,
    onAddCommentForCurrentPage: () -> Unit,
    onJumpToComment: (CommentEntry) -> Unit,
    onDeleteComment: (CommentEntry) -> Unit,
    onOpenGridView: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(0) } // 0=Páginas, 1=Esquema, 2=Notas

    Surface(
        color = GraphiteSurface.copy(alpha = 0.98f),
        shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp),
        border = BorderStroke(1.dp, GraphiteBorder),
        shadowElevation = 16.dp,
        modifier = modifier.width(268.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Vista general",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = InkTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClose) {
                    Text("×", style = MaterialTheme.typography.titleMedium, color = InkTextSecondary)
                }
            }

            // 3 Tabs: Páginas (页面概览) | Esquema (大纲) | Notas (备注)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("Páginas", "Esquema", "Notas").forEachIndexed { idx, label ->
                    val sel = activeTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) ActiveTile else GraphiteBg)
                            .clickable { activeTab = idx }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (sel) Mint else InkTextSecondary
                        )
                    }
                }
            }

            Text(
                text = if (layoutMode == CanvasLayoutMode.PAGED) "$pageCount páginas en total" else "Modo Lienzo Infinito",
                style = MaterialTheme.typography.labelSmall,
                color = InkTextSecondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (activeTab) {
                    0 -> {
                        for (p in 0 until pageCount) {
                            val isCurrent = p == currentPageIndex
                            val (pageRect, miniItems) = pageSnapshotProvider(p)
                            Surface(
                                color = if (isCurrent) ActiveTile else GraphiteBg,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    width = if (isCurrent) 1.5.dp else 1.dp,
                                    color = if (isCurrent) Mint else GraphiteBorder
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onJumpToPage(p) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(106.dp)
                                            .height(142.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isDarkPaper) Color(0xFF16191F) else Color(0xFFFDFEFF))
                                            .border(1.dp, GraphiteBorder, RoundedCornerShape(6.dp))
                                    ) {
                                        Canvas(Modifier.matchParentSize()) {
                                            val pw = max(1f, pageRect.width())
                                            val ph = max(1f, pageRect.height())
                                            for ((r, argb) in miniItems) {
                                                val l = ((r.left - pageRect.left) / pw) * size.width
                                                val t = ((r.top - pageRect.top) / ph) * size.height
                                                val rr = ((r.right - pageRect.left) / pw) * size.width
                                                val bb = ((r.bottom - pageRect.top) / ph) * size.height
                                                drawRect(
                                                    color = Color(argb),
                                                    topLeft = Offset(l.coerceIn(0f, size.width), t.coerceIn(0f, size.height)),
                                                    size = Size(max(2f, rr - l), max(2f, bb - t))
                                                )
                                            }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Pág ${p + 1}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isCurrent) Mint else InkTextPrimary
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                "+",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Mint,
                                                modifier = Modifier
                                                    .clickable { onInsertPageAfter(p) }
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                            Text(
                                                "⧉",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = InkTextSecondary,
                                                modifier = Modifier
                                                    .clickable { onDuplicatePage(p) }
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                            if (pageCount > 1) {
                                                Text(
                                                    "🗑",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFFE06C75),
                                                    modifier = Modifier
                                                        .clickable { onDeletePage(p) }
                                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        TextButton(onClick = onAddOutlineForCurrentPage) {
                            Text("+ Agregar marcador en Pág ${currentPageIndex + 1}", color = Mint)
                        }
                        if (outlines.isEmpty()) {
                            Text(
                                "Sin esquema todavía. Agrega títulos o capítulos para saltar rápido.",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkTextSecondary
                            )
                        } else {
                            outlines.forEach { item ->
                                Surface(
                                    color = GraphiteBg,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, GraphiteBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = (item.indentLevel * 12).dp)
                                        .clickable { onJumpToOutline(item) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(item.title, style = MaterialTheme.typography.bodySmall, color = InkTextPrimary)
                                            Text("Pág ${item.pageIndex + 1}", style = MaterialTheme.typography.labelSmall, color = Mint)
                                        }
                                        if (item.indentLevel > 0) {
                                            Text(
                                                "←",
                                                color = InkTextSecondary,
                                                modifier = Modifier
                                                    .clickable { onIndentOutline(item, -1) }
                                                    .padding(horizontal = 4.dp)
                                            )
                                        }
                                        if (item.indentLevel < 5) {
                                            Text(
                                                "→",
                                                color = InkTextSecondary,
                                                modifier = Modifier
                                                    .clickable { onIndentOutline(item, 1) }
                                                    .padding(horizontal = 4.dp)
                                            )
                                        }
                                        Text(
                                            "×",
                                            color = Color(0xFFE06C75),
                                            modifier = Modifier
                                                .clickable { onDeleteOutline(item) }
                                                .padding(horizontal = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        TextButton(onClick = onAddCommentForCurrentPage) {
                            Text("+ Agregar nota/comentario en Pág ${currentPageIndex + 1}", color = Mint)
                        }
                        if (comments.isEmpty()) {
                            Text(
                                "Sin comentarios en el documento.",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkTextSecondary
                            )
                        } else {
                            comments.forEach { c ->
                                Surface(
                                    color = GraphiteBg,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, GraphiteBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onJumpToComment(c) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Pág ${c.pageIndex + 1}", style = MaterialTheme.typography.labelSmall, color = Mint)
                                            Text(c.text, style = MaterialTheme.typography.bodySmall, color = InkTextPrimary)
                                        }
                                        Text(
                                            "×",
                                            color = Color(0xFFE06C75),
                                            modifier = Modifier
                                                .clickable { onDeleteComment(c) }
                                                .padding(horizontal = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = GraphiteBorder, modifier = Modifier.padding(vertical = 6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onOpenGridView) {
                    Text("⊞ Miniaturas en cuadrícula", style = MaterialTheme.typography.labelMedium, color = Mint)
                }
                TextButton(onClick = { onInsertPageAfter(pageCount - 1) }) {
                    Text("+ Pág", style = MaterialTheme.typography.labelMedium, color = InkTextPrimary)
                }
            }
        }
    }
}

/**
 * Full-screen Bird's-Eye Page Grid Modal (Sections 18 & 23 · 平铺缩略图).
 */
@Composable
internal fun PageGridModal(
    currentPageIndex: Int,
    pageCount: Int,
    isDarkPaper: Boolean,
    pageSnapshotProvider: (pageIdx: Int) -> Pair<RectF, List<Pair<RectF, Int>>>,
    onJumpToPage: (Int) -> Unit,
    onInsertPageAfter: (Int) -> Unit,
    onDuplicatePage: (Int) -> Unit,
    onDeletePage: (Int) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = GraphiteBg.copy(alpha = 0.98f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Cuadrícula de páginas ($pageCount)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = InkTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onInsertPageAfter(pageCount - 1) }) {
                    Text("+ Nueva página", color = Mint)
                }
                TextButton(onClick = onClose) {
                    Text("Cerrar")
                }
            }
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                (0 until pageCount).chunked(3).forEach { rowPages ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowPages.forEach { p ->
                            val isCurrent = p == currentPageIndex
                            val (pageRect, miniItems) = pageSnapshotProvider(p)
                            Surface(
                                color = if (isCurrent) ActiveTile else GraphiteSurface,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(
                                    width = if (isCurrent) 2.dp else 1.dp,
                                    color = if (isCurrent) Mint else GraphiteBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        onJumpToPage(p)
                                        onClose()
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(132.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isDarkPaper) Color(0xFF16191F) else Color(0xFFFDFEFF))
                                            .border(1.dp, GraphiteBorder, RoundedCornerShape(8.dp))
                                    ) {
                                        Canvas(Modifier.matchParentSize()) {
                                            val pw = max(1f, pageRect.width())
                                            val ph = max(1f, pageRect.height())
                                            for ((r, argb) in miniItems) {
                                                val l = ((r.left - pageRect.left) / pw) * size.width
                                                val t = ((r.top - pageRect.top) / ph) * size.height
                                                val rr = ((r.right - pageRect.left) / pw) * size.width
                                                val bb = ((r.bottom - pageRect.top) / ph) * size.height
                                                drawRect(
                                                    color = Color(argb),
                                                    topLeft = Offset(l.coerceIn(0f, size.width), t.coerceIn(0f, size.height)),
                                                    size = Size(max(2f, rr - l), max(2f, bb - t))
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        "Página ${p + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isCurrent) Mint else InkTextPrimary,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            "⧉",
                                            color = InkTextSecondary,
                                            modifier = Modifier
                                                .clickable { onDuplicatePage(p) }
                                                .padding(4.dp)
                                        )
                                        if (pageCount > 1) {
                                            Text(
                                                "🗑",
                                                color = Color(0xFFE06C75),
                                                modifier = Modifier
                                                    .clickable { onDeletePage(p) }
                                                    .padding(4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        repeat(3 - rowPages.size) {
                            Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Left Document Drawer + In-App Split Screen Launcher (Section 16 · 左侧边栏的作用 & 应用内分屏).
 */
@Composable
internal fun LeftDocumentDrawer(
    notebooks: List<NoteMetadata>,
    activeUuid: String,
    splitUuid: String?,
    onSelectNotebook: (NoteMetadata) -> Unit,
    onOpenInSplitScreen: (NoteMetadata) -> Unit,
    onCloseSplitScreen: () -> Unit,
    onCreateQuickNote: () -> Unit,
    onOpenFullLibrary: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = GraphiteSurface.copy(alpha = 0.98f),
        shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
        border = BorderStroke(1.dp, GraphiteBorder),
        shadowElevation = 16.dp,
        modifier = modifier.width(268.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Documentos recientes",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = InkTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClose) {
                    Text("×", style = MaterialTheme.typography.titleMedium, color = InkTextSecondary)
                }
            }
            Text(
                "Toca para abrir o usa «◫ Dividir» para pantalla dividida in-app",
                style = MaterialTheme.typography.labelSmall,
                color = InkTextSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (splitUuid != null) {
                Surface(
                    color = ActiveTile,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Mint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { onCloseSplitScreen() }
                ) {
                    Text(
                        "Cerrar pantalla dividida activa (×)",
                        style = MaterialTheme.typography.labelMedium,
                        color = Mint,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                notebooks.forEach { nb ->
                    val isCurrent = nb.uuid == activeUuid
                    val isSplit = nb.uuid == splitUuid
                    Surface(
                        color = if (isCurrent) ActiveTile else GraphiteBg,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = if (isCurrent || isSplit) 1.5.dp else 1.dp,
                            color = when {
                                isCurrent -> Mint
                                isSplit -> Color(0xFF00B4D8)
                                else -> GraphiteBorder
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectNotebook(nb)
                                onClose()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(width = 10.dp, height = 36.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(nb.coverColorArgb))
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    nb.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    color = InkTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (nb.layoutMode == CanvasLayoutMode.PAGED) "${nb.pageCount} págs · ${nb.paperTemplate.label}"
                                    else "Lienzo infinito · ${nb.paperTemplate.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = InkTextSecondary
                                )
                            }
                            if (!isCurrent) {
                                TextButton(
                                    onClick = {
                                        onOpenInSplitScreen(nb)
                                        onClose()
                                    }
                                ) {
                                    Text("◫ Dividir", style = MaterialTheme.typography.labelSmall, color = Mint)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = GraphiteBorder, modifier = Modifier.padding(vertical = 6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    onCreateQuickNote()
                    onClose()
                }) {
                    Text("+ Nota rápida", style = MaterialTheme.typography.labelMedium, color = Mint)
                }
                TextButton(onClick = {
                    onOpenFullLibrary()
                    onClose()
                }) {
                    Text("Carpetas →", style = MaterialTheme.typography.labelMedium, color = InkTextPrimary)
                }
            }
        }
    }
}

/**
 * Full-screen Notebook & Color-Coded Folder Library Modal (Sections 01 & 04 · 无限层级文件夹).
 */
@Composable
internal fun NotebookLibraryModal(
    folders: List<NotebookFolder>,
    notebooks: List<NoteMetadata>,
    activeUuid: String,
    selectedFolderId: String?,
    onSelectFolder: (String?) -> Unit,
    onCreateFolder: (name: String, colorArgb: Int) -> Unit,
    onCreateNotebook: (title: String, layoutMode: CanvasLayoutMode, folderId: String?, coverColorArgb: Int) -> Unit,
    onOpenNotebook: (NoteMetadata) -> Unit,
    onOpenInSplitScreen: (NoteMetadata) -> Unit,
    onDeleteNotebook: (NoteMetadata) -> Unit,
    onClose: () -> Unit
) {
    var showNewFolderRow by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var newFolderColor by remember { mutableStateOf(0xFF2AA1B7.toInt()) }

    var showNewNoteRow by remember { mutableStateOf(false) }
    var newNoteTitle by remember { mutableStateOf("") }
    var newNoteMode by remember { mutableStateOf(CanvasLayoutMode.PAGED) }
    var newNoteColor by remember { mutableStateOf(0xFF2AA1B7.toInt()) }

    val folderSwatches = listOf(
        0xFF2AA1B7.toInt(),
        0xFF1E58CC.toInt(),
        0xFF1E7A56.toInt(),
        0xFF6C4AB6.toInt(),
        0xFFC77818.toInt(),
        0xFFB9324B.toInt()
    )

    Surface(
        color = GraphiteBg.copy(alpha = 0.99f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Biblioteca · Cuadernos y Carpetas",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = InkTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showNewFolderRow = !showNewFolderRow }) {
                    Text("+ Carpeta", color = InkTextSecondary)
                }
                TextButton(onClick = { showNewNoteRow = !showNewNoteRow }) {
                    Text("+ Cuaderno", color = Mint)
                }
                TextButton(onClick = onClose) {
                    Text("Volver al editor")
                }
            }

            // Folder filter chips (All + color-coded folders)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val allSel = selectedFolderId == null
                Surface(
                    color = if (allSel) ActiveTile else GraphiteSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (allSel) Mint else GraphiteBorder),
                    modifier = Modifier.clickable { onSelectFolder(null) }
                ) {
                    Text(
                        "Todos (${notebooks.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (allSel) Mint else InkTextPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                folders.forEach { folder ->
                    val sel = selectedFolderId == folder.id
                    Surface(
                        color = if (sel) ActiveTile else GraphiteSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.5.dp, if (sel) Mint else Color(folder.colorArgb)),
                        modifier = Modifier.clickable { onSelectFolder(folder.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(folder.colorArgb))
                            )
                            Text(
                                folder.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sel) Mint else InkTextPrimary
                            )
                        }
                    }
                }
            }

            if (showNewFolderRow) {
                Surface(
                    color = GraphiteSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, GraphiteBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Color:", style = MaterialTheme.typography.labelSmall, color = InkTextSecondary)
                        folderSwatches.forEach { col ->
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color(col))
                                    .border(if (col == newFolderColor) 2.dp else 1.dp, InkTextPrimary, CircleShape)
                                    .clickable { newFolderColor = col }
                            )
                        }
                        TextButton(
                            onClick = {
                                val name = if (newFolderName.isBlank()) "Carpeta ${folders.size + 1}" else newFolderName.trim()
                                onCreateFolder(name, newFolderColor)
                                newFolderName = ""
                                showNewFolderRow = false
                            }
                        ) {
                            Text("Crear carpeta", color = Mint)
                        }
                    }
                }
            }

            if (showNewNoteRow) {
                Surface(
                    color = GraphiteSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Mint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CanvasLayoutMode.entries.forEach { mode ->
                                val sel = mode == newNoteMode
                                Surface(
                                    color = if (sel) ActiveTile else GraphiteBg,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (sel) Mint else GraphiteBorder),
                                    modifier = Modifier.clickable { newNoteMode = mode }
                                ) {
                                    Text(
                                        mode.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (sel) Mint else InkTextSecondary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                            folderSwatches.forEach { col ->
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Color(col))
                                        .border(if (col == newNoteColor) 2.dp else 1.dp, InkTextPrimary, CircleShape)
                                        .clickable { newNoteColor = col }
                                )
                            }
                            TextButton(
                                onClick = {
                                    val t = if (newNoteTitle.isBlank()) "Apunte ${notebooks.size + 1}" else newNoteTitle.trim()
                                    onCreateNotebook(t, newNoteMode, selectedFolderId, newNoteColor)
                                    newNoteTitle = ""
                                    showNewNoteRow = false
                                }
                            ) {
                                Text("Crear y abrir", color = Mint)
                            }
                        }
                    }
                }
            }

            val visibleNotes = if (selectedFolderId == null) {
                notebooks
            } else {
                notebooks.filter { it.folderId == selectedFolderId }
            }

            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                visibleNotes.chunked(2).forEach { rowNotes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowNotes.forEach { nb ->
                            val isCurrent = nb.uuid == activeUuid
                            Surface(
                                color = if (isCurrent) ActiveTile else GraphiteSurface,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(
                                    width = if (isCurrent) 2.dp else 1.dp,
                                    color = if (isCurrent) Mint else GraphiteBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        onOpenNotebook(nb)
                                        onClose()
                                    }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(10.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(nb.coverColorArgb))
                                    )
                                    Text(
                                        nb.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = InkTextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                    Text(
                                        if (nb.layoutMode == CanvasLayoutMode.PAGED) "${nb.pageCount} páginas · ${nb.paperTemplate.label}"
                                        else "Lienzo infinito · ${nb.paperTemplate.label}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = InkTextSecondary
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        if (!isCurrent) {
                                            Text(
                                                "◫ Dividir pantalla",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Mint,
                                                modifier = Modifier
                                                    .clickable {
                                                        onOpenInSplitScreen(nb)
                                                        onClose()
                                                    }
                                                    .padding(vertical = 2.dp)
                                            )
                                            if (notebooks.size > 1) {
                                                Text(
                                                    "Eliminar",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFFE06C75),
                                                    modifier = Modifier
                                                        .clickable { onDeleteNotebook(nb) }
                                                        .padding(vertical = 2.dp)
                                                )
                                            }
                                        } else {
                                            Text(
                                                "Abierto actualmente",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Mint
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (rowNotes.size == 1) {
                            Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
