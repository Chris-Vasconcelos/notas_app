package mx.spen.lab

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

sealed class RecognizedShape(val label: String) {
    abstract fun samplePoints(stepPx: Float = 8f): List<PointF>
    abstract fun translated(dx: Float, dy: Float): RecognizedShape
    abstract fun computeWorldBounds(strokeWidthWorld: Float): RectF

    class Line(val start: PointF, val end: PointF) : RecognizedShape("Línea") {
        override fun samplePoints(stepPx: Float): List<PointF> =
            sampleSegment(start, end, stepPx)

        override fun translated(dx: Float, dy: Float): Line =
            Line(PointF(start.x + dx, start.y + dy), PointF(end.x + dx, end.y + dy))

        override fun computeWorldBounds(strokeWidthWorld: Float): RectF =
            inflatedBounds(
                min(start.x, end.x),
                min(start.y, end.y),
                max(start.x, end.x),
                max(start.y, end.y),
                strokeWidthWorld
            )
    }

    class Arrow(
        val start: PointF,
        val end: PointF,
        val wingLeft: PointF,
        val wingRight: PointF
    ) : RecognizedShape("Flecha") {
        override fun samplePoints(stepPx: Float): List<PointF> {
            val pts = mutableListOf<PointF>()
            pts += sampleSegment(start, end, stepPx)
            pts += sampleSegment(end, wingLeft, stepPx)
            pts += sampleSegment(wingLeft, end, stepPx)
            pts += sampleSegment(end, wingRight, stepPx)
            return pts
        }

        override fun translated(dx: Float, dy: Float): Arrow = Arrow(
            start = PointF(start.x + dx, start.y + dy),
            end = PointF(end.x + dx, end.y + dy),
            wingLeft = PointF(wingLeft.x + dx, wingLeft.y + dy),
            wingRight = PointF(wingRight.x + dx, wingRight.y + dy)
        )

        override fun computeWorldBounds(strokeWidthWorld: Float): RectF {
            val minX = minOf(start.x, end.x, wingLeft.x, wingRight.x)
            val minY = minOf(start.y, end.y, wingLeft.y, wingRight.y)
            val maxX = maxOf(start.x, end.x, wingLeft.x, wingRight.x)
            val maxY = maxOf(start.y, end.y, wingLeft.y, wingRight.y)
            return inflatedBounds(minX, minY, maxX, maxY, strokeWidthWorld)
        }
    }

    class Rectangle(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val isSquare: Boolean
    ) : RecognizedShape(if (isSquare) "Cuadrado" else "Rectángulo") {
        override fun samplePoints(stepPx: Float): List<PointF> {
            val tl = PointF(left, top)
            val tr = PointF(right, top)
            val br = PointF(right, bottom)
            val bl = PointF(left, bottom)
            val pts = mutableListOf<PointF>()
            pts += sampleSegment(tl, tr, stepPx)
            pts += sampleSegment(tr, br, stepPx).drop(1)
            pts += sampleSegment(br, bl, stepPx).drop(1)
            pts += sampleSegment(bl, tl, stepPx).drop(1)
            return pts
        }

        override fun translated(dx: Float, dy: Float): Rectangle = Rectangle(
            left = left + dx,
            top = top + dy,
            right = right + dx,
            bottom = bottom + dy,
            isSquare = isSquare
        )

        override fun computeWorldBounds(strokeWidthWorld: Float): RectF =
            inflatedBounds(min(left, right), min(top, bottom), max(left, right), max(top, bottom), strokeWidthWorld)
    }

    class Triangle(
        val a: PointF,
        val b: PointF,
        val c: PointF
    ) : RecognizedShape("Triángulo") {
        override fun samplePoints(stepPx: Float): List<PointF> {
            val pts = mutableListOf<PointF>()
            pts += sampleSegment(a, b, stepPx)
            pts += sampleSegment(b, c, stepPx).drop(1)
            pts += sampleSegment(c, a, stepPx).drop(1)
            return pts
        }

        override fun translated(dx: Float, dy: Float): Triangle = Triangle(
            a = PointF(a.x + dx, a.y + dy),
            b = PointF(b.x + dx, b.y + dy),
            c = PointF(c.x + dx, c.y + dy)
        )

        override fun computeWorldBounds(strokeWidthWorld: Float): RectF {
            val minX = minOf(a.x, b.x, c.x)
            val minY = minOf(a.y, b.y, c.y)
            val maxX = maxOf(a.x, b.x, c.x)
            val maxY = maxOf(a.y, b.y, c.y)
            return inflatedBounds(minX, minY, maxX, maxY, strokeWidthWorld)
        }
    }

    class Ellipse(
        val cx: Float,
        val cy: Float,
        val rx: Float,
        val ry: Float,
        val isCircle: Boolean
    ) : RecognizedShape(if (isCircle) "Círculo" else "Elipse") {
        override fun samplePoints(stepPx: Float): List<PointF> {
            val circumference = (PI * (3 * (rx + ry) - sqrt((3 * rx + ry) * (rx + 3 * ry)))).toFloat()
            val steps = max(24, (circumference / stepPx).toInt())
            val pts = ArrayList<PointF>(steps + 1)
            for (i in 0..steps) {
                val angle = (2.0 * PI * i) / steps
                pts.add(
                    PointF(
                        cx + (rx * cos(angle)).toFloat(),
                        cy + (ry * sin(angle)).toFloat()
                    )
                )
            }
            return pts
        }

        override fun translated(dx: Float, dy: Float): Ellipse = Ellipse(
            cx = cx + dx,
            cy = cy + dy,
            rx = rx,
            ry = ry,
            isCircle = isCircle
        )

        override fun computeWorldBounds(strokeWidthWorld: Float): RectF =
            inflatedBounds(cx - rx, cy - ry, cx + rx, cy + ry, strokeWidthWorld)
    }

    /** Closed multi-vertex polygon: Rombo, Paralelogramo, Trapecio, Pentágono, Hexágono, Estrella. */
    class Polygon(
        val vertices: List<PointF>,
        val shapeLabel: String
    ) : RecognizedShape(shapeLabel) {
        override fun samplePoints(stepPx: Float): List<PointF> {
            if (vertices.size < 2) return vertices
            val pts = mutableListOf<PointF>()
            for (i in vertices.indices) {
                val a = vertices[i]
                val b = vertices[(i + 1) % vertices.size]
                val seg = sampleSegment(a, b, stepPx)
                if (i == 0) pts += seg else pts += seg.drop(1)
            }
            return pts
        }

        override fun translated(dx: Float, dy: Float): Polygon = Polygon(
            vertices = vertices.map { PointF(it.x + dx, it.y + dy) },
            shapeLabel = shapeLabel
        )

        override fun computeWorldBounds(strokeWidthWorld: Float): RectF {
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (v in vertices) {
                if (v.x < minX) minX = v.x
                if (v.x > maxX) maxX = v.x
                if (v.y < minY) minY = v.y
                if (v.y > maxY) maxY = v.y
            }
            return inflatedBounds(minX, minY, maxX, maxY, strokeWidthWorld)
        }
    }

    /** Open multi-segment polyline: Ejes L, Ángulo, Ruta/Cable, Pulso. */
    class Polyline(
        val vertices: List<PointF>,
        val shapeLabel: String = "Polilínea"
    ) : RecognizedShape(shapeLabel) {
        override fun samplePoints(stepPx: Float): List<PointF> {
            if (vertices.size < 2) return vertices
            val pts = mutableListOf<PointF>()
            for (i in 0 until vertices.lastIndex) {
                val seg = sampleSegment(vertices[i], vertices[i + 1], stepPx)
                if (i == 0) pts += seg else pts += seg.drop(1)
            }
            return pts
        }

        override fun translated(dx: Float, dy: Float): Polyline = Polyline(
            vertices = vertices.map { PointF(it.x + dx, it.y + dy) },
            shapeLabel = shapeLabel
        )

        override fun computeWorldBounds(strokeWidthWorld: Float): RectF {
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (v in vertices) {
                if (v.x < minX) minX = v.x
                if (v.x > maxX) maxX = v.x
                if (v.y < minY) minY = v.y
                if (v.y > maxY) maxY = v.y
            }
            return inflatedBounds(minX, minY, maxX, maxY, strokeWidthWorld)
        }
    }

    /** Smooth open quadratic curve / circular arc. */
    class Arc(
        val start: PointF,
        val control: PointF,
        val end: PointF
    ) : RecognizedShape("Arco") {
        override fun samplePoints(stepPx: Float): List<PointF> {
            val approxLen = hypot(control.x - start.x, control.y - start.y) +
                hypot(end.x - control.x, end.y - control.y)
            val steps = max(16, (approxLen / stepPx).toInt())
            val pts = ArrayList<PointF>(steps + 1)
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val u = 1f - t
                val x = u * u * start.x + 2f * u * t * control.x + t * t * end.x
                val y = u * u * start.y + 2f * u * t * control.y + t * t * end.y
                pts.add(PointF(x, y))
            }
            return pts
        }

        override fun translated(dx: Float, dy: Float): Arc = Arc(
            start = PointF(start.x + dx, start.y + dy),
            control = PointF(control.x + dx, control.y + dy),
            end = PointF(end.x + dx, end.y + dy)
        )

        override fun computeWorldBounds(strokeWidthWorld: Float): RectF {
            val pts = samplePoints(12f)
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (p in pts) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
            return inflatedBounds(minX, minY, maxX, maxY, strokeWidthWorld)
        }
    }

    protected fun inflatedBounds(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        strokeWidthWorld: Float
    ): RectF {
        val pad = strokeWidthWorld * 0.5f + 2f
        return RectF(minX - pad, minY - pad, maxX + pad, maxY + pad)
    }
}

private fun sampleSegment(a: PointF, b: PointF, stepPx: Float): List<PointF> {
    val dist = hypot(b.x - a.x, b.y - a.y)
    val steps = max(2, (dist / stepPx).toInt())
    val pts = ArrayList<PointF>(steps + 1)
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        pts.add(PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
    }
    return pts
}

object ShapeRecognizer {

    fun recognize(rawPoints: List<PointF>, density: Float): RecognizedShape? {
        if (rawPoints.size < 5) return null
        val minSize = 20f * density

        // Trim stationary hold tail and resample equidistantly to eliminate speed bias
        val pts = resampleEquidistant(rawPoints, max(2.5f * density, 1.5f), maxPoints = 96)
        if (pts.size < 5) return null

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var pathLen = 0f

        for (i in pts.indices) {
            val p = pts[i]
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
            if (i > 0) {
                pathLen += hypot(p.x - pts[i - 1].x, p.y - pts[i - 1].y)
            }
        }

        val boxW = maxX - minX
        val boxH = maxY - minY
        val diag = hypot(boxW, boxH)
        if (pathLen < minSize || diag < minSize * 0.65f) return null

        val first = pts.first()
        val last = pts.last()
        val endGap = hypot(last.x - first.x, last.y - first.y)

        // 1. Single straight line check
        if (endGap > minSize && pathLen < endGap * 1.15f) {
            val maxDev = maxDistanceToSegment(pts, first, last)
            if (maxDev <= max(9f * density, endGap * 0.075f)) {
                val snapped = snapLineEndpoints(first, last)
                return RecognizedShape.Line(snapped.first, snapped.second)
            }
        }

        // 2. Single-stroke Arrow check (straight shaft from start to farthest point + tip hook/wings)
        recognizeSingleStrokeArrow(pts, density, minSize)?.let { return it }

        // 3. Closed shapes (Triangles, Quads, Rombo, Paralelogramo, Trapecio, Pentágono, Hexágono, Estrella, Círculo, Elipse)
        val isClosed = endGap <= max(32f * density, diag * 0.32f) && pathLen > diag * 1.7f
        if (isClosed) {
            recognizeClosedShape(pts, minX, maxX, minY, maxY, diag, density)?.let { return it }
        }

        // 4. Open multi-segment polylines (Ejes L, Ángulo, Cable/Ruta, Pulso digital)
        recognizeOpenPolyline(pts, diag, density, minSize)?.let { return it }

        // 5. Smooth open Arc / Parabola
        recognizeSmoothArc(pts, first, last, endGap, pathLen, density, minSize)?.let { return it }

        return null
    }

    private fun recognizeClosedShape(
        pts: List<PointF>,
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        diag: Float,
        density: Float
    ): RecognizedShape? {
        val boxW = maxX - minX
        val boxH = maxY - minY
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f

        // Re-seed closed loop at the point farthest from centroid (guaranteed true vertex on polygons)
        val loop = if (hypot(pts.last().x - pts.first().x, pts.last().y - pts.first().y) < 4f * density) {
            pts.dropLast(1)
        } else {
            pts
        }
        if (loop.size < 6) return null

        var centroidX = 0f
        var centroidY = 0f
        for (p in loop) {
            centroidX += p.x
            centroidY += p.y
        }
        centroidX /= loop.size
        centroidY /= loop.size

        var anchorIdx = 0
        var maxR = -1f
        for (i in loop.indices) {
            val r = hypot(loop[i].x - centroidX, loop[i].y - centroidY)
            if (r > maxR) {
                maxR = r
                anchorIdx = i
            }
        }

        val orderedLoop = ArrayList<PointF>(loop.size + 1)
        for (i in loop.indices) {
            orderedLoop.add(loop[(anchorIdx + i) % loop.size])
        }
        orderedLoop.add(orderedLoop.first())

        // Extract candidate polygon corners via RDP + collinear angle pruning
        val rdpEps = max(7f * density, diag * 0.048f)
        val rawCorners = removeDuplicateClosingPoint(rdp(orderedLoop, rdpEps), max(12f * density, diag * 0.07f))
        val corners = pruneCollinearClosedCorners(rawCorners, minTurnDegrees = 25.0, minEdgeLen = diag * 0.11f)

        // Compute Polygon Fit Error (normalized by diag)
        val polyErr = if (corners.size >= 3) {
            meanDistanceToClosedPolygon(loop, corners) / diag
        } else {
            1f
        }

        // Compute Ellipse Fit Error
        val rx = boxW * 0.5f
        val ry = boxH * 0.5f
        var ellipseMeanErr = 1f
        var ellipseMaxErr = 1f
        if (rx > 8f * density && ry > 8f * density) {
            var sum = 0f
            var mx = 0f
            for (p in loop) {
                val nx = (p.x - cx) / rx
                val ny = (p.y - cy) / ry
                val err = abs(hypot(nx, ny) - 1f)
                sum += err
                if (err > mx) mx = err
            }
            ellipseMeanErr = sum / loop.size
            ellipseMaxErr = mx
        }

        // Check if it's a Star (8..12 vertices alternating outer/inner radius)
        val starCorners = pruneCollinearClosedCorners(
            removeDuplicateClosingPoint(rdp(orderedLoop, max(5f * density, diag * 0.032f)), 8f * density),
            minTurnDegrees = 22.0,
            minEdgeLen = diag * 0.08f
        )
        if (starCorners.size in 8..12 && starCorners.size % 2 == 0) {
            if (isAlternatingStar(starCorners, centroidX, centroidY)) {
                val regularizedStar = buildRegularStar(
                    cx = centroidX,
                    cy = centroidY,
                    pointsCount = starCorners.size / 2,
                    outerR = maxR
                )
                return RecognizedShape.Polygon(regularizedStar, "Estrella")
            }
        }

        // Decide between Polygon (3..6 corners) vs Ellipse/Circle
        val hasSharpPolygonFit = corners.size in 3..6 && polyErr < 0.045f
        val isEllipseBetter = ellipseMeanErr < 0.11f && ellipseMaxErr < 0.26f &&
            (!hasSharpPolygonFit || (corners.size >= 5 && ellipseMeanErr * 0.42f < polyErr))

        if (isEllipseBetter) {
            val aspect = min(rx, ry) / max(rx, ry)
            val isCircle = aspect >= 0.84f
            val finalRx = if (isCircle) (rx + ry) * 0.5f else rx
            val finalRy = if (isCircle) finalRx else ry
            return RecognizedShape.Ellipse(cx, cy, finalRx, finalRy, isCircle)
        }

        if (hasSharpPolygonFit) {
            when (corners.size) {
                3 -> {
                    val snappedTri = regularizeTriangle(corners)
                    return RecognizedShape.Triangle(snappedTri[0], snappedTri[1], snappedTri[2])
                }
                4 -> {
                    return classifyQuadrilateral(corners, minX, maxX, minY, maxY)
                }
                5 -> {
                    val verts = maybeRegularizePolygon(corners, centroidX, centroidY, 5)
                    return RecognizedShape.Polygon(verts, "Pentágono")
                }
                6 -> {
                    val verts = maybeRegularizePolygon(corners, centroidX, centroidY, 6)
                    return RecognizedShape.Polygon(verts, "Hexágono")
                }
            }
        }

        // Secondary check for Circle/Ellipse with slightly looser threshold if polygon didn't match
        if (ellipseMeanErr < 0.145f && ellipseMaxErr < 0.31f) {
            val aspect = min(rx, ry) / max(rx, ry)
            val isCircle = aspect >= 0.84f
            val finalRx = if (isCircle) (rx + ry) * 0.5f else rx
            val finalRy = if (isCircle) finalRx else ry
            return RecognizedShape.Ellipse(cx, cy, finalRx, finalRy, isCircle)
        }

        return null
    }

    private fun classifyQuadrilateral(
        corners: List<PointF>,
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float
    ): RecognizedShape {
        val boxW = maxX - minX
        val boxH = maxY - minY
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f

        // Measure edge angles
        val edgeAngles = DoubleArray(4)
        var axisAlignedCount = 0
        for (i in 0..3) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val deg = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()))
            edgeAngles[i] = deg
            val mod90 = abs(((deg % 90.0) + 90.0) % 90.0)
            if (mod90 <= 14.0 || mod90 >= 76.0) {
                axisAlignedCount++
            }
        }

        // 1. Axis-aligned Rectangle or Square (at least 3 edges near 0°/90°)
        if (axisAlignedCount >= 3) {
            val aspect = min(boxW, boxH) / max(boxW, boxH)
            val isSquare = aspect >= 0.86f
            val finalW = if (isSquare) (boxW + boxH) * 0.5f else boxW
            val finalH = if (isSquare) finalW else boxH
            return RecognizedShape.Rectangle(
                left = cx - finalW * 0.5f,
                top = cy - finalH * 0.5f,
                right = cx + finalW * 0.5f,
                bottom = cy + finalH * 0.5f,
                isSquare = isSquare
            )
        }

        // 2. Diamond / Rombo check (vertices near top, right, bottom, left midpoints of bounding box)
        val topMid = PointF(cx, minY)
        val rightMid = PointF(maxX, cy)
        val bottomMid = PointF(cx, maxY)
        val leftMid = PointF(minX, cy)
        val targets = listOf(topMid, rightMid, bottomMid, leftMid)
        val diag = hypot(boxW, boxH)
        val matchesDiamond = targets.all { target ->
            corners.any { c -> hypot(c.x - target.x, c.y - target.y) <= diag * 0.18f }
        }
        if (matchesDiamond) {
            return RecognizedShape.Polygon(targets, "Rombo")
        }

        // 3. Parallelogram vs Trapezoid vs General Quad
        val pair02Parallel = areEdgesParallel(corners[0], corners[1], corners[3], corners[2], toleranceDeg = 15.0)
        val pair13Parallel = areEdgesParallel(corners[1], corners[2], corners[0], corners[3], toleranceDeg = 15.0)

        val label = when {
            pair02Parallel && pair13Parallel -> "Paralelogramo"
            pair02Parallel || pair13Parallel -> "Trapecio"
            else -> "Cuadrilátero"
        }
        val cleaned = snapNearAxisEdgesInPolygon(corners)
        return RecognizedShape.Polygon(cleaned, label)
    }

    private fun recognizeSingleStrokeArrow(
        pts: List<PointF>,
        density: Float,
        minSize: Float
    ): RecognizedShape.Arrow? {
        val first = pts.first()
        var tipIdx = 0
        var maxDist = 0f
        for (i in 1 until pts.size) {
            val d = hypot(pts[i].x - first.x, pts[i].y - first.y)
            if (d > maxDist) {
                maxDist = d
                tipIdx = i
            }
        }
        if (maxDist < minSize * 1.15f) return null
        if (tipIdx < (pts.size * 0.52f).toInt() || tipIdx >= pts.size - 2) return null

        val tip = pts[tipIdx]
        val shaftPts = pts.subList(0, tipIdx + 1)
        var shaftLen = 0f
        for (i in 1 until shaftPts.size) {
            shaftLen += hypot(shaftPts[i].x - shaftPts[i - 1].x, shaftPts[i].y - shaftPts[i - 1].y)
        }
        // Shaft must be reasonably straight
        if (shaftLen > maxDist * 1.18f) return null
        if (maxDistanceToSegment(shaftPts, first, tip) > max(10f * density, maxDist * 0.085f)) return null

        // Tail after tip must represent the arrowhead hook/wings
        val tailPts = pts.subList(tipIdx, pts.size)
        var tailLen = 0f
        var maxTailDistFromTip = 0f
        for (i in 1 until tailPts.size) {
            tailLen += hypot(tailPts[i].x - tailPts[i - 1].x, tailPts[i].y - tailPts[i - 1].y)
            val dTip = hypot(tailPts[i].x - tip.x, tailPts[i].y - tip.y)
            if (dTip > maxTailDistFromTip) maxTailDistFromTip = dTip
        }
        if (tailLen < 9f * density || tailLen > maxDist * 0.85f) return null
        if (maxTailDistFromTip < 8f * density || maxTailDistFromTip > maxDist * 0.45f) return null

        val snappedShaft = snapLineEndpoints(first, tip)
        val sStart = snappedShaft.first
        val sTip = snappedShaft.second
        val shaftAngle = atan2(sTip.y - sStart.y, sTip.x - sStart.x)
        val headLen = max(14f * density, min(28f * density, maxDist * 0.22f))
        val wingAngle = 0.52f // ~30 deg
        val leftWing = PointF(
            sTip.x - headLen * cos(shaftAngle - wingAngle),
            sTip.y - headLen * sin(shaftAngle - wingAngle)
        )
        val rightWing = PointF(
            sTip.x - headLen * cos(shaftAngle + wingAngle),
            sTip.y - headLen * sin(shaftAngle + wingAngle)
        )
        return RecognizedShape.Arrow(sStart, sTip, leftWing, rightWing)
    }

    private fun recognizeOpenPolyline(
        pts: List<PointF>,
        diag: Float,
        density: Float,
        minSize: Float
    ): RecognizedShape.Polyline? {
        val simplified = rdp(pts, max(7f * density, diag * 0.045f))
        val corners = pruneCollinearOpenCorners(simplified, minTurnDegrees = 26.0, minEdgeLen = 15f * density)
        if (corners.size !in 3..6) return null

        // Verify that the raw points stay close to the polyline segments
        var errSum = 0f
        var maxErr = 0f
        for (p in pts) {
            var best = Float.POSITIVE_INFINITY
            for (i in 0 until corners.lastIndex) {
                val d = distanceToSegment(p, corners[i], corners[i + 1])
                if (d < best) best = d
            }
            errSum += best
            if (best > maxErr) maxErr = best
        }
        val meanErr = errSum / pts.size
        if (meanErr > max(6f * density, diag * 0.035f) || maxErr > max(14f * density, diag * 0.085f)) {
            return null
        }

        // Snap segments that are nearly horizontal/vertical/45-deg
        val snapped = ArrayList<PointF>(corners.size)
        snapped.add(corners.first())
        for (i in 1 until corners.size) {
            val prev = snapped[i - 1]
            val cur = corners[i]
            snapped.add(snapLineEndpoints(prev, cur).second)
        }
        val label = if (snapped.size == 3) "Ángulo / Ejes" else "Polilínea"
        return RecognizedShape.Polyline(snapped, label)
    }

    private fun recognizeSmoothArc(
        pts: List<PointF>,
        first: PointF,
        last: PointF,
        endGap: Float,
        pathLen: Float,
        density: Float,
        minSize: Float
    ): RecognizedShape.Arc? {
        if (endGap < minSize || pathLen < endGap * 1.12f || pathLen > endGap * 2.3f) return null

        // Check that all points lie predominantly on one side of the chord (single-arch curve)
        val dx = last.x - first.x
        val dy = last.y - first.y
        var posCount = 0
        var negCount = 0
        var maxDist = 0f
        var peakPoint = pts[pts.size / 2]

        for (i in 1 until pts.lastIndex) {
            val p = pts[i]
            val cross = (p.x - first.x) * dy - (p.y - first.y) * dx
            val dist = distanceToSegment(p, first, last)
            if (dist > 3f * density) {
                if (cross > 0f) posCount++ else negCount++
            }
            if (dist > maxDist) {
                maxDist = dist
                peakPoint = p
            }
        }
        if (maxDist < max(14f * density, endGap * 0.14f) || maxDist > endGap * 0.85f) return null
        val totalSigned = posCount + negCount
        if (totalSigned < 4) return null
        val dominance = max(posCount, negCount).toFloat() / totalSigned
        if (dominance < 0.9f) return null

        // Compute quadratic Bezier control point so curve passes through peakPoint at t = 0.5
        val midX = (first.x + last.x) * 0.5f
        val midY = (first.y + last.y) * 0.5f
        val control = PointF(2f * peakPoint.x - midX, 2f * peakPoint.y - midY)

        // Verify fit error against quadratic arc
        val candidate = RecognizedShape.Arc(first, control, last)
        val arcSamples = candidate.samplePoints(max(4f * density, 4f))
        var errSum = 0f
        for (p in pts) {
            var best = Float.POSITIVE_INFINITY
            for (s in arcSamples) {
                val d = hypot(p.x - s.x, p.y - s.y)
                if (d < best) best = d
            }
            errSum += best
        }
        if (errSum / pts.size > max(8f * density, endGap * 0.045f)) return null
        return candidate
    }

    private fun resampleEquidistant(
        rawPoints: List<PointF>,
        step: Float,
        maxPoints: Int
    ): List<PointF> {
        var totalLen = 0f
        for (i in 1 until rawPoints.size) {
            totalLen += hypot(rawPoints[i].x - rawPoints[i - 1].x, rawPoints[i].y - rawPoints[i - 1].y)
        }
        if (totalLen <= step) return rawPoints
        val actualStep = max(step, totalLen / maxPoints)
        val out = ArrayList<PointF>(maxPoints + 2)
        out.add(PointF(rawPoints.first().x, rawPoints.first().y))
        var acc = 0f
        var prev = rawPoints.first()

        for (i in 1 until rawPoints.size) {
            val cur = rawPoints[i]
            var segLen = hypot(cur.x - prev.x, cur.y - prev.y)
            if (segLen < 1e-3f) continue
            while (acc + segLen >= actualStep) {
                val need = actualStep - acc
                val t = need / segLen
                val nx = prev.x + (cur.x - prev.x) * t
                val ny = prev.y + (cur.y - prev.y) * t
                prev = PointF(nx, ny)
                out.add(prev)
                segLen = hypot(cur.x - prev.x, cur.y - prev.y)
                acc = 0f
            }
            acc += segLen
            prev = cur
        }
        val last = rawPoints.last()
        if (hypot(out.last().x - last.x, out.last().y - last.y) > actualStep * 0.25f) {
            out.add(PointF(last.x, last.y))
        }
        return out
    }

    private fun pruneCollinearClosedCorners(
        corners: List<PointF>,
        minTurnDegrees: Double,
        minEdgeLen: Float
    ): List<PointF> {
        if (corners.size <= 3) return corners
        var current = corners.toMutableList()

        // First merge corners that are too close
        var i = 0
        while (i < current.size && current.size > 3) {
            val a = current[i]
            val b = current[(i + 1) % current.size]
            if (hypot(b.x - a.x, b.y - a.y) < minEdgeLen) {
                current.removeAt((i + 1) % current.size)
            } else {
                i++
            }
        }

        // Next remove vertices where turning angle is < minTurnDegrees
        var changed = true
        while (changed && current.size > 3) {
            changed = false
            for (idx in current.indices) {
                val prev = current[(idx - 1 + current.size) % current.size]
                val cur = current[idx]
                val next = current[(idx + 1) % current.size]
                val turn = turningAngleDegrees(prev, cur, next)
                if (turn < minTurnDegrees) {
                    current.removeAt(idx)
                    changed = true
                    break
                }
            }
        }
        return current
    }

    private fun pruneCollinearOpenCorners(
        corners: List<PointF>,
        minTurnDegrees: Double,
        minEdgeLen: Float
    ): List<PointF> {
        if (corners.size <= 2) return corners
        val current = corners.toMutableList()
        var i = 1
        while (i < current.lastIndex) {
            val prev = current[i - 1]
            val cur = current[i]
            val next = current[i + 1]
            val len1 = hypot(cur.x - prev.x, cur.y - prev.y)
            val len2 = hypot(next.x - cur.x, next.y - cur.y)
            val turn = turningAngleDegrees(prev, cur, next)
            if (len1 < minEdgeLen || len2 < minEdgeLen || turn < minTurnDegrees) {
                current.removeAt(i)
            } else {
                i++
            }
        }
        return current
    }

    private fun turningAngleDegrees(prev: PointF, cur: PointF, next: PointF): Double {
        val ux = (cur.x - prev.x).toDouble()
        val uy = (cur.y - prev.y).toDouble()
        val vx = (next.x - cur.x).toDouble()
        val vy = (next.y - cur.y).toDouble()
        val lenU = hypot(ux, uy)
        val lenV = hypot(vx, vy)
        if (lenU < 1e-4 || lenV < 1e-4) return 0.0
        val cosTheta = ((ux * vx + uy * vy) / (lenU * lenV)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosTheta))
    }

    private fun meanDistanceToClosedPolygon(points: List<PointF>, corners: List<PointF>): Float {
        var sum = 0f
        for (p in points) {
            var best = Float.POSITIVE_INFINITY
            for (i in corners.indices) {
                val a = corners[i]
                val b = corners[(i + 1) % corners.size]
                val d = distanceToSegment(p, a, b)
                if (d < best) best = d
            }
            sum += best
        }
        return sum / points.size
    }

    private fun regularizeTriangle(corners: List<PointF>): List<PointF> {
        if (corners.size != 3) return corners
        val out = corners.map { PointF(it.x, it.y) }.toMutableList()
        // If any edge is within 7 degrees of horizontal or vertical, level that edge
        for (i in 0..2) {
            val a = out[i]
            val b = out[(i + 1) % 3]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
            if (abs(deg) <= 7.0 || abs(abs(deg) - 180.0) <= 7.0) {
                val avgY = (a.y + b.y) * 0.5f
                a.y = avgY
                b.y = avgY
            } else if (abs(abs(deg) - 90.0) <= 7.0) {
                val avgX = (a.x + b.x) * 0.5f
                a.x = avgX
                b.x = avgX
            }
        }
        return out
    }

    private fun maybeRegularizePolygon(
        corners: List<PointF>,
        cx: Float,
        cy: Float,
        n: Int
    ): List<PointF> {
        val radii = corners.map { hypot(it.x - cx, it.y - cy) }
        val meanR = radii.average().toFloat()
        val maxRelDev = radii.maxOf { abs(it - meanR) / meanR }
        if (maxRelDev > 0.22f) return corners

        // Regularize around first vertex angle
        val startAngle = atan2((corners[0].y - cy).toDouble(), (corners[0].x - cx).toDouble())
        // Determine winding direction
        val cross = (corners[0].x - cx) * (corners[1].y - cy) - (corners[0].y - cy) * (corners[1].x - cx)
        val sign = if (cross >= 0f) 1.0 else -1.0
        return List(n) { i ->
            val angle = startAngle + sign * (2.0 * PI * i) / n
            PointF(
                cx + (meanR * cos(angle)).toFloat(),
                cy + (meanR * sin(angle)).toFloat()
            )
        }
    }

    private fun isAlternatingStar(corners: List<PointF>, cx: Float, cy: Float): Boolean {
        val radii = corners.map { hypot(it.x - cx, it.y - cy) }
        val evenAvg = radii.filterIndexed { idx, _ -> idx % 2 == 0 }.average().toFloat()
        val oddAvg = radii.filterIndexed { idx, _ -> idx % 2 == 1 }.average().toFloat()
        val outer = max(evenAvg, oddAvg)
        val inner = min(evenAvg, oddAvg)
        return outer > 1f && inner / outer in 0.22f..0.72f
    }

    private fun buildRegularStar(cx: Float, cy: Float, pointsCount: Int, outerR: Float): List<PointF> {
        val innerR = outerR * 0.42f
        val total = pointsCount * 2
        val startAngle = -PI / 2.0
        return List(total) { i ->
            val r = if (i % 2 == 0) outerR else innerR
            val angle = startAngle + (PI * i) / pointsCount
            PointF(
                cx + (r * cos(angle)).toFloat(),
                cy + (r * sin(angle)).toFloat()
            )
        }
    }

    private fun areEdgesParallel(a1: PointF, a2: PointF, b1: PointF, b2: PointF, toleranceDeg: Double): Boolean {
        val angA = Math.toDegrees(atan2((a2.y - a1.y).toDouble(), (a2.x - a1.x).toDouble()))
        val angB = Math.toDegrees(atan2((b2.y - b1.y).toDouble(), (b2.x - b1.x).toDouble()))
        var diff = abs(angA - angB) % 180.0
        if (diff > 90.0) diff = 180.0 - diff
        return diff <= toleranceDeg
    }

    private fun snapNearAxisEdgesInPolygon(corners: List<PointF>): List<PointF> {
        val out = corners.map { PointF(it.x, it.y) }
        for (i in out.indices) {
            val a = out[i]
            val b = out[(i + 1) % out.size]
            val deg = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()))
            if (abs(deg) <= 7.0 || abs(abs(deg) - 180.0) <= 7.0) {
                val avgY = (a.y + b.y) * 0.5f
                a.y = avgY
                b.y = avgY
            } else if (abs(abs(deg) - 90.0) <= 7.0) {
                val avgX = (a.x + b.x) * 0.5f
                a.x = avgX
                b.x = avgX
            }
        }
        return out
    }

    private fun snapLineEndpoints(a: PointF, b: PointF): Pair<PointF, PointF> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        val len = hypot(dx, dy)
        val snapAngles = doubleArrayOf(
            0.0, 30.0, 45.0, 60.0, 90.0, 120.0, 135.0, 150.0, 180.0,
            -30.0, -45.0, -60.0, -90.0, -120.0, -135.0, -150.0, -180.0
        )
        for (target in snapAngles) {
            if (abs(angleDeg - target) <= 5.5) {
                val rad = Math.toRadians(target)
                return a to PointF(
                    a.x + (len * cos(rad)).toFloat(),
                    a.y + (len * sin(rad)).toFloat()
                )
            }
        }
        return a to b
    }

    private fun maxDistanceToSegment(points: List<PointF>, a: PointF, b: PointF): Float {
        var maxD = 0f
        for (p in points) {
            val d = distanceToSegment(p, a, b)
            if (d > maxD) maxD = d
        }
        return maxD
    }

    private fun distanceToSegment(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
        val px = a.x + t * dx
        val py = a.y + t * dy
        return hypot(p.x - px, p.y - py)
    }

    private fun rdp(points: List<PointF>, epsilon: Float): List<PointF> {
        if (points.size < 3) return points
        var maxDist = 0f
        var index = 0
        val start = points.first()
        val end = points.last()
        for (i in 1 until points.lastIndex) {
            val d = distanceToSegment(points[i], start, end)
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }
        return if (maxDist > epsilon) {
            val left = rdp(points.subList(0, index + 1), epsilon)
            val right = rdp(points.subList(index, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(start, end)
        }
    }

    private fun removeDuplicateClosingPoint(points: List<PointF>, tolerance: Float): List<PointF> {
        if (points.size <= 1) return points
        val out = points.toMutableList()
        if (hypot(out.last().x - out.first().x, out.last().y - out.first().y) <= tolerance) {
            out.removeAt(out.lastIndex)
        }
        return out
    }
}
