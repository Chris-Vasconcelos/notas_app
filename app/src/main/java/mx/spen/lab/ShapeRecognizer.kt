package mx.spen.lab

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
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
        if (rawPoints.size < 6) return null
        val minSize = 22f * density

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var pathLen = 0f

        for (i in rawPoints.indices) {
            val p = rawPoints[i]
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
            if (i > 0) {
                pathLen += hypot(p.x - rawPoints[i - 1].x, p.y - rawPoints[i - 1].y)
            }
        }

        val boxW = maxX - minX
        val boxH = maxY - minY
        val diag = hypot(boxW, boxH)
        if (pathLen < minSize || diag < minSize * 0.7f) return null

        val first = rawPoints.first()
        val last = rawPoints.last()
        val endGap = hypot(last.x - first.x, last.y - first.y)

        // 1. Straight line check (open stroke with low deviation from chord)
        if (endGap > minSize && pathLen < endGap * 1.18f) {
            val maxDev = maxDistanceToSegment(rawPoints, first, last)
            if (maxDev <= max(10f * density, endGap * 0.08f)) {
                val snapped = snapLineEndpoints(first, last)
                return RecognizedShape.Line(snapped.first, snapped.second)
            }
        }

        // 2. Closed shapes (circle, ellipse, rectangle, triangle)
        val isClosed = endGap <= max(28f * density, diag * 0.28f) && pathLen > diag * 1.8f
        if (isClosed) {
            val cx = (minX + maxX) * 0.5f
            val cy = (minY + maxY) * 0.5f
            val rx = boxW * 0.5f
            val ry = boxH * 0.5f
            if (rx > 8f * density && ry > 8f * density) {
                var radialErrSum = 0f
                var maxRadialErr = 0f
                for (p in rawPoints) {
                    val nx = (p.x - cx) / rx
                    val ny = (p.y - cy) / ry
                    val r = hypot(nx, ny)
                    val err = abs(r - 1f)
                    radialErrSum += err
                    if (err > maxRadialErr) maxRadialErr = err
                }
                val meanRadialErr = radialErrSum / rawPoints.size
                if (meanRadialErr < 0.14f && maxRadialErr < 0.32f) {
                    val aspect = min(rx, ry) / max(rx, ry)
                    val isCircle = aspect >= 0.84f
                    val finalRx = if (isCircle) (rx + ry) * 0.5f else rx
                    val finalRy = if (isCircle) finalRx else ry
                    return RecognizedShape.Ellipse(cx, cy, finalRx, finalRy, isCircle)
                }
            }

            val closedPts = rawPoints + first
            val simplified = rdp(closedPts, max(10f * density, diag * 0.06f))
            val corners = removeDuplicateClosingPoint(simplified, 16f * density)

            if (corners.size == 3) {
                return RecognizedShape.Triangle(corners[0], corners[1], corners[2])
            }
            if (corners.size == 4) {
                val aspect = min(boxW, boxH) / max(boxW, boxH)
                val isSquare = aspect >= 0.86f
                val finalW = if (isSquare) (boxW + boxH) * 0.5f else boxW
                val finalH = if (isSquare) finalW else boxH
                val cxRect = (minX + maxX) * 0.5f
                val cyRect = (minY + maxY) * 0.5f
                return RecognizedShape.Rectangle(
                    left = cxRect - finalW * 0.5f,
                    top = cyRect - finalH * 0.5f,
                    right = cxRect + finalW * 0.5f,
                    bottom = cyRect + finalH * 0.5f,
                    isSquare = isSquare
                )
            }

            val boxPerimeter = 2f * (boxW + boxH)
            if (abs(pathLen - boxPerimeter) / boxPerimeter < 0.22f) {
                var edgeDistSum = 0f
                for (p in rawPoints) {
                    val dx = min(abs(p.x - minX), abs(p.x - maxX))
                    val dy = min(abs(p.y - minY), abs(p.y - maxY))
                    edgeDistSum += min(dx, dy)
                }
                if (edgeDistSum / rawPoints.size < max(12f * density, diag * 0.07f)) {
                    val aspect = min(boxW, boxH) / max(boxW, boxH)
                    val isSquare = aspect >= 0.86f
                    return RecognizedShape.Rectangle(minX, minY, maxX, maxY, isSquare)
                }
            }
        }

        // 3. Arrow heuristic (single-stroke shaft + tip hook)
        val simplifiedOpen = rdp(rawPoints, max(8f * density, diag * 0.05f))
        if (simplifiedOpen.size in 3..5) {
            val shaftStart = simplifiedOpen[0]
            val tip = simplifiedOpen[1]
            val shaftLen = hypot(tip.x - shaftStart.x, tip.y - shaftStart.y)
            val tailLen = hypot(last.x - tip.x, last.y - tip.y)
            if (shaftLen > minSize && tailLen in (10f * density)..(shaftLen * 0.55f)) {
                val shaftAngle = atan2(tip.y - shaftStart.y, tip.x - shaftStart.x)
                val headLen = max(14f * density, min(26f * density, shaftLen * 0.22f))
                val wingAngle = 0.52f
                val leftWing = PointF(
                    tip.x - headLen * cos(shaftAngle - wingAngle),
                    tip.y - headLen * sin(shaftAngle - wingAngle)
                )
                val rightWing = PointF(
                    tip.x - headLen * cos(shaftAngle + wingAngle),
                    tip.y - headLen * sin(shaftAngle + wingAngle)
                )
                return RecognizedShape.Arrow(shaftStart, tip, leftWing, rightWing)
            }
        }

        return null
    }

    private fun snapLineEndpoints(a: PointF, b: PointF): Pair<PointF, PointF> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        val len = hypot(dx, dy)
        val snapAngles = doubleArrayOf(0.0, 45.0, 90.0, 135.0, 180.0, -45.0, -90.0, -135.0, -180.0)
        for (target in snapAngles) {
            if (abs(angleDeg - target) <= 6.0) {
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
