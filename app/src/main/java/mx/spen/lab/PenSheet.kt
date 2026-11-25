package mx.spen.lab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** High-frequency stylus canvas with world-normalized strokes, 2D/3D shapes, text boxes, layers, ruler, tape, laser, and lasso. */
class PenSheet(
    context: Context,
    private val onStateChanged: (canUndo: Boolean, canRedo: Boolean, hasSelection: Boolean) -> Unit,
    private val onTelemetryChanged: (PenTelemetry) -> Unit = {},
    private val onRequestTextBoxEditor: (existing: TextBoxItem?, worldX: Float, worldY: Float) -> Unit = { _, _, _ -> }
) : FrameLayout(context) {

    companion object {
        private const val TAG = "PenSheet"
    }

    sealed interface CanvasItem {
        val id: Long
        val layerId: Int
        val worldBounds: RectF
        val isHighlighter: Boolean
        val isTape: Boolean
        fun translated(dx: Float, dy: Float, newId: Long = id): CanvasItem
        fun scaled(anchorX: Float, anchorY: Float, factor: Float, newId: Long = id): CanvasItem
        fun recolored(newColor: Int): CanvasItem
        fun withLayer(newLayerId: Int): CanvasItem
    }

    data class DebugRawStroke(
        val redStroke: Stroke,
        val creationScale: Float,
        val creationPanX: Float,
        val creationPanY: Float
    )

    data class InkItem(
        override val id: Long,
        override val layerId: Int,
        val data: StrokeData,
        val stroke: Stroke,
        override val worldBounds: RectF,
        override val isHighlighter: Boolean,
        val debugRaw: DebugRawStroke? = null
    ) : CanvasItem {
        override val isTape: Boolean get() = data.brushType == BrushType.TAPE

        override fun translated(dx: Float, dy: Float, newId: Long): InkItem {
            val movedData = data.translated(dx, dy)
            val movedStroke = buildStrokeFromData(movedData)
            val movedBounds = computeStrokeWorldBounds(movedStroke, movedData)
            return InkItem(
                id = newId,
                layerId = layerId,
                data = movedData,
                stroke = movedStroke,
                worldBounds = movedBounds,
                isHighlighter = isHighlighter,
                debugRaw = null
            )
        }

        override fun scaled(anchorX: Float, anchorY: Float, factor: Float, newId: Long): InkItem {
            val scaledData = data.scaled(anchorX, anchorY, factor)
            val scaledStroke = buildStrokeFromData(scaledData)
            val scaledBounds = computeStrokeWorldBounds(scaledStroke, scaledData)
            return InkItem(
                id = newId,
                layerId = layerId,
                data = scaledData,
                stroke = scaledStroke,
                worldBounds = scaledBounds,
                isHighlighter = isHighlighter,
                debugRaw = null
            )
        }

        override fun recolored(newColor: Int): InkItem {
            val newData = data.copy(color = newColor)
            val newStroke = stroke.copy(stroke.brush.copyWithColorIntArgb(newColor))
            return copy(data = newData, stroke = newStroke, debugRaw = null)
        }

        override fun withLayer(newLayerId: Int): InkItem = copy(layerId = newLayerId)

        override fun equals(other: Any?): Boolean = (other is InkItem) && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    data class ShapeItem(
        override val id: Long,
        override val layerId: Int,
        val shape: RecognizedShape,
        val color: Int,
        val fillColor: Int = Color.TRANSPARENT,
        val strokeWidthWorld: Float,
        override val worldBounds: RectF,
        override val isHighlighter: Boolean
    ) : CanvasItem {
        override val isTape: Boolean get() = false

        override fun translated(dx: Float, dy: Float, newId: Long): ShapeItem {
            val movedShape = shape.translated(dx, dy)
            return copy(
                id = newId,
                shape = movedShape,
                worldBounds = movedShape.computeWorldBounds(strokeWidthWorld)
            )
        }

        override fun scaled(anchorX: Float, anchorY: Float, factor: Float, newId: Long): ShapeItem {
            val scaledShape = shape.scaled(anchorX, anchorY, factor)
            val newWidth = (strokeWidthWorld * factor).coerceIn(0.5f, 120f)
            return copy(
                id = newId,
                shape = scaledShape,
                strokeWidthWorld = newWidth,
                worldBounds = scaledShape.computeWorldBounds(newWidth)
            )
        }

        override fun recolored(newColor: Int): ShapeItem = copy(color = newColor)
        override fun withLayer(newLayerId: Int): ShapeItem = copy(layerId = newLayerId)

        override fun equals(other: Any?): Boolean = (other is ShapeItem) && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    data class TextBoxItem(
        override val id: Long,
        override val layerId: Int,
        val x: Float,
        val y: Float,
        val text: String,
        val color: Int,
        val bgColor: Int = Color.TRANSPARENT,
        val fontSizeWorld: Float,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isUnderline: Boolean = false,
        override val worldBounds: RectF
    ) : CanvasItem {
        override val isHighlighter: Boolean get() = false
        override val isTape: Boolean get() = false

        override fun translated(dx: Float, dy: Float, newId: Long): TextBoxItem {
            val movedBounds = RectF(worldBounds).apply { offset(dx, dy) }
            return copy(id = newId, x = x + dx, y = y + dy, worldBounds = movedBounds)
        }

        override fun scaled(anchorX: Float, anchorY: Float, factor: Float, newId: Long): TextBoxItem {
            val nx = anchorX + (x - anchorX) * factor
            val ny = anchorY + (y - anchorY) * factor
            val nSize = (fontSizeWorld * factor).coerceIn(8f, 180f)
            val bw = worldBounds.width() * factor
            val bh = worldBounds.height() * factor
            return copy(
                id = newId,
                x = nx,
                y = ny,
                fontSizeWorld = nSize,
                worldBounds = RectF(nx, ny, nx + bw, ny + bh)
            )
        }

        override fun recolored(newColor: Int): TextBoxItem = copy(color = newColor)
        override fun withLayer(newLayerId: Int): TextBoxItem = copy(layerId = newLayerId)

        override fun equals(other: Any?): Boolean = (other is TextBoxItem) && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    private data class LaserTrail(
        val points: MutableList<PointF> = mutableListOf(),
        var lastPointMs: Long = SystemClock.uptimeMillis(),
        val color: Int = 0xFFFF1744.toInt()
    )

    private val isDebugBuild =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val density = resources.displayMetrics.density
    private val renderInk = CanvasStrokeRenderer.create()
    private val wetInk = InProgressStrokesView(context)

    private var nextItemId = 1L
    private fun allocateId(): Long = nextItemId++

    private val items = mutableListOf<CanvasItem>()
    private val undoHistory = ArrayDeque<List<CanvasItem>>()
    private val redoHistory = ArrayDeque<List<CanvasItem>>()
    private val erasedIds = mutableSetOf<Long>()
    private val selectedIds = mutableSetOf<Long>()
    private val revealedTapeIds = mutableSetOf<Long>()
    private val waitingForInk = mutableMapOf<InProgressStrokeId, PendingStrokeMeta>()

    // Layers (Section 19 · 图层)
    val layers = mutableListOf(NoteLayer(id = 1, name = "Capa 1"))
    var activeLayerId: Int = 1

    // Laser pointer trails (ephemeral)
    private var activeLaserTrail: LaserTrail? = null
    private val laserTrails = mutableListOf<LaserTrail>()
    var laserFadeDurationMs: Long = 1500L
    var laserColor: Int = 0xFFFF1744.toInt()

    private data class PendingStrokeMeta(
        val scale: Float,
        val panX: Float,
        val panY: Float,
        val layerId: Int,
        val brushType: BrushType,
        val color: Int,
        val fillColor: Int,
        val sizeWorld: Float,
        val epsilonWorld: Float,
        val isHighlighter: Boolean,
        val pressureSensitivity: Float,
        val snappedShape: RecognizedShape? = null
    )

    // Active stylus state
    private var activePointer = -1
    private var activeInk: InProgressStrokeId? = null
    private var predictor: MotionEventPredictor? = null
    private var erasing = false
    private var hovering = false
    private var sideButtonPressed = false
    private var pressure = 0f
    private var tiltDegrees = 0
    private var x = 0f
    private var y = 0f
    private var lastEraseWorld: PointF? = null

    // Direct Shape drag state (Section 09 · 图形笔)
    private var directShapeStartWorld: PointF? = null
    private var directShapePreview: RecognizedShape? = null

    // Lasso-erase loop state (Section 09 · 圈选擦除)
    private val eraserLassoWorld = mutableListOf<PointF>()

    // Diagnostics counters
    private var fingerContacts = 0
    private var cancellations = 0
    private var lastShapeDetected: String? = null
    private var shapeCorrection: ShapeCorrection? = null

    private data class ShapeCorrection(val id: Long, val rawStroke: Stroke, val meta: PendingStrokeMeta)

    // Hold-to-Shape tracking
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rawWorldPoints = mutableListOf<PointF>()
    private var holdAnchorX = 0f
    private var holdAnchorY = 0f
    private var holdSnappedShape: RecognizedShape? = null
    private val holdRunnable = Runnable { trySnapHoldShape() }

    // Single-finger double-tap & long-press tracking (Part 4 · 手势操作)
    private var lastSingleFingerTapMs = 0L
    private var lastSingleFingerTapX = 0f
    private var lastSingleFingerTapY = 0f
    private val longPressRunnable = Runnable { handleSingleFingerLongPress() }

    // Zoom & Pan (2-finger) & Multi-finger Tap Undo/Redo gestures
    var viewScale = 1f
        private set
    var panX = 0f
        private set
    var panY = 0f
        private set
    private var isTwoFingerGesturing = false
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var gestureStartMs = 0L
    private var gestureMaxFingers = 0
    private var gestureMaxMovePx = 0f
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var singleFingerStartX = 0f
    private var singleFingerStartY = 0f
    private var singleFingerMoved = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prevScale = viewScale
                val nextScale = (viewScale * detector.scaleFactor).coerceIn(0.45f, 4.0f)
                val factor = nextScale / prevScale
                panX = detector.focusX - (detector.focusX - panX) * factor
                panY = detector.focusY - (detector.focusY - panY) * factor
                viewScale = nextScale
                dryInk.invalidate()
                emitTelemetry()
                return true
            }
        }
    )

    // Lasso selection, temporary drag & corner resize state
    private val lassoPathWorld = mutableListOf<PointF>()
    private var selectionDragLastWorld: PointF? = null
    private var isDraggingSelection = false
    private var isScalingSelection = false
    private var scaleAnchorX = 0f
    private var scaleAnchorY = 0f
    private var scaleInitialDist = 1f
    private var activeScaleFactor = 1f
    private var dragDx = 0f
    private var dragDy = 0f

    // Virtual Ruler state (Guides and Shapes)
    var rulerEnabled: Boolean = false
        set(value) {
            field = value
            if (value && rulerWorldX == 0f && rulerWorldY == 0f && width > 0) {
                rulerWorldX = screenToWorldX(width * 0.5f)
                rulerWorldY = screenToWorldY(height * 0.5f)
            }
            dryInk.invalidate()
        }
    var rulerWorldX: Float = 0f
    var rulerWorldY: Float = 0f
    var rulerAngleDeg: Float = 0f
        set(value) {
            field = ((value % 360f) + 360f) % 360f
            dryInk.invalidate()
        }
    private var isDraggingRuler = false
    private var isRotatingRuler = false
    private var rulerDragLastWorld: PointF? = null

    // Configurable editor properties
    var toolMode: ToolMode = ToolMode.PEN
        set(value) {
            field = value
            if (value != ToolMode.LASSO && selectedIds.isNotEmpty()) {
                clearSelection()
            }
            diagnostics.invalidate()
        }

    var eraserMode: EraserMode = EraserMode.STROKE
    var eraseOnlyHighlighter: Boolean = false
    var eraseOnlyTape: Boolean = false

    var lassoMode: LassoMode = LassoMode.FREEHAND
    var lassoSelectInk: Boolean = true
    var lassoSelectShapes: Boolean = true
    var lassoSelectText: Boolean = true

    var directShapeKind: DirectShapeKind = DirectShapeKind.AUTO
    var shapeFillEnabled: Boolean = false
    var shapeFillOpacity: Float = 0.25f

    var inkColor: Int = PaperStyle.LIGHT.defaultInkColor
        set(value) {
            field = value
            diagnostics.invalidate()
        }

    var highlighterColor: Int = EditorPalettes.highlighterColors.first().argb
        set(value) {
            field = value
            diagnostics.invalidate()
        }

    var tapeColor: Int = EditorPalettes.tapeColors.first().argb
        set(value) {
            field = value
            diagnostics.invalidate()
        }

    var penWidthDp: Float = 4.2f
    var highlighterWidthDp: Float = 18f
    var tapeWidthDp: Float = 22f
    var eraserRadiusDp: Float = 12f
    var pressureSensitivity: Float = 1.0f
    var ballpointOpacity: Float = 1.0f
    var autoShapeEnabled: Boolean = false

    // Advanced Notein-inspired settings
    var readingMode: Boolean = false
        set(value) {
            field = value
            if (value && selectedIds.isNotEmpty()) clearSelection()
            diagnostics.invalidate()
        }

    var allowFingerDrawing: Boolean = false
    var adaptiveStrokeWidth: Boolean = true
    var gestureTapUndoRedo: Boolean = true
    var doubleTapFitPage: Boolean = true
    var pressureEraserEnabled: Boolean = false
    var pressureEraserThreshold: Float = 0.78f
    var revealAllTapes: Boolean = false
        set(value) {
            field = value
            dryInk.invalidate()
        }

    var paperStyle: PaperStyle = PaperStyle.LIGHT
        set(value) {
            field = value
            dryInk.invalidate()
            diagnostics.invalidate()
        }

    var paperTemplate: PaperTemplate = PaperTemplate.GRID
        set(value) {
            field = value
            dryInk.invalidate()
        }

    // Live template customization (Section 05 & 22)
    var templateSpacingDp: Float = 22f
        set(value) {
            field = value.coerceIn(12f, 52f)
            dryInk.invalidate()
        }

    var templateOpacity: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.15f, 1.0f)
            dryInk.invalidate()
        }

    var showDiagnostics: Boolean = false
        set(value) {
            field = value
            diagnostics.invalidate()
        }

    var debugCompareNormalized: Boolean = false
        set(value) {
            val enabled = isDebugBuild && value
            field = enabled
            if (!enabled) {
                for (i in items.indices) {
                    val it = items[i]
                    if (it is InkItem && it.debugRaw != null) {
                        items[i] = it.copy(debugRaw = null)
                    }
                }
            }
            dryInk.invalidate()
        }

    private val diagnosticTextSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dashedShapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(42, 161, 183)
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    val isWriting: Boolean get() = activePointer != -1 || waitingForInk.isNotEmpty()

    private fun screenToWorldX(sx: Float): Float = (sx - panX) / viewScale
    private fun screenToWorldY(sy: Float): Float = (sy - panY) / viewScale

    private fun isLayerVisible(layerId: Int): Boolean =
        layers.firstOrNull { it.id == layerId }?.visible ?: true

    private fun isLayerLocked(layerId: Int): Boolean =
        layers.firstOrNull { it.id == layerId }?.locked ?: false

    private val dryInk = object : View(context) {
        private val visibleWorldRect = RectF()
        private val itemCheckRect = RectF()

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(paperStyle.bgColor)

            canvas.save()
            canvas.translate(panX, panY)
            canvas.scale(viewScale, viewScale)

            visibleWorldRect.set(
                -panX / viewScale,
                -panY / viewScale,
                (width - panX) / viewScale,
                (height - panY) / viewScale
            )

            drawPaperTemplate(canvas)

            // Pass 1: Highlighter strokes & shapes behind opaque ink
            for (item in items) {
                if (item.id !in erasedIds && isLayerVisible(item.layerId) &&
                    item.isHighlighter && !item.isTape &&
                    isItemVisible(item, visibleWorldRect, itemCheckRect)
                ) {
                    drawCanvasItem(canvas, item)
                }
            }
            // Pass 2: Opaque pen strokes, geometric shapes, and text boxes
            for (item in items) {
                if (item.id !in erasedIds && isLayerVisible(item.layerId) &&
                    !item.isHighlighter && !item.isTape &&
                    isItemVisible(item, visibleWorldRect, itemCheckRect)
                ) {
                    drawCanvasItem(canvas, item)
                }
            }
            // Pass 3: Study Tapes (drawn OVER opaque ink to mask formulas/answers)
            for (item in items) {
                if (item.id !in erasedIds && isLayerVisible(item.layerId) &&
                    item.isTape &&
                    isItemVisible(item, visibleWorldRect, itemCheckRect)
                ) {
                    drawCanvasItem(canvas, item)
                }
            }

            // Active lasso loop or rectangle
            if (toolMode == ToolMode.LASSO && lassoPathWorld.size > 1) {
                lassoPaint.strokeWidth = 2f * density / viewScale
                lassoPaint.color = Color.rgb(42, 161, 183)
                if (lassoMode == LassoMode.RECTANGLE) {
                    val f = lassoPathWorld.first()
                    val l = lassoPathWorld.last()
                    canvas.drawRect(min(f.x, l.x), min(f.y, l.y), max(f.x, l.x), max(f.y, l.y), lassoPaint)
                } else {
                    val path = Path().apply {
                        moveTo(lassoPathWorld[0].x, lassoPathWorld[0].y)
                        for (i in 1 until lassoPathWorld.size) {
                            lineTo(lassoPathWorld[i].x, lassoPathWorld[i].y)
                        }
                    }
                    canvas.drawPath(path, lassoPaint)
                }
            }

            // Active lasso-eraser loop
            if (erasing && eraserMode == EraserMode.LASSO_ERASE && eraserLassoWorld.size > 1) {
                lassoPaint.strokeWidth = 2f * density / viewScale
                lassoPaint.color = Color.rgb(224, 108, 117)
                val path = Path().apply {
                    moveTo(eraserLassoWorld[0].x, eraserLassoWorld[0].y)
                    for (i in 1 until eraserLassoWorld.size) {
                        lineTo(eraserLassoWorld[i].x, eraserLassoWorld[i].y)
                    }
                    close()
                }
                canvas.drawPath(path, lassoPaint)
            }

            // Selection bounding box + bottom-right resize handle
            if (selectedIds.isNotEmpty()) {
                val bounds = computeSelectionBounds()
                if (!bounds.isEmpty) {
                    val pad = 8f * density / viewScale
                    bounds.inset(-pad, -pad)
                    lassoPaint.strokeWidth = 2f * density / viewScale
                    lassoPaint.color = Color.rgb(42, 161, 183)
                    canvas.drawRoundRect(bounds, 8f / viewScale, 8f / viewScale, lassoPaint)

                    // Corner resize handle
                    val handleR = 7f * density / viewScale
                    paint.style = Paint.Style.FILL
                    paint.color = Color.rgb(42, 161, 183)
                    canvas.drawCircle(bounds.right, bounds.bottom, handleR, paint)
                }
            }

            // Live Hold-to-Shape or Direct Shape preview
            val previewShape = directShapePreview ?: holdSnappedShape
            if (previewShape != null) {
                val pColor = if (directShapePreview != null) inkColor else Color.argb(190, 42, 161, 183)
                shapePaint.color = pColor
                shapePaint.strokeWidth = (penWidthDp * density) / viewScale
                val pFill = if (shapeFillEnabled) applyAlpha(inkColor, (shapeFillOpacity * 255).toInt()) else Color.TRANSPARENT
                drawShape(canvas, previewShape, pFill, shapePaint.strokeWidth)
            }

            // Virtual Ruler overlay
            if (rulerEnabled) {
                drawVirtualRuler(canvas)
            }

            // Ephemeral Laser pointer trails
            drawLaserTrails(canvas)

            canvas.restore()
        }
    }

    private fun drawVirtualRuler(canvas: Canvas) {
        val len = 340f * density / viewScale
        val thick = 44f * density / viewScale
        canvas.save()
        canvas.translate(rulerWorldX, rulerWorldY)
        canvas.rotate(rulerAngleDeg)

        val rect = RectF(-len * 0.5f, 0f, len * 0.5f, thick)
        fillPaint.color = Color.argb(180, 29, 33, 40)
        canvas.drawRoundRect(rect, 8f / viewScale, 8f / viewScale, fillPaint)

        shapePaint.color = Color.rgb(145, 225, 213)
        shapePaint.strokeWidth = 2f * density / viewScale
        canvas.drawRoundRect(rect, 8f / viewScale, 8f / viewScale, shapePaint)

        // Graduations along the top ruling edge (y = 0)
        val step = 10f * density / viewScale
        var gx = -len * 0.45f
        var tickIdx = 0
        shapePaint.strokeWidth = 1f * density / viewScale
        while (gx <= len * 0.45f) {
            val tickH = if (tickIdx % 5 == 0) thick * 0.34f else thick * 0.18f
            canvas.drawLine(gx, 0f, gx, tickH, shapePaint)
            gx += step
            tickIdx++
        }

        // Degree badge in center and rotation handle at right end
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(145, 225, 213)
        paint.textSize = 12f * density / viewScale
        canvas.drawText(
            String.format(Locale.getDefault(), "%.0f°", rulerAngleDeg),
            -14f * density / viewScale,
            thick * 0.68f,
            paint
        )
        canvas.drawCircle(len * 0.42f, thick * 0.5f, 8f * density / viewScale, paint)
        canvas.restore()
    }

    private fun drawLaserTrails(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        var needsNextFrame = false
        val fadeMs = laserFadeDurationMs.coerceAtLeast(300L)
        val iterator = laserTrails.iterator()
        while (iterator.hasNext()) {
            val trail = iterator.next()
            val age = now - trail.lastPointMs
            if (age >= fadeMs && trail !== activeLaserTrail) {
                iterator.remove()
                continue
            }
            val alphaFactor = if (trail === activeLaserTrail) {
                1f
            } else {
                (1f - age.toFloat() / fadeMs).coerceIn(0f, 1f)
            }
            if (trail.points.size >= 2) {
                val path = Path().apply {
                    moveTo(trail.points[0].x, trail.points[0].y)
                    for (i in 1 until trail.points.size) {
                        lineTo(trail.points[i].x, trail.points[i].y)
                    }
                }
                laserPaint.color = Color.argb(
                    (115 * alphaFactor).toInt(),
                    Color.red(trail.color),
                    Color.green(trail.color),
                    Color.blue(trail.color)
                )
                laserPaint.strokeWidth = (12f * density) / viewScale
                canvas.drawPath(path, laserPaint)

                laserPaint.color = Color.argb((245 * alphaFactor).toInt(), 255, 245, 248)
                laserPaint.strokeWidth = (3.5f * density) / viewScale
                canvas.drawPath(path, laserPaint)
                needsNextFrame = true
            }
        }
        if (needsNextFrame) {
            dryInk.postInvalidateOnAnimation()
        }
    }

    private fun isItemVisible(item: CanvasItem, visibleWorld: RectF, scratch: RectF): Boolean {
        scratch.set(item.worldBounds)
        if (item.id in selectedIds) {
            if (isDraggingSelection) {
                scratch.offset(dragDx, dragDy)
            } else if (isScalingSelection) {
                return true
            }
        }
        return RectF.intersects(visibleWorld, scratch)
    }

    private fun drawPaperTemplate(canvas: Canvas) {
        if (paperTemplate == PaperTemplate.BLANK) return

        val worldLeft = -panX / viewScale
        val worldTop = -panY / viewScale
        val worldRight = (width - panX) / viewScale
        val worldBottom = (height - panY) / viewScale

        paint.style = Paint.Style.STROKE
        val baseColor = paperStyle.lineColor
        paint.color = Color.argb(
            (templateOpacity * 255).toInt().coerceIn(25, 255),
            Color.red(baseColor),
            Color.green(baseColor),
            Color.blue(baseColor)
        )
        paint.strokeWidth = (density * 0.75f) / viewScale

        val spacing = templateSpacingDp * density
        val startX = (kotlin.math.floor((worldLeft / spacing).toDouble()) * spacing).toFloat()
        val startY = (kotlin.math.floor((worldTop / spacing).toDouble()) * spacing).toFloat()

        when (paperTemplate) {
            PaperTemplate.BLANK -> Unit
            PaperTemplate.GRID -> {
                var gx = startX
                while (gx <= worldRight) {
                    canvas.drawLine(gx, worldTop, gx, worldBottom, paint)
                    gx += spacing
                }
                var gy = startY
                while (gy <= worldBottom) {
                    canvas.drawLine(worldLeft, gy, worldRight, gy, paint)
                    gy += spacing
                }
            }
            PaperTemplate.RULED -> {
                val lineSpacing = spacing * 1.25f
                val rStartY = (kotlin.math.floor((worldTop / lineSpacing).toDouble()) * lineSpacing).toFloat()
                var gy = rStartY
                while (gy <= worldBottom) {
                    canvas.drawLine(worldLeft, gy, worldRight, gy, paint)
                    gy += lineSpacing
                }
            }
            PaperTemplate.DOTTED -> {
                paint.style = Paint.Style.FILL
                val dotR = (1.35f * density) / viewScale
                var gx = startX
                while (gx <= worldRight) {
                    var gy = startY
                    while (gy <= worldBottom) {
                        canvas.drawCircle(gx, gy, dotR, paint)
                        gy += spacing
                    }
                    gx += spacing
                }
            }
            PaperTemplate.ISOMETRIC -> {
                val step = spacing * 1.25f
                val dx = step
                val dy = step * sqrt(3f) * 0.5f
                val span = max(worldRight - worldLeft, worldBottom - worldTop) + step * 4
                val baseX = (kotlin.math.floor((worldLeft / dx).toDouble()) * dx).toFloat() - span
                val baseY = (kotlin.math.floor((worldTop / dy).toDouble()) * dy).toFloat()
                var gy = baseY
                while (gy <= worldBottom) {
                    canvas.drawLine(worldLeft, gy, worldRight, gy, paint)
                    gy += dy
                }
                var gx = baseX
                val h = worldBottom - worldTop
                val tan30 = 1f / sqrt(3f)
                while (gx <= worldRight + span) {
                    canvas.drawLine(gx, worldTop, gx + h * tan30, worldBottom, paint)
                    canvas.drawLine(gx, worldTop, gx - h * tan30, worldBottom, paint)
                    gx += dx
                }
            }
            PaperTemplate.CORNELL -> {
                val lineSpacing = spacing * 1.25f
                val rStartY = (kotlin.math.floor((worldTop / lineSpacing).toDouble()) * lineSpacing).toFloat()
                var gy = rStartY
                while (gy <= worldBottom) {
                    canvas.drawLine(worldLeft, gy, worldRight, gy, paint)
                    gy += lineSpacing
                }
                // Cornell cue margin line & top header line
                paint.strokeWidth = (density * 1.5f) / viewScale
                val marginX = 95f * density
                val headerY = 64f * density
                canvas.drawLine(marginX, worldTop, marginX, worldBottom, paint)
                canvas.drawLine(worldLeft, headerY, worldRight, headerY, paint)
            }
        }
    }

    private fun drawCanvasItem(canvas: Canvas, item: CanvasItem) {
        val isSelected = item.id in selectedIds
        val applyTempDrag = isDraggingSelection && isSelected
        val applyTempScale = isScalingSelection && isSelected
        if (applyTempDrag || applyTempScale) {
            canvas.save()
            if (applyTempDrag) {
                canvas.translate(dragDx, dragDy)
            } else if (applyTempScale) {
                canvas.scale(activeScaleFactor, activeScaleFactor, scaleAnchorX, scaleAnchorY)
            }
        }

        when (item) {
            is InkItem -> {
                if (item.isTape) {
                    val isRevealed = revealAllTapes || item.id in revealedTapeIds
                    val tapeStroke = if (isRevealed) {
                        val ghostColor = (item.data.color and 0x00FFFFFF) or 0x38000000
                        item.stroke.copy(item.stroke.brush.copyWithColorIntArgb(ghostColor))
                    } else {
                        item.stroke
                    }
                    renderInk.draw(canvas, tapeStroke, AffineTransform.IDENTITY)
                    if (isRevealed) {
                        lassoPaint.color = item.data.color
                        lassoPaint.strokeWidth = 1.5f * density / viewScale
                        canvas.drawRoundRect(item.worldBounds, 6f / viewScale, 6f / viewScale, lassoPaint)
                    }
                } else if (debugCompareNormalized && item.debugRaw != null) {
                    val raw = item.debugRaw
                    canvas.save()
                    canvas.scale(1f / raw.creationScale, 1f / raw.creationScale)
                    canvas.translate(-raw.creationPanX, -raw.creationPanY)
                    renderInk.draw(canvas, raw.redStroke, AffineTransform.IDENTITY)
                    canvas.restore()

                    val blueStroke = item.stroke.copy(
                        item.stroke.brush.copyWithColorIntArgb(0x9900B0FF.toInt())
                    )
                    renderInk.draw(canvas, blueStroke, AffineTransform.IDENTITY)
                } else {
                    renderInk.draw(canvas, item.stroke, AffineTransform.IDENTITY)
                }
            }
            is ShapeItem -> {
                shapePaint.color = item.color
                shapePaint.strokeWidth = item.strokeWidthWorld
                drawShape(canvas, item.shape, item.fillColor, item.strokeWidthWorld)
            }
            is TextBoxItem -> {
                drawTextBoxItem(canvas, item)
            }
        }

        if (applyTempDrag || applyTempScale) {
            canvas.restore()
        }
    }

    private fun drawTextBoxItem(canvas: Canvas, item: TextBoxItem) {
        if (Color.alpha(item.bgColor) > 0) {
            fillPaint.color = item.bgColor
            canvas.drawRoundRect(item.worldBounds, 6f, 6f, fillPaint)
        }
        textPaint.color = item.color
        textPaint.textSize = item.fontSizeWorld
        val style = when {
            item.isBold && item.isItalic -> Typeface.BOLD_ITALIC
            item.isBold -> Typeface.BOLD
            item.isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, style)
        textPaint.isUnderlineText = item.isUnderline

        val lines = item.text.split('\n')
        val lineHeight = item.fontSizeWorld * 1.3f
        var baselineY = item.y + item.fontSizeWorld
        val padX = 6f
        for (line in lines) {
            canvas.drawText(line, item.x + padX, baselineY, textPaint)
            baselineY += lineHeight
        }
    }

    private fun drawShape(
        canvas: Canvas,
        shape: RecognizedShape,
        fillColor: Int = Color.TRANSPARENT,
        strokeWidth: Float = shapePaint.strokeWidth
    ) {
        val hasFill = Color.alpha(fillColor) > 0
        if (hasFill) {
            fillPaint.color = fillColor
        }
        dashedShapePaint.color = shapePaint.color
        dashedShapePaint.strokeWidth = strokeWidth
        val dashUnit = max(6f, strokeWidth * 2.2f)
        dashedShapePaint.pathEffect = DashPathEffect(floatArrayOf(dashUnit, dashUnit), 0f)

        when (shape) {
            is RecognizedShape.Line -> {
                canvas.drawLine(shape.start.x, shape.start.y, shape.end.x, shape.end.y, shapePaint)
            }
            is RecognizedShape.WavyLine -> {
                val pts = shape.samplePoints(4f)
                if (pts.size >= 2) {
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                    }
                    canvas.drawPath(path, shapePaint)
                }
            }
            is RecognizedShape.Arrow -> {
                canvas.drawLine(shape.start.x, shape.start.y, shape.end.x, shape.end.y, shapePaint)
                canvas.drawLine(shape.end.x, shape.end.y, shape.wingLeft.x, shape.wingLeft.y, shapePaint)
                canvas.drawLine(shape.end.x, shape.end.y, shape.wingRight.x, shape.wingRight.y, shapePaint)
            }
            is RecognizedShape.CoordinateAxes -> {
                val ox = shape.originX
                val oy = shape.originY
                val head = max(10f, min(shape.right - shape.left, shape.bottom - shape.top) * 0.08f)
                // X axis (left to right with arrow at right)
                canvas.drawLine(shape.left, oy, shape.right, oy, shapePaint)
                canvas.drawLine(shape.right, oy, shape.right - head, oy - head * 0.6f, shapePaint)
                canvas.drawLine(shape.right, oy, shape.right - head, oy + head * 0.6f, shapePaint)
                // Y axis (bottom to top with arrow at top)
                canvas.drawLine(ox, shape.bottom, ox, shape.top, shapePaint)
                canvas.drawLine(ox, shape.top, ox - head * 0.6f, shape.top + head, shapePaint)
                canvas.drawLine(ox, shape.top, ox + head * 0.6f, shape.top + head, shapePaint)
            }
            is RecognizedShape.Shape3D -> {
                draw3DShape(canvas, shape, hasFill)
            }
            is RecognizedShape.Rectangle -> {
                if (hasFill) canvas.drawRect(shape.left, shape.top, shape.right, shape.bottom, fillPaint)
                canvas.drawRect(shape.left, shape.top, shape.right, shape.bottom, shapePaint)
            }
            is RecognizedShape.Triangle -> {
                val path = Path().apply {
                    moveTo(shape.a.x, shape.a.y)
                    lineTo(shape.b.x, shape.b.y)
                    lineTo(shape.c.x, shape.c.y)
                    close()
                }
                if (hasFill) canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, shapePaint)
            }
            is RecognizedShape.Ellipse -> {
                if (hasFill) {
                    canvas.drawOval(
                        shape.cx - shape.rx, shape.cy - shape.ry,
                        shape.cx + shape.rx, shape.cy + shape.ry,
                        fillPaint
                    )
                }
                canvas.drawOval(
                    shape.cx - shape.rx, shape.cy - shape.ry,
                    shape.cx + shape.rx, shape.cy + shape.ry,
                    shapePaint
                )
            }
            is RecognizedShape.Polygon -> {
                val verts = shape.vertices
                if (verts.size >= 2) {
                    val path = Path().apply {
                        moveTo(verts[0].x, verts[0].y)
                        for (i in 1 until verts.size) {
                            lineTo(verts[i].x, verts[i].y)
                        }
                        close()
                    }
                    if (hasFill) canvas.drawPath(path, fillPaint)
                    canvas.drawPath(path, shapePaint)
                }
            }
            is RecognizedShape.Polyline -> {
                val verts = shape.vertices
                if (verts.size >= 2) {
                    val path = Path().apply {
                        moveTo(verts[0].x, verts[0].y)
                        for (i in 1 until verts.size) {
                            lineTo(verts[i].x, verts[i].y)
                        }
                    }
                    canvas.drawPath(path, shapePaint)
                }
            }
            is RecognizedShape.Arc -> {
                val path = Path().apply {
                    moveTo(shape.start.x, shape.start.y)
                    quadTo(shape.control.x, shape.control.y, shape.end.x, shape.end.y)
                }
                canvas.drawPath(path, shapePaint)
            }
        }
    }

    private fun draw3DShape(canvas: Canvas, shape: RecognizedShape.Shape3D, hasFill: Boolean) {
        val l = shape.left
        val t = shape.top
        val r = shape.right
        val b = shape.bottom
        val w = r - l
        val h = b - t
        val cx = (l + r) * 0.5f
        val cy = (t + b) * 0.5f

        when (shape.kind) {
            DirectShapeKind.CUBE -> {
                val dx = w * 0.28f
                val dy = h * 0.28f
                val fl = l
                val ft = t + dy
                val fr = r - dx
                val fb = b
                val bl = l + dx
                val bt = t
                val br = r
                val bb = b - dy

                if (hasFill) {
                    canvas.drawRect(fl, ft, fr, fb, fillPaint)
                }
                // Front face (solid)
                canvas.drawRect(fl, ft, fr, fb, shapePaint)
                // Top and Right visible back edges (solid)
                canvas.drawLine(fl, ft, bl, bt, shapePaint)
                canvas.drawLine(fr, ft, br, bt, shapePaint)
                canvas.drawLine(fr, fb, br, bb, shapePaint)
                canvas.drawLine(bl, bt, br, bt, shapePaint)
                canvas.drawLine(br, bt, br, bb, shapePaint)
                // Hidden interior back edges (dashed)
                canvas.drawLine(fl, fb, bl, bb, dashedShapePaint)
                canvas.drawLine(bl, bt, bl, bb, dashedShapePaint)
                canvas.drawLine(bl, bb, br, bb, dashedShapePaint)
            }
            DirectShapeKind.CYLINDER -> {
                val ry = min(h * 0.18f, w * 0.25f)
                val topOval = RectF(l, t, r, t + 2f * ry)
                val botOval = RectF(l, b - 2f * ry, r, b)
                if (hasFill) {
                    canvas.drawRect(l, t + ry, r, b - ry, fillPaint)
                    canvas.drawOval(topOval, fillPaint)
                    canvas.drawOval(botOval, fillPaint)
                }
                canvas.drawOval(topOval, shapePaint)
                canvas.drawLine(l, t + ry, l, b - ry, shapePaint)
                canvas.drawLine(r, t + ry, r, b - ry, shapePaint)
                canvas.drawArc(botOval, 0f, 180f, false, shapePaint)
                canvas.drawArc(botOval, 180f, 180f, false, dashedShapePaint)
            }
            DirectShapeKind.CONE -> {
                val ry = min(h * 0.18f, w * 0.25f)
                val botOval = RectF(l, b - 2f * ry, r, b)
                val apexX = cx
                val apexY = t
                val baseCy = b - ry
                if (hasFill) {
                    val tri = Path().apply {
                        moveTo(apexX, apexY)
                        lineTo(r, baseCy)
                        lineTo(l, baseCy)
                        close()
                    }
                    canvas.drawPath(tri, fillPaint)
                    canvas.drawOval(botOval, fillPaint)
                }
                canvas.drawLine(apexX, apexY, l, baseCy, shapePaint)
                canvas.drawLine(apexX, apexY, r, baseCy, shapePaint)
                canvas.drawArc(botOval, 0f, 180f, false, shapePaint)
                canvas.drawArc(botOval, 180f, 180f, false, dashedShapePaint)
            }
            DirectShapeKind.SPHERE -> {
                val rad = min(w, h) * 0.5f
                val sphereRect = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
                val eqRy = rad * 0.32f
                val eqRect = RectF(cx - rad, cy - eqRy, cx + rad, cy + eqRy)
                if (hasFill) {
                    canvas.drawOval(sphereRect, fillPaint)
                }
                canvas.drawOval(sphereRect, shapePaint)
                canvas.drawArc(eqRect, 0f, 180f, false, shapePaint)
                canvas.drawArc(eqRect, 180f, 180f, false, dashedShapePaint)
            }
            else -> {
                canvas.drawRect(l, t, r, b, shapePaint)
            }
        }
    }

    private val diagnostics = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            if (showDiagnostics) {
                paint.style = Paint.Style.FILL
                paint.color = if (paperStyle.isDark) Color.argb(235, 24, 28, 36) else Color.argb(238, 229, 237, 250)
                canvas.drawRect(0f, 0f, width.toFloat(), 44f * density, paint)

                paint.color = if (paperStyle.isDark) Color.rgb(225, 232, 244) else Color.rgb(24, 46, 80)
                paint.textSize = diagnosticTextSize
                val telemetry = String.format(
                    Locale.getDefault(),
                    "Presión %.2f · Inclinación %d° · Zoom %d%%",
                    pressure, tiltDegrees, (viewScale * 100).toInt()
                )
                canvas.drawText(telemetry, 12f * density, 16f * density, paint)
                val button = if (sideButtonPressed) "presionado" else "suelto"
                val shapeLabel = lastShapeDetected?.let { " · Forma: $it" } ?: ""
                canvas.drawText(
                    "Botón $button · Táctil $fingerContacts · Cancelados $cancellations$shapeLabel",
                    12f * density, 34f * density, paint
                )
            }

            if ((hovering || erasing) && !readingMode) {
                val isErasingCursor = sideButtonPressed || erasing || toolMode == ToolMode.ERASER
                paint.color = when {
                    isErasingCursor -> Color.rgb(200, 72, 84)
                    toolMode == ToolMode.HIGHLIGHTER -> highlighterColor
                    toolMode == ToolMode.TAPE -> tapeColor
                    toolMode == ToolMode.LASER -> laserColor
                    toolMode == ToolMode.LASSO || toolMode == ToolMode.SHAPES || toolMode == ToolMode.TEXT ->
                        Color.rgb(42, 161, 183)
                    else -> inkColor
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density * 1.3f
                val cursorRadius = when {
                    isErasingCursor -> eraserRadiusDp * density
                    toolMode == ToolMode.HIGHLIGHTER -> (highlighterWidthDp * density) / 2f
                    toolMode == ToolMode.TAPE -> (tapeWidthDp * density) / 2f
                    toolMode == ToolMode.LASSO || toolMode == ToolMode.LASER ||
                        toolMode == ToolMode.SHAPES || toolMode == ToolMode.TEXT -> 6f * density
                    else -> (penWidthDp * density) / 2f
                }
                canvas.drawCircle(x, y, max(3f * density, cursorRadius), paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    init {
        addView(dryInk, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wetInk, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(diagnostics, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentDescription = "Hoja de apuntes interactiva. Usa el S Pen para escribir y dos dedos para zoom o moverte."

        wetInk.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                for ((id, rawStroke) in strokes) {
                    val meta = waitingForInk.remove(id)
                    if (meta == null) {
                        Log.e(TAG, "onStrokesFinished: missing PendingStrokeMeta for $id; discarding stroke")
                        continue
                    }
                    rememberBeforeChange()
                    val itemId = allocateId()
                    val shape = meta.snappedShape
                    if (shape != null && meta.brushType != BrushType.TAPE) {
                        items.add(
                            ShapeItem(
                                id = itemId,
                                layerId = meta.layerId,
                                shape = shape,
                                color = meta.color,
                                fillColor = meta.fillColor,
                                strokeWidthWorld = meta.sizeWorld,
                                worldBounds = shape.computeWorldBounds(meta.sizeWorld),
                                isHighlighter = meta.isHighlighter
                            )
                        )
                        lastShapeDetected = shape.label
                        shapeCorrection = ShapeCorrection(itemId, rawStroke, meta)
                    } else {
                        items.add(createInkItem(itemId, rawStroke, meta))
                    }
                }
                dryInk.invalidate()
                wetInk.removeFinishedStrokes(strokes.keys)
                emitState()
                emitTelemetry()
            }
        })
        post { wetInk.eagerInit() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex

        // Multi-finger gesture handling (2-finger zoom/pan + 2-finger tap Undo / 3-finger tap Redo)
        if (activePointer == -1 && event.pointerCount >= 2 && !isPen(event, 0) && !isPen(event, 1)) {
            mainHandler.removeCallbacks(longPressRunnable)
            if (event.pointerCount == 2) {
                scaleDetector.onTouchEvent(event)
            }
            val midX = (event.getX(0) + event.getX(1)) * 0.5f
            val midY = (event.getY(0) + event.getY(1)) * 0.5f
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (!isTwoFingerGesturing) {
                        isTwoFingerGesturing = true
                        gestureStartMs = SystemClock.uptimeMillis()
                        gestureMaxFingers = event.pointerCount
                        gestureMaxMovePx = 0f
                        gestureStartX = midX
                        gestureStartY = midY
                    } else {
                        gestureMaxFingers = max(gestureMaxFingers, event.pointerCount)
                    }
                    lastMidX = midX
                    lastMidY = midY
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTwoFingerGesturing && event.pointerCount == 2) {
                        val moveDist = hypot(midX - gestureStartX, midY - gestureStartY)
                        if (moveDist > gestureMaxMovePx) gestureMaxMovePx = moveDist
                        panX += midX - lastMidX
                        panY += midY - lastMidY
                        lastMidX = midX
                        lastMidY = midY
                        dryInk.invalidate()
                        emitTelemetry()
                    }
                }
            }
            return true
        }

        if (isTwoFingerGesturing) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                val elapsed = SystemClock.uptimeMillis() - gestureStartMs
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    gestureTapUndoRedo &&
                    elapsed <= 260L &&
                    gestureMaxMovePx <= 16f * density
                ) {
                    if (gestureMaxFingers == 2) {
                        undo()
                    } else if (gestureMaxFingers >= 3) {
                        redo()
                    }
                }
                isTwoFingerGesturing = false
                gestureMaxFingers = 0
            }
            return true
        }

        // Virtual Ruler interaction with finger (drag or rotate ruler)
        if (rulerEnabled && activePointer == -1 && !isPen(event, index)) {
            val wx = screenToWorldX(event.getX(index))
            val wy = screenToWorldY(event.getY(index))
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = hitTestRuler(wx, wy)
                    if (hit != 0) {
                        isRotatingRuler = (hit == 2)
                        isDraggingRuler = (hit == 1)
                        rulerDragLastWorld = PointF(wx, wy)
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingRuler) {
                        val prev = rulerDragLastWorld ?: PointF(wx, wy)
                        rulerWorldX += wx - prev.x
                        rulerWorldY += wy - prev.y
                        rulerDragLastWorld = PointF(wx, wy)
                        dryInk.postInvalidateOnAnimation()
                        return true
                    } else if (isRotatingRuler) {
                        val deg = Math.toDegrees(atan2((wy - rulerWorldY).toDouble(), (wx - rulerWorldX).toDouble())).toFloat()
                        rulerAngleDeg = deg
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingRuler || isRotatingRuler) {
                        isDraggingRuler = false
                        isRotatingRuler = false
                        rulerDragLastWorld = null
                        return true
                    }
                }
            }
        }

        // Reading mode or non-drawing finger touch: pan with 1 finger, double-tap to fit, long-press to edit, or tap Study Tape
        val isStylusOrAllowedFinger = isPen(event, index) || (allowFingerDrawing && !readingMode)
        if (readingMode || (!isStylusOrAllowedFinger && activePointer == -1)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER) {
                        fingerContacts++
                        emitTelemetry()
                    }
                    singleFingerStartX = event.getX(index)
                    singleFingerStartY = event.getY(index)
                    singleFingerMoved = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.postDelayed(longPressRunnable, 480L)
                }
                MotionEvent.ACTION_MOVE -> {
                    val cx = event.getX(0)
                    val cy = event.getY(0)
                    val dx = cx - singleFingerStartX
                    val dy = cy - singleFingerStartY
                    if (hypot(dx, dy) > 10f * density) {
                        singleFingerMoved = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (readingMode && singleFingerMoved) {
                        panX += dx
                        panY += dy
                        singleFingerStartX = cx
                        singleFingerStartY = cy
                        dryInk.invalidate()
                        emitTelemetry()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!singleFingerMoved) {
                        val ux = event.getX(index)
                        val uy = event.getY(index)
                        if (!toggleTapeAtScreen(ux, uy)) {
                            val now = SystemClock.uptimeMillis()
                            if (doubleTapFitPage &&
                                now - lastSingleFingerTapMs <= 300L &&
                                hypot(ux - lastSingleFingerTapX, uy - lastSingleFingerTapY) <= 28f * density
                            ) {
                                fitToContent()
                                lastSingleFingerTapMs = 0L
                            } else {
                                lastSingleFingerTapMs = now
                                lastSingleFingerTapX = ux
                                lastSingleFingerTapY = uy
                            }
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                }
            }
            return true
        }

        if (event.pointerCount != 1) predictor = null
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val toolType = event.getToolType(index)
                if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
                    fingerContacts++
                    emitTelemetry()
                }
                if (activePointer == -1 && (isPen(event, index) || allowFingerDrawing)) {
                    beginPen(event, index)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val penIndex = event.findPointerIndex(activePointer)
                if (penIndex >= 0) movePen(event, penIndex)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val canceled = (event.flags and MotionEvent.FLAG_CANCELED) != 0
                if (canceled) {
                    cancellations++
                    emitTelemetry()
                }
                if (event.getPointerId(index) == activePointer) {
                    finishPen(event, index, canceled)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancellations++
                mainHandler.removeCallbacks(holdRunnable)
                activeInk?.let { id ->
                    waitingForInk.remove(id)
                    wetInk.cancelStroke(id, event)
                }
                activeLaserTrail = null
                directShapeStartWorld = null
                directShapePreview = null
                eraserLassoWorld.clear()
                isDraggingSelection = false
                isScalingSelection = false
                selectionDragLastWorld = null
                dragDx = 0f
                dragDy = 0f
                endPen()
                emitTelemetry()
            }
        }
        diagnostics.postInvalidateOnAnimation()
        return true
    }

    private fun handleSingleFingerLongPress() {
        if (isWriting || isTwoFingerGesturing || singleFingerMoved) return
        val wx = screenToWorldX(singleFingerStartX)
        val wy = screenToWorldY(singleFingerStartY)
        // Find topmost unlocked TextBoxItem or ShapeItem under long-press
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (!isLayerVisible(item.layerId) || isLayerLocked(item.layerId)) continue
            if (item.worldBounds.contains(wx, wy)) {
                if (item is TextBoxItem) {
                    onRequestTextBoxEditor(item, item.x, item.y)
                    return
                } else {
                    toolMode = ToolMode.LASSO
                    selectedIds.clear()
                    selectedIds.add(item.id)
                    dryInk.invalidate()
                    emitState()
                    return
                }
            }
        }
    }

    private fun hitTestRuler(wx: Float, wy: Float): Int {
        val dx = wx - rulerWorldX
        val dy = wy - rulerWorldY
        val rad = Math.toRadians(-rulerAngleDeg.toDouble())
        val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
        val len = 340f * density / viewScale
        val thick = 44f * density / viewScale
        if (lx in (-len * 0.5f)..(len * 0.5f) && ly in 0f..thick) {
            return if (lx >= len * 0.32f) 2 else 1
        }
        return 0
    }

    /** If virtual ruler is enabled and point is close to ruling edge, project onto ruler edge. */
    private fun snapPointToRulerIfClose(wx: Float, wy: Float): PointF {
        if (!rulerEnabled) return PointF(wx, wy)
        val dx = wx - rulerWorldX
        val dy = wy - rulerWorldY
        val rad = Math.toRadians(-rulerAngleDeg.toDouble())
        val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
        val len = 340f * density / viewScale
        val snapDist = 26f * density / viewScale
        if (lx in (-len * 0.52f)..(len * 0.52f) && abs(ly) <= snapDist) {
            val invRad = Math.toRadians(rulerAngleDeg.toDouble())
            val sx = rulerWorldX + (lx * cos(invRad)).toFloat()
            val sy = rulerWorldY + (lx * sin(invRad)).toFloat()
            return PointF(sx, sy)
        }
        return PointF(wx, wy)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!isPen(event, 0)) return super.onHoverEvent(event)
        hovering = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        readPen(event, 0)
        if (!hovering) sideButtonPressed = false
        diagnostics.postInvalidateOnAnimation()
        emitTelemetry()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isPen(event, 0) &&
            (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE)
        ) {
            readPen(event, 0)
            diagnostics.postInvalidateOnAnimation()
            emitTelemetry()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun isPen(event: MotionEvent, index: Int): Boolean =
        when (event.getToolType(index)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
            else -> false
        }

    private fun readPen(event: MotionEvent, index: Int) {
        x = event.getX(index)
        y = event.getY(index)
        pressure = event.getPressure(index)
        tiltDegrees = Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, index).toDouble()).toInt()
        sideButtonPressed = event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)
    }

    private fun beginPen(event: MotionEvent, index: Int) {
        readPen(event, index)
        hovering = false
        activePointer = event.getPointerId(index)
        holdSnappedShape = null
        rawWorldPoints.clear()
        directShapeStartWorld = null
        directShapePreview = null

        val hardwareEraser = sideButtonPressed ||
            event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER ||
            (pressureEraserEnabled && pressure >= pressureEraserThreshold)
        erasing = hardwareEraser || toolMode == ToolMode.ERASER

        if (erasing) {
            if (!hardwareEraser && eraserMode == EraserMode.LASSO_ERASE) {
                eraserLassoWorld.clear()
                eraserLassoWorld.add(PointF(screenToWorldX(x), screenToWorldY(y)))
                dryInk.invalidate()
            } else {
                eraseAtScreen(x, y)
            }
            return
        }

        if (toolMode == ToolMode.TEXT) {
            val wx = screenToWorldX(x)
            val wy = screenToWorldY(y)
            val hitText = items.lastOrNull {
                it is TextBoxItem && isLayerVisible(it.layerId) && !isLayerLocked(it.layerId) && it.worldBounds.contains(wx, wy)
            } as? TextBoxItem
            onRequestTextBoxEditor(hitText, wx, wy)
            endPen()
            return
        }

        if (toolMode == ToolMode.LASER) {
            val trail = LaserTrail(
                points = mutableListOf(PointF(screenToWorldX(x), screenToWorldY(y))),
                lastPointMs = SystemClock.uptimeMillis(),
                color = laserColor
            )
            activeLaserTrail = trail
            laserTrails.add(trail)
            dryInk.postInvalidateOnAnimation()
            return
        }

        if (toolMode == ToolMode.LASSO) {
            val wx = screenToWorldX(x)
            val wy = screenToWorldY(y)
            if (selectedIds.isNotEmpty()) {
                val bounds = computeSelectionBounds()
                val pad = 8f * density / viewScale
                bounds.inset(-pad, -pad)
                val handleDist = hypot(wx - bounds.right, wy - bounds.bottom)
                if (handleDist <= 22f * density / viewScale) {
                    isScalingSelection = true
                    isDraggingSelection = false
                    scaleAnchorX = bounds.left
                    scaleAnchorY = bounds.top
                    scaleInitialDist = hypot(bounds.right - scaleAnchorX, bounds.bottom - scaleAnchorY).coerceAtLeast(10f)
                    activeScaleFactor = 1f
                    return
                } else if (bounds.contains(wx, wy)) {
                    isDraggingSelection = true
                    isScalingSelection = false
                    selectionDragLastWorld = PointF(wx, wy)
                    dragDx = 0f
                    dragDy = 0f
                    return
                }
            }
            selectedIds.clear()
            isDraggingSelection = false
            isScalingSelection = false
            dragDx = 0f
            dragDy = 0f
            lassoPathWorld.clear()
            lassoPathWorld.add(PointF(wx, wy))
            dryInk.invalidate()
            emitState()
            return
        }

        // Direct 2D / 3D Shape creation mode (Section 09 · 图形笔)
        if (toolMode == ToolMode.SHAPES && directShapeKind != DirectShapeKind.AUTO) {
            val startPt = PointF(screenToWorldX(x), screenToWorldY(y))
            directShapeStartWorld = startPt
            directShapePreview = null
            return
        }

        // Check if active layer is locked
        if (isLayerLocked(activeLayerId)) return

        val capturedScale = viewScale
        val capturedPanX = panX
        val capturedPanY = panY
        val isHighlighter = toolMode == ToolMode.HIGHLIGHTER
        val isTape = toolMode == ToolMode.TAPE
        val brushType = when (toolMode) {
            ToolMode.HIGHLIGHTER -> BrushType.HIGHLIGHTER
            ToolMode.BALLPOINT -> BrushType.BALLPOINT
            ToolMode.DASHED -> BrushType.DASHED
            ToolMode.TAPE -> BrushType.TAPE
            else -> BrushType.PEN
        }
        val activeColor = when {
            isHighlighter -> highlighterColor
            isTape -> tapeColor
            toolMode == ToolMode.BALLPOINT && ballpointOpacity < 0.99f ->
                applyAlpha(inkColor, (ballpointOpacity * 255).toInt())
            else -> inkColor
        }
        val baseWidthDp = when {
            isHighlighter -> highlighterWidthDp
            isTape -> tapeWidthDp
            else -> penWidthDp
        }
        val sizePx = if (adaptiveStrokeWidth) {
            baseWidthDp * density
        } else {
            baseWidthDp * density * capturedScale
        }
        val epsilonPx = density / 5f
        val brush = Brush.createWithColorIntArgb(familyFor(brushType), activeColor, sizePx, epsilonPx)

        val strokeId = wetInk.startStroke(event, activePointer, brush)
        activeInk = strokeId
        val fillCol = if (shapeFillEnabled) applyAlpha(activeColor, (shapeFillOpacity * 255).toInt()) else Color.TRANSPARENT
        waitingForInk[strokeId] = PendingStrokeMeta(
            scale = capturedScale,
            panX = capturedPanX,
            panY = capturedPanY,
            layerId = activeLayerId,
            brushType = brushType,
            color = activeColor,
            fillColor = fillCol,
            sizeWorld = sizePx / capturedScale,
            epsilonWorld = epsilonPx / capturedScale,
            isHighlighter = isHighlighter,
            pressureSensitivity = pressureSensitivity,
            snappedShape = null
        )

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            predictor = MotionEventPredictor.newInstance(wetInk).also { it.record(event) }
        }

        val wPt = snapPointToRulerIfClose((x - capturedPanX) / capturedScale, (y - capturedPanY) / capturedScale)
        rawWorldPoints.add(wPt)
        if (!isTape) {
            scheduleHoldCheck(x, y)
        }
    }

    private fun movePen(event: MotionEvent, index: Int) {
        readPen(event, index)
        if (erasing) {
            if (eraserLassoWorld.isNotEmpty()) {
                eraserLassoWorld.add(PointF(screenToWorldX(x), screenToWorldY(y)))
                dryInk.postInvalidateOnAnimation()
            } else {
                for (h in 0 until event.historySize) {
                    eraseAtScreen(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                }
                eraseAtScreen(x, y)
            }
            return
        }

        if (toolMode == ToolMode.LASER) {
            val trail = activeLaserTrail ?: return
            for (h in 0 until event.historySize) {
                trail.points.add(
                    PointF(
                        screenToWorldX(event.getHistoricalX(index, h)),
                        screenToWorldY(event.getHistoricalY(index, h))
                    )
                )
            }
            trail.points.add(PointF(screenToWorldX(x), screenToWorldY(y)))
            trail.lastPointMs = SystemClock.uptimeMillis()
            dryInk.postInvalidateOnAnimation()
            return
        }

        if (toolMode == ToolMode.LASSO) {
            val wx = screenToWorldX(x)
            val wy = screenToWorldY(y)
            if (isScalingSelection) {
                val curDist = hypot(wx - scaleAnchorX, wy - scaleAnchorY)
                activeScaleFactor = (curDist / scaleInitialDist).coerceIn(0.25f, 4.0f)
                dryInk.postInvalidateOnAnimation()
            } else if (isDraggingSelection) {
                val prev = selectionDragLastWorld ?: PointF(wx, wy)
                val dx = wx - prev.x
                val dy = wy - prev.y
                if (abs(dx) > 0.01f || abs(dy) > 0.01f) {
                    dragDx += dx
                    dragDy += dy
                    selectionDragLastWorld = PointF(wx, wy)
                    dryInk.postInvalidateOnAnimation()
                }
            } else {
                lassoPathWorld.add(PointF(wx, wy))
                dryInk.postInvalidateOnAnimation()
            }
            return
        }

        directShapeStartWorld?.let { startPt ->
            val curPt = PointF(screenToWorldX(x), screenToWorldY(y))
            directShapePreview = ShapeRecognizer.buildDirectShape(
                kind = directShapeKind,
                start = startPt,
                end = curPt,
                density = density / viewScale
            )
            dryInk.postInvalidateOnAnimation()
            return
        }

        val strokeId = activeInk ?: return
        val meta = waitingForInk[strokeId]
        val sScale = meta?.scale ?: viewScale
        val sPanX = meta?.panX ?: panX
        val sPanY = meta?.panY ?: panY

        for (h in 0 until event.historySize) {
            val hx = event.getHistoricalX(index, h)
            val hy = event.getHistoricalY(index, h)
            rawWorldPoints.add(snapPointToRulerIfClose((hx - sPanX) / sScale, (hy - sPanY) / sScale))
        }
        rawWorldPoints.add(snapPointToRulerIfClose((x - sPanX) / sScale, (y - sPanY) / sScale))

        if (toolMode != ToolMode.TAPE && hypot(x - holdAnchorX, y - holdAnchorY) > 6f * density) {
            if (holdSnappedShape != null) {
                holdSnappedShape = null
                dryInk.postInvalidateOnAnimation()
            }
            scheduleHoldCheck(x, y)
        }

        predictor?.record(event)
        val prediction = predictor?.predict()
        try {
            wetInk.addToStroke(event, activePointer, strokeId, prediction)
        } finally {
            prediction?.recycle()
        }
    }

    private fun finishPen(event: MotionEvent, index: Int, canceled: Boolean) {
        mainHandler.removeCallbacks(holdRunnable)
        readPen(event, index)
        if (canceled) {
            activeInk?.let { id ->
                waitingForInk.remove(id)
                wetInk.cancelStroke(id, event)
            }
            activeLaserTrail = null
            directShapeStartWorld = null
            directShapePreview = null
            eraserLassoWorld.clear()
            lassoPathWorld.clear()
            isDraggingSelection = false
            isScalingSelection = false
            selectionDragLastWorld = null
            dragDx = 0f
            dragDy = 0f
        } else if (erasing) {
            if (eraserLassoWorld.size >= 3) {
                eraseItemsInsidePolygon(eraserLassoWorld)
                eraserLassoWorld.clear()
            } else {
                eraseAtScreen(x, y)
                if (erasedIds.isNotEmpty()) {
                    rememberBeforeChange()
                    items.removeAll { it.id in erasedIds }
                    selectedIds.removeAll(erasedIds)
                    revealedTapeIds.removeAll(erasedIds)
                    emitState()
                }
            }
        } else if (toolMode == ToolMode.LASER) {
            activeLaserTrail?.lastPointMs = SystemClock.uptimeMillis()
            activeLaserTrail = null
            dryInk.postInvalidateOnAnimation()
        } else if (toolMode == ToolMode.LASSO) {
            if (isScalingSelection) {
                if (abs(activeScaleFactor - 1f) > 0.02f) {
                    rememberBeforeChange()
                    bakeSelectionScale(scaleAnchorX, scaleAnchorY, activeScaleFactor)
                }
                isScalingSelection = false
                activeScaleFactor = 1f
            } else if (isDraggingSelection) {
                if (hypot(dragDx, dragDy) > 0.01f) {
                    rememberBeforeChange()
                    bakeSelectionDrag(dragDx, dragDy)
                }
                isDraggingSelection = false
                selectionDragLastWorld = null
                dragDx = 0f
                dragDy = 0f
            } else {
                selectItemsInsideLasso()
                lassoPathWorld.clear()
            }
            dryInk.invalidate()
            emitState()
        } else if (directShapeStartWorld != null) {
            val built = directShapePreview
            directShapeStartWorld = null
            directShapePreview = null
            if (built != null && !isLayerLocked(activeLayerId)) {
                rememberBeforeChange()
                val sizeWorld = if (adaptiveStrokeWidth) {
                    (penWidthDp * density) / viewScale
                } else {
                    penWidthDp * density
                }
                val fillCol = if (shapeFillEnabled) applyAlpha(inkColor, (shapeFillOpacity * 255).toInt()) else Color.TRANSPARENT
                items.add(
                    ShapeItem(
                        id = allocateId(),
                        layerId = activeLayerId,
                        shape = built,
                        color = inkColor,
                        fillColor = fillCol,
                        strokeWidthWorld = sizeWorld,
                        worldBounds = built.computeWorldBounds(sizeWorld),
                        isHighlighter = false
                    )
                )
                lastShapeDetected = built.label
                dryInk.invalidate()
                emitState()
                emitTelemetry()
            }
        } else {
            if (toolMode == ToolMode.TAPE && rawWorldPoints.size <= 4) {
                val first = rawWorldPoints.firstOrNull()
                val last = rawWorldPoints.lastOrNull()
                if (first != null && last != null && hypot(last.x - first.x, last.y - first.y) < 6f * density / viewScale) {
                    if (toggleTapeAtScreen(x, y)) {
                        activeInk?.let { id ->
                            waitingForInk.remove(id)
                            wetInk.cancelStroke(id, event)
                        }
                        endPen()
                        return
                    }
                }
            }
            activeInk?.let { strokeId ->
                val existing = waitingForInk[strokeId]
                if (existing != null) {
                    val shouldAutoRecognize = autoShapeEnabled || toolMode == ToolMode.SHAPES
                    val finalShape = if (existing.brushType == BrushType.TAPE) {
                        null
                    } else if (rulerEnabled && rawWorldPoints.size >= 2) {
                        val s0 = snapPointToRulerIfClose(rawWorldPoints.first().x, rawWorldPoints.first().y)
                        val s1 = snapPointToRulerIfClose(rawWorldPoints.last().x, rawWorldPoints.last().y)
                        if (hypot(s0.x - rawWorldPoints.first().x, s0.y - rawWorldPoints.first().y) < 1f &&
                            hypot(s1.x - rawWorldPoints.last().x, s1.y - rawWorldPoints.last().y) < 1f &&
                            maxDistanceToLine(rawWorldPoints, s0, s1) < 28f * density / existing.scale
                        ) {
                            RecognizedShape.Line(s0, s1)
                        } else {
                            holdSnappedShape
                                ?: if (shouldAutoRecognize) ShapeRecognizer.recognize(rawWorldPoints, density / existing.scale) else null
                        }
                    } else {
                        holdSnappedShape
                            ?: if (shouldAutoRecognize) ShapeRecognizer.recognize(rawWorldPoints, density / existing.scale) else null
                    }
                    waitingForInk[strokeId] = existing.copy(snappedShape = finalShape)
                } else {
                    Log.e(TAG, "finishPen: missing PendingStrokeMeta for $strokeId")
                }
                predictor?.record(event)
                wetInk.finishStroke(event, activePointer, strokeId)
            }
        }
        endPen()
    }

    private fun maxDistanceToLine(pts: List<PointF>, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1e-3f) return 0f
        var mx = 0f
        for (p in pts) {
            val d = abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len
            if (d > mx) mx = d
        }
        return mx
    }

    private fun endPen() {
        mainHandler.removeCallbacks(holdRunnable)
        predictor = null
        activeInk = null
        activePointer = -1
        erasing = false
        partialEraseRecordedUndo = false
        lastEraseWorld = null
        holdSnappedShape = null
        rawWorldPoints.clear()
        if (erasedIds.isNotEmpty()) {
            erasedIds.clear()
            dryInk.invalidate()
        }
    }

    private fun toggleTapeAtScreen(sx: Float, sy: Float): Boolean {
        val wx = screenToWorldX(sx)
        val wy = screenToWorldY(sy)
        val hitRadius = (10f * density) / viewScale
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (!item.isTape || item !is InkItem || !isLayerVisible(item.layerId)) continue
            val b = item.worldBounds
            if (wx < b.left - hitRadius || wx > b.right + hitRadius ||
                wy < b.top - hitRadius || wy > b.bottom + hitRadius
            ) {
                continue
            }
            val area = ImmutableBox.fromCenterAndDimensions(
                ImmutableVec(wx, wy), 2f * hitRadius, 2f * hitRadius
            )
            if (area.intersects(item.stroke.shape, AffineTransform.IDENTITY)) {
                if (!revealedTapeIds.add(item.id)) {
                    revealedTapeIds.remove(item.id)
                }
                dryInk.invalidate()
                return true
            }
        }
        return false
    }

    private fun scheduleHoldCheck(sx: Float, sy: Float) {
        holdAnchorX = sx
        holdAnchorY = sy
        mainHandler.removeCallbacks(holdRunnable)
        mainHandler.postDelayed(holdRunnable, 420L)
    }

    private fun trySnapHoldShape() {
        val strokeId = activeInk ?: return
        if (erasing || toolMode == ToolMode.LASSO || toolMode == ToolMode.TAPE || toolMode == ToolMode.LASER) return
        val scale = waitingForInk[strokeId]?.scale ?: viewScale
        val recognized = ShapeRecognizer.recognize(rawWorldPoints, density / scale)
        if (recognized != null) {
            holdSnappedShape = recognized
            dryInk.postInvalidateOnAnimation()
        }
    }

    private fun canEraseItem(item: CanvasItem): Boolean {
        if (!isLayerVisible(item.layerId) || isLayerLocked(item.layerId)) return false
        if (eraseOnlyHighlighter && !item.isHighlighter) return false
        if (eraseOnlyTape && !item.isTape) return false
        return true
    }

    private fun eraseItemsInsidePolygon(poly: List<PointF>) {
        val toRemove = mutableSetOf<Long>()
        for (item in items) {
            if (!canEraseItem(item)) continue
            val b = item.worldBounds
            if (isPointInPolygon(b.centerX(), b.centerY(), poly) ||
                isPointInPolygon(b.left, b.top, poly) ||
                isPointInPolygon(b.right, b.bottom, poly)
            ) {
                toRemove.add(item.id)
            }
        }
        if (toRemove.isNotEmpty()) {
            rememberBeforeChange()
            items.removeAll { it.id in toRemove }
            selectedIds.removeAll(toRemove)
            revealedTapeIds.removeAll(toRemove)
            dryInk.invalidate()
            emitState()
        }
    }

    private fun eraseAtScreen(sx: Float, sy: Float) {
        val wx = screenToWorldX(sx)
        val wy = screenToWorldY(sy)
        val worldRadius = (eraserRadiusDp * density) / viewScale
        val start = lastEraseWorld ?: PointF(wx, wy)
        val steps = ceil(hypot(wx - start.x, wy - start.y) / worldRadius).toInt().coerceAtLeast(1)

        if (eraserMode == EraserMode.PIXEL) {
            partialPixelEraseSegment(start, PointF(wx, wy), steps, worldRadius)
            lastEraseWorld = PointF(wx, wy)
            return
        }

        val before = erasedIds.size
        for (step in 1..steps) {
            val progress = step.toFloat() / steps
            val px = start.x + (wx - start.x) * progress
            val py = start.y + (wy - start.y) * progress

            for (item in items) {
                if (item.id in erasedIds || !canEraseItem(item)) continue
                val b = item.worldBounds
                if (px < b.left - worldRadius || px > b.right + worldRadius ||
                    py < b.top - worldRadius || py > b.bottom + worldRadius
                ) {
                    continue
                }
                val hit = when (item) {
                    is InkItem -> {
                        val area = ImmutableBox.fromCenterAndDimensions(
                            ImmutableVec(px, py), 2f * worldRadius, 2f * worldRadius
                        )
                        area.intersects(item.stroke.shape, AffineTransform.IDENTITY)
                    }
                    is ShapeItem -> {
                        val pts = item.shape.samplePoints(8f)
                        val threshold = worldRadius + item.strokeWidthWorld * 0.5f
                        pts.any { pt ->
                            hypot(pt.x - px, pt.y - py) <= threshold
                        }
                    }
                    is TextBoxItem -> b.contains(px, py)
                }
                if (hit) erasedIds.add(item.id)
            }
        }
        lastEraseWorld = PointF(wx, wy)
        if (before != erasedIds.size) dryInk.postInvalidateOnAnimation()
    }

    /** Pixel/Partial eraser (Section 09 · 像素擦除): splits InkItem strokes at points covered by the eraser. */
    private var partialEraseRecordedUndo = false
    private fun partialPixelEraseSegment(start: PointF, end: PointF, steps: Int, worldRadius: Float) {
        var modified = false
        val nextItems = ArrayList<CanvasItem>(items.size + 4)

        for (item in items) {
            if (!canEraseItem(item) || item !is InkItem) {
                nextItems.add(item)
                continue
            }
            val b = item.worldBounds
            val segMinX = min(start.x, end.x) - worldRadius
            val segMaxX = max(start.x, end.x) + worldRadius
            val segMinY = min(start.y, end.y) - worldRadius
            val segMaxY = max(start.y, end.y) + worldRadius
            if (b.right < segMinX || b.left > segMaxX || b.bottom < segMinY || b.top > segMaxY) {
                nextItems.add(item)
                continue
            }

            val hitRadius = worldRadius + item.data.sizeWorld * 0.45f
            val pts = item.data.points
            var anyHit = false
            val keepFlags = BooleanArray(pts.size) { idx ->
                val pt = pts[idx]
                var hit = false
                for (s in 1..steps) {
                    val t = s.toFloat() / steps
                    val px = start.x + (end.x - start.x) * t
                    val py = start.y + (end.y - start.y) * t
                    if (hypot(pt.x - px, pt.y - py) <= hitRadius) {
                        hit = true
                        break
                    }
                }
                if (hit) anyHit = true
                !hit
            }

            if (!anyHit) {
                nextItems.add(item)
                continue
            }

            if (!partialEraseRecordedUndo) {
                rememberBeforeChange()
                partialEraseRecordedUndo = true
            }
            modified = true

            // Split contiguous kept points into new InkItems
            var runStart = -1
            for (i in pts.indices) {
                if (keepFlags[i]) {
                    if (runStart == -1) runStart = i
                } else if (runStart != -1) {
                    val subPts = pts.subList(runStart, i)
                    if (subPts.size >= 2) {
                        val subData = item.data.copy(points = subPts.toList())
                        val subStroke = buildStrokeFromData(subData)
                        nextItems.add(
                            InkItem(
                                id = allocateId(),
                                layerId = item.layerId,
                                data = subData,
                                stroke = subStroke,
                                worldBounds = computeStrokeWorldBounds(subStroke, subData),
                                isHighlighter = item.isHighlighter
                            )
                        )
                    }
                    runStart = -1
                }
            }
            if (runStart != -1) {
                val subPts = pts.subList(runStart, pts.size)
                if (subPts.size >= 2) {
                    val subData = item.data.copy(points = subPts.toList())
                    val subStroke = buildStrokeFromData(subData)
                    nextItems.add(
                        InkItem(
                            id = allocateId(),
                            layerId = item.layerId,
                            data = subData,
                            stroke = subStroke,
                            worldBounds = computeStrokeWorldBounds(subStroke, subData),
                            isHighlighter = item.isHighlighter
                        )
                    )
                }
            }
        }

        if (modified) {
            items.clear()
            items.addAll(nextItems)
            dryInk.postInvalidateOnAnimation()
            emitState()
        }
    }

    private fun selectItemsInsideLasso() {
        selectedIds.clear()
        if (lassoPathWorld.size < 2) return
        val rectFilter = if (lassoMode == LassoMode.RECTANGLE) {
            val f = lassoPathWorld.first()
            val l = lassoPathWorld.last()
            RectF(min(f.x, l.x), min(f.y, l.y), max(f.x, l.x), max(f.y, l.y))
        } else {
            null
        }
        if (rectFilter == null && lassoPathWorld.size < 3) return

        for (item in items) {
            if (!isLayerVisible(item.layerId) || isLayerLocked(item.layerId)) continue
            val allowedByType = when (item) {
                is InkItem -> lassoSelectInk
                is ShapeItem -> lassoSelectShapes
                is TextBoxItem -> lassoSelectText
            }
            if (!allowedByType) continue

            val b = item.worldBounds
            if (b.isEmpty) continue
            val matched = if (rectFilter != null) {
                RectF.intersects(rectFilter, b)
            } else {
                isPointInPolygon(b.centerX(), b.centerY(), lassoPathWorld) ||
                    isPointInPolygon(b.left, b.top, lassoPathWorld) ||
                    isPointInPolygon(b.right, b.bottom, lassoPathWorld)
            }
            if (matched) {
                selectedIds.add(item.id)
            }
        }
    }

    private fun isPointInPolygon(px: Float, py: Float, poly: List<PointF>): Boolean {
        var inside = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val xi = poly[i].x
            val yi = poly[i].y
            val xj = poly[j].x
            val yj = poly[j].y
            val intersect = ((yi > py) != (yj > py)) &&
                (px < (xj - xi) * (py - yi) / ((yj - yi).takeIf { it != 0f } ?: 1e-6f) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    private fun computeSelectionBounds(): RectF {
        val out = RectF()
        for (item in items) {
            if (item.id in selectedIds) {
                val b = item.worldBounds
                if (!b.isEmpty) {
                    if (out.isEmpty) out.set(b) else out.union(b)
                }
            }
        }
        if (!out.isEmpty) {
            if (isDraggingSelection) {
                out.offset(dragDx, dragDy)
            } else if (isScalingSelection) {
                out.right = scaleAnchorX + (out.right - scaleAnchorX) * activeScaleFactor
                out.bottom = scaleAnchorY + (out.bottom - scaleAnchorY) * activeScaleFactor
            }
        }
        return out
    }

    private fun bakeSelectionDrag(dx: Float, dy: Float) {
        for (i in items.indices) {
            val item = items[i]
            if (item.id in selectedIds) {
                items[i] = item.translated(dx, dy, newId = item.id)
            }
        }
    }

    private fun bakeSelectionScale(anchorX: Float, anchorY: Float, factor: Float) {
        for (i in items.indices) {
            val item = items[i]
            if (item.id in selectedIds) {
                items[i] = item.scaled(anchorX, anchorY, factor, newId = item.id)
            }
        }
    }

    /** Adds or updates a rich TextBoxItem in world coordinates (Section 13 · 文本框). */
    fun addOrUpdateTextBox(
        existingId: Long?,
        worldX: Float,
        worldY: Float,
        text: String,
        color: Int,
        bgColor: Int,
        fontSizeDp: Float,
        isBold: Boolean,
        isItalic: Boolean,
        isUnderline: Boolean
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            if (existingId != null) {
                rememberBeforeChange()
                items.removeAll { it.id == existingId }
                dryInk.invalidate()
                emitState()
            }
            return
        }
        rememberBeforeChange()
        val fontSizeWorld = (fontSizeDp * density) / viewScale
        textPaint.textSize = fontSizeWorld
        val style = when {
            isBold && isItalic -> Typeface.BOLD_ITALIC
            isBold -> Typeface.BOLD
            isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, style)
        val lines = trimmed.split('\n')
        val maxW = lines.maxOfOrNull { textPaint.measureText(it) } ?: (40f * density)
        val totalH = lines.size * (fontSizeWorld * 1.3f) + 8f
        val bounds = RectF(worldX, worldY, worldX + maxW + 14f, worldY + totalH)

        val newItem = TextBoxItem(
            id = existingId ?: allocateId(),
            layerId = activeLayerId,
            x = worldX,
            y = worldY,
            text = trimmed,
            color = color,
            bgColor = bgColor,
            fontSizeWorld = fontSizeWorld,
            isBold = isBold,
            isItalic = isItalic,
            isUnderline = isUnderline,
            worldBounds = bounds
        )
        val idx = if (existingId != null) items.indexOfFirst { it.id == existingId } else -1
        if (idx >= 0) {
            items[idx] = newItem
        } else {
            items.add(newItem)
        }
        dryInk.invalidate()
        emitState()
    }

    // Layer management operations (Section 19 · 图层)
    fun addLayer(name: String): NoteLayer {
        val newId = (layers.maxOfOrNull { it.id } ?: 0) + 1
        val layer = NoteLayer(id = newId, name = name)
        layers.add(layer)
        activeLayerId = newId
        return layer
    }

    fun toggleLayerVisibility(layerId: Int) {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx >= 0) {
            layers[idx] = layers[idx].copy(visible = !layers[idx].visible)
            dryInk.invalidate()
        }
    }

    fun toggleLayerLock(layerId: Int) {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx >= 0) {
            layers[idx] = layers[idx].copy(locked = !layers[idx].locked)
        }
    }

    fun mergeLayerDown(layerId: Int): Boolean {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx <= 0) return false
        val targetId = layers[idx - 1].id
        rememberBeforeChange()
        for (i in items.indices) {
            if (items[i].layerId == layerId) {
                items[i] = items[i].withLayer(targetId)
            }
        }
        layers.removeAt(idx)
        if (activeLayerId == layerId) activeLayerId = targetId
        dryInk.invalidate()
        return true
    }

    /** Minimap data & navigation jump for Infinite Canvas (Section 03 · 无限画布). */
    fun computeMinimapState(): Pair<RectF, RectF> {
        val viewport = RectF(
            -panX / viewScale,
            -panY / viewScale,
            (width.coerceAtLeast(1) - panX) / viewScale,
            (height.coerceAtLeast(1) - panY) / viewScale
        )
        val content = RectF(viewport)
        for (item in items) {
            if (!item.worldBounds.isEmpty) {
                content.union(item.worldBounds)
            }
        }
        content.inset(-120f * density, -120f * density)
        return content to viewport
    }

    fun jumpCameraToWorldCenter(worldCx: Float, worldCy: Float) {
        panX = width * 0.5f - worldCx * viewScale
        panY = height * 0.5f - worldCy * viewScale
        dryInk.invalidate()
        emitTelemetry()
    }

    fun snapshotItemBoundsForMinimap(): List<Pair<RectF, Int>> =
        items.filter { isLayerVisible(it.layerId) }.map { item ->
            val col = when (item) {
                is InkItem -> item.data.color
                is ShapeItem -> item.color
                is TextBoxItem -> item.color
            }
            item.worldBounds to col
        }

    fun recolorSelection(newColor: Int) {
        if (isWriting || selectedIds.isEmpty()) return
        rememberBeforeChange()
        for (i in items.indices) {
            val item = items[i]
            if (item.id in selectedIds) {
                items[i] = item.recolored(newColor)
            }
        }
        dryInk.invalidate()
        emitState()
    }

    fun deleteSelection() {
        if (isWriting || selectedIds.isEmpty()) return
        rememberBeforeChange()
        items.removeAll { it.id in selectedIds }
        revealedTapeIds.removeAll(selectedIds)
        selectedIds.clear()
        dryInk.invalidate()
        emitState()
    }

    fun duplicateSelection() {
        if (isWriting || selectedIds.isEmpty()) return
        rememberBeforeChange()
        val shift = 24f * density / viewScale
        val newSelected = mutableSetOf<Long>()
        val clones = items.filter { it.id in selectedIds }.map { item ->
            val freshId = allocateId()
            newSelected.add(freshId)
            item.translated(shift, shift, newId = freshId)
        }
        items.addAll(clones)
        selectedIds.clear()
        selectedIds.addAll(newSelected)
        dryInk.invalidate()
        emitState()
    }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        isDraggingSelection = false
        isScalingSelection = false
        dragDx = 0f
        dragDy = 0f
        dryInk.invalidate()
        emitState()
    }

    fun resetZoom() {
        viewScale = 1f
        panX = 0f
        panY = 0f
        dryInk.invalidate()
        emitTelemetry()
    }

    fun fitToContent() {
        if (items.isEmpty() || width <= 0 || height <= 0) {
            resetZoom()
            return
        }
        val contentBounds = RectF()
        for (item in items) {
            if (!isLayerVisible(item.layerId)) continue
            val b = item.worldBounds
            if (!b.isEmpty) {
                if (contentBounds.isEmpty) contentBounds.set(b) else contentBounds.union(b)
            }
        }
        if (contentBounds.isEmpty) {
            resetZoom()
            return
        }

        val marginPx = 32f * density
        val availW = (width - 2f * marginPx).coerceAtLeast(1f)
        val availH = (height - 2f * marginPx).coerceAtLeast(1f)
        val cw = contentBounds.width().coerceAtLeast(1f)
        val ch = contentBounds.height().coerceAtLeast(1f)

        val idealScale = min(availW / cw, availH / ch)
        val targetScale = idealScale.coerceIn(0.45f, 4.0f)

        viewScale = targetScale
        panX = if (cw * targetScale > availW) {
            marginPx - contentBounds.left * targetScale
        } else {
            width * 0.5f - contentBounds.centerX() * targetScale
        }
        panY = if (ch * targetScale > availH) {
            marginPx - contentBounds.top * targetScale
        } else {
            height * 0.5f - contentBounds.centerY() * targetScale
        }
        dryInk.invalidate()
        emitTelemetry()
    }

    fun restoreOriginalStroke() {
        if (isWriting) return
        val correction = shapeCorrection ?: return
        val index = items.indexOfLast { it is ShapeItem && it.id == correction.id }
        if (index < 0) return
        items[index] = createInkItem(correction.id, correction.rawStroke, correction.meta)
        redoHistory.clear()
        clearShapeCorrection()
        dryInk.invalidate()
        emitState()
    }

    private fun clearShapeCorrection() {
        if (shapeCorrection == null) return
        shapeCorrection = null
        lastShapeDetected = null
        emitTelemetry()
    }

    private fun rememberBeforeChange() {
        clearShapeCorrection()
        undoHistory.addLast(items.toList())
        if (undoHistory.size > 50) undoHistory.removeFirst()
        redoHistory.clear()
        emitState()
    }

    fun undo(): Boolean {
        if (isWriting || undoHistory.isEmpty()) return false
        clearShapeCorrection()
        redoHistory.addLast(items.toList())
        items.clear()
        items.addAll(undoHistory.removeLast())
        selectedIds.clear()
        dryInk.invalidate()
        emitState()
        return true
    }

    fun redo(): Boolean {
        if (isWriting || redoHistory.isEmpty()) return false
        clearShapeCorrection()
        undoHistory.addLast(items.toList())
        items.clear()
        items.addAll(redoHistory.removeLast())
        selectedIds.clear()
        dryInk.invalidate()
        emitState()
    }

    fun clear() {
        if (isWriting || items.isEmpty()) return
        rememberBeforeChange()
        items.clear()
        selectedIds.clear()
        revealedTapeIds.clear()
        dryInk.invalidate()
        emitState()
    }

    fun exportBitmap(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val prevSelected = selectedIds.toSet()
        val prevRuler = rulerEnabled
        selectedIds.clear()
        rulerEnabled = false
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        dryInk.draw(canvas)
        selectedIds.addAll(prevSelected)
        rulerEnabled = prevRuler
        return bmp
    }

    private fun emitState() {
        onStateChanged(undoHistory.isNotEmpty(), redoHistory.isNotEmpty(), selectedIds.isNotEmpty())
    }

    private fun emitTelemetry() {
        onTelemetryChanged(
            PenTelemetry(
                pressure = pressure,
                tiltDegrees = tiltDegrees,
                sideButtonPressed = sideButtonPressed,
                fingerContacts = fingerContacts,
                cancellations = cancellations,
                zoomPercent = (viewScale * 100).toInt(),
                lastShapeDetected = lastShapeDetected
            )
        )
    }

    private fun createInkItem(id: Long, rawStroke: Stroke, meta: PendingStrokeMeta): InkItem {
        val (strokeData, worldStroke) = normalizeAndRebuildStroke(rawStroke, meta)
        val debugRaw = if (isDebugBuild && debugCompareNormalized) {
            DebugRawStroke(
                redStroke = rawStroke.copy(rawStroke.brush.copyWithColorIntArgb(0xFFFF1744.toInt())),
                creationScale = meta.scale,
                creationPanX = meta.panX,
                creationPanY = meta.panY
            )
        } else {
            null
        }
        return InkItem(
            id = id,
            layerId = meta.layerId,
            data = strokeData,
            stroke = worldStroke,
            worldBounds = computeStrokeWorldBounds(worldStroke, strokeData),
            isHighlighter = meta.isHighlighter,
            debugRaw = debugRaw
        )
    }

    private fun normalizeAndRebuildStroke(
        rawStroke: Stroke,
        meta: PendingStrokeMeta
    ): Pair<StrokeData, Stroke> {
        val inputs = rawStroke.inputs
        val count = inputs.size
        val pts = ArrayList<StrokePoint>(count)
        val scratch = StrokeInput()
        val pSens = meta.pressureSensitivity.coerceIn(0f, 1.5f)
        for (i in 0 until count) {
            inputs.populate(i, scratch)
            val rawWx = (scratch.x - meta.panX) / meta.scale
            val rawWy = (scratch.y - meta.panY) / meta.scale
            val snapped = snapPointToRulerIfClose(rawWx, rawWy)
            val unitCm = if (scratch.strokeUnitLengthCm != StrokeInput.NO_STROKE_UNIT_LENGTH) {
                scratch.strokeUnitLengthCm * meta.scale
            } else {
                StrokeInput.NO_STROKE_UNIT_LENGTH
            }
            val adjPressure = if (scratch.pressure != StrokeInput.NO_PRESSURE && pSens != 1f) {
                (0.5f + (scratch.pressure - 0.5f) * pSens).coerceIn(0.05f, 1f)
            } else {
                scratch.pressure
            }
            pts.add(
                StrokePoint(
                    x = snapped.x,
                    y = snapped.y,
                    elapsedTimeMillis = scratch.elapsedTimeMillis,
                    pressure = adjPressure,
                    tiltRadians = scratch.tiltRadians,
                    orientationRadians = scratch.orientationRadians,
                    strokeUnitLengthCm = unitCm
                )
            )
        }
        val sizeWorld = rawStroke.brush.size / meta.scale
        val epsilonWorld = rawStroke.brush.epsilon / meta.scale
        val data = StrokeData(
            brushType = meta.brushType,
            color = rawStroke.brush.colorIntArgb,
            sizeWorld = sizeWorld,
            epsilonWorld = epsilonWorld,
            isHighlighter = meta.isHighlighter,
            toolTypeInt = inputs.getToolType().toCode(),
            noiseSeed = inputs.getNoiseSeed(),
            points = pts
        )
        val worldStroke = buildStrokeFromData(data)
        return data to worldStroke
    }

    private fun applyAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}

private fun InputToolType.toCode(): Int = when (this) {
    InputToolType.STYLUS -> 1
    InputToolType.TOUCH -> 2
    InputToolType.MOUSE -> 3
    else -> 0
}

private fun toolTypeFromCode(code: Int): InputToolType = when (code) {
    1 -> InputToolType.STYLUS
    2 -> InputToolType.TOUCH
    3 -> InputToolType.MOUSE
    else -> InputToolType.UNKNOWN
}

private fun familyFor(type: BrushType): BrushFamily = when (type) {
    BrushType.PEN -> StockBrushes.pressurePen()
    BrushType.PENCIL -> StockBrushes.marker()
    BrushType.HIGHLIGHTER -> StockBrushes.pressurePen()
    BrushType.BALLPOINT -> StockBrushes.marker()
    BrushType.DASHED -> StockBrushes.dashedLine()
    BrushType.TAPE -> StockBrushes.marker()
}

internal fun buildStrokeFromData(data: StrokeData): Stroke {
    val brush = Brush.createWithColorIntArgb(
        familyFor(data.brushType),
        data.color,
        data.sizeWorld,
        data.epsilonWorld
    )
    val batch = MutableStrokeInputBatch()
    batch.setNoiseSeed(data.noiseSeed)
    val toolType = toolTypeFromCode(data.toolTypeInt)
    var rejectedPoints = 0
    for (i in data.points.indices) {
        val pt = data.points[i]
        runCatching {
            batch.add(
                toolType,
                pt.x,
                pt.y,
                pt.elapsedTimeMillis,
                pt.strokeUnitLengthCm,
                pt.pressure,
                pt.tiltRadians,
                pt.orientationRadians
            )
        }.onFailure { err ->
            rejectedPoints++
            Log.w("PenSheet", "buildStrokeFromData: rejected point[$i] (t=${pt.elapsedTimeMillis}ms): ${err.message}")
        }
    }
    if (rejectedPoints > 0 || batch.size != data.points.size) {
        Log.w(
            "PenSheet",
            "buildStrokeFromData: batch.size=${batch.size} vs expected=${data.points.size} (rejected=$rejectedPoints)"
        )
    }
    return Stroke(brush, batch)
}

internal fun computeStrokeWorldBounds(stroke: Stroke, data: StrokeData): RectF {
    val pad = data.sizeWorld * 0.5f + data.epsilonWorld + 2f
    val box = stroke.shape.computeBoundingBox()
    if (box != null) {
        return RectF(
            box.xMin - pad,
            box.yMin - pad,
            box.xMax + pad,
            box.yMax + pad
        )
    }
    if (data.points.isEmpty()) return RectF()
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (p in data.points) {
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }
    return RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
}
