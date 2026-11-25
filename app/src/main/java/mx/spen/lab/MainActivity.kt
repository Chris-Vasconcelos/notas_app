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
import androidx.activity.SystemBarStyle
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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale

internal val GraphiteBg = Color(0xFF14161A)
internal val GraphiteSurface = Color(0xFF1D2128)
internal val GraphiteBorder = Color(0xFF2C333F)
internal val Petroleum = Color(0xFF2AA1B7)
private val PetroleumDeep = Color(0xFF165664)
internal val InkTextPrimary = Color(0xFFEDF1F7)
internal val InkTextSecondary = Color(0xFF9AA5B4)

class MainActivity : ComponentActivity() {
    private var sheet: PenSheet? = null

    // Editor state
    private var activeTool by mutableStateOf(ToolMode.PEN)
    private var selectedColorSetIdx by mutableIntStateOf(0) // Clásicos
    private var selectedPenColorIdx by mutableIntStateOf(1) // Marino
    private var selectedHighlighterIdx by mutableIntStateOf(0) // Amarillo
    private var selectedTapeIdx by mutableIntStateOf(0) // Menta
    private var penWidthDp by mutableFloatStateOf(4.2f)
    private var highlighterWidthDp by mutableFloatStateOf(18f)
    private var tapeWidthDp by mutableFloatStateOf(22f)
    private var eraserRadiusDp by mutableFloatStateOf(12f)
    private var autoShapeEnabled by mutableStateOf(false)
    private var paperStyle by mutableStateOf(PaperStyle.LIGHT)
    private var paperTemplate by mutableStateOf(PaperTemplate.GRID)

    // Notein-inspired advanced settings
    private var readingMode by mutableStateOf(false)
    private var revealAllTapes by mutableStateOf(false)
    private var gestureTapUndoRedo by mutableStateOf(true)
    private var adaptiveStrokeWidth by mutableStateOf(true)
    private var allowFingerDrawing by mutableStateOf(false)
    private var pressureEraserEnabled by mutableStateOf(false)
    private var pressureEraserThreshold by mutableFloatStateOf(0.78f)

    // Favorite presets in the movable pencil box
    private val favoritePresets = mutableStateListOf(
        PenPreset(ToolMode.PEN, EditorPalettes.basicColors[1].argb, 4.2f),
        PenPreset(ToolMode.BALLPOINT, EditorPalettes.basicColors[4].argb, 3.0f),
        PenPreset(ToolMode.HIGHLIGHTER, EditorPalettes.highlighterColors[0].argb, 18f)
    )

    // History & selection state
    private var canUndo by mutableStateOf(false)
    private var canRedo by mutableStateOf(false)
    private var hasSelection by mutableStateOf(false)

    // Panels & dialogs
    private var focusMode by mutableStateOf(false)
    private var showStrokePanel by mutableStateOf(false)
    private var showPaperDialog by mutableStateOf(false)
    private var showToolSettingsDialog by mutableStateOf(false)
    private var showDiagnosticsDialog by mutableStateOf(false)
    private var showHelpDialog by mutableStateOf(false)
    private var confirmClear by mutableStateOf(false)

    // Diagnostics & Air Actions
    private var showHudOverlay by mutableStateOf(false)
    private var debugCompareNormalized by mutableStateOf(false)
    private var telemetry by mutableStateOf(PenTelemetry())
    private var remoteCount by mutableIntStateOf(0)
    private var remoteStatus by mutableStateOf("En espera de acciones")
    private var canvasSize by mutableStateOf(IntSize.Zero)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val darkBars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = darkBars, navigationBarStyle = darkBars)
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
                        readingMode = readingMode,
                        onToggleReadingMode = {
                            readingMode = !readingMode
                            sheet?.readingMode = readingMode
                            if (readingMode) showStrokePanel = false
                        },
                        focusMode = focusMode,
                        onToggleFocus = {
                            if (sheet?.isWriting != true) {
                                showStrokePanel = false
                                focusMode = !focusMode
                            }
                        },
                        canUndo = canUndo,
                        canRedo = canRedo,
                        revealAllTapes = revealAllTapes,
                        onToggleRevealTapes = {
                            revealAllTapes = !revealAllTapes
                            sheet?.revealAllTapes = revealAllTapes
                        },
                        onFitContent = { sheet?.fitToContent() },
                        onUndo = { sheet?.undo() },
                        onRedo = { sheet?.redo() },
                        onOpenPaper = { showPaperDialog = true },
                        onOpenToolSettings = { showToolSettingsDialog = true },
                        onOpenDiagnostics = { showDiagnosticsDialog = true },
                        onExportPng = { exportAndSharePng() },
                        canClear = canUndo,
                        onClear = { confirmClear = true }
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onSizeChanged { canvasSize = it }
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
                                Row(
                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(shapeName, style = MaterialTheme.typography.labelMedium)
                                    TextButton(onClick = { sheet?.restoreOriginalStroke() }) {
                                        Text("Conservar trazo")
                                    }
                                }
                            }
                        }

                        // Floating lasso action bar (with 1-tap recolor swatches)
                        androidx.compose.animation.AnimatedVisibility(
                            visible = hasSelection,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                        ) {
                            Surface(
                                color = GraphiteSurface.copy(alpha = 0.96f),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Petroleum.copy(alpha = 0.6f)),
                                shadowElevation = 8.dp
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(
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
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    ) {
                                        currentPenPalette().forEach { swatch ->
                                            Box(
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(swatch.argb))
                                                    .border(1.dp, GraphiteBorder, CircleShape)
                                                    .clickable { sheet?.recolorSelection(swatch.argb) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Floating color & width tuning popover
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showStrokePanel,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            StrokeConfigPanel(
                                tool = activeTool,
                                selectedColorSetIdx = selectedColorSetIdx,
                                selectedPenIdx = selectedPenColorIdx,
                                selectedHighlighterIdx = selectedHighlighterIdx,
                                selectedTapeIdx = selectedTapeIdx,
                                currentWidthDp = currentToolWidthDp(),
                                autoShapeEnabled = autoShapeEnabled,
                                onSelectColorSet = { setIdx ->
                                    selectedColorSetIdx = setIdx
                                    selectedPenColorIdx = selectedPenColorIdx.coerceIn(0, currentPenPalette().lastIndex)
                                    sheet?.inkColor = currentPenPalette()[selectedPenColorIdx].argb
                                },
                                onSelectPenColor = { idx ->
                                    selectedPenColorIdx = idx
                                    sheet?.inkColor = currentPenPalette()[idx].argb
                                },
                                onSelectHighlighterColor = { idx ->
                                    selectedHighlighterIdx = idx
                                    sheet?.highlighterColor = EditorPalettes.highlighterColors[idx].argb
                                },
                                onSelectTapeColor = { idx ->
                                    selectedTapeIdx = idx
                                    sheet?.tapeColor = EditorPalettes.tapeColors[idx].argb
                                },
                                onChangeWidth = { value -> applyWidthToActiveTool(value) },
                                onToggleAutoShape = {
                                    autoShapeEnabled = !autoShapeEnabled
                                    sheet?.autoShapeEnabled = autoShapeEnabled
                                },
                                onSaveFavorite = {
                                    val preset = PenPreset(
                                        tool = activeTool,
                                        colorArgb = currentActiveArgb(),
                                        widthDp = currentToolWidthDp()
                                    )
                                    if (!favoritePresets.contains(preset)) {
                                        if (favoritePresets.size >= 5) favoritePresets.removeAt(0)
                                        favoritePresets.add(preset)
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Guardado en el estuche flotante",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onClose = { showStrokePanel = false }
                            )
                        }

                        // Movable Floating Pencil Box ("笔盒") — moves across the screen, docks vertically or horizontally, or collapses
                        if (!focusMode && !readingMode) {
                            MovablePenBox(
                                containerSize = canvasSize,
                                activeTool = activeTool,
                                activeColor = currentIndicatorColor(),
                                currentWidthDp = currentToolWidthDp(),
                                autoShapeEnabled = autoShapeEnabled,
                                strokePanelOpen = showStrokePanel,
                                favorites = favoritePresets,
                                onSelectTool = { tool ->
                                    activeTool = tool
                                    sheet?.toolMode = tool
                                    if (tool == ToolMode.LASSO || tool == ToolMode.LASER) {
                                        showStrokePanel = false
                                    }
                                },
                                onToggleStrokePanel = { showStrokePanel = !showStrokePanel },
                                onToggleAutoShape = {
                                    autoShapeEnabled = !autoShapeEnabled
                                    sheet?.autoShapeEnabled = autoShapeEnabled
                                },
                                onQuickWidthSelect = { w -> applyWidthToActiveTool(w) },
                                onApplyFavorite = { preset ->
                                    activeTool = preset.tool
                                    sheet?.toolMode = preset.tool
                                    applyWidthToActiveTool(preset.widthDp)
                                    when (preset.tool) {
                                        ToolMode.HIGHLIGHTER -> {
                                            val idx = EditorPalettes.highlighterColors.indexOfFirst { it.argb == preset.colorArgb }
                                            if (idx >= 0) selectedHighlighterIdx = idx
                                            sheet?.highlighterColor = preset.colorArgb
                                        }
                                        ToolMode.TAPE -> {
                                            val idx = EditorPalettes.tapeColors.indexOfFirst { it.argb == preset.colorArgb }
                                            if (idx >= 0) selectedTapeIdx = idx
                                            sheet?.tapeColor = preset.colorArgb
                                        }
                                        else -> {
                                            sheet?.inkColor = preset.colorArgb
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                if (showPaperDialog) {
                    PaperSettingsDialog(
                        currentStyle = paperStyle,
                        currentTemplate = paperTemplate,
                        onSelectStyle = { style ->
                            paperStyle = style
                            sheet?.paperStyle = style
                            val palette = currentPenPalette()
                            if (style.isDark && selectedPenColorIdx in 0..1) {
                                selectedPenColorIdx = palette.lastIndex
                                sheet?.inkColor = palette[selectedPenColorIdx].argb
                            } else if (!style.isDark && selectedPenColorIdx == palette.lastIndex) {
                                selectedPenColorIdx = 1
                                sheet?.inkColor = palette[selectedPenColorIdx].argb
                            }
                        },
                        onSelectTemplate = { template ->
                            paperTemplate = template
                            sheet?.paperTemplate = template
                        },
                        onDismiss = { showPaperDialog = false }
                    )
                }

                if (showToolSettingsDialog) {
                    ToolAndGestureSettingsDialog(
                        gestureTapUndoRedo = gestureTapUndoRedo,
                        adaptiveStrokeWidth = adaptiveStrokeWidth,
                        allowFingerDrawing = allowFingerDrawing,
                        pressureEraserEnabled = pressureEraserEnabled,
                        pressureEraserThreshold = pressureEraserThreshold,
                        onChangeGestureUndoRedo = {
                            gestureTapUndoRedo = it
                            sheet?.gestureTapUndoRedo = it
                        },
                        onChangeAdaptiveStroke = {
                            adaptiveStrokeWidth = it
                            sheet?.adaptiveStrokeWidth = it
                        },
                        onChangeAllowFinger = {
                            allowFingerDrawing = it
                            sheet?.allowFingerDrawing = it
                        },
                        onChangePressureEraser = {
                            pressureEraserEnabled = it
                            sheet?.pressureEraserEnabled = it
                        },
                        onChangePressureThreshold = {
                            pressureEraserThreshold = it
                            sheet?.pressureEraserThreshold = it
                        },
                        onDismiss = { showToolSettingsDialog = false }
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
                                "• Estuche flotante: Arrástralo desde los puntos de agarre a cualquier parte. Si lo llevas a los bordes laterales se vuelve vertical; con «−» se contrae en una burbuja flotante.\n" +
                                    "• Opciones del lápiz: Toca de nuevo la herramienta activa o el círculo de color para abrir paletas (Clásicos, Morandi, Intensos), grosores y guardar favoritos (★).\n" +
                                    "• Cinta de estudio: Tapa fórmulas o definiciones; tócalas con el dedo para revelarlas u ocultarlas al estudiar.\n" +
                                    "• Puntero láser: Subraya al explicar; el trazo brillante desaparece solo en 1.5 s.\n" +
                                    "• Gestos: Toque con 2 dedos = Deshacer · Toque con 3 dedos = Rehacer · Pellizcar con 2 dedos = Zoom."
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

    private fun currentPenPalette(): List<InkSwatch> =
        EditorPalettes.colorSets[selectedColorSetIdx].swatches

    private fun currentToolWidthDp(): Float = when (activeTool) {
        ToolMode.HIGHLIGHTER -> highlighterWidthDp
        ToolMode.TAPE -> tapeWidthDp
        ToolMode.ERASER -> eraserRadiusDp
        else -> penWidthDp
    }

    private fun applyWidthToActiveTool(value: Float) {
        when (activeTool) {
            ToolMode.HIGHLIGHTER -> {
                highlighterWidthDp = value
                sheet?.highlighterWidthDp = value
            }
            ToolMode.TAPE -> {
                tapeWidthDp = value
                sheet?.tapeWidthDp = value
            }
            ToolMode.ERASER -> {
                eraserRadiusDp = value
                sheet?.eraserRadiusDp = value
            }
            else -> {
                penWidthDp = value
                sheet?.penWidthDp = value
            }
        }
    }

    private fun currentActiveArgb(): Int = when (activeTool) {
        ToolMode.HIGHLIGHTER -> EditorPalettes.highlighterColors[selectedHighlighterIdx].argb
        ToolMode.TAPE -> EditorPalettes.tapeColors[selectedTapeIdx].argb
        else -> currentPenPalette()[selectedPenColorIdx].argb
    }

    private fun syncSheetConfig(view: PenSheet) {
        view.toolMode = activeTool
        view.inkColor = currentPenPalette()[selectedPenColorIdx].argb
        view.highlighterColor = EditorPalettes.highlighterColors[selectedHighlighterIdx].argb
        view.tapeColor = EditorPalettes.tapeColors[selectedTapeIdx].argb
        view.penWidthDp = penWidthDp
        view.highlighterWidthDp = highlighterWidthDp
        view.tapeWidthDp = tapeWidthDp
        view.eraserRadiusDp = eraserRadiusDp
        view.autoShapeEnabled = autoShapeEnabled
        view.readingMode = readingMode
        view.revealAllTapes = revealAllTapes
        view.gestureTapUndoRedo = gestureTapUndoRedo
        view.adaptiveStrokeWidth = adaptiveStrokeWidth
        view.allowFingerDrawing = allowFingerDrawing
        view.pressureEraserEnabled = pressureEraserEnabled
        view.pressureEraserThreshold = pressureEraserThreshold
        view.paperStyle = paperStyle
        view.paperTemplate = paperTemplate
        view.showDiagnostics = showHudOverlay
        view.debugCompareNormalized = debugCompareNormalized
    }

    private fun currentIndicatorColor(): Color = when (activeTool) {
        ToolMode.HIGHLIGHTER -> Color(EditorPalettes.highlighterColors[selectedHighlighterIdx].argb)
        ToolMode.TAPE -> Color(EditorPalettes.tapeColors[selectedTapeIdx].argb)
        ToolMode.LASER -> Color(0xFFFF1744)
        ToolMode.ERASER -> Color(0xFFE06C75)
        ToolMode.LASSO -> Petroleum
        else -> Color(currentPenPalette()[selectedPenColorIdx].argb)
    }

    private fun cycleColor() {
        if (sheet?.isWriting == true) return
        val palette = currentPenPalette()
        selectedPenColorIdx = (selectedPenColorIdx + 1) % palette.size
        sheet?.inkColor = palette[selectedPenColorIdx].argb
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
            showHelpDialog || showDiagnosticsDialog || showPaperDialog || showToolSettingsDialog || confirmClear || view.isWriting ->
                "Comando recibido · editor ocupado"
            event.keyCode == KeyEvent.KEYCODE_F9 ->
                if (view.undo()) "Clic · último cambio deshecho" else "Clic · nada por deshacer"
            else -> {
                cycleColor()
                "Gesto → · color ${currentPenPalette()[selectedPenColorIdx].name}"
            }
        }
        return true
    }
}

@Composable
private fun StrokeConfigPanel(
    tool: ToolMode,
    selectedColorSetIdx: Int,
    selectedPenIdx: Int,
    selectedHighlighterIdx: Int,
    selectedTapeIdx: Int,
    currentWidthDp: Float,
    autoShapeEnabled: Boolean,
    onSelectColorSet: (Int) -> Unit,
    onSelectPenColor: (Int) -> Unit,
    onSelectHighlighterColor: (Int) -> Unit,
    onSelectTapeColor: (Int) -> Unit,
    onChangeWidth: (Float) -> Unit,
    onToggleAutoShape: () -> Unit,
    onSaveFavorite: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = GraphiteSurface.copy(alpha = 0.97f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, GraphiteBorder),
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Opciones · ${tool.label}",
                    style = MaterialTheme.typography.labelLarge,
                    color = InkTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (tool != ToolMode.ERASER && tool != ToolMode.LASSO && tool != ToolMode.LASER) {
                    TextButton(onClick = onSaveFavorite) {
                        Text("★ Favorito", color = Petroleum)
                    }
                }
                TextButton(onClick = onClose) { Text("Cerrar") }
            }

            if (tool != ToolMode.ERASER) {
                // Color set tabs for Pen / Ballpoint / Dashed
                if (tool != ToolMode.HIGHLIGHTER && tool != ToolMode.TAPE) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        EditorPalettes.colorSets.forEachIndexed { idx, set ->
                            FilterChip(
                                selected = idx == selectedColorSetIdx,
                                onClick = { onSelectColorSet(idx) },
                                label = { Text(set.title) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PetroleumDeep,
                                    selectedLabelColor = InkTextPrimary
                                )
                            )
                        }
                    }
                }

                val swatches = when (tool) {
                    ToolMode.HIGHLIGHTER -> EditorPalettes.highlighterColors
                    ToolMode.TAPE -> EditorPalettes.tapeColors
                    else -> EditorPalettes.colorSets[selectedColorSetIdx].swatches
                }
                val selectedIdx = when (tool) {
                    ToolMode.HIGHLIGHTER -> selectedHighlighterIdx
                    ToolMode.TAPE -> selectedTapeIdx
                    else -> selectedPenIdx
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    swatches.forEachIndexed { idx, swatch ->
                        val isSelected = idx == selectedIdx
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        when (tool) {
                                            ToolMode.HIGHLIGHTER -> onSelectHighlighterColor(idx)
                                            ToolMode.TAPE -> onSelectTapeColor(idx)
                                            else -> onSelectPenColor(idx)
                                        }
                                    }
                                )
                                .semantics { contentDescription = swatch.name },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(swatch.argb))
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) Petroleum else GraphiteBorder,
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            val range = when (tool) {
                ToolMode.HIGHLIGHTER -> 10f..32f
                ToolMode.TAPE -> 14f..36f
                ToolMode.ERASER -> 6f..28f
                else -> 1.8f..12f
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = String.format(Locale.getDefault(), "%.1f dp", currentWidthDp),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkTextSecondary,
                    modifier = Modifier.width(56.dp)
                )
                Slider(
                    value = currentWidthDp.coerceIn(range.start, range.endInclusive),
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

            if (tool == ToolMode.PEN || tool == ToolMode.BALLPOINT || tool == ToolMode.DASHED || tool == ToolMode.HIGHLIGHTER) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Trazo a figura automático (o mantener 0.4 s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkTextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = autoShapeEnabled,
                        onCheckedChange = { onToggleAutoShape() },
                        colors = SwitchDefaults.colors(checkedThumbColor = Petroleum)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolAndGestureSettingsDialog(
    gestureTapUndoRedo: Boolean,
    adaptiveStrokeWidth: Boolean,
    allowFingerDrawing: Boolean,
    pressureEraserEnabled: Boolean,
    pressureEraserThreshold: Float,
    onChangeGestureUndoRedo: (Boolean) -> Unit,
    onChangeAdaptiveStroke: (Boolean) -> Unit,
    onChangeAllowFinger: (Boolean) -> Unit,
    onChangePressureEraser: (Boolean) -> Unit,
    onChangePressureThreshold: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GraphiteSurface,
        title = { Text("Ajustes de lápiz, página y gestos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Toque 2 dedos = Deshacer / 3 dedos = Rehacer", style = MaterialTheme.typography.bodyMedium)
                        Text("Gesto rápido con los dedos sobre la hoja", style = MaterialTheme.typography.bodySmall, color = InkTextSecondary)
                    }
                    Switch(
                        checked = gestureTapUndoRedo,
                        onCheckedChange = onChangeGestureUndoRedo,
                        colors = SwitchDefaults.colors(checkedThumbColor = Petroleum)
                    )
                }
                HorizontalDivider(color = GraphiteBorder)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Grosor de trazo adaptativo", style = MaterialTheme.typography.bodyMedium)
                        Text("Escala el grosor proporcionalmente al zoom", style = MaterialTheme.typography.bodySmall, color = InkTextSecondary)
                    }
                    Switch(
                        checked = adaptiveStrokeWidth,
                        onCheckedChange = onChangeAdaptiveStroke,
                        colors = SwitchDefaults.colors(checkedThumbColor = Petroleum)
                    )
                }
                HorizontalDivider(color = GraphiteBorder)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Cambio rápido S Pen / Dedo", style = MaterialTheme.typography.bodyMedium)
                        Text("Permitir dibujar también con 1 dedo", style = MaterialTheme.typography.bodySmall, color = InkTextSecondary)
                    }
                    Switch(
                        checked = allowFingerDrawing,
                        onCheckedChange = onChangeAllowFinger,
                        colors = SwitchDefaults.colors(checkedThumbColor = Petroleum)
                    )
                }
                HorizontalDivider(color = GraphiteBorder)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Borrador por presión fuerte", style = MaterialTheme.typography.bodyMedium)
                        Text("Activa el borrador al presionar fuerte el S Pen", style = MaterialTheme.typography.bodySmall, color = InkTextSecondary)
                    }
                    Switch(
                        checked = pressureEraserEnabled,
                        onCheckedChange = onChangePressureEraser,
                        colors = SwitchDefaults.colors(checkedThumbColor = Petroleum)
                    )
                }
                if (pressureEraserEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Umbral ${(pressureEraserThreshold * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkTextSecondary,
                            modifier = Modifier.width(82.dp)
                        )
                        Slider(
                            value = pressureEraserThreshold,
                            onValueChange = onChangePressureThreshold,
                            valueRange = 0.45f..0.95f,
                            colors = SliderDefaults.colors(thumbColor = Petroleum, activeTrackColor = Petroleum),
                            modifier = Modifier.weight(1f)
                        )
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
