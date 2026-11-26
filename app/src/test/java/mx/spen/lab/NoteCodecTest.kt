package mx.spen.lab

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteCodecTest {

    private fun pt(x: Float, y: Float): PointF = PointF(x, y).apply {
        this.x = x
        this.y = y
    }

    @Test
    fun roundTrip_inkShapesAndText_preservesAllFields() {
        val strokeData = StrokeData(
            brushType = BrushType.PEN,
            color = 0xFF182E50.toInt(),
            sizeWorld = 4.2f,
            epsilonWorld = 0.25f,
            isHighlighter = false,
            toolTypeInt = 1,
            noiseSeed = 42,
            points = listOf(
                StrokePoint(
                    x = 12.5f,
                    y = 34.75f,
                    elapsedTimeMillis = 10L,
                    pressure = 0.65f,
                    tiltRadians = 0.3f,
                    orientationRadians = 1.1f,
                    strokeUnitLengthCm = 0.08f
                ),
                StrokePoint(
                    x = 28.0f,
                    y = 64.25f,
                    elapsedTimeMillis = 25L,
                    pressure = 0.80f,
                    tiltRadians = 0.35f,
                    orientationRadians = 1.2f,
                    strokeUnitLengthCm = 0.08f
                )
            )
        )

        val original = listOf(
            PersistedItem.InkRecord(id = 101L, layerId = 1, data = strokeData),
            PersistedItem.ShapeRecord(
                id = 102L,
                layerId = 2,
                shape = RecognizedShape.Shape3D(
                    kind = DirectShapeKind.CUBE,
                    left = 50f,
                    top = 60f,
                    right = 180f,
                    bottom = 200f
                ),
                color = 0xFF1E58CC.toInt(),
                fillColor = 0x331E58CC,
                strokeWidthWorld = 3.5f,
                isHighlighter = false
            ),
            PersistedItem.ShapeRecord(
                id = 103L,
                layerId = 1,
                shape = RecognizedShape.Polygon(
                    vertices = listOf(pt(10f, 20f), pt(40f, 20f), pt(25f, 55f)),
                    shapeLabel = "Estrella"
                ),
                color = 0xFFB9324B.toInt(),
                fillColor = 0,
                strokeWidthWorld = 4.0f,
                isHighlighter = false
            ),
            PersistedItem.TextRecord(
                id = 104L,
                layerId = 1,
                x = 120f,
                y = 240f,
                text = "Física: E = mc²",
                color = 0xFF1A2230.toInt(),
                bgColor = 0x222AA1B7,
                fontSizeWorld = 24f,
                isBold = true,
                isItalic = false,
                isUnderline = true
            )
        )

        val bytes = NoteCodec.encodeItems(original)
        assertTrue(bytes.isNotEmpty())

        val decoded = NoteCodec.decodeItems(bytes)
        assertEquals(4, decoded.size)

        val ink = decoded[0] as PersistedItem.InkRecord
        assertEquals(101L, ink.id)
        assertEquals(1, ink.layerId)
        assertEquals(BrushType.PEN, ink.data.brushType)
        assertEquals(42, ink.data.noiseSeed)
        assertEquals(2, ink.data.points.size)
        assertEquals(12.5f, ink.data.points[0].x, 1e-4f)
        assertEquals(0.80f, ink.data.points[1].pressure, 1e-4f)

        val cube = decoded[1] as PersistedItem.ShapeRecord
        assertEquals(102L, cube.id)
        assertEquals(2, cube.layerId)
        val shape3D = cube.shape as RecognizedShape.Shape3D
        assertEquals(DirectShapeKind.CUBE, shape3D.kind)
        assertEquals(180f, shape3D.right, 1e-4f)

        val poly = decoded[2] as PersistedItem.ShapeRecord
        val polyShape = poly.shape as RecognizedShape.Polygon
        assertEquals("Estrella", polyShape.shapeLabel)
        assertEquals(3, polyShape.vertices.size)
        assertEquals(40f, polyShape.vertices[1].x, 1e-4f)

        val txt = decoded[3] as PersistedItem.TextRecord
        assertEquals("Física: E = mc²", txt.text)
        assertTrue(txt.isBold)
        assertTrue(txt.isUnderline)
    }

    @Test
    fun decodeItems_skipsCorruptedRecordViaCrc32_andRecoversRemaining() {
        val rec1 = PersistedItem.TextRecord(
            id = 1L,
            layerId = 1,
            x = 10f,
            y = 10f,
            text = "Registro 1 intacto",
            color = -1,
            bgColor = 0,
            fontSizeWorld = 18f,
            isBold = false,
            isItalic = false,
            isUnderline = false
        )
        val rec2 = rec1.copy(id = 2L, text = "Registro 2 intacto")
        val bytes = NoteCodec.encodeItems(listOf(rec1, rec2))

        // Flip a byte inside the first record's payload (after 10-byte header + 12-byte record prefix)
        bytes[25] = (bytes[25].toInt() xor 0xFF).toByte()

        val recovered = NoteCodec.decodeItems(bytes)
        assertEquals(1, recovered.size)
        assertEquals(2L, recovered[0].id)
    }
}
