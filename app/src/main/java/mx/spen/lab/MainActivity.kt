package mx.spen.lab

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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

private data class PendingTextBoxEdit(
    val existing: PenSheet.TextBoxItem?,
    val worldX: Float,
    val worldY: Float
)

private data class PendingLinkEdit(
    val existing: PenSheet.LinkItem?,
    val worldX: Float,
    val worldY: Float
)

class MainActivity : ComponentActivity() {
    private var sheet: PenSheet? = null
    private var splitSheet: PenSheet? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            importPickedImage(uri)
        }
    }

    // Notebook & Folder Library state (Sections 01, 04, 16)
    private var activeNotebookUuid by mutableStateOf("default-notebook")
    private var activeNotebookTitle by mutableStateOf("Cuaderno Principal")
    private var activeNotebookFolderId by mutableStateOf<String?>(null)
    private var activeNotebookCoverColor by mutableIntStateOf(0xFF2AA1B7.toInt())
    private var splitNotebookUuid by mutableStateOf<String?>(null)
    private var splitNotebookTitle by mutableStateOf("")
    private val allNotebooks = mutableStateListOf<NoteMetadata>()
    private val allFolders = mutableStateListOf<NotebookFolder>()
    private var selectedLibraryFolderId by mutableStateOf<String?>(null)
    private var leftDrawerOpen by mutableStateOf(false)
    private var libraryModalOpen by mutableStateOf(false)

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
    private var pressureSensitivity by mutableFloatStateOf(1.0f)
    private var ballpointOpacity by mutableFloatStateOf(1.0f)
    private var autoShapeEnabled by mutableStateOf(false)

    // Tool-specific sub-modes (Section 09, 10, 11)
    private var directShapeKind by mutableStateOf(DirectShapeKind.AUTO)
    private var shapeFillEnabled by mutableStateOf(false)
    private var shapeFillOpacity by mutableFloatStateOf(0.25f)
    private var eraserMode by mutableStateOf(EraserMode.STROKE)
    private var eraseOnlyHighlighter by mutableStateOf(false)
    private var eraseOnlyTape by mutableStateOf(false)
    private var lassoMode by mutableStateOf(LassoMode.FREEHAND)
    private var lassoSelectInk by mutableStateOf(true)
    private var lassoSelectShapes by mutableStateOf(true)
    private var lassoSelectText by mutableStateOf(true)
    private var laserColorArgb by mutableIntStateOf(0xFFFF1744.toInt())
    private var laserFadeDurationMs by mutableLongStateOf(1500L)

    // Paper, layout mode & live template controls (Section 01, 03, 05 & 22)
    private var layoutMode by mutableStateOf(CanvasLayoutMode.PAGED)
    private var autoAddPage by mutableStateOf(true)
    private var paperStyle by mutableStateOf(PaperStyle.LIGHT)
    private var paperTemplate by mutableStateOf(PaperTemplate.GRID)
    private var templateSpacingDp by mutableFloatStateOf(22f)
    private var templateOpacity by mutableFloatStateOf(1.0f)

    // Notein-inspired advanced settings & guides
    private var rulerEnabled by mutableStateOf(false)
    private var minimapOpen by mutableStateOf(false)
    private var rightSidebarOpen by mutableStateOf(false)
    private var gridViewOpen by mutableStateOf(false)
    private var fixedToolbarMode by mutableStateOf(false)
    private var readingMode by mutableStateOf(false)
    private var revealAllTapes by mutableStateOf(false)
    private var gestureTapUndoRedo by mutableStateOf(true)
    private var doubleTapFitPage by mutableStateOf(true)
    private var adaptiveStrokeWidth by mutableStateOf(true)
    private var allowFingerDrawing by mutableStateOf(false)
    private var pressureEraserEnabled by mutableStateOf(false)
    private var pressureEraserThreshold by mutableFloatStateOf(0.78f)

    // Layers, Outline & Comments state (Section 17 & 19)
    private var activeLayerName by mutableStateOf("Capa 1")
    private var layersRevision by mutableIntStateOf(0)
    private val outlines = mutableStateListOf<OutlineEntry>()
    private val comments = mutableStateListOf<CommentEntry>()
    private var showAddOutlineDialog by mutableStateOf(false)
    private var showAddCommentDialog by mutableStateOf(false)

    // Debounced background auto-save
    private val saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val debouncedSaveRunnable = Runnable { performPersistToDisk() }

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
    private var showLayersDialog by mutableStateOf(false)
    private var showToolSettingsDialog by mutableStateOf(false)
    private var showDiagnosticsDialog by mutableStateOf(false)
    private var showHelpDialog by mutableStateOf(false)
    private var confirmClear by mutableStateOf(false)
    private var pendingTextBoxEdit by mutableStateOf<PendingTextBoxEdit?>(null)
    private var pendingLinkEdit by mutableStateOf<PendingLinkEdit?>(null)

    // Diagnostics & Air Actions
    private var showHudOverlay by mutableStateOf(false)
    private var debugCompareNormalized by mutableStateOf(false)
    private var telemetry by mutableStateOf(PenTelemetry())
    private var remoteCount by mutableIntStateOf(0)
    private var remoteStatus by mutableStateOf("En espera de acciones")
    private var canvasSize by mutableStateOf(IntSize.Zero)
    private var minimapTrigger by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val darkBars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = darkBars, navigationBarStyle = darkBars)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshLibraryLists()

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
                        title = if (splitNotebookUuid != null) "$activeNotebookTitle ◫ $splitNotebookTitle" else activeNotebookTitle,
                        zoomPercent = telemetry.zoomPercent,
                        currentPage = telemetry.currentPage,
                        totalPages = telemetry.totalPages,
                        layoutMode = layoutMode,
                        activeLayerName = activeLayerName,
                        leftDrawerOpen = leftDrawerOpen,
                        onToggleLeftDrawer = {
                            leftDrawerOpen = !leftDrawerOpen
                            if (leftDrawerOpen) {
                                rightSidebarOpen = false
                                gridViewOpen = false
                                refreshLibraryLists()
                            }
                        },
                        onOpenLibrary = {
                            refreshLibraryLists()
                            libraryModalOpen = true
                            leftDrawerOpen = false
                            rightSidebarOpen = false
                            gridViewOpen = false
                        },
                        onInsertImage = {
                            pickImageLauncher.launch("image/*")
                        },
                        rulerEnabled = rulerEnabled,
                        onToggleRuler = {
                            rulerEnabled = !rulerEnabled
                            sheet?.rulerEnabled = rulerEnabled
                        },
                        minimapOpen = minimapOpen,
                        onToggleMinimap = {
                            minimapOpen = !minimapOpen
                            minimapTrigger++
                        },
                        gridViewOpen = gridViewOpen,
                        onToggleGridView = {
                            gridViewOpen = !gridViewOpen
                            if (gridViewOpen) rightSidebarOpen = false
                            minimapTrigger++
                        },
                        rightSidebarOpen = rightSidebarOpen,
                        onToggleRightSidebar = {
                            rightSidebarOpen = !rightSidebarOpen
                            if (rightSidebarOpen) gridViewOpen = false
                            minimapTrigger++
                        },
                        onOpenLayers = {
                            layersRevision++
                            showLayersDialog = true
                        },
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
                        fixedToolbarMode = fixedToolbarMode,
                        onToggleFixedToolbarMode = {
                            fixedToolbarMode = !fixedToolbarMode
                        },
                        onFitContent = {
                            sheet?.fitToContent()
                            minimapTrigger++
                        },
                        onUndo = {
                            sheet?.undo()
                            minimapTrigger++
                        },
                        onRedo = {
                            sheet?.redo()
                            minimapTrigger++
                        },
                        onOpenPaper = { showPaperDialog = true },
                        onOpenToolSettings = { showToolSettingsDialog = true },
                        onOpenDiagnostics = { showDiagnosticsDialog = true },
                        onExportPng = { exportAndSharePng() },
                        canClear = canUndo,
                        onClear = { confirmClear = true }
                    )

                    if (fixedToolbarMode && !focusMode && !readingMode) {
                        FixedTopToolBar(
                            activeTool = activeTool,
                            activeColor = currentIndicatorColor(),
                            currentWidthDp = currentToolWidthDp(),
                            strokePanelOpen = showStrokePanel,
                            favorites = favoritePresets,
                            onSelectTool = { tool -> selectTool(tool) },
                            onToggleStrokePanel = { showStrokePanel = !showStrokePanel },
                            onQuickWidthSelect = { w -> applyWidthToActiveTool(w) },
                            onApplyFavorite = { preset -> applyFavoritePreset(preset) }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onSizeChanged { canvasSize = it }
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                AndroidView(
                                    factory = { context ->
                                        PenSheet(
                                            context = context,
                                            onStateChanged = { undo, redo, sel ->
                                                canUndo = undo
                                                canRedo = redo
                                                hasSelection = sel
                                                minimapTrigger++
                                            },
                                            onTelemetryChanged = { t ->
                                                telemetry = t
                                                minimapTrigger++
                                            },
                                            onRequestTextBoxEditor = { existing, wx, wy ->
                                                pendingTextBoxEdit = PendingTextBoxEdit(existing, wx, wy)
                                            },
                                            onRequestLinkEditor = { existing, wx, wy ->
                                                pendingLinkEdit = PendingLinkEdit(existing, wx, wy)
                                            },
                                            onActivateLink = { link ->
                                                handleActivateLink(link)
                                            },
                                            onContentMutated = {
                                                scheduleAutoSave()
                                            }
                                        ).also { view ->
                                            sheet = view
                                            syncSheetConfig(view)
                                            loadPersistedNotebook(view, activeNotebookUuid)
                                            refreshActiveLayerName()
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // In-App Split Screen Secondary Notebook (Section 16 · 应用内分屏)
                            val activeSplitUuid = splitNotebookUuid
                            if (activeSplitUuid != null) {
                                Box(
                                    Modifier
                                        .width(2.dp)
                                        .fillMaxHeight()
                                        .background(Petroleum)
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                ) {
                                    androidx.compose.runtime.key(activeSplitUuid) {
                                        AndroidView(
                                            factory = { context ->
                                                PenSheet(
                                                    context = context,
                                                    onStateChanged = { _, _, _ -> },
                                                    onContentMutated = {
                                                        scheduleAutoSave()
                                                    }
                                                ).also { sView ->
                                                    splitSheet = sView
                                                    syncSheetConfig(sView)
                                                    val loaded = NoteRepository.load(filesDir, activeSplitUuid)
                                                    if (loaded != null) {
                                                        sView.importPersistedSession(
                                                            loaded.first,
                                                            loaded.second,
                                                            imageLoader = { fileName -> loadNotebookImage(activeSplitUuid, fileName) }
                                                        )
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Surface(
                                        color = GraphiteSurface.copy(alpha = 0.92f),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, Petroleum),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                splitNotebookTitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = InkTextPrimary
                                            )
                                            Text(
                                                "×",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Color(0xFFE06C75),
                                                modifier = Modifier
                                                    .clickable {
                                                        persistSplitNotebook()
                                                        splitNotebookUuid = null
                                                        splitSheet = null
                                                    }
                                                    .padding(horizontal = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

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

                        // Floating lasso action bar (with 1-tap recolor swatches & resize hint)
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
                                            "Selección (arrastra esquina ↘ para escalar)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Petroleum,
                                            modifier = Modifier.padding(horizontal = 4.dp)
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

                        // Infinite Canvas Minimap (Section 03 · 无限画布 "蓝色方框")
                        if (minimapOpen) {
                            val currentSheet = sheet
                            val (worldRect, viewportRect) = remember(minimapTrigger, telemetry.zoomPercent, currentSheet) {
                                currentSheet?.computeMinimapState() ?: (RectF(0f, 0f, 1000f, 1000f) to RectF(0f, 0f, 1000f, 1000f))
                            }
                            val snapshots = remember(minimapTrigger, currentSheet) {
                                currentSheet?.snapshotItemBoundsForMinimap() ?: emptyList()
                            }
                            InfiniteCanvasMinimap(
                                worldBounds = worldRect,
                                viewportBounds = viewportRect,
                                itemSnapshots = snapshots,
                                isDarkPaper = paperStyle.isDark,
                                onJumpToWorld = { wx, wy ->
                                    sheet?.jumpCameraToWorldCenter(wx, wy)
                                    minimapTrigger++
                                },
                                onFitAll = {
                                    sheet?.fitToContent()
                                    minimapTrigger++
                                },
                                onClose = { minimapOpen = false },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                            )
                        }

                        // Floating color, width & sub-mode tuning popover
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
                                pressureSensitivity = pressureSensitivity,
                                ballpointOpacity = ballpointOpacity,
                                autoShapeEnabled = autoShapeEnabled,
                                directShapeKind = directShapeKind,
                                shapeFillEnabled = shapeFillEnabled,
                                shapeFillOpacity = shapeFillOpacity,
                                eraserMode = eraserMode,
                                eraseOnlyHighlighter = eraseOnlyHighlighter,
                                eraseOnlyTape = eraseOnlyTape,
                                lassoMode = lassoMode,
                                lassoSelectInk = lassoSelectInk,
                                lassoSelectShapes = lassoSelectShapes,
                                lassoSelectText = lassoSelectText,
                                laserColorArgb = laserColorArgb,
                                laserFadeDurationMs = laserFadeDurationMs,
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
                                onChangePressureSensitivity = { value ->
                                    pressureSensitivity = value
                                    sheet?.pressureSensitivity = value
                                },
                                onChangeBallpointOpacity = { value ->
                                    ballpointOpacity = value
                                    sheet?.ballpointOpacity = value
                                },
                                onToggleAutoShape = {
                                    autoShapeEnabled = !autoShapeEnabled
                                    sheet?.autoShapeEnabled = autoShapeEnabled
                                },
                                onSelectDirectShapeKind = { kind ->
                                    directShapeKind = kind
                                    sheet?.directShapeKind = kind
                                },
                                onToggleShapeFill = { enabled ->
                                    shapeFillEnabled = enabled
                                    sheet?.shapeFillEnabled = enabled
                                },
                                onChangeShapeFillOpacity = { op ->
                                    shapeFillOpacity = op
                                    sheet?.shapeFillOpacity = op
                                },
                                onSelectEraserMode = { mode ->
                                    eraserMode = mode
                                    sheet?.eraserMode = mode
                                },
                                onToggleEraseOnlyHighlighter = { enabled ->
                                    eraseOnlyHighlighter = enabled
                                    if (enabled) eraseOnlyTape = false
                                    sheet?.eraseOnlyHighlighter = eraseOnlyHighlighter
                                    sheet?.eraseOnlyTape = eraseOnlyTape
                                },
                                onToggleEraseOnlyTape = { enabled ->
                                    eraseOnlyTape = enabled
                                    if (enabled) eraseOnlyHighlighter = false
                                    sheet?.eraseOnlyTape = eraseOnlyTape
                                    sheet?.eraseOnlyHighlighter = eraseOnlyHighlighter
                                },
                                onSelectLassoMode = { mode ->
                                    lassoMode = mode
                                    sheet?.lassoMode = mode
                                },
                                onToggleLassoFilter = { ink, shapes, text ->
                                    lassoSelectInk = ink
                                    lassoSelectShapes = shapes
                                    lassoSelectText = text
                                    sheet?.lassoSelectInk = ink
                                    sheet?.lassoSelectShapes = shapes
                                    sheet?.lassoSelectText = text
                                },
                                onSelectLaserColor = { argb ->
                                    laserColorArgb = argb
                                    sheet?.laserColor = argb
                                },
                                onChangeLaserDuration = { ms ->
                                    laserFadeDurationMs = ms
                                    sheet?.laserFadeDurationMs = ms
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

                        // Movable Floating Pencil Box ("笔盒") — moves across the screen, docks on 4 edges, or auto-collapses in 4 corners
                        if (!fixedToolbarMode && !focusMode && !readingMode) {
                            MovablePenBox(
                                containerSize = canvasSize,
                                activeTool = activeTool,
                                activeColor = currentIndicatorColor(),
                                currentWidthDp = currentToolWidthDp(),
                                strokePanelOpen = showStrokePanel,
                                favorites = favoritePresets,
                                onSelectTool = { tool -> selectTool(tool) },
                                onToggleStrokePanel = { showStrokePanel = !showStrokePanel },
                                onQuickWidthSelect = { w -> applyWidthToActiveTool(w) },
                                onApplyFavorite = { preset -> applyFavoritePreset(preset) }
                            )
                        }

                        // Right Sidebar Drawer (Section 17 · 右侧边栏: Páginas, Esquema, Notas)
                        if (rightSidebarOpen) {
                            val currentSheet = sheet
                            RightOverviewSidebar(
                                currentPageIndex = (telemetry.currentPage - 1).coerceAtLeast(0),
                                pageCount = telemetry.totalPages.coerceAtLeast(1),
                                layoutMode = layoutMode,
                                isDarkPaper = paperStyle.isDark,
                                outlines = outlines,
                                comments = comments,
                                pageSnapshotProvider = { idx ->
                                    currentSheet?.snapshotPageMiniItems(idx)
                                        ?: (RectF(0f, 0f, 1000f, 1400f) to emptyList())
                                },
                                onJumpToPage = { idx ->
                                    currentSheet?.jumpToPage(idx)
                                    minimapTrigger++
                                },
                                onInsertPageAfter = { idx ->
                                    currentSheet?.insertPageAfter(idx)
                                    minimapTrigger++
                                    scheduleAutoSave()
                                },
                                onDuplicatePage = { idx ->
                                    currentSheet?.duplicatePage(idx)
                                    minimapTrigger++
                                    scheduleAutoSave()
                                },
                                onDeletePage = { idx ->
                                    currentSheet?.deletePage(idx)
                                    minimapTrigger++
                                    scheduleAutoSave()
                                },
                                onAddOutlineForCurrentPage = { showAddOutlineDialog = true },
                                onJumpToOutline = { entry ->
                                    if (layoutMode == CanvasLayoutMode.PAGED) {
                                        currentSheet?.jumpToPage(entry.pageIndex)
                                    } else {
                                        currentSheet?.jumpCameraToWorldCenter(entry.worldX, entry.worldY)
                                    }
                                    minimapTrigger++
                                },
                                onIndentOutline = { entry, delta ->
                                    val i = outlines.indexOfFirst { it.id == entry.id }
                                    if (i >= 0) {
                                        outlines[i] = outlines[i].copy(
                                            indentLevel = (outlines[i].indentLevel + delta).coerceIn(0, 5)
                                        )
                                        scheduleAutoSave()
                                    }
                                },
                                onDeleteOutline = { entry ->
                                    outlines.removeAll { it.id == entry.id }
                                    scheduleAutoSave()
                                },
                                onAddCommentForCurrentPage = { showAddCommentDialog = true },
                                onJumpToComment = { c ->
                                    if (layoutMode == CanvasLayoutMode.PAGED) {
                                        currentSheet?.jumpToPage(c.pageIndex)
                                    } else {
                                        currentSheet?.jumpCameraToWorldCenter(c.worldX, c.worldY)
                                    }
                                    minimapTrigger++
                                },
                                onDeleteComment = { c ->
                                    comments.removeAll { it.id == c.id }
                                    scheduleAutoSave()
                                },
                                onOpenGridView = {
                                    rightSidebarOpen = false
                                    gridViewOpen = true
                                },
                                onClose = { rightSidebarOpen = false },
                                modifier = Modifier.align(Alignment.TopEnd)
                            )
                        }

                        // Full-screen Bird's-Eye Page Grid Modal (Sections 18 & 23 · 平铺缩略图)
                        if (gridViewOpen) {
                            val currentSheet = sheet
                            PageGridModal(
                                currentPageIndex = (telemetry.currentPage - 1).coerceAtLeast(0),
                                pageCount = telemetry.totalPages.coerceAtLeast(1),
                                isDarkPaper = paperStyle.isDark,
                                pageSnapshotProvider = { idx ->
                                    currentSheet?.snapshotPageMiniItems(idx)
                                        ?: (RectF(0f, 0f, 1000f, 1400f) to emptyList())
                                },
                                onJumpToPage = { idx ->
                                    currentSheet?.jumpToPage(idx)
                                    minimapTrigger++
                                },
                                onInsertPageAfter = { idx ->
                                    currentSheet?.insertPageAfter(idx)
                                    minimapTrigger++
                                    scheduleAutoSave()
                                },
                                onDuplicatePage = { idx ->
                                    currentSheet?.duplicatePage(idx)
                                    minimapTrigger++
                                    scheduleAutoSave()
                                },
                                onDeletePage = { idx ->
                                    currentSheet?.deletePage(idx)
                                    minimapTrigger++
                                    scheduleAutoSave()
                                },
                                onClose = { gridViewOpen = false }
                            )
                        }

                        // Left Document Drawer + Split Screen Launcher (Section 16 · 左侧边栏的作用)
                        if (leftDrawerOpen) {
                            LeftDocumentDrawer(
                                notebooks = allNotebooks,
                                activeUuid = activeNotebookUuid,
                                splitUuid = splitNotebookUuid,
                                onSelectNotebook = { nb -> switchActiveNotebook(nb.uuid) },
                                onOpenInSplitScreen = { nb -> openInSplitScreen(nb) },
                                onCloseSplitScreen = {
                                    persistSplitNotebook()
                                    splitNotebookUuid = null
                                    splitSheet = null
                                },
                                onCreateQuickNote = { createNewNotebook("Nota rápida ${allNotebooks.size + 1}", CanvasLayoutMode.PAGED, null, 0xFF2AA1B7.toInt()) },
                                onOpenFullLibrary = { libraryModalOpen = true },
                                onClose = { leftDrawerOpen = false },
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                        }

                        // Full-screen Notebook & Color-Coded Folder Library Modal (Sections 01 & 04)
                        if (libraryModalOpen) {
                            NotebookLibraryModal(
                                folders = allFolders,
                                notebooks = allNotebooks,
                                activeUuid = activeNotebookUuid,
                                selectedFolderId = selectedLibraryFolderId,
                                onSelectFolder = { fid -> selectedLibraryFolderId = fid },
                                onCreateFolder = { name, col ->
                                    val folder = NotebookFolder(
                                        id = "f-${System.currentTimeMillis()}",
                                        name = name,
                                        colorArgb = col
                                    )
                                    allFolders.add(folder)
                                    NoteRepository.saveFolders(filesDir, allFolders)
                                },
                                onCreateNotebook = { title, mode, fid, col ->
                                    createNewNotebook(title, mode, fid, col)
                                    libraryModalOpen = false
                                },
                                onOpenNotebook = { nb -> switchActiveNotebook(nb.uuid) },
                                onOpenInSplitScreen = { nb -> openInSplitScreen(nb) },
                                onDeleteNotebook = { nb ->
                                    if (nb.uuid != activeNotebookUuid && allNotebooks.size > 1) {
                                        if (splitNotebookUuid == nb.uuid) {
                                            splitNotebookUuid = null
                                            splitSheet = null
                                        }
                                        NoteRepository.deleteNotebook(filesDir, nb.uuid)
                                        refreshLibraryLists()
                                    }
                                },
                                onClose = { libraryModalOpen = false }
                            )
                        }
                    }
                }

                // Bidirectional & Page Hyperlink Editor Dialog (Sections 20 & 21 · 双向链接 & 超链接)
                pendingLinkEdit?.let { linkReq ->
                    LinkEditorDialog(
                        existing = linkReq.existing,
                        notebooks = allNotebooks,
                        totalPages = telemetry.totalPages.coerceAtLeast(1),
                        defaultColorArgb = currentPenPalette()[selectedPenColorIdx].argb,
                        onConfirm = { label, kind, targetVal, col ->
                            sheet?.addOrUpdateLinkItem(
                                existingId = linkReq.existing?.id,
                                worldX = linkReq.worldX,
                                worldY = linkReq.worldY,
                                label = label,
                                targetKind = kind,
                                targetValue = targetVal,
                                color = col
                            )
                            pendingLinkEdit = null
                        },
                        onDelete = {
                            linkReq.existing?.let { existing ->
                                sheet?.addOrUpdateLinkItem(
                                    existingId = existing.id,
                                    worldX = linkReq.worldX,
                                    worldY = linkReq.worldY,
                                    label = "",
                                    targetKind = existing.targetKind,
                                    targetValue = existing.targetValue,
                                    color = existing.color
                                )
                            }
                            pendingLinkEdit = null
                        },
                        onDismiss = { pendingLinkEdit = null }
                    )
                }

                // Add Outline Bookmark Dialog (Section 17 · 大纲)
                if (showAddOutlineDialog) {
                    var titleText by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showAddOutlineDialog = false },
                        containerColor = GraphiteSurface,
                        title = { Text("Nuevo marcador en Esquema (Pág ${telemetry.currentPage})") },
                        text = {
                            OutlinedTextField(
                                value = titleText,
                                onValueChange = { titleText = it },
                                label = { Text("Título del tema o capítulo") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddOutlineDialog = false }) { Text("Cancelar") }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val t = titleText.trim()
                                    if (t.isNotEmpty()) {
                                        val s = sheet
                                        val pIdx = (telemetry.currentPage - 1).coerceAtLeast(0)
                                        val wx = if (s != null) (s.width * 0.5f - s.panX) / s.viewScale else 0f
                                        val wy = if (s != null) (s.height * 0.5f - s.panY) / s.viewScale else 0f
                                        outlines.add(
                                            OutlineEntry(
                                                id = System.currentTimeMillis(),
                                                title = t,
                                                pageIndex = pIdx,
                                                worldX = wx,
                                                worldY = wy,
                                                indentLevel = 0
                                            )
                                        )
                                        scheduleAutoSave()
                                    }
                                    showAddOutlineDialog = false
                                }
                            ) {
                                Text("Agregar", color = Petroleum)
                            }
                        }
                    )
                }

                // Add Comment Dialog (Section 17 · 备注)
                if (showAddCommentDialog) {
                    var commentText by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showAddCommentDialog = false },
                        containerColor = GraphiteSurface,
                        title = { Text("Nueva nota en Pág ${telemetry.currentPage}") },
                        text = {
                            OutlinedTextField(
                                value = commentText,
                                onValueChange = { commentText = it },
                                label = { Text("Comentario o recordatorio") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddCommentDialog = false }) { Text("Cancelar") }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val t = commentText.trim()
                                    if (t.isNotEmpty()) {
                                        val s = sheet
                                        val pIdx = (telemetry.currentPage - 1).coerceAtLeast(0)
                                        val wx = if (s != null) (s.width * 0.5f - s.panX) / s.viewScale else 0f
                                        val wy = if (s != null) (s.height * 0.5f - s.panY) / s.viewScale else 0f
                                        comments.add(
                                            CommentEntry(
                                                id = System.currentTimeMillis(),
                                                text = t,
                                                pageIndex = pIdx,
                                                worldX = wx,
                                                worldY = wy
                                            )
                                        )
                                        scheduleAutoSave()
                                    }
                                    showAddCommentDialog = false
                                }
                            ) {
                                Text("Guardar", color = Petroleum)
                            }
                        }
                    )
                }

                // Rich Text Box Editor Dialog (Section 13 · 文本框)
                pendingTextBoxEdit?.let { editReq ->
                    TextBoxEditorDialog(
                        existing = editReq.existing,
                        palette = currentPenPalette(),
                        defaultColorArgb = currentPenPalette()[selectedPenColorIdx].argb,
                        onConfirm = { text, colorArgb, bgArgb, fontSizeDp, bold, italic, underline ->
                            sheet?.addOrUpdateTextBox(
                                existingId = editReq.existing?.id,
                                worldX = editReq.worldX,
                                worldY = editReq.worldY,
                                text = text,
                                color = colorArgb,
                                bgColor = bgArgb,
                                fontSizeDp = fontSizeDp,
                                isBold = bold,
                                isItalic = italic,
                                isUnderline = underline
                            )
                            pendingTextBoxEdit = null
                        },
                        onDelete = {
                            editReq.existing?.let { existing ->
                                sheet?.addOrUpdateTextBox(
                                    existingId = existing.id,
                                    worldX = editReq.worldX,
                                    worldY = editReq.worldY,
                                    text = "",
                                    color = existing.color,
                                    bgColor = existing.bgColor,
                                    fontSizeDp = 18f,
                                    isBold = false,
                                    isItalic = false,
                                    isUnderline = false
                                )
                            }
                            pendingTextBoxEdit = null
                        },
                        onDismiss = { pendingTextBoxEdit = null }
                    )
                }

                // Multi-Layer Dialog (Section 19 · 图层)
                if (showLayersDialog) {
                    val currentSheet = sheet
                    if (currentSheet != null) {
                        LayersDialog(
                            revision = layersRevision,
                            layers = currentSheet.layers,
                            activeLayerId = currentSheet.activeLayerId,
                            onSelectLayer = { id ->
                                currentSheet.activeLayerId = id
                                refreshActiveLayerName()
                                layersRevision++
                            },
                            onAddLayer = {
                                val nextNum = currentSheet.layers.size + 1
                                currentSheet.addLayer("Capa $nextNum")
                                refreshActiveLayerName()
                                layersRevision++
                            },
                            onToggleVisibility = { id ->
                                currentSheet.toggleLayerVisibility(id)
                                layersRevision++
                                minimapTrigger++
                            },
                            onToggleLock = { id ->
                                currentSheet.toggleLayerLock(id)
                                layersRevision++
                            },
                            onMergeDown = { id ->
                                if (currentSheet.mergeLayerDown(id)) {
                                    refreshActiveLayerName()
                                    layersRevision++
                                } else {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "No hay capa inferior para combinar",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onDismiss = { showLayersDialog = false }
                        )
                    }
                }

                if (showPaperDialog) {
                    PaperSettingsDialog(
                        layoutMode = layoutMode,
                        autoAddPage = autoAddPage,
                        currentStyle = paperStyle,
                        currentTemplate = paperTemplate,
                        templateSpacingDp = templateSpacingDp,
                        templateOpacity = templateOpacity,
                        onSelectLayoutMode = { mode ->
                            layoutMode = mode
                            sheet?.layoutMode = mode
                            scheduleAutoSave()
                        },
                        onChangeAutoAddPage = { enabled ->
                            autoAddPage = enabled
                            sheet?.autoAddPage = enabled
                            scheduleAutoSave()
                        },
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
                            scheduleAutoSave()
                        },
                        onSelectTemplate = { template ->
                            paperTemplate = template
                            sheet?.paperTemplate = template
                            scheduleAutoSave()
                        },
                        onChangeSpacing = { spacing ->
                            templateSpacingDp = spacing
                            sheet?.templateSpacingDp = spacing
                            scheduleAutoSave()
                        },
                        onChangeOpacity = { op ->
                            templateOpacity = op
                            sheet?.templateOpacity = op
                            scheduleAutoSave()
                        },
                        onResetDefaults = {
                            templateSpacingDp = 22f
                            templateOpacity = 1.0f
                            sheet?.templateSpacingDp = 22f
                            sheet?.templateOpacity = 1.0f
                            scheduleAutoSave()
                        },
                        onDismiss = { showPaperDialog = false }
                    )
                }

                if (showToolSettingsDialog) {
                    ToolAndGestureSettingsDialog(
                        gestureTapUndoRedo = gestureTapUndoRedo,
                        doubleTapFitPage = doubleTapFitPage,
                        adaptiveStrokeWidth = adaptiveStrokeWidth,
                        allowFingerDrawing = allowFingerDrawing,
                        pressureEraserEnabled = pressureEraserEnabled,
                        pressureEraserThreshold = pressureEraserThreshold,
                        rulerAngleDeg = sheet?.rulerAngleDeg ?: 0f,
                        rulerEnabled = rulerEnabled,
                        onChangeGestureUndoRedo = {
                            gestureTapUndoRedo = it
                            sheet?.gestureTapUndoRedo = it
                        },
                        onChangeDoubleTapFitPage = {
                            doubleTapFitPage = it
                            sheet?.doubleTapFitPage = it
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
                        onSnapRulerAngle = { angle ->
                            sheet?.rulerAngleDeg = angle
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
                                "• Estuche flotante: Arrástralo a cualquier borde para acoplarlo en vertical u horizontal. Si lo arrastras a cualquiera de las 4 esquinas, se contrae automáticamente en el ícono compacto de pluma.\n" +
                                    "• Figuras 2D y 3D: Usa la herramienta «Figuras» para trazar cubos 3D, cilindros, conos, esferas, ejes X-Y, parábolas, líneas onduladas y polígonos con o sin relleno.\n" +
                                    "• Borrador avanzado: Toca dos veces el borrador para elegir Trazo completo, Por píxel (parcial) o Por lazo, o activar «Solo borrar resaltador».\n" +
                                    "• Cajas de texto y Capas: Con «Texto» toca cualquier punto de la hoja para insertar texto enriquecido. Usa el botón de Capas para crear, ocultar, bloquear o combinar capas.\n" +
                                    "• Regla y Minimapa: Activa la regla virtual en la barra superior (arrástrala del centro o gírala de los extremos) o abre el minimapa con su cuadro azul navegable.\n" +
                                    "• Gestos: Toque con 2 dedos = Deshacer · Toque con 3 dedos = Rehacer · Doble toque con 1 dedo = Ajustar hoja · Mantener 1 dedo sobre texto o figura = Editar."
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

    private fun selectTool(tool: ToolMode) {
        activeTool = tool
        sheet?.toolMode = tool
        splitSheet?.toolMode = tool
        if (tool == ToolMode.TEXT) {
            showStrokePanel = false
            Toast.makeText(
                this,
                "Toca cualquier parte de la hoja para insertar o editar una caja de texto",
                Toast.LENGTH_SHORT
            ).show()
        } else if (tool == ToolMode.LINK) {
            showStrokePanel = false
            Toast.makeText(
                this,
                "Toca cualquier parte de la hoja para insertar o editar un hipervínculo (🔗)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun applyFavoritePreset(preset: PenPreset) {
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

    private fun refreshActiveLayerName() {
        val currentSheet = sheet ?: return
        val layer = currentSheet.layers.firstOrNull { it.id == currentSheet.activeLayerId }
            ?: currentSheet.layers.firstOrNull()
        if (layer != null) {
            activeLayerName = layer.name
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
        ToolMode.LASER -> laserColorArgb
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
        view.pressureSensitivity = pressureSensitivity
        view.ballpointOpacity = ballpointOpacity
        view.autoShapeEnabled = autoShapeEnabled
        view.directShapeKind = directShapeKind
        view.shapeFillEnabled = shapeFillEnabled
        view.shapeFillOpacity = shapeFillOpacity
        view.eraserMode = eraserMode
        view.eraseOnlyHighlighter = eraseOnlyHighlighter
        view.eraseOnlyTape = eraseOnlyTape
        view.lassoMode = lassoMode
        view.lassoSelectInk = lassoSelectInk
        view.lassoSelectShapes = lassoSelectShapes
        view.lassoSelectText = lassoSelectText
        view.laserColor = laserColorArgb
        view.laserFadeDurationMs = laserFadeDurationMs
        view.rulerEnabled = rulerEnabled
        view.readingMode = readingMode
        view.revealAllTapes = revealAllTapes
        view.gestureTapUndoRedo = gestureTapUndoRedo
        view.doubleTapFitPage = doubleTapFitPage
        view.adaptiveStrokeWidth = adaptiveStrokeWidth
        view.allowFingerDrawing = allowFingerDrawing
        view.pressureEraserEnabled = pressureEraserEnabled
        view.pressureEraserThreshold = pressureEraserThreshold
        view.layoutMode = layoutMode
        view.autoAddPage = autoAddPage
        view.paperStyle = paperStyle
        view.paperTemplate = paperTemplate
        view.templateSpacingDp = templateSpacingDp
        view.templateOpacity = templateOpacity
        view.showDiagnostics = showHudOverlay
        view.debugCompareNormalized = debugCompareNormalized
    }

    override fun onPause() {
        super.onPause()
        saveHandler.removeCallbacks(debouncedSaveRunnable)
        performPersistToDisk()
        persistSplitNotebook()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveHandler.removeCallbacks(debouncedSaveRunnable)
        saveExecutor.shutdown()
    }

    private fun scheduleAutoSave() {
        saveHandler.removeCallbacks(debouncedSaveRunnable)
        saveHandler.postDelayed(debouncedSaveRunnable, 750L)
    }

    private fun performPersistToDisk() {
        val currentSheet = sheet ?: return
        val records = currentSheet.exportPersistedItems()
        val meta = NoteMetadata(
            uuid = activeNotebookUuid,
            title = activeNotebookTitle,
            folderId = activeNotebookFolderId,
            coverColorArgb = activeNotebookCoverColor,
            layoutMode = layoutMode,
            pageCount = currentSheet.pageCount,
            autoAddPage = autoAddPage,
            paperStyle = paperStyle,
            paperTemplate = paperTemplate,
            templateSpacingDp = templateSpacingDp,
            templateOpacity = templateOpacity,
            viewScale = currentSheet.viewScale,
            panX = currentSheet.panX,
            panY = currentSheet.panY,
            activeLayerId = currentSheet.activeLayerId,
            layers = currentSheet.layers.toList(),
            outlines = outlines.toList(),
            comments = comments.toList(),
            updatedAtMs = System.currentTimeMillis()
        )
        val dir = filesDir
        saveExecutor.execute {
            NoteRepository.saveAtomically(dir, meta, records)
        }
        persistSplitNotebook()
    }

    private fun persistSplitNotebook() {
        val sUuid = splitNotebookUuid ?: return
        val sView = splitSheet ?: return
        val existingMeta = allNotebooks.firstOrNull { it.uuid == sUuid }
        val records = sView.exportPersistedItems()
        val meta = (existingMeta ?: NoteMetadata(uuid = sUuid, title = splitNotebookTitle)).copy(
            pageCount = sView.pageCount,
            viewScale = sView.viewScale,
            panX = sView.panX,
            panY = sView.panY,
            activeLayerId = sView.activeLayerId,
            layers = sView.layers.toList(),
            updatedAtMs = System.currentTimeMillis()
        )
        val dir = filesDir
        saveExecutor.execute {
            NoteRepository.saveAtomically(dir, meta, records)
        }
    }

    private fun loadPersistedNotebook(view: PenSheet, uuid: String = activeNotebookUuid) {
        val loaded = NoteRepository.load(filesDir, uuid) ?: return
        val (meta, records) = loaded
        activeNotebookUuid = meta.uuid
        activeNotebookTitle = meta.title
        activeNotebookFolderId = meta.folderId
        activeNotebookCoverColor = meta.coverColorArgb
        layoutMode = meta.layoutMode
        autoAddPage = meta.autoAddPage
        paperStyle = meta.paperStyle
        paperTemplate = meta.paperTemplate
        templateSpacingDp = meta.templateSpacingDp
        templateOpacity = meta.templateOpacity
        outlines.clear()
        outlines.addAll(meta.outlines)
        comments.clear()
        comments.addAll(meta.comments)
        view.importPersistedSession(
            meta,
            records,
            imageLoader = { fileName -> loadNotebookImage(meta.uuid, fileName) }
        )
        refreshActiveLayerName()
        minimapTrigger++
    }

    private fun refreshLibraryLists() {
        allFolders.clear()
        allFolders.addAll(NoteRepository.loadFolders(filesDir))
        allNotebooks.clear()
        allNotebooks.addAll(NoteRepository.listAllNotebooks(filesDir))
        val cur = allNotebooks.firstOrNull { it.uuid == activeNotebookUuid } ?: allNotebooks.firstOrNull()
        if (cur != null) {
            activeNotebookUuid = cur.uuid
            activeNotebookTitle = cur.title
            activeNotebookFolderId = cur.folderId
            activeNotebookCoverColor = cur.coverColorArgb
        }
    }

    private fun switchActiveNotebook(targetUuid: String) {
        if (targetUuid == activeNotebookUuid) return
        performPersistToDisk()
        val view = sheet ?: return
        if (splitNotebookUuid == targetUuid) {
            persistSplitNotebook()
            splitNotebookUuid = null
            splitSheet = null
        }
        loadPersistedNotebook(view, targetUuid)
        refreshLibraryLists()
    }

    private fun createNewNotebook(
        title: String,
        mode: CanvasLayoutMode,
        folderId: String?,
        coverColorArgb: Int
    ) {
        performPersistToDisk()
        val newUuid = "note-${System.currentTimeMillis()}"
        val meta = NoteMetadata(
            uuid = newUuid,
            title = title,
            folderId = folderId,
            coverColorArgb = coverColorArgb,
            layoutMode = mode,
            pageCount = if (mode == CanvasLayoutMode.PAGED) 3 else 1
        )
        NoteRepository.saveAtomically(filesDir, meta, emptyList())
        refreshLibraryLists()
        sheet?.let { loadPersistedNotebook(it, newUuid) }
    }

    private fun openInSplitScreen(nb: NoteMetadata) {
        if (nb.uuid == activeNotebookUuid) return
        persistSplitNotebook()
        splitNotebookUuid = nb.uuid
        splitNotebookTitle = nb.title
    }

    private fun importPickedImage(uri: Uri) {
        runCatching {
            val rawBmp = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return
            val maxDim = 1280
            val bmp = if (rawBmp.width > maxDim || rawBmp.height > maxDim) {
                val ratio = kotlin.math.min(maxDim.toFloat() / rawBmp.width, maxDim.toFloat() / rawBmp.height)
                Bitmap.createScaledBitmap(
                    rawBmp,
                    (rawBmp.width * ratio).toInt().coerceAtLeast(1),
                    (rawBmp.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                rawBmp
            }
            val dir = java.io.File(NoteRepository.noteDir(filesDir, activeNotebookUuid), "images").apply { mkdirs() }
            val fileName = "img_${System.currentTimeMillis()}.png"
            val destFile = java.io.File(dir, fileName)
            java.io.FileOutputStream(destFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 95, fos)
            }
            sheet?.addImageItem(fileName, bmp)
            scheduleAutoSave()
            Toast.makeText(this, "Imagen insertada (usa Lazo para mover o escalar)", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "No se pudo cargar la imagen seleccionada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadNotebookImage(uuid: String, fileName: String): Bitmap? = runCatching {
        val f = java.io.File(java.io.File(NoteRepository.noteDir(filesDir, uuid), "images"), fileName)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }.getOrNull()

    private fun handleActivateLink(link: PenSheet.LinkItem) {
        when (link.targetKind) {
            LinkTargetKind.PAGE -> {
                val pageNum = link.targetValue.toIntOrNull() ?: 1
                sheet?.jumpToPage((pageNum - 1).coerceAtLeast(0))
                minimapTrigger++
                Toast.makeText(this, "Saltando a Página $pageNum", Toast.LENGTH_SHORT).show()
            }
            LinkTargetKind.NOTEBOOK -> {
                refreshLibraryLists()
                val targetNb = allNotebooks.firstOrNull {
                    it.uuid == link.targetValue || it.title.equals(link.targetValue, ignoreCase = true)
                }
                if (targetNb != null) {
                    if (targetNb.uuid != activeNotebookUuid) {
                        openInSplitScreen(targetNb)
                        Toast.makeText(this, "Abierto en pantalla dividida: ${targetNb.title}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Cuaderno enlazado no encontrado", Toast.LENGTH_SHORT).show()
                }
            }
            LinkTargetKind.WEB_URL -> {
                val rawUrl = link.targetValue.trim()
                val fullUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "https://$rawUrl"
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)))
                }.onFailure {
                    Toast.makeText(this, "No se pudo abrir el enlace: $fullUrl", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun currentIndicatorColor(): Color = when (activeTool) {
        ToolMode.HIGHLIGHTER -> Color(EditorPalettes.highlighterColors[selectedHighlighterIdx].argb)
        ToolMode.TAPE -> Color(EditorPalettes.tapeColors[selectedTapeIdx].argb)
        ToolMode.LASER -> Color(laserColorArgb)
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
            showHelpDialog || showDiagnosticsDialog || showPaperDialog || showToolSettingsDialog ||
                showLayersDialog || pendingTextBoxEdit != null || confirmClear || view.isWriting ->
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
    pressureSensitivity: Float,
    ballpointOpacity: Float,
    autoShapeEnabled: Boolean,
    directShapeKind: DirectShapeKind,
    shapeFillEnabled: Boolean,
    shapeFillOpacity: Float,
    eraserMode: EraserMode,
    eraseOnlyHighlighter: Boolean,
    eraseOnlyTape: Boolean,
    lassoMode: LassoMode,
    lassoSelectInk: Boolean,
    lassoSelectShapes: Boolean,
    lassoSelectText: Boolean,
    laserColorArgb: Int,
    laserFadeDurationMs: Long,
    onSelectColorSet: (Int) -> Unit,
    onSelectPenColor: (Int) -> Unit,
    onSelectHighlighterColor: (Int) -> Unit,
    onSelectTapeColor: (Int) -> Unit,
    onChangeWidth: (Float) -> Unit,
    onChangePressureSensitivity: (Float) -> Unit,
    onChangeBallpointOpacity: (Float) -> Unit,
    onToggleAutoShape: () -> Unit,
    onSelectDirectShapeKind: (DirectShapeKind) -> Unit,
    onToggleShapeFill: (Boolean) -> Unit,
    onChangeShapeFillOpacity: (Float) -> Unit,
    onSelectEraserMode: (EraserMode) -> Unit,
    onToggleEraseOnlyHighlighter: (Boolean) -> Unit,
    onToggleEraseOnlyTape: (Boolean) -> Unit,
    onSelectLassoMode: (LassoMode) -> Unit,
    onToggleLassoFilter: (ink: Boolean, shapes: Boolean, text: Boolean) -> Unit,
    onSelectLaserColor: (Int) -> Unit,
    onChangeLaserDuration: (Long) -> Unit,
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
        Column(
            modifier = Modifier
                .padding(14.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Opciones · ${tool.label}",
                    style = MaterialTheme.typography.labelLarge,
                    color = InkTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (tool == ToolMode.PEN || tool == ToolMode.BALLPOINT || tool == ToolMode.DASHED ||
                    tool == ToolMode.HIGHLIGHTER || tool == ToolMode.TAPE || tool == ToolMode.SHAPES
                ) {
                    TextButton(onClick = onSaveFavorite) {
                        Text("★ Favorito", color = Petroleum)
                    }
                }
                TextButton(onClick = onClose) { Text("Cerrar") }
            }

            // 1. Direct Shape 2D/3D selector (Section 09 · 图形笔)
            if (tool == ToolMode.SHAPES) {
                Text(
                    "Tipo de figura 2D / 3D (arrastra en la hoja)",
                    style = MaterialTheme.typography.labelMedium,
                    color = InkTextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DirectShapeKind.entries.forEach { kind ->
                        FilterChip(
                            selected = kind == directShapeKind,
                            onClick = { onSelectDirectShapeKind(kind) },
                            label = { Text(kind.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PetroleumDeep,
                                selectedLabelColor = InkTextPrimary
                            )
                        )
                    }
                }
            }

            // 2. Eraser modes & filters (Section 09 · 橡皮擦)
            if (tool == ToolMode.ERASER) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    EraserMode.entries.forEach { mode ->
                        FilterChip(
                            selected = mode == eraserMode,
                            onClick = { onSelectEraserMode(mode) },
                            label = { Text(mode.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PetroleumDeep,
                                selectedLabelColor = InkTextPrimary
                            )
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Solo borrar resaltador",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkTextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = eraseOnlyHighlighter,
                        onCheckedChange = onToggleEraseOnlyHighlighter,
                        colors = SwitchDefaults.colors(checkedThumbColor = Petroleum)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Solo borrar cinta de estudio",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkTextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = eraseOnlyTape,
                        onCheckedChange = onToggleEraseOnlyTape,
                        colors = SwitchDefaults.colors(checkedThumbColor = Petroleum)
                    )
                }
            }

            // 3. Lasso mode & object filters (Section 10 · 套索)
            if (tool == ToolMode.LASSO) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    LassoMode.entries.forEach { mode ->
                        FilterChip(
                            selected = mode == lassoMode,
                            onClick = { onSelectLassoMode(mode) },
                            label = { Text(mode.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PetroleumDeep,
                                selectedLabelColor = InkTextPrimary
                            )
                        )
                    }
                }
                Text(
                    "Objetos a seleccionar:",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkTextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = lassoSelectInk,
                        onClick = { onToggleLassoFilter(!lassoSelectInk, lassoSelectShapes, lassoSelectText) },
                        label = { Text("Trazos") }
                    )
                    FilterChip(
                        selected = lassoSelectShapes,
                        onClick = { onToggleLassoFilter(lassoSelectInk, !lassoSelectShapes, lassoSelectText) },
                        label = { Text("Figuras") }
                    )
                    FilterChip(
                        selected = lassoSelectText,
                        onClick = { onToggleLassoFilter(lassoSelectInk, lassoSelectShapes, !lassoSelectText) },
                        label = { Text("Textos") }
                    )
                }
            }

            // 4. Laser pointer options (Section 10 · 激光笔)
            if (tool == ToolMode.LASER) {
                val laserSwatches = listOf(
                    0xFFFF1744.toInt() to "Rojo láser",
                    0xFF00E676.toInt() to "Verde neón",
                    0xFF00E5FF.toInt() to "Cian láser",
                    0xFFFFEA00.toInt() to "Amarillo",
                    0xFFD500F9.toInt() to "Magenta"
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    laserSwatches.forEach { (argb, label) ->
                        val isSelected = argb == laserColorArgb
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) InkTextPrimary else GraphiteBorder,
                                    shape = CircleShape
                                )
                                .clickable { onSelectLaserColor(argb) }
                                .semantics { contentDescription = label }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        String.format(Locale.getDefault(), "Duración %.1fs", laserFadeDurationMs / 1000f),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkTextSecondary,
                        modifier = Modifier.width(86.dp)
                    )
                    Slider(
                        value = laserFadeDurationMs.toFloat(),
                        onValueChange = { onChangeLaserDuration(it.toLong()) },
                        valueRange = 500f..4000f,
                        colors = SliderDefaults.colors(thumbColor = Petroleum, activeTrackColor = Petroleum),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 5. Color palettes & swatches for drawing tools (Section 12 · 色彩集)
            if (tool == ToolMode.PEN || tool == ToolMode.BALLPOINT || tool == ToolMode.DASHED ||
                tool == ToolMode.HIGHLIGHTER || tool == ToolMode.TAPE || tool == ToolMode.SHAPES
            ) {
                if (tool != ToolMode.HIGHLIGHTER && tool != ToolMode.TAPE) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp)
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
                                .size(38.dp)
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
                                    .size(28.dp)
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

            // 6. Width slider (for tools with stroke width / eraser radius)
            if (tool != ToolMode.LASSO && tool != ToolMode.LASER && tool != ToolMode.TEXT) {
                Spacer(Modifier.height(6.dp))
                val range = when (tool) {
                    ToolMode.HIGHLIGHTER -> 10f..32f
                    ToolMode.TAPE -> 14f..36f
                    ToolMode.ERASER -> 6f..28f
                    else -> 1.5f..12f
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = String.format(Locale.getDefault(), "%.1f dp", currentWidthDp),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkTextSecondary,
                        modifier = Modifier.width(64.dp)
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
            }

            // 7. Fountain Pen pressure sensitivity (Section 09 · 钢笔压感)
            if (tool == ToolMode.PEN) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Presión ${(pressureSensitivity * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkTextSecondary,
                        modifier = Modifier.width(86.dp)
                    )
                    Slider(
                        value = pressureSensitivity,
                        onValueChange = onChangePressureSensitivity,
                        valueRange = 0.25f..1.5f,
                        colors = SliderDefaults.colors(thumbColor = Petroleum, activeTrackColor = Petroleum),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 8. Ballpoint opacity (Section 09 · 圆珠笔透明度)
            if (tool == ToolMode.BALLPOINT) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Opacidad ${(ballpointOpacity * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkTextSecondary,
                        modifier = Modifier.width(92.dp)
                    )
                    Slider(
                        value = ballpointOpacity,
                        onValueChange = onChangeBallpointOpacity,
                        valueRange = 0.2f..1.0f,
                        colors = SliderDefaults.colors(thumbColor = Petroleum, activeTrackColor = Petroleum),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 9. Shape interior fill toggle & opacity (Section 09 · 图形填充)
            if (tool == ToolMode.SHAPES || autoShapeEnabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Relleno traslúcido en figuras cerradas",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkTextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = shapeFillEnabled,
                        onCheckedChange = onToggleShapeFill,
                        colors = SwitchDefaults.colors(checkedThumbColor = Petroleum)
                    )
                }
                if (shapeFillEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Relleno ${(shapeFillOpacity * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkTextSecondary,
                            modifier = Modifier.width(86.dp)
                        )
                        Slider(
                            value = shapeFillOpacity,
                            onValueChange = onChangeShapeFillOpacity,
                            valueRange = 0.1f..0.75f,
                            colors = SliderDefaults.colors(thumbColor = Petroleum, activeTrackColor = Petroleum),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 10. Auto-shape toggle for freehand pens
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
private fun TextBoxEditorDialog(
    existing: PenSheet.TextBoxItem?,
    palette: List<InkSwatch>,
    defaultColorArgb: Int,
    onConfirm: (text: String, colorArgb: Int, bgArgb: Int, fontSizeDp: Float, bold: Boolean, italic: Boolean, underline: Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(existing) { mutableStateOf(existing?.text ?: "") }
    var colorArgb by remember(existing) { mutableIntStateOf(existing?.color ?: defaultColorArgb) }
    var hasBgHighlight by remember(existing) {
        mutableStateOf(existing != null && existing.bgColor != android.graphics.Color.TRANSPARENT)
    }
    var fontSizeDp by remember(existing) { mutableFloatStateOf(18f) }
    var isBold by remember(existing) { mutableStateOf(existing?.isBold ?: false) }
    var isItalic by remember(existing) { mutableStateOf(existing?.isItalic ?: false) }
    var isUnderline by remember(existing) { mutableStateOf(existing?.isUnderline ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GraphiteSurface,
        title = { Text(if (existing == null) "Nueva caja de texto" else "Editar caja de texto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Escribe tu nota o fórmula") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = isBold,
                        onClick = { isBold = !isBold },
                        label = { Text("B", fontWeight = FontWeight.Bold) }
                    )
                    FilterChip(
                        selected = isItalic,
                        onClick = { isItalic = !isItalic },
                        label = { Text("I", fontStyle = FontStyle.Italic) }
                    )
                    FilterChip(
                        selected = isUnderline,
                        onClick = { isUnderline = !isUnderline },
                        label = { Text("U", textDecoration = TextDecoration.Underline) }
                    )
                    FilterChip(
                        selected = hasBgHighlight,
                        onClick = { hasBgHighlight = !hasBgHighlight },
                        label = { Text("Fondo") }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Tamaño ${fontSizeDp.toInt()} pt",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkTextSecondary,
                        modifier = Modifier.width(86.dp)
                    )
                    Slider(
                        value = fontSizeDp,
                        onValueChange = { fontSizeDp = it },
                        valueRange = 11f..42f,
                        colors = SliderDefaults.colors(thumbColor = Petroleum, activeTrackColor = Petroleum),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    palette.forEach { swatch ->
                        val selected = swatch.argb == colorArgb
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(swatch.argb))
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) Petroleum else GraphiteBorder,
                                    shape = CircleShape
                                )
                                .clickable { colorArgb = swatch.argb }
                        )
                    }
                }
            }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = onDelete) {
                        Text("Eliminar", color = Color(0xFFE06C75))
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val bg = if (hasBgHighlight) {
                        (colorArgb and 0x00FFFFFF) or (0x2E shl 24)
                    } else {
                        android.graphics.Color.TRANSPARENT
                    }
                    onConfirm(text, colorArgb, bg, fontSizeDp, isBold, isItalic, isUnderline)
                }
            ) {
                Text("Guardar", color = Petroleum)
            }
        }
    )
}

@Composable
private fun LayersDialog(
    revision: Int,
    layers: List<NoteLayer>,
    activeLayerId: Int,
    onSelectLayer: (Int) -> Unit,
    onAddLayer: () -> Unit,
    onToggleVisibility: (Int) -> Unit,
    onToggleLock: (Int) -> Unit,
    onMergeDown: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GraphiteSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Capas del documento", modifier = Modifier.weight(1f))
                if (layers.size < 10) {
                    TextButton(onClick = onAddLayer) {
                        Text("+ Nueva capa", color = Petroleum)
                    }
                }
            }
        },
        text = {
            // Observe revision so toggles trigger recomposition immediately
            val _rev = revision
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                layers.asReversed().forEach { layer ->
                    val isSelected = layer.id == activeLayerId
                    Surface(
                        color = if (isSelected) PetroleumDeep.copy(alpha = 0.45f) else GraphiteBg,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (isSelected) Petroleum else GraphiteBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectLayer(layer.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = if (isSelected) "${layer.name} (Activa)" else layer.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    text = buildString {
                                        append(if (layer.visible) "Visible" else "Oculta")
                                        if (layer.locked) append(" · Bloqueada")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = InkTextSecondary
                                )
                            }
                            TextButton(onClick = { onToggleVisibility(layer.id) }) {
                                Text(if (layer.visible) "Ocultar" else "Mostrar")
                            }
                            TextButton(onClick = { onToggleLock(layer.id) }) {
                                Text(if (layer.locked) "Desbloq." else "Bloquear")
                            }
                            if (layers.indexOfFirst { it.id == layer.id } > 0) {
                                TextButton(onClick = { onMergeDown(layer.id) }) {
                                    Text("↓", color = Petroleum)
                                }
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
private fun ToolAndGestureSettingsDialog(
    gestureTapUndoRedo: Boolean,
    doubleTapFitPage: Boolean,
    adaptiveStrokeWidth: Boolean,
    allowFingerDrawing: Boolean,
    pressureEraserEnabled: Boolean,
    pressureEraserThreshold: Float,
    rulerAngleDeg: Float,
    rulerEnabled: Boolean,
    onChangeGestureUndoRedo: (Boolean) -> Unit,
    onChangeDoubleTapFitPage: (Boolean) -> Unit,
    onChangeAdaptiveStroke: (Boolean) -> Unit,
    onChangeAllowFinger: (Boolean) -> Unit,
    onChangePressureEraser: (Boolean) -> Unit,
    onChangePressureThreshold: (Float) -> Unit,
    onSnapRulerAngle: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GraphiteSurface,
        title = { Text("Ajustes de lápiz, guías y gestos") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
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
                        Text("Doble toque con 1 dedo para encajar hoja", style = MaterialTheme.typography.bodyMedium)
                        Text("Ajusta el zoom automáticamente al contenido", style = MaterialTheme.typography.bodySmall, color = InkTextSecondary)
                    }
                    Switch(
                        checked = doubleTapFitPage,
                        onCheckedChange = onChangeDoubleTapFitPage,
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
                if (rulerEnabled) {
                    HorizontalDivider(color = GraphiteBorder)
                    Text(
                        "Ángulo rápido de la regla (${rulerAngleDeg.toInt()}°)",
                        style = MaterialTheme.typography.labelMedium,
                        color = InkTextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0f, 30f, 45f, 60f, 90f).forEach { ang ->
                            FilterChip(
                                selected = kotlin.math.abs(rulerAngleDeg - ang) < 1f,
                                onClick = { onSnapRulerAngle(ang) },
                                label = { Text("${ang.toInt()}°") }
                            )
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
private fun PaperSettingsDialog(
    layoutMode: CanvasLayoutMode,
    autoAddPage: Boolean,
    currentStyle: PaperStyle,
    currentTemplate: PaperTemplate,
    templateSpacingDp: Float,
    templateOpacity: Float,
    onSelectLayoutMode: (CanvasLayoutMode) -> Unit,
    onChangeAutoAddPage: (Boolean) -> Unit,
    onSelectStyle: (PaperStyle) -> Unit,
    onSelectTemplate: (PaperTemplate) -> Unit,
    onChangeSpacing: (Float) -> Unit,
    onChangeOpacity: (Float) -> Unit,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GraphiteSurface,
        title = { Text("Tipo de cuaderno, papel y plantilla") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text("Modo de documento", style = MaterialTheme.typography.labelLarge, color = InkTextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CanvasLayoutMode.entries.forEach { mode ->
                        val selected = mode == layoutMode
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectLayoutMode(mode) },
                            label = { Text(mode.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PetroleumDeep,
                                selectedLabelColor = InkTextPrimary
                            )
                        )
                    }
                }
                if (layoutMode == CanvasLayoutMode.PAGED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Agregar página automáticamente al escribir abajo",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkTextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = autoAddPage,
                            onCheckedChange = onChangeAutoAddPage,
                            colors = SwitchDefaults.colors(checkedThumbColor = Petroleum)
                        )
                    }
                }
                HorizontalDivider(color = GraphiteBorder)
                Text("Color de hoja", style = MaterialTheme.typography.labelLarge, color = InkTextSecondary)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
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
                if (currentTemplate != PaperTemplate.BLANK) {
                    HorizontalDivider(color = GraphiteBorder)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Espaciado ${templateSpacingDp.toInt()} dp",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkTextSecondary,
                            modifier = Modifier.width(104.dp)
                        )
                        Slider(
                            value = templateSpacingDp,
                            onValueChange = onChangeSpacing,
                            valueRange = 14f..48f,
                            colors = SliderDefaults.colors(thumbColor = Petroleum, activeTrackColor = Petroleum),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Opacidad ${(templateOpacity * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkTextSecondary,
                            modifier = Modifier.width(104.dp)
                        )
                        Slider(
                            value = templateOpacity,
                            onValueChange = onChangeOpacity,
                            valueRange = 0.2f..1.0f,
                            colors = SliderDefaults.colors(thumbColor = Petroleum, activeTrackColor = Petroleum),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onResetDefaults) { Text("Restaurar") }
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

@Composable
private fun LinkEditorDialog(
    existing: PenSheet.LinkItem?,
    notebooks: List<NoteMetadata>,
    totalPages: Int,
    defaultColorArgb: Int,
    onConfirm: (label: String, kind: LinkTargetKind, targetVal: String, colorArgb: Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember(existing) { mutableStateOf(existing?.label ?: "Ir a referencia") }
    var kind by remember(existing) { mutableStateOf(existing?.targetKind ?: LinkTargetKind.PAGE) }
    var targetVal by remember(existing) { mutableStateOf(existing?.targetValue ?: "1") }
    var colorArgb by remember(existing) { mutableIntStateOf(existing?.color ?: defaultColorArgb) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GraphiteSurface,
        title = { Text(if (existing == null) "Nuevo hipervínculo (🔗)" else "Editar hipervínculo (🔗)") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Etiqueta visible en la hoja") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Destino del enlace:", style = MaterialTheme.typography.labelMedium, color = InkTextSecondary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinkTargetKind.entries.forEach { k ->
                        FilterChip(
                            selected = k == kind,
                            onClick = {
                                kind = k
                                targetVal = when (k) {
                                    LinkTargetKind.PAGE -> "1"
                                    LinkTargetKind.NOTEBOOK -> notebooks.firstOrNull()?.uuid ?: "default-notebook"
                                    LinkTargetKind.WEB_URL -> "https://es.wikipedia.org"
                                }
                            },
                            label = { Text(k.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PetroleumDeep,
                                selectedLabelColor = InkTextPrimary
                            )
                        )
                    }
                }
                when (kind) {
                    LinkTargetKind.PAGE -> {
                        OutlinedTextField(
                            value = targetVal,
                            onValueChange = { targetVal = it },
                            label = { Text("Número de página (1 .. $totalPages)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    LinkTargetKind.NOTEBOOK -> {
                        Text(
                            "Selecciona el cuaderno (se abrirá en pantalla dividida):",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkTextSecondary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            notebooks.forEach { nb ->
                                FilterChip(
                                    selected = targetVal == nb.uuid,
                                    onClick = {
                                        targetVal = nb.uuid
                                        if (label == "Ir a referencia") label = nb.title
                                    },
                                    label = { Text(nb.title) }
                                )
                            }
                        }
                    }
                    LinkTargetKind.WEB_URL -> {
                        OutlinedTextField(
                            value = targetVal,
                            onValueChange = { targetVal = it },
                            label = { Text("Dirección URL (https://...)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = onDelete) {
                        Text("Eliminar", color = Color(0xFFE06C75))
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(label, kind, targetVal, colorArgb)
                }
            ) {
                Text("Guardar enlace", color = Petroleum)
            }
        }
    )
}
