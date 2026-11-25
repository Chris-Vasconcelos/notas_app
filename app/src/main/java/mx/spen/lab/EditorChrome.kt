package mx.spen.lab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val ActiveTile = Color(0xff23393c)
private val Mint = Color(0xff91e1d5)
private val SheetActions = RoundedCornerShape(12.dp)
private val InkDock = RoundedCornerShape(18.dp)

@Composable
internal fun TopEditorBar(
    title: String,
    zoomPercent: Int,
    focusMode: Boolean,
    onToggleFocus: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onFitContent: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenPaper: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onExportPng: () -> Unit,
    canClear: Boolean,
    onClear: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${zoomPercent}%",
                style = MaterialTheme.typography.labelMedium,
                color = InkTextSecondary
            )
        }
        HeaderButton("Deshacer", EditorGlyph.Undo, enabled = canUndo, action = onUndo)
        HeaderButton("Rehacer", EditorGlyph.Redo, enabled = canRedo, action = onRedo)
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
                val actions = listOf(
                    "Ajustar hoja a pantalla" to onFitContent,
                    "Papel y plantilla" to onOpenPaper,
                    "Diagnóstico" to onOpenDiagnostics,
                    "Exportar PNG" to onExportPng,
                    "Limpiar hoja" to onClear
                )
                actions.forEachIndexed { index, (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        enabled = index != actions.lastIndex || canClear,
                        onClick = {
                            expanded = false
                            action()
                        }
                    )
                }
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
        Modifier.size(48.dp)
            .clip(SheetActions)
            .background(if (active) ActiveTile else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = action)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        DrawGlyph(glyph, if (active) Mint else InkTextPrimary.copy(alpha = if (enabled) 1f else .38f))
    }
}

@Composable
internal fun BottomToolBar(
    activeTool: ToolMode,
    activeColor: Color,
    autoShapeEnabled: Boolean,
    strokePanelOpen: Boolean,
    onSelectTool: (ToolMode) -> Unit,
    onToggleStrokePanel: () -> Unit,
    onToggleAutoShape: () -> Unit
) {
    Surface(
        color = GraphiteSurface,
        shape = InkDock,
        border = BorderStroke(1.dp, GraphiteBorder),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolMode.entries.forEach { tool ->
                val (label, glyph) = when (tool) {
                    ToolMode.PEN -> "Pluma" to EditorGlyph.Pen
                    ToolMode.HIGHLIGHTER -> "Resaltar" to EditorGlyph.Marker
                    ToolMode.ERASER -> "Borrar" to EditorGlyph.Eraser
                    ToolMode.LASSO -> "Lazo" to EditorGlyph.Lasso
                }
                DockTile(
                    label, glyph, activeTool == tool,
                    Modifier.weight(1f)
                        .semantics { selected = activeTool == tool }
                        .clickable(role = Role.Button) { onSelectTool(tool) }
                )
            }
            DockTile(
                "Figuras", EditorGlyph.Shapes, autoShapeEnabled,
                Modifier.weight(1f).toggleable(
                    value = autoShapeEnabled,
                    role = Role.Switch,
                    onValueChange = { onToggleAutoShape() }
                )
            )
            DockTile(
                "Trazo", EditorGlyph.Swatch, strokePanelOpen,
                Modifier.weight(1f).clickable(
                    enabled = activeTool != ToolMode.LASSO,
                    role = Role.Button,
                    onClick = onToggleStrokePanel
                ),
                swatch = activeColor.copy(alpha = if (activeTool == ToolMode.LASSO) .28f else activeColor.alpha)
            )
        }
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
        modifier = interaction.heightIn(min = 56.dp).padding(horizontal = 1.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(width = 38.dp, height = 29.dp)
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

private enum class EditorGlyph { Pen, Marker, Eraser, Lasso, Shapes, Swatch, Undo, Redo, Focus, Menu }

@Composable
private fun DrawGlyph(glyph: EditorGlyph, tint: Color, swatch: Color = Color.Transparent) {
    Canvas(Modifier.size(22.dp)) {
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
                EditorGlyph.Marker -> {
                    lines(listOf(Offset(5f, 16f), Offset(15f, 5f), Offset(20f, 10f), Offset(10f, 20f)), close = true)
                    lines(listOf(Offset(8f, 13f), Offset(13f, 18f)))
                    lines(listOf(Offset(4f, 20f), Offset(6f, 20f)))
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
