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
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Keeps high-frequency stylus input, zoom/pan, shapes, lasso, and ink layers outside Compose state. */
class PenSheet(
    context: Context,
    private val onStateChanged: (canUndo: Boolean, canRedo: Boolean, hasSelection: Boolean) -> Unit,
    private val onTelemetryChanged: (PenTelemetry) -> Unit = {}
) : FrameLayout(context) {

    sealed class CanvasItem {
        abstract val isHighlighter: Boolean
        abstract val offsetX: Float
        abstract val offsetY: Float
        abstract fun translated(dx: Float, dy: Float): CanvasItem
        abstract fun recolored(newColor: Int): CanvasItem?
        abstract fun worldBounds(): RectF
    }

    data class InkItem(
        val stroke: Stroke,
        val creationScale: Float,
        val creationPanX: Float,
        val creationPanY: Float,
        override val isHighlighter: Boolean,
        override val offsetX: Float = 0f,
        override val offsetY: Float = 0f
    ) : CanvasItem() {
        override fun translated(dx: Float, dy: Float): InkItem =
            copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

        override fun recolored(newColor: Int): InkItem? = null

        override fun worldBounds(): RectF {
            val box = stroke.shape.computeBoundingBox() ?: return RectF()
            val left = (box.xMin - creationPanX) / creationScale + offsetX
            val top = (box.yMin - creationPanY) / creationScale + offsetY
            val right = (box.xMax - creationPanX) / creationScale + offsetX
            val bottom = (box.yMax - creationPanY) / creationScale + offsetY
            return RectF(left, top, right, bottom)
        }
    }

    data class ShapeItem(
        val shape: RecognizedShape,
        val color: Int,
        val strokeWidthWorld: Float,
        override val isHighlighter: Boolean,
        override val offsetX: Float = 0f,
        override val offsetY: Float = 0f
    ) : CanvasItem() {
        override fun translated(dx: Float, dy: Float): ShapeItem =
            copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

        override fun recolored(newColor: Int): ShapeItem = copy(color = newColor)

        override fun worldBounds(): RectF {
            val pts = shape.samplePoints(12f)
            if (pts.isEmpty()) return RectF()
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (p in pts) {
                val wx = p.x + offsetX
                val wy = p.y + offsetY
                if (wx < minX) minX = wx
                if (wx > maxX) maxX = wx
                if (wy < minY) minY = wy
                if (wy > maxY) maxY = wy
            }
            val pad = strokeWidthWorld * 0.5f
            return RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
        }
    }

    private val density = resources.displayMetrics.density
    private val renderInk = CanvasStrokeRenderer.create()
    private val wetInk = InProgressStrokesView(context)
    private val penFamily = StockBrushes.pressurePen()

    private val items = mutableListOf<CanvasItem>()
    private val undoHistory = ArrayDeque<List<CanvasItem>>()
    private val redoHistory = ArrayDeque<List<CanvasItem>>()
    private val erased = mutableSetOf<CanvasItem>()
    private val waitingForInk = mutableMapOf<InProgressStrokeId, PendingStrokeMeta>()

    private data class PendingStrokeMeta(
        val scale: Float,
        val panX: Float,
        val panY: Float,
        val isHighlighter: Boolean,
        val color: Int,
        val strokeWidthWorld: Float,
        val snappedShape: RecognizedShape?
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

    // Lasso selection state
    private val lassoPathWorld = mutableListOf<PointF>()
    private val selectedItems = mutableSetOf<CanvasItem>()
    private var selectionDragLastWorld: PointF? = null
    private var isDraggingSelection = false

    // Configurable editor properties
    var toolMode: ToolMode = ToolMode.PEN
        set(value) {
            field = value
            if (value != ToolMode.LASSO && selectedItems.isNotEmpty()) {
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
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(paperStyle.bgColor)

            canvas.save()
            canvas.translate(panX, panY)
            canvas.scale(viewScale, viewScale)

            drawPaperTemplate(canvas)

            // Pass 1: Highlighter strokes & shapes behind opaque ink
            for (item in items) {
                if (item !in erased && item.isHighlighter) {
                    drawCanvasItem(canvas, item)
                }
            }
            // Pass 2: Opaque pen strokes & geometric shapes
            for (item in items) {
                if (item !in erased && !item.isHighlighter) {
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

            // Selection bounding box
            if (selectedItems.isNotEmpty()) {
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
                drawShape(canvas, preview, 0f, 0f)
            }

            canvas.restore()
        }
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
                // Horizontal-ish and 60-degree diagonals
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
        when (item) {
            is InkItem -> {
                canvas.save()
                canvas.translate(item.offsetX, item.offsetY)
                canvas.scale(1f / item.creationScale, 1f / item.creationScale)
                canvas.translate(-item.creationPanX, -item.creationPanY)
                renderInk.draw(canvas, item.stroke, AffineTransform.IDENTITY)
                canvas.restore()
            }
            is ShapeItem -> {
                shapePaint.color = item.color
                shapePaint.strokeWidth = item.strokeWidthWorld
                drawShape(canvas, item.shape, item.offsetX, item.offsetY)
            }
        }
    }

    private fun drawShape(canvas: Canvas, shape: RecognizedShape, ox: Float, oy: Float) {
        when (shape) {
            is RecognizedShape.Line -> {
                canvas.drawLine(
                    shape.start.x + ox, shape.start.y + oy,
                    shape.end.x + ox, shape.end.y + oy,
                    shapePaint
                )
            }
            is RecognizedShape.Arrow -> {
                canvas.drawLine(
                    shape.start.x + ox, shape.start.y + oy,
                    shape.end.x + ox, shape.end.y + oy,
                    shapePaint
                )
                canvas.drawLine(
                    shape.end.x + ox, shape.end.y + oy,
                    shape.wingLeft.x + ox, shape.wingLeft.y + oy,
                    shapePaint
                )
                canvas.drawLine(
                    shape.end.x + ox, shape.end.y + oy,
                    shape.wingRight.x + ox, shape.wingRight.y + oy,
                    shapePaint
                )
            }
            is RecognizedShape.Rectangle -> {
                canvas.drawRect(
                    shape.left + ox, shape.top + oy,
                    shape.right + ox, shape.bottom + oy,
                    shapePaint
                )
            }
            is RecognizedShape.Triangle -> {
                val path = Path().apply {
                    moveTo(shape.a.x + ox, shape.a.y + oy)
                    lineTo(shape.b.x + ox, shape.b.y + oy)
                    lineTo(shape.c.x + ox, shape.c.y + oy)
                    close()
                }
                canvas.drawPath(path, shapePaint)
            }
            is RecognizedShape.Ellipse -> {
                canvas.drawOval(
                    shape.cx - shape.rx + ox,
                    shape.cy - shape.ry + oy,
                    shape.cx + shape.rx + ox,
                    shape.cy + shape.ry + oy,
                    shapePaint
                )
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
                for ((id, stroke) in strokes) {
                    val meta = waitingForInk.remove(id)
                    rememberBeforeChange()
                    if (meta?.snappedShape != null) {
                        items.add(
                            ShapeItem(
                                shape = meta.snappedShape,
                                color = meta.color,
                                strokeWidthWorld = meta.strokeWidthWorld,
                                isHighlighter = meta.isHighlighter
                            )
                        )
                        lastShapeDetected = meta.snappedShape.label
                    } else {
                        items.add(
                            InkItem(
                                stroke = stroke,
                                creationScale = meta?.scale ?: viewScale,
                                creationPanX = meta?.panX ?: panX,
                                creationPanY = meta?.panY ?: panY,
                                isHighlighter = meta?.isHighlighter ?: (toolMode == ToolMode.HIGHLIGHTER)
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
                activeInk?.let { wetInk.cancelStroke(it, event) }
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
            if (selectedItems.isNotEmpty() && computeSelectionBounds().contains(wx, wy)) {
                isDraggingSelection = true
                selectionDragLastWorld = PointF(wx, wy)
                rememberBeforeChange()
            } else {
                selectedItems.clear()
                isDraggingSelection = false
                lassoPathWorld.clear()
                lassoPathWorld.add(PointF(wx, wy))
                dryInk.invalidate()
                emitState()
            }
            return
        }

        val isHighlighter = toolMode == ToolMode.HIGHLIGHTER
        val activeColor = if (isHighlighter) highlighterColor else inkColor
        val sizePx = (if (isHighlighter) highlighterWidthDp else penWidthDp) * density
        val brush = Brush.createWithColorIntArgb(penFamily, activeColor, sizePx, density / 5f)
        activeInk = wetInk.startStroke(event, activePointer, brush)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            predictor = MotionEventPredictor.newInstance(wetInk).also { it.record(event) }
        }

        rawWorldPoints.add(PointF(screenToWorldX(x), screenToWorldY(y)))
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
                    moveSelectedItems(dx, dy)
                    selectionDragLastWorld = PointF(wx, wy)
                }
            } else {
                lassoPathWorld.add(PointF(wx, wy))
                dryInk.postInvalidateOnAnimation()
            }
            return
        }

        val stroke = activeInk ?: return
        for (h in 0 until event.historySize) {
            val hx = event.getHistoricalX(index, h)
            val hy = event.getHistoricalY(index, h)
            rawWorldPoints.add(PointF(screenToWorldX(hx), screenToWorldY(hy)))
        }
        rawWorldPoints.add(PointF(screenToWorldX(x), screenToWorldY(y)))

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
            wetInk.addToStroke(event, activePointer, stroke, prediction)
        } finally {
            prediction?.recycle()
        }
    }

    private fun finishPen(event: MotionEvent, index: Int, canceled: Boolean) {
        mainHandler.removeCallbacks(holdRunnable)
        readPen(event, index)
        if (canceled) {
            activeInk?.let { wetInk.cancelStroke(it, event) }
            lassoPathWorld.clear()
        } else if (erasing) {
            eraseAtScreen(x, y)
            if (erased.isNotEmpty()) {
                rememberBeforeChange()
                items.removeAll(erased)
                selectedItems.removeAll(erased)
            }
        } else if (toolMode == ToolMode.LASSO) {
            if (isDraggingSelection) {
                isDraggingSelection = false
                selectionDragLastWorld = null
            } else {
                selectItemsInsideLasso()
                lassoPathWorld.clear()
            }
            dryInk.invalidate()
            emitState()
        } else {
            val isHighlighter = toolMode == ToolMode.HIGHLIGHTER
            val activeColor = if (isHighlighter) highlighterColor else inkColor
            val sizeWorld = ((if (isHighlighter) highlighterWidthDp else penWidthDp) * density) / viewScale

            val finalShape = holdSnappedShape
                ?: if (autoShapeEnabled) ShapeRecognizer.recognize(rawWorldPoints, density / viewScale) else null

            activeInk?.let { stroke ->
                predictor?.record(event)
                waitingForInk[stroke] = PendingStrokeMeta(
                    scale = viewScale,
                    panX = panX,
                    panY = panY,
                    isHighlighter = isHighlighter,
                    color = activeColor,
                    strokeWidthWorld = sizeWorld,
                    snappedShape = finalShape
                )
                wetInk.finishStroke(event, activePointer, stroke)
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
        if (erased.isNotEmpty()) {
            erased.clear()
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
        if (activeInk == null || erasing || toolMode == ToolMode.LASSO) return
        val recognized = ShapeRecognizer.recognize(rawWorldPoints, density / viewScale)
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
        val before = erased.size

        for (step in 1..steps) {
            val progress = step.toFloat() / steps
            val px = start.x + (wx - start.x) * progress
            val py = start.y + (wy - start.y) * progress

            for (item in items) {
                if (item in erased) continue
                val hit = when (item) {
                    is InkItem -> {
                        val ix = (px - item.offsetX) * item.creationScale + item.creationPanX
                        val iy = (py - item.offsetY) * item.creationScale + item.creationPanY
                        val r = worldRadius * item.creationScale
                        val area = ImmutableBox.fromCenterAndDimensions(
                            ImmutableVec(ix, iy), 2 * r, 2 * r
                        )
                        area.intersects(item.stroke.shape, AffineTransform.IDENTITY)
                    }
                    is ShapeItem -> {
                        val pts = item.shape.samplePoints(8f)
                        val threshold = worldRadius + item.strokeWidthWorld * 0.5f
                        pts.any { pt ->
                            hypot((pt.x + item.offsetX) - px, (pt.y + item.offsetY) - py) <= threshold
                        }
                    }
                }
                if (hit) erased.add(item)
            }
        }
        lastEraseWorld = PointF(wx, wy)
        if (before != erased.size) dryInk.postInvalidateOnAnimation()
    }

    private fun selectItemsInsideLasso() {
        selectedItems.clear()
        if (lassoPathWorld.size < 3) return
        for (item in items) {
            val b = item.worldBounds()
            if (b.isEmpty) continue
            val cx = b.centerX()
            val cy = b.centerY()
            if (isPointInPolygon(cx, cy, lassoPathWorld) ||
                isPointInPolygon(b.left, b.top, lassoPathWorld) ||
                isPointInPolygon(b.right, b.bottom, lassoPathWorld)
            ) {
                selectedItems.add(item)
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
        for (item in selectedItems) {
            val b = item.worldBounds()
            if (!b.isEmpty) {
                if (out.isEmpty) out.set(b) else out.union(b)
            }
        }
        return out
    }

    private fun moveSelectedItems(dx: Float, dy: Float) {
        val updatedSelection = mutableSetOf<CanvasItem>()
        for (i in items.indices) {
            val item = items[i]
            if (item in selectedItems) {
                val moved = item.translated(dx, dy)
                items[i] = moved
                updatedSelection.add(moved)
            }
        }
        selectedItems.clear()
        selectedItems.addAll(updatedSelection)
        dryInk.postInvalidateOnAnimation()
    }

    fun deleteSelection() {
        if (isWriting || selectedItems.isEmpty()) return
        rememberBeforeChange()
        items.removeAll(selectedItems)
        selectedItems.clear()
        dryInk.invalidate()
        emitState()
    }

    fun duplicateSelection() {
        if (isWriting || selectedItems.isEmpty()) return
        rememberBeforeChange()
        val shift = 24f * density / viewScale
        val clones = selectedItems.map { it.translated(shift, shift) }
        items.addAll(clones)
        selectedItems.clear()
        selectedItems.addAll(clones)
        dryInk.invalidate()
        emitState()
    }

    fun clearSelection() {
        if (selectedItems.isEmpty()) return
        selectedItems.clear()
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
        selectedItems.clear()
        dryInk.invalidate()
        emitState()
        return true
    }

    fun redo(): Boolean {
        if (isWriting || redoHistory.isEmpty()) return false
        undoHistory.addLast(items.toList())
        items.clear()
        items.addAll(redoHistory.removeLast())
        selectedItems.clear()
        dryInk.invalidate()
        emitState()
        return true
    }

    fun clear() {
        if (isWriting || items.isEmpty()) return
        rememberBeforeChange()
        items.clear()
        selectedItems.clear()
        dryInk.invalidate()
        emitState()
    }

    fun exportBitmap(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val prevSelected = selectedItems.toSet()
        selectedItems.clear()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        dryInk.draw(canvas)
        selectedItems.addAll(prevSelected)
        return bmp
    }

    private fun emitState() {
        onStateChanged(undoHistory.isNotEmpty(), redoHistory.isNotEmpty(), selectedItems.isNotEmpty())
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
}
