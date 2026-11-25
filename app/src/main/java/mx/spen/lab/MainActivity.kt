package mx.spen.lab

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale

private val GraphiteBg = Color(0xFF14161A)
private val GraphiteSurface = Color(0xFF1D2128)
private val GraphiteBorder = Color(0xFF2C333F)
private val Petroleum = Color(0xFF2AA1B7)
private val PetroleumDeep = Color(0xFF165664)
private val InkTextPrimary = Color(0xFFEDF1F7)
private val InkTextSecondary = Color(0xFF9AA5B4)

class MainActivity : ComponentActivity() {
    private var sheet: PenSheet? = null

    // Editor state
    private var activeTool by mutableStateOf(ToolMode.PEN)
    private var selectedPenColorIdx by mutableIntStateOf(1) // Marino
    private var selectedHighlighterIdx by mutableIntStateOf(0) // Amarillo
    private var penWidthDp by mutableFloatStateOf(4.2f)
    private var highlighterWidthDp by mutableFloatStateOf(18f)
    private var eraserRadiusDp by mutableFloatStateOf(12f)
    private var autoShapeEnabled by mutableStateOf(false)
    private var paperStyle by mutableStateOf(PaperStyle.LIGHT)
    private var paperTemplate by mutableStateOf(PaperTemplate.GRID)

    // History & selection state
    private var canUndo by mutableStateOf(false)
    private var canRedo by mutableStateOf(false)
    private var hasSelection by mutableStateOf(false)

    // Panels & dialogs
    private var showStrokePanel by mutableStateOf(false)
    private var showPaperDialog by mutableStateOf(false)
    private var showDiagnosticsDialog by mutableStateOf(false)
    private var showHelpDialog by mutableStateOf(false)
    private var confirmClear by mutableStateOf(false)

    // Diagnostics & Air Actions
    private var showHudOverlay by mutableStateOf(false)
    private var debugCompareNormalized by mutableStateOf(false)
    private var telemetry by mutableStateOf(PenTelemetry())
    private var remoteCount by mutableIntStateOf(0)
    private var remoteStatus by mutableStateOf("En espera de acciones")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val appTitle = getString(applicationInfo.labelRes)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Petroleum,
                    onPrimary = Color(0xFF07161B),
                    background = GraphiteBg,
                    surface = GraphiteSurface,
                    onBackground = InkTextPrimary,
                    onSurface = InkTextPrimary
                )
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(GraphiteBg)
                        .safeDrawingPadding()
                ) {
                    TopEditorBar(
                        title = appTitle,
                        zoomPercent = telemetry.zoomPercent,
                        canUndo = canUndo,
                        canRedo = canRedo,
                        onFitContent = { sheet?.fitToContent() },
                        onUndo = { sheet?.undo() },
                        onRedo = { sheet?.redo() },
                        onOpenPaper = { showPaperDialog = true },
                        onOpenDiagnostics = { showDiagnosticsDialog = true },
                        onExportPng = { exportAndSharePng() }
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        AndroidView(
                            factory = { context ->
                                PenSheet(
                                    context = context,
                                    onStateChanged = { undo, redo, sel ->
                                        canUndo = undo
                                        canRedo = redo
                                        hasSelection = sel
                                    },
                                    onTelemetryChanged = { t -> telemetry = t }
                                ).also { view ->
                                    sheet = view
                                    syncSheetConfig(view)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Floating feedback pill for detected shapes
                        telemetry.lastShapeDetected?.let { shapeName ->
                            Surface(
                                color = GraphiteSurface.copy(alpha = 0.92f),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, Petroleum.copy(alpha = 0.7f)),
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 10.dp)
                            ) {
                                Text(
                                    text = "✓ $shapeName",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = InkTextPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }

                        // Floating lasso action bar
                        androidx.compose.animation.AnimatedVisibility(
                            visible = hasSelection,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                        ) {
                            Surface(
                                color = GraphiteSurface.copy(alpha = 0.96f),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Petroleum.copy(alpha = 0.6f)),
                                shadowElevation = 8.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        "Selección",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Petroleum,
                                        modifier = Modifier.padding(horizontal = 6.dp)
                                    )
                                    TextButton(onClick = { sheet?.duplicateSelection() }) {
                                        Text("Duplicar")
                                    }
                                    TextButton(onClick = { sheet?.deleteSelection() }) {
                                        Text("Borrar", color = Color(0xFFE06C75))
                                    }
                                    TextButton(onClick = { sheet?.clearSelection() }) {
                                        Text("Listo")
                                    }
                                }
                            }
                        }

                        // Floating color & width panel
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showStrokePanel,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            StrokeConfigPanel(
                                tool = activeTool,
                                selectedPenIdx = selectedPenColorIdx,
                                selectedHighlighterIdx = selectedHighlighterIdx,
                                penWidthDp = penWidthDp,
                                highlighterWidthDp = highlighterWidthDp,
                                eraserRadiusDp = eraserRadiusDp,
                                onSelectPenColor = { idx ->
                                    selectedPenColorIdx = idx
                                    sheet?.inkColor = EditorPalettes.penColors[idx].argb
                                },
                                onSelectHighlighterColor = { idx ->
                                    selectedHighlighterIdx = idx
                                    sheet?.highlighterColor = EditorPalettes.highlighterColors[idx].argb
                                },
                                onChangeWidth = { value ->
                                    when (activeTool) {
                                        ToolMode.PEN, ToolMode.LASSO -> {
                                            penWidthDp = value
                                            sheet?.penWidthDp = value
                                        }
                                        ToolMode.HIGHLIGHTER -> {
                                            highlighterWidthDp = value
                                            sheet?.highlighterWidthDp = value
                                        }
                                        ToolMode.ERASER -> {
                                            eraserRadiusDp = value
                                            sheet?.eraserRadiusDp = value
                                        }
                                    }
                                },
                                onClose = { showStrokePanel = false }
                            )
                        }
                    }

                    BottomToolBar(
                        activeTool = activeTool,
                        activeColor = currentIndicatorColor(),
                        autoShapeEnabled = autoShapeEnabled,
                        hasContent = canUndo,
                        strokePanelOpen = showStrokePanel,
                        onSelectTool = { tool ->
                            activeTool = tool
                            sheet?.toolMode = tool
                            if (tool == ToolMode.LASSO) showStrokePanel = false
                        },
                        onToggleStrokePanel = { showStrokePanel = !showStrokePanel },
                        onToggleAutoShape = {
                            autoShapeEnabled = !autoShapeEnabled
                            sheet?.autoShapeEnabled = autoShapeEnabled
                        },
                        onRequestClear = { confirmClear = true }
                    )
                }

                if (showPaperDialog) {
                    PaperSettingsDialog(
                        currentStyle = paperStyle,
                        currentTemplate = paperTemplate,
                        onSelectStyle = { style ->
                            paperStyle = style
                            sheet?.paperStyle = style
                            // Auto-adjust default ink if switching between light and dark paper
                            if (style.isDark && selectedPenColorIdx in 0..1) {
                                selectedPenColorIdx = EditorPalettes.penColors.lastIndex // Tiza
                                sheet?.inkColor = EditorPalettes.penColors[selectedPenColorIdx].argb
                            } else if (!style.isDark && selectedPenColorIdx == EditorPalettes.penColors.lastIndex) {
                                selectedPenColorIdx = 1 // Marino
                                sheet?.inkColor = EditorPalettes.penColors[selectedPenColorIdx].argb
                            }
                        },
                        onSelectTemplate = { template ->
                            paperTemplate = template
                            sheet?.paperTemplate = template
                        },
                        onDismiss = { showPaperDialog = false }
                    )
                }

                if (showDiagnosticsDialog) {
                    DiagnosticsDialog(
                        telemetry = telemetry,
                        showHud = showHudOverlay,
                        debugCompareNormalized = debugCompareNormalized,
                        remoteCount = remoteCount,
                        remoteStatus = remoteStatus,
                        onToggleHud = { enabled ->
                            showHudOverlay = enabled
                            sheet?.showDiagnostics = enabled
                        },
                        onToggleCompareNormalized = { enabled ->
                            debugCompareNormalized = enabled
                            sheet?.debugCompareNormalized = enabled
                        },
                        onOpenHelp = {
                            showDiagnosticsDialog = false
                            showHelpDialog = true
                        },
                        onDismiss = { showDiagnosticsDialog = false }
                    )
                }

                if (showHelpDialog) {
                    AlertDialog(
                        onDismissRequest = { showHelpDialog = false },
                        containerColor = GraphiteSurface,
                        title = { Text("Guía rápida · S Pen Lab") },
                        text = {
                            Text(
                                "• Escribir: Usa el S Pen; un solo dedo nunca pinta (rechazo de palma).\n" +
                                    "• Zoom y paneo: Pellizca o arrastra con dos dedos para acercar o moverte.\n" +
                                    "• Borrador rápido: Mantén el botón lateral del S Pen mientras tocas un trazo.\n" +
                                    "• Figuras geométricas: Mantén la punta quieta medio segundo al terminar un trazo (línea, flecha, círculo, rectángulo, triángulo), o activa «Formas» en la barra.\n" +
                                    "• Lazo: Encierra trazos para moverlos, duplicarlos o borrarlos.\n" +
                                    "• Air Actions: Clic = deshacer · Gesto → = rotar color."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showHelpDialog = false }) { Text("Entendido") }
                        }
                    )
                }

                if (confirmClear) {
                    AlertDialog(
                        onDismissRequest = { confirmClear = false },
                        containerColor = GraphiteSurface,
                        title = { Text("¿Limpiar la hoja?") },
                        text = { Text("Puedes recuperar el dibujo con Deshacer.") },
                        dismissButton = {
                            TextButton(onClick = { confirmClear = false }) { Text("Cancelar") }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                sheet?.clear()
                                confirmClear = false
                            }) { Text("Limpiar", color = Color(0xFFE06C75)) }
                        }
                    )
                }
            }
        }
    }

    private fun syncSheetConfig(view: PenSheet) {
        view.toolMode = activeTool
        view.inkColor = EditorPalettes.penColors[selectedPenColorIdx].argb
        view.highlighterColor = EditorPalettes.highlighterColors[selectedHighlighterIdx].argb
        view.penWidthDp = penWidthDp
        view.highlighterWidthDp = highlighterWidthDp
        view.eraserRadiusDp = eraserRadiusDp
        view.autoShapeEnabled = autoShapeEnabled
        view.paperStyle = paperStyle
        view.paperTemplate = paperTemplate
        view.showDiagnostics = showHudOverlay
        view.debugCompareNormalized = debugCompareNormalized
    }

    private fun currentIndicatorColor(): Color = when (activeTool) {
        ToolMode.HIGHLIGHTER -> Color(EditorPalettes.highlighterColors[selectedHighlighterIdx].argb)
        ToolMode.ERASER -> Color(0xFFE06C75)
        ToolMode.LASSO -> Petroleum
        ToolMode.PEN -> Color(EditorPalettes.penColors[selectedPenColorIdx].argb)
    }

    private fun cycleColor() {
        if (sheet?.isWriting == true) return
        selectedPenColorIdx = (selectedPenColorIdx + 1) % EditorPalettes.penColors.size
        sheet?.inkColor = EditorPalettes.penColors[selectedPenColorIdx].argb
    }

    private fun exportAndSharePng() {
        val bmp = sheet?.exportBitmap()
        if (bmp == null) {
            Toast.makeText(this, "No hay contenido listo para exportar", Toast.LENGTH_SHORT).show()
            return
        }
        val filename = "SPenLab_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/S Pen Lab")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "No se pudo crear el archivo PNG", Toast.LENGTH_SHORT).show()
            return
        }
        contentResolver.openOutputStream(uri)?.use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Exportar apunte como PNG"))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_F9 && event.keyCode != KeyEvent.KEYCODE_F10) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        val view = sheet ?: return true
        remoteCount++
        remoteStatus = when {
            showHelpDialog || showDiagnosticsDialog || showPaperDialog || confirmClear || view.isWriting ->
                "Comando recibido · editor ocupado"
            event.keyCode == KeyEvent.KEYCODE_F9 ->
                if (view.undo()) "Clic · último cambio deshecho" else "Clic · nada por deshacer"
            else -> {
                cycleColor()
                "Gesto → · color ${EditorPalettes.penColors[selectedPenColorIdx].name}"
            }
        }
        return true
    }
}

@Composable
private fun TopEditorBar(
    title: String,
    zoomPercent: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    onFitContent: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenPaper: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onExportPng: () -> Unit
) {
    Surface(
        color = GraphiteSurface,
        border = BorderStroke(0.5.dp, GraphiteBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = InkTextPrimary
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                color = PetroleumDeep,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.clickable(onClick = onFitContent)
            ) {
                Text(
                    text = "$zoomPercent% · Encajar",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkTextPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onUndo, enabled = canUndo) {
                Text("↶", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onRedo, enabled = canRedo) {
                Text("↷", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onOpenPaper) {
                Text("Hoja", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = onOpenDiagnostics) {
                Text("Lab", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = onExportPng) {
                Text("PNG", style = MaterialTheme.typography.labelLarge, color = Petroleum)
            }
        }
    }
}

@Composable
private fun BottomToolBar(
    activeTool: ToolMode,
    activeColor: Color,
    autoShapeEnabled: Boolean,
    hasContent: Boolean,
    strokePanelOpen: Boolean,
    onSelectTool: (ToolMode) -> Unit,
    onToggleStrokePanel: () -> Unit,
    onToggleAutoShape: () -> Unit,
    onRequestClear: () -> Unit
) {
    Surface(
        color = GraphiteSurface,
        border = BorderStroke(0.5.dp, GraphiteBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ToolMode.entries.forEach { tool ->
                val selected = activeTool == tool
                FilterChip(
                    selected = selected,
                    onClick = { onSelectTool(tool) },
                    label = { Text(tool.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PetroleumDeep,
                        selectedLabelColor = InkTextPrimary,
                        containerColor = GraphiteBg,
                        labelColor = InkTextSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = GraphiteBorder,
                        selectedBorderColor = Petroleum
                    )
                )
            }

            // Color & Width trigger
            Surface(
                color = if (strokePanelOpen) PetroleumDeep else GraphiteBg,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (strokePanelOpen) Petroleum else GraphiteBorder),
                modifier = Modifier.clickable(onClick = onToggleStrokePanel)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(activeColor)
                            .border(1.dp, InkTextPrimary.copy(alpha = 0.5f), CircleShape)
                    )
                    Text("Trazo", style = MaterialTheme.typography.labelLarge, color = InkTextPrimary)
                }
            }

            // Auto-Shape toggle
            FilterChip(
                selected = autoShapeEnabled,
                onClick = onToggleAutoShape,
                label = { Text("Formas") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PetroleumDeep,
                    selectedLabelColor = InkTextPrimary,
                    containerColor = GraphiteBg,
                    labelColor = InkTextSecondary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = autoShapeEnabled,
                    borderColor = GraphiteBorder,
                    selectedBorderColor = Petroleum
                )
            )

            TextButton(onClick = onRequestClear, enabled = hasContent) {
                Text("Limpiar", color = if (hasContent) Color(0xFFE06C75) else InkTextSecondary)
            }
        }
    }
}

@Composable
private fun StrokeConfigPanel(
    tool: ToolMode,
    selectedPenIdx: Int,
    selectedHighlighterIdx: Int,
    penWidthDp: Float,
    highlighterWidthDp: Float,
    eraserRadiusDp: Float,
    onSelectPenColor: (Int) -> Unit,
    onSelectHighlighterColor: (Int) -> Unit,
    onChangeWidth: (Float) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = GraphiteSurface.copy(alpha = 0.97f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, GraphiteBorder),
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (tool) {
                        ToolMode.HIGHLIGHTER -> "Color y grosor · Resaltador"
                        ToolMode.ERASER -> "Radio del borrador"
                        else -> "Color y grosor · Pluma"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = InkTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClose) { Text("Cerrar") }
            }

            if (tool != ToolMode.ERASER) {
                Spacer(Modifier.height(6.dp))
                val swatches = if (tool == ToolMode.HIGHLIGHTER) {
                    EditorPalettes.highlighterColors
                } else {
                    EditorPalettes.penColors
                }
                val selectedIdx = if (tool == ToolMode.HIGHLIGHTER) selectedHighlighterIdx else selectedPenIdx
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    swatches.forEachIndexed { idx, swatch ->
                        val isSelected = idx == selectedIdx
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(swatch.argb))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) Petroleum else GraphiteBorder,
                                    shape = CircleShape
                                )
                                .clickable {
                                    if (tool == ToolMode.HIGHLIGHTER) onSelectHighlighterColor(idx)
                                    else onSelectPenColor(idx)
                                }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            val value = when (tool) {
                ToolMode.HIGHLIGHTER -> highlighterWidthDp
                ToolMode.ERASER -> eraserRadiusDp
                else -> penWidthDp
            }
            val range = when (tool) {
                ToolMode.HIGHLIGHTER -> 10f..32f
                ToolMode.ERASER -> 6f..28f
                else -> 2f..12f
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = String.format(Locale.getDefault(), "%.1f dp", value),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkTextSecondary,
                    modifier = Modifier.width(56.dp)
                )
                Slider(
                    value = value,
                    onValueChange = onChangeWidth,
                    valueRange = range,
                    colors = SliderDefaults.colors(
                        thumbColor = Petroleum,
                        activeTrackColor = Petroleum,
                        inactiveTrackColor = GraphiteBorder
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PaperSettingsDialog(
    currentStyle: PaperStyle,
    currentTemplate: PaperTemplate,
    onSelectStyle: (PaperStyle) -> Unit,
    onSelectTemplate: (PaperTemplate) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GraphiteSurface,
        title = { Text("Papel y plantilla") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Color de hoja", style = MaterialTheme.typography.labelLarge, color = InkTextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperStyle.entries.forEach { style ->
                        val selected = style == currentStyle
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectStyle(style) },
                            label = { Text(style.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PetroleumDeep,
                                selectedLabelColor = InkTextPrimary
                            )
                        )
                    }
                }
                HorizontalDivider(color = GraphiteBorder)
                Text("Plantilla", style = MaterialTheme.typography.labelLarge, color = InkTextSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaperTemplate.entries.chunked(3).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { tpl ->
                                val selected = tpl == currentTemplate
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectTemplate(tpl) },
                                    label = { Text(tpl.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = PetroleumDeep,
                                        selectedLabelColor = InkTextPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Listo") }
        }
    )
}

@Composable
private fun DiagnosticsDialog(
    telemetry: PenTelemetry,
    showHud: Boolean,
    debugCompareNormalized: Boolean,
    remoteCount: Int,
    remoteStatus: String,
    onToggleHud: (Boolean) -> Unit,
    onToggleCompareNormalized: (Boolean) -> Unit,
    onOpenHelp: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GraphiteSurface,
        title = { Text("Diagnóstico S Pen · Lab") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Mostrar barra de telemetría en el lienzo",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = showHud,
                        onCheckedChange = onToggleHud,
                        colors = SwitchDefaults.colors(checkedThumbColor = Petroleum)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Paso A: Superponer trazo original (rojo) vs normalizado (azul)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = debugCompareNormalized,
                        onCheckedChange = onToggleCompareNormalized,
                        colors = SwitchDefaults.colors(checkedThumbColor = Petroleum)
                    )
                }
                HorizontalDivider(color = GraphiteBorder)
                Text(
                    String.format(
                        Locale.getDefault(),
                        "Presión: %.2f  ·  Inclinación: %d°  ·  Zoom: %d%%",
                        telemetry.pressure, telemetry.tiltDegrees, telemetry.zoomPercent
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkTextSecondary
                )
                Text(
                    "Botón lateral: ${if (telemetry.sideButtonPressed) "presionado" else "suelto"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkTextSecondary
                )
                Text(
                    "Contactos táctiles: ${telemetry.fingerContacts}  ·  Cancelados: ${telemetry.cancellations}",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkTextSecondary
                )
                telemetry.lastShapeDetected?.let {
                    Text("Última figura: $it", style = MaterialTheme.typography.bodySmall, color = Petroleum)
                }
                HorizontalDivider(color = GraphiteBorder)
                Text("Air Actions · $remoteCount recibidas", style = MaterialTheme.typography.labelMedium)
                Text(remoteStatus, style = MaterialTheme.typography.bodySmall, color = InkTextSecondary)
            }
        },
        dismissButton = {
            TextButton(onClick = onOpenHelp) { Text("Guía de uso") }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    )
}
