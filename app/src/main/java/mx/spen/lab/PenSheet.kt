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
import android.os.Handler
import android.os.Looper
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
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Keeps high-frequency stylus input, world-normalized strokes, zoom/pan, shapes, and lasso outside Compose state. */
class PenSheet(
    context: Context,
    private val onStateChanged: (canUndo: Boolean, canRedo: Boolean, hasSelection: Boolean) -> Unit,
    private val onTelemetryChanged: (PenTelemetry) -> Unit = {}
) : FrameLayout(context) {

    companion object {
        private const val TAG = "PenSheet"
    }

    sealed interface CanvasItem {
        val id: Long
        val worldBounds: RectF
        val isHighlighter: Boolean
        fun translated(dx: Float, dy: Float, newId: Long = id): CanvasItem
    }

    data class DebugRawStroke(
        val redStroke: Stroke,
        val creationScale: Float,
        val creationPanX: Float,
        val creationPanY: Float
    )

    data class InkItem(
        override val id: Long,
        val data: StrokeData,
        val stroke: Stroke,
        override val worldBounds: RectF,
        override val isHighlighter: Boolean,
        val debugRaw: DebugRawStroke? = null
    ) : CanvasItem {
        override fun translated(dx: Float, dy: Float, newId: Long): InkItem {
            val movedData = data.translated(dx, dy)
            val movedStroke = buildStrokeFromData(movedData)
            val movedBounds = computeStrokeWorldBounds(movedStroke, movedData)
            return InkItem(
                id = newId,
                data = movedData,
                stroke = movedStroke,
                worldBounds = movedBounds,
                isHighlighter = isHighlighter,
                debugRaw = null
            )
        }

        override fun equals(other: Any?): Boolean = (other is InkItem) && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    data class ShapeItem(
        override val id: Long,
        val shape: RecognizedShape,
        val color: Int,
        val strokeWidthWorld: Float,
        override val worldBounds: RectF,
        override val isHighlighter: Boolean
    ) : CanvasItem {
        override fun translated(dx: Float, dy: Float, newId: Long): ShapeItem {
            val movedShape = shape.translated(dx, dy)
            return ShapeItem(
                id = newId,
                shape = movedShape,
                color = color,
                strokeWidthWorld = strokeWidthWorld,
                worldBounds = movedShape.computeWorldBounds(strokeWidthWorld),
                isHighlighter = isHighlighter
            )
        }

        override fun equals(other: Any?): Boolean = (other is ShapeItem) && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

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
    private val waitingForInk = mutableMapOf<InProgressStrokeId, PendingStrokeMeta>()

    private data class PendingStrokeMeta(
        val scale: Float,
        val panX: Float,
        val panY: Float,
        val brushType: BrushType,
        val color: Int,
        val sizeWorld: Float,
        val epsilonWorld: Float,
        val isHighlighter: Boolean,
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

    // Diagnostics counters
    private var fingerContacts = 0
    private var cancellations = 0
    private var lastShapeDetected: String? = null

    // Hold-to-Shape tracking
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rawWorldPoints = mutableListOf<PointF>()
    private var holdAnchorX = 0f
    private var holdAnchorY = 0f
    private var holdSnappedShape: RecognizedShape? = null
    private val holdRunnable = Runnable { trySnapHoldShape() }

    // Zoom & Pan (2-finger)
    private var viewScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var isTwoFingerGesturing = false
    private var lastMidX = 0f
    private var lastMidY = 0f

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

    // Lasso selection & temporary drag state (baked on finishPen)
    private val lassoPathWorld = mutableListOf<PointF>()
    private var selectionDragLastWorld: PointF? = null
    private var isDraggingSelection = false
    private var dragDx = 0f
    private var dragDy = 0f

    // Configurable editor properties
    var toolMode: ToolMode = ToolMode.PEN
        set(value) {
            field = value
            if (value != ToolMode.LASSO && selectedIds.isNotEmpty()) {
                clearSelection()
            }
            diagnostics.invalidate()
        }

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

    var penWidthDp: Float = 4.2f
    var highlighterWidthDp: Float = 18f
    var eraserRadiusDp: Float = 12f
    var autoShapeEnabled: Boolean = false

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

    var showDiagnostics: Boolean = false
        set(value) {
            field = value
            diagnostics.invalidate()
        }

    /** Debug mode for Step A exit test: draws raw screen-space stroke in red and normalized world stroke in semi-transparent blue. */
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
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(42, 161, 183)
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    val isWriting: Boolean get() = activePointer != -1 || waitingForInk.isNotEmpty()

    private fun screenToWorldX(sx: Float): Float = (sx - panX) / viewScale
    private fun screenToWorldY(sy: Float): Float = (sy - panY) / viewScale

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
                if (item.id !in erasedIds && item.isHighlighter && isItemVisible(item, visibleWorldRect, itemCheckRect)) {
                    drawCanvasItem(canvas, item)
                }
            }
            // Pass 2: Opaque pen strokes & geometric shapes
            for (item in items) {
                if (item.id !in erasedIds && !item.isHighlighter && isItemVisible(item, visibleWorldRect, itemCheckRect)) {
                    drawCanvasItem(canvas, item)
                }
            }

            // Active lasso loop
            if (toolMode == ToolMode.LASSO && lassoPathWorld.size > 1) {
                lassoPaint.strokeWidth = 2f * density / viewScale
                lassoPaint.color = Color.rgb(42, 161, 183)
                val path = Path().apply {
                    moveTo(lassoPathWorld[0].x, lassoPathWorld[0].y)
                    for (i in 1 until lassoPathWorld.size) {
                        lineTo(lassoPathWorld[i].x, lassoPathWorld[i].y)
                    }
                }
                canvas.drawPath(path, lassoPaint)
            }

            // Selection bounding box (includes temporary dragDx/dragDy while dragging)
            if (selectedIds.isNotEmpty()) {
                val bounds = computeSelectionBounds()
                if (!bounds.isEmpty) {
                    val pad = 8f * density / viewScale
                    bounds.inset(-pad, -pad)
                    lassoPaint.strokeWidth = 2f * density / viewScale
                    lassoPaint.color = Color.rgb(42, 161, 183)
                    canvas.drawRoundRect(bounds, 8f / viewScale, 8f / viewScale, lassoPaint)
                }
            }

            // Live Hold-to-Shape preview while stylus is still down
            holdSnappedShape?.let { preview ->
                shapePaint.color = Color.argb(180, 42, 161, 183)
                shapePaint.strokeWidth = (penWidthDp * density) / viewScale
                drawShape(canvas, preview)
            }

            canvas.restore()
        }
    }

    private fun isItemVisible(item: CanvasItem, visibleWorld: RectF, scratch: RectF): Boolean {
        scratch.set(item.worldBounds)
        if (isDraggingSelection && item.id in selectedIds) {
            scratch.offset(dragDx, dragDy)
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
        paint.color = paperStyle.lineColor
        paint.strokeWidth = (density * 0.7f) / viewScale

        val spacing = 22f * density
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
                val lineSpacing = 28f * density
                val rStartY = (kotlin.math.floor((worldTop / lineSpacing).toDouble()) * lineSpacing).toFloat()
                var gy = rStartY
                while (gy <= worldBottom) {
                    canvas.drawLine(worldLeft, gy, worldRight, gy, paint)
                    gy += lineSpacing
                }
            }
            PaperTemplate.DOTTED -> {
                paint.style = Paint.Style.FILL
                val dotR = (1.3f * density) / viewScale
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
                val step = 28f * density
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
        }
    }

    private fun drawCanvasItem(canvas: Canvas, item: CanvasItem) {
        val applyTempDrag = isDraggingSelection && item.id in selectedIds
        if (applyTempDrag) {
            canvas.save()
            canvas.translate(dragDx, dragDy)
        }

        when (item) {
            is InkItem -> {
                if (debugCompareNormalized && item.debugRaw != null) {
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
                drawShape(canvas, item.shape)
            }
        }

        if (applyTempDrag) {
            canvas.restore()
        }
    }

    private fun drawShape(canvas: Canvas, shape: RecognizedShape) {
        when (shape) {
            is RecognizedShape.Line -> {
                canvas.drawLine(
                    shape.start.x, shape.start.y,
                    shape.end.x, shape.end.y,
                    shapePaint
                )
            }
            is RecognizedShape.Arrow -> {
                canvas.drawLine(
                    shape.start.x, shape.start.y,
                    shape.end.x, shape.end.y,
                    shapePaint
                )
                canvas.drawLine(
                    shape.end.x, shape.end.y,
                    shape.wingLeft.x, shape.wingLeft.y,
                    shapePaint
                )
                canvas.drawLine(
                    shape.end.x, shape.end.y,
                    shape.wingRight.x, shape.wingRight.y,
                    shapePaint
                )
            }
            is RecognizedShape.Rectangle -> {
                canvas.drawRect(
                    shape.left, shape.top,
                    shape.right, shape.bottom,
                    shapePaint
                )
            }
            is RecognizedShape.Triangle -> {
                val path = Path().apply {
                    moveTo(shape.a.x, shape.a.y)
                    lineTo(shape.b.x, shape.b.y)
                    lineTo(shape.c.x, shape.c.y)
                    close()
                }
                canvas.drawPath(path, shapePaint)
            }
            is RecognizedShape.Ellipse -> {
                canvas.drawOval(
                    shape.cx - shape.rx,
                    shape.cy - shape.ry,
                    shape.cx + shape.rx,
                    shape.cy + shape.ry,
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

            if (hovering || erasing) {
                val isErasingCursor = sideButtonPressed || erasing || toolMode == ToolMode.ERASER
                paint.color = when {
                    isErasingCursor -> Color.rgb(200, 72, 84)
                    toolMode == ToolMode.HIGHLIGHTER -> highlighterColor
                    toolMode == ToolMode.LASSO -> Color.rgb(42, 161, 183)
                    else -> inkColor
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density * 1.3f
                val cursorRadius = when {
                    isErasingCursor -> eraserRadiusDp * density
                    toolMode == ToolMode.HIGHLIGHTER -> (highlighterWidthDp * density) / 2f
                    toolMode == ToolMode.LASSO -> 6f * density
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
                    if (meta.snappedShape != null) {
                        val shapeBounds = meta.snappedShape.computeWorldBounds(meta.sizeWorld)
                        items.add(
                            ShapeItem(
                                id = allocateId(),
                                shape = meta.snappedShape,
                                color = meta.color,
                                strokeWidthWorld = meta.sizeWorld,
                                worldBounds = shapeBounds,
                                isHighlighter = meta.isHighlighter
                            )
                        )
                        lastShapeDetected = meta.snappedShape.label
                    } else {
                        val (strokeData, worldStroke) = normalizeAndRebuildStroke(rawStroke, meta)
                        val bounds = computeStrokeWorldBounds(worldStroke, strokeData)
                        val debugRaw = if (isDebugBuild && debugCompareNormalized) {
                            val redBrush = rawStroke.brush.copyWithColorIntArgb(0xFFFF1744.toInt())
                            DebugRawStroke(
                                redStroke = rawStroke.copy(redBrush),
                                creationScale = meta.scale,
                                creationPanX = meta.panX,
                                creationPanY = meta.panY
                            )
                        } else {
                            null
                        }
                        items.add(
                            InkItem(
                                id = allocateId(),
                                data = strokeData,
                                stroke = worldStroke,
                                worldBounds = bounds,
                                isHighlighter = meta.isHighlighter,
                                debugRaw = debugRaw
                            )
                        )
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

        // Two-finger zoom & pan when stylus is not actively drawing
        if (activePointer == -1 && event.pointerCount == 2 && !isPen(event, 0) && !isPen(event, 1)) {
            scaleDetector.onTouchEvent(event)
            val midX = (event.getX(0) + event.getX(1)) * 0.5f
            val midY = (event.getY(0) + event.getY(1)) * 0.5f
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    isTwoFingerGesturing = true
                    lastMidX = midX
                    lastMidY = midY
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTwoFingerGesturing) {
                        panX += midX - lastMidX
                        panY += midY - lastMidY
                        lastMidX = midX
                        lastMidY = midY
                        dryInk.invalidate()
                        emitTelemetry()
                    }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isTwoFingerGesturing = false
                }
            }
            return true
        }

        if (isTwoFingerGesturing) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                isTwoFingerGesturing = false
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
                if (activePointer == -1 && isPen(event, index)) beginPen(event, index)
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
                isDraggingSelection = false
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

        val hardwareEraser = sideButtonPressed || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        erasing = hardwareEraser || toolMode == ToolMode.ERASER

        if (erasing) {
            eraseAtScreen(x, y)
            return
        }

        if (toolMode == ToolMode.LASSO) {
            val wx = screenToWorldX(x)
            val wy = screenToWorldY(y)
            if (selectedIds.isNotEmpty() && computeSelectionBounds().contains(wx, wy)) {
                isDraggingSelection = true
                selectionDragLastWorld = PointF(wx, wy)
                dragDx = 0f
                dragDy = 0f
            } else {
                selectedIds.clear()
                isDraggingSelection = false
                dragDx = 0f
                dragDy = 0f
                lassoPathWorld.clear()
                lassoPathWorld.add(PointF(wx, wy))
                dryInk.invalidate()
                emitState()
            }
            return
        }

        // Capture camera at stroke start (beginPen)
        val capturedScale = viewScale
        val capturedPanX = panX
        val capturedPanY = panY
        val isHighlighter = toolMode == ToolMode.HIGHLIGHTER
        val brushType = if (isHighlighter) BrushType.HIGHLIGHTER else BrushType.PEN
        val activeColor = if (isHighlighter) highlighterColor else inkColor
        val sizePx = (if (isHighlighter) highlighterWidthDp else penWidthDp) * density
        val epsilonPx = density / 5f
        val brush = Brush.createWithColorIntArgb(familyFor(brushType), activeColor, sizePx, epsilonPx)

        val strokeId = wetInk.startStroke(event, activePointer, brush)
        activeInk = strokeId
        waitingForInk[strokeId] = PendingStrokeMeta(
            scale = capturedScale,
            panX = capturedPanX,
            panY = capturedPanY,
            brushType = brushType,
            color = activeColor,
            sizeWorld = sizePx / capturedScale,
            epsilonWorld = epsilonPx / capturedScale,
            isHighlighter = isHighlighter,
            snappedShape = null
        )

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            predictor = MotionEventPredictor.newInstance(wetInk).also { it.record(event) }
        }

        rawWorldPoints.add(PointF((x - capturedPanX) / capturedScale, (y - capturedPanY) / capturedScale))
        scheduleHoldCheck(x, y)
    }

    private fun movePen(event: MotionEvent, index: Int) {
        readPen(event, index)
        if (erasing) {
            for (h in 0 until event.historySize) {
                eraseAtScreen(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
            }
            eraseAtScreen(x, y)
            return
        }

        if (toolMode == ToolMode.LASSO) {
            val wx = screenToWorldX(x)
            val wy = screenToWorldY(y)
            if (isDraggingSelection) {
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

        val strokeId = activeInk ?: return
        val meta = waitingForInk[strokeId]
        val sScale = meta?.scale ?: viewScale
        val sPanX = meta?.panX ?: panX
        val sPanY = meta?.panY ?: panY

        for (h in 0 until event.historySize) {
            val hx = event.getHistoricalX(index, h)
            val hy = event.getHistoricalY(index, h)
            rawWorldPoints.add(PointF((hx - sPanX) / sScale, (hy - sPanY) / sScale))
        }
        rawWorldPoints.add(PointF((x - sPanX) / sScale, (y - sPanY) / sScale))

        if (hypot(x - holdAnchorX, y - holdAnchorY) > 6f * density) {
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
            lassoPathWorld.clear()
            isDraggingSelection = false
            selectionDragLastWorld = null
            dragDx = 0f
            dragDy = 0f
        } else if (erasing) {
            eraseAtScreen(x, y)
            if (erasedIds.isNotEmpty()) {
                rememberBeforeChange()
                items.removeAll { it.id in erasedIds }
                selectedIds.removeAll(erasedIds)
            }
        } else if (toolMode == ToolMode.LASSO) {
            if (isDraggingSelection) {
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
        } else {
            activeInk?.let { strokeId ->
                val existing = waitingForInk[strokeId]
                if (existing != null) {
                    val finalShape = holdSnappedShape
                        ?: if (autoShapeEnabled) ShapeRecognizer.recognize(rawWorldPoints, density / existing.scale) else null
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

    private fun endPen() {
        mainHandler.removeCallbacks(holdRunnable)
        predictor = null
        activeInk = null
        activePointer = -1
        erasing = false
        lastEraseWorld = null
        holdSnappedShape = null
        rawWorldPoints.clear()
        if (erasedIds.isNotEmpty()) {
            erasedIds.clear()
            dryInk.invalidate()
        }
    }

    private fun scheduleHoldCheck(sx: Float, sy: Float) {
        holdAnchorX = sx
        holdAnchorY = sy
        mainHandler.removeCallbacks(holdRunnable)
        mainHandler.postDelayed(holdRunnable, 420L)
    }

    private fun trySnapHoldShape() {
        val strokeId = activeInk ?: return
        if (erasing || toolMode == ToolMode.LASSO) return
        val scale = waitingForInk[strokeId]?.scale ?: viewScale
        val recognized = ShapeRecognizer.recognize(rawWorldPoints, density / scale)
        if (recognized != null) {
            holdSnappedShape = recognized
            dryInk.postInvalidateOnAnimation()
        }
    }

    private fun eraseAtScreen(sx: Float, sy: Float) {
        val wx = screenToWorldX(sx)
        val wy = screenToWorldY(sy)
        val worldRadius = (eraserRadiusDp * density) / viewScale
        val start = lastEraseWorld ?: PointF(wx, wy)
        val steps = ceil(hypot(wx - start.x, wy - start.y) / worldRadius).toInt().coerceAtLeast(1)
        val before = erasedIds.size

        for (step in 1..steps) {
            val progress = step.toFloat() / steps
            val px = start.x + (wx - start.x) * progress
            val py = start.y + (wy - start.y) * progress

            for (item in items) {
                if (item.id in erasedIds) continue
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
                }
                if (hit) erasedIds.add(item.id)
            }
        }
        lastEraseWorld = PointF(wx, wy)
        if (before != erasedIds.size) dryInk.postInvalidateOnAnimation()
    }

    private fun selectItemsInsideLasso() {
        selectedIds.clear()
        if (lassoPathWorld.size < 3) return
        for (item in items) {
            val b = item.worldBounds
            if (b.isEmpty) continue
            val cx = b.centerX()
            val cy = b.centerY()
            if (isPointInPolygon(cx, cy, lassoPathWorld) ||
                isPointInPolygon(b.left, b.top, lassoPathWorld) ||
                isPointInPolygon(b.right, b.bottom, lassoPathWorld)
            ) {
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
        if (!out.isEmpty && isDraggingSelection) {
            out.offset(dragDx, dragDy)
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

    fun deleteSelection() {
        if (isWriting || selectedIds.isEmpty()) return
        rememberBeforeChange()
        items.removeAll { it.id in selectedIds }
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

    /** Fits all content within a 32 dp margin (clamped to 45%..400%). Aligns top-left if content exceeds view at 45%. */
    fun fitToContent() {
        if (items.isEmpty() || width <= 0 || height <= 0) {
            resetZoom()
            return
        }
        val contentBounds = RectF()
        for (item in items) {
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

    private fun rememberBeforeChange() {
        undoHistory.addLast(items.toList())
        if (undoHistory.size > 50) undoHistory.removeFirst()
        redoHistory.clear()
        emitState()
    }

    fun undo(): Boolean {
        if (isWriting || undoHistory.isEmpty()) return false
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
        undoHistory.addLast(items.toList())
        items.clear()
        items.addAll(redoHistory.removeLast())
        selectedIds.clear()
        dryInk.invalidate()
        emitState()
        return true
    }

    fun clear() {
        if (isWriting || items.isEmpty()) return
        rememberBeforeChange()
        items.clear()
        selectedIds.clear()
        dryInk.invalidate()
        emitState()
    }

    fun exportBitmap(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val prevSelected = selectedIds.toSet()
        selectedIds.clear()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        dryInk.draw(canvas)
        selectedIds.addAll(prevSelected)
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

    private fun normalizeAndRebuildStroke(
        rawStroke: Stroke,
        meta: PendingStrokeMeta
    ): Pair<StrokeData, Stroke> {
        val inputs = rawStroke.inputs
        val count = inputs.size
        val pts = ArrayList<StrokePoint>(count)
        val scratch = StrokeInput()
        for (i in 0 until count) {
            inputs.populate(i, scratch)
            val wx = (scratch.x - meta.panX) / meta.scale
            val wy = (scratch.y - meta.panY) / meta.scale
            val unitCm = if (scratch.strokeUnitLengthCm != StrokeInput.NO_STROKE_UNIT_LENGTH) {
                scratch.strokeUnitLengthCm * meta.scale
            } else {
                StrokeInput.NO_STROKE_UNIT_LENGTH
            }
            pts.add(
                StrokePoint(
                    x = wx,
                    y = wy,
                    elapsedTimeMillis = scratch.elapsedTimeMillis,
                    pressure = scratch.pressure,
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
