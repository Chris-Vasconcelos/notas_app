package mx.spen.lab

import android.graphics.PointF
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * Pure JVM / Android serializable items and binary codec with length-prefixed CRC32 records
 * and atomic persistence (`notes/<uuid>/note.json` + `content.bin` + `content.bak`).
 */
sealed interface PersistedItem {
    val id: Long
    val layerId: Int

    data class InkRecord(
        override val id: Long,
        override val layerId: Int,
        val data: StrokeData
    ) : PersistedItem

    data class ShapeRecord(
        override val id: Long,
        override val layerId: Int,
        val shape: RecognizedShape,
        val color: Int,
        val fillColor: Int,
        val strokeWidthWorld: Float,
        val isHighlighter: Boolean
    ) : PersistedItem

    data class TextRecord(
        override val id: Long,
        override val layerId: Int,
        val x: Float,
        val y: Float,
        val text: String,
        val color: Int,
        val bgColor: Int,
        val fontSizeWorld: Float,
        val isBold: Boolean,
        val isItalic: Boolean,
        val isUnderline: Boolean
    ) : PersistedItem
}

data class NoteMetadata(
    val uuid: String = "default-notebook",
    val title: String = "Cuaderno S Pen Lab",
    val layoutMode: CanvasLayoutMode = CanvasLayoutMode.PAGED,
    val pageCount: Int = 3,
    val autoAddPage: Boolean = true,
    val paperStyle: PaperStyle = PaperStyle.LIGHT,
    val paperTemplate: PaperTemplate = PaperTemplate.GRID,
    val templateSpacingDp: Float = 22f,
    val templateOpacity: Float = 1.0f,
    val viewScale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val activeLayerId: Int = 1,
    val layers: List<NoteLayer> = listOf(NoteLayer(id = 1, name = "Capa 1")),
    val outlines: List<OutlineEntry> = emptyList(),
    val comments: List<CommentEntry> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

object NoteCodec {
    private const val MAGIC = 0x5350454E // "SPEN"
    private const val VERSION: Short = 1

    private const val REC_INK = 1
    private const val REC_SHAPE = 2
    private const val REC_TEXT = 3

    private const val SHAPE_LINE = 1
    private const val SHAPE_WAVY = 2
    private const val SHAPE_ARROW = 3
    private const val SHAPE_AXES = 4
    private const val SHAPE_3D = 5
    private const val SHAPE_RECT = 6
    private const val SHAPE_TRIANGLE = 7
    private const val SHAPE_ELLIPSE = 8
    private const val SHAPE_POLYGON = 9
    private const val SHAPE_POLYLINE = 10
    private const val SHAPE_ARC = 11

    fun encodeItems(items: List<PersistedItem>): ByteArray {
        val out = ByteArrayOutputStream(1024)
        val dos = DataOutputStream(out)
        dos.writeInt(MAGIC)
        dos.writeShort(VERSION.toInt())
        dos.writeInt(items.size)

        val crc = CRC32()
        for (item in items) {
            val payload = encodeSingleRecord(item)
            crc.reset()
            crc.update(payload)
            dos.writeInt(payload.size)
            dos.writeLong(crc.value)
            dos.write(payload)
        }
        dos.flush()
        return out.toByteArray()
    }

    fun decodeItems(bytes: ByteArray): List<PersistedItem> {
        if (bytes.size < 10) return emptyList()
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        if (magic != MAGIC) {
            throw IllegalArgumentException("Invalid magic header: 0x${Integer.toHexString(magic)}")
        }
        val version = dis.readShort()
        if (version < 1) {
            throw IllegalArgumentException("Unsupported codec version: $version")
        }
        val count = dis.readInt().coerceIn(0, 200_000)
        val result = ArrayList<PersistedItem>(count)
        val crc = CRC32()

        for (i in 0 until count) {
            if (dis.available() < 12) break
            val payloadLen = dis.readInt()
            val expectedCrc = dis.readLong()
            if (payloadLen <= 0 || payloadLen > dis.available()) break
            val payload = ByteArray(payloadLen)
            dis.readFully(payload)
            crc.reset()
            crc.update(payload)
            if (crc.value != expectedCrc) {
                continue
            }
            decodeSingleRecord(payload)?.let { result.add(it) }
        }
        return result
    }

    private fun encodeSingleRecord(item: PersistedItem): ByteArray {
        val baos = ByteArrayOutputStream(256)
        val out = DataOutputStream(baos)
        when (item) {
            is PersistedItem.InkRecord -> {
                out.writeByte(REC_INK)
                out.writeLong(item.id)
                out.writeInt(item.layerId)
                val d = item.data
                out.writeInt(d.brushType.code)
                out.writeInt(d.color)
                out.writeFloat(d.sizeWorld)
                out.writeFloat(d.epsilonWorld)
                out.writeBoolean(d.isHighlighter)
                out.writeInt(d.toolTypeInt)
                out.writeInt(d.noiseSeed)
                out.writeInt(d.points.size)
                for (pt in d.points) {
                    out.writeFloat(pt.x)
                    out.writeFloat(pt.y)
                    out.writeLong(pt.elapsedTimeMillis)
                    out.writeFloat(pt.pressure)
                    out.writeFloat(pt.tiltRadians)
                    out.writeFloat(pt.orientationRadians)
                    out.writeFloat(pt.strokeUnitLengthCm)
                }
            }
            is PersistedItem.ShapeRecord -> {
                out.writeByte(REC_SHAPE)
                out.writeLong(item.id)
                out.writeInt(item.layerId)
                out.writeInt(item.color)
                out.writeInt(item.fillColor)
                out.writeFloat(item.strokeWidthWorld)
                out.writeBoolean(item.isHighlighter)
                writeShape(out, item.shape)
            }
            is PersistedItem.TextRecord -> {
                out.writeByte(REC_TEXT)
                out.writeLong(item.id)
                out.writeInt(item.layerId)
                out.writeFloat(item.x)
                out.writeFloat(item.y)
                out.writeInt(item.color)
                out.writeInt(item.bgColor)
                out.writeFloat(item.fontSizeWorld)
                out.writeBoolean(item.isBold)
                out.writeBoolean(item.isItalic)
                out.writeBoolean(item.isUnderline)
                writeString(out, item.text)
            }
        }
        out.flush()
        return baos.toByteArray()
    }

    private fun decodeSingleRecord(payload: ByteArray): PersistedItem? = runCatching {
        val inp = DataInputStream(ByteArrayInputStream(payload))
        val type = inp.readUnsignedByte()
        val id = inp.readLong()
        val layerId = inp.readInt()
        when (type) {
            REC_INK -> {
                val brushType = BrushType.fromCode(inp.readInt())
                val color = inp.readInt()
                val sizeWorld = inp.readFloat()
                val epsilonWorld = inp.readFloat()
                val isHighlighter = inp.readBoolean()
                val toolTypeInt = inp.readInt()
                val noiseSeed = inp.readInt()
                val ptCount = inp.readInt().coerceIn(0, 100_000)
                val pts = ArrayList<StrokePoint>(ptCount)
                for (i in 0 until ptCount) {
                    pts.add(
                        StrokePoint(
                            x = inp.readFloat(),
                            y = inp.readFloat(),
                            elapsedTimeMillis = inp.readLong(),
                            pressure = inp.readFloat(),
                            tiltRadians = inp.readFloat(),
                            orientationRadians = inp.readFloat(),
                            strokeUnitLengthCm = inp.readFloat()
                        )
                    )
                }
                PersistedItem.InkRecord(
                    id = id,
                    layerId = layerId,
                    data = StrokeData(
                        brushType = brushType,
                        color = color,
                        sizeWorld = sizeWorld,
                        epsilonWorld = epsilonWorld,
                        isHighlighter = isHighlighter,
                        toolTypeInt = toolTypeInt,
                        noiseSeed = noiseSeed,
                        points = pts
                    )
                )
            }
            REC_SHAPE -> {
                val color = inp.readInt()
                val fillColor = inp.readInt()
                val strokeWidthWorld = inp.readFloat()
                val isHighlighter = inp.readBoolean()
                val shape = readShape(inp) ?: return@runCatching null
                PersistedItem.ShapeRecord(
                    id = id,
                    layerId = layerId,
                    shape = shape,
                    color = color,
                    fillColor = fillColor,
                    strokeWidthWorld = strokeWidthWorld,
                    isHighlighter = isHighlighter
                )
            }
            REC_TEXT -> {
                val x = inp.readFloat()
                val y = inp.readFloat()
                val color = inp.readInt()
                val bgColor = inp.readInt()
                val fontSizeWorld = inp.readFloat()
                val isBold = inp.readBoolean()
                val isItalic = inp.readBoolean()
                val isUnderline = inp.readBoolean()
                val text = readString(inp)
                PersistedItem.TextRecord(
                    id = id,
                    layerId = layerId,
                    x = x,
                    y = y,
                    text = text,
                    color = color,
                    bgColor = bgColor,
                    fontSizeWorld = fontSizeWorld,
                    isBold = isBold,
                    isItalic = isItalic,
                    isUnderline = isUnderline
                )
            }
            else -> null
        }
    }.getOrNull()

    private fun writeShape(out: DataOutputStream, shape: RecognizedShape) {
        when (shape) {
            is RecognizedShape.Line -> {
                out.writeByte(SHAPE_LINE)
                writePt(out, shape.start)
                writePt(out, shape.end)
            }
            is RecognizedShape.WavyLine -> {
                out.writeByte(SHAPE_WAVY)
                writePt(out, shape.start)
                writePt(out, shape.end)
                out.writeFloat(shape.amplitude)
                out.writeFloat(shape.wavelength)
            }
            is RecognizedShape.Arrow -> {
                out.writeByte(SHAPE_ARROW)
                writePt(out, shape.start)
                writePt(out, shape.end)
                writePt(out, shape.wingLeft)
                writePt(out, shape.wingRight)
            }
            is RecognizedShape.CoordinateAxes -> {
                out.writeByte(SHAPE_AXES)
                out.writeFloat(shape.left)
                out.writeFloat(shape.top)
                out.writeFloat(shape.right)
                out.writeFloat(shape.bottom)
            }
            is RecognizedShape.Shape3D -> {
                out.writeByte(SHAPE_3D)
                out.writeInt(shape.kind.code)
                out.writeFloat(shape.left)
                out.writeFloat(shape.top)
                out.writeFloat(shape.right)
                out.writeFloat(shape.bottom)
            }
            is RecognizedShape.Rectangle -> {
                out.writeByte(SHAPE_RECT)
                out.writeFloat(shape.left)
                out.writeFloat(shape.top)
                out.writeFloat(shape.right)
                out.writeFloat(shape.bottom)
                out.writeBoolean(shape.isSquare)
            }
            is RecognizedShape.Triangle -> {
                out.writeByte(SHAPE_TRIANGLE)
                writePt(out, shape.a)
                writePt(out, shape.b)
                writePt(out, shape.c)
            }
            is RecognizedShape.Ellipse -> {
                out.writeByte(SHAPE_ELLIPSE)
                out.writeFloat(shape.cx)
                out.writeFloat(shape.cy)
                out.writeFloat(shape.rx)
                out.writeFloat(shape.ry)
                out.writeBoolean(shape.isCircle)
            }
            is RecognizedShape.Polygon -> {
                out.writeByte(SHAPE_POLYGON)
                writeString(out, shape.shapeLabel)
                out.writeInt(shape.vertices.size)
                for (v in shape.vertices) writePt(out, v)
            }
            is RecognizedShape.Polyline -> {
                out.writeByte(SHAPE_POLYLINE)
                writeString(out, shape.shapeLabel)
                out.writeInt(shape.vertices.size)
                for (v in shape.vertices) writePt(out, v)
            }
            is RecognizedShape.Arc -> {
                out.writeByte(SHAPE_ARC)
                writePt(out, shape.start)
                writePt(out, shape.control)
                writePt(out, shape.end)
            }
        }
    }

    private fun readShape(inp: DataInputStream): RecognizedShape? = when (inp.readUnsignedByte()) {
        SHAPE_LINE -> RecognizedShape.Line(readPt(inp), readPt(inp))
        SHAPE_WAVY -> RecognizedShape.WavyLine(
            start = readPt(inp),
            end = readPt(inp),
            amplitude = inp.readFloat(),
            wavelength = inp.readFloat()
        )
        SHAPE_ARROW -> RecognizedShape.Arrow(
            start = readPt(inp),
            end = readPt(inp),
            wingLeft = readPt(inp),
            wingRight = readPt(inp)
        )
        SHAPE_AXES -> RecognizedShape.CoordinateAxes(
            left = inp.readFloat(),
            top = inp.readFloat(),
            right = inp.readFloat(),
            bottom = inp.readFloat()
        )
        SHAPE_3D -> RecognizedShape.Shape3D(
            kind = DirectShapeKind.fromCode(inp.readInt()),
            left = inp.readFloat(),
            top = inp.readFloat(),
            right = inp.readFloat(),
            bottom = inp.readFloat()
        )
        SHAPE_RECT -> RecognizedShape.Rectangle(
            left = inp.readFloat(),
            top = inp.readFloat(),
            right = inp.readFloat(),
            bottom = inp.readFloat(),
            isSquare = inp.readBoolean()
        )
        SHAPE_TRIANGLE -> RecognizedShape.Triangle(readPt(inp), readPt(inp), readPt(inp))
        SHAPE_ELLIPSE -> RecognizedShape.Ellipse(
            cx = inp.readFloat(),
            cy = inp.readFloat(),
            rx = inp.readFloat(),
            ry = inp.readFloat(),
            isCircle = inp.readBoolean()
        )
        SHAPE_POLYGON -> {
            val label = readString(inp)
            val count = inp.readInt().coerceIn(0, 256)
            val verts = List(count) { readPt(inp) }
            RecognizedShape.Polygon(verts, label)
        }
        SHAPE_POLYLINE -> {
            val label = readString(inp)
            val count = inp.readInt().coerceIn(0, 256)
            val verts = List(count) { readPt(inp) }
            RecognizedShape.Polyline(verts, label)
        }
        SHAPE_ARC -> RecognizedShape.Arc(readPt(inp), readPt(inp), readPt(inp))
        else -> null
    }

    private fun writePt(out: DataOutputStream, p: PointF) {
        out.writeFloat(p.x)
        out.writeFloat(p.y)
    }

    private fun readPt(inp: DataInputStream): PointF {
        val px = inp.readFloat()
        val py = inp.readFloat()
        return PointF(px, py).apply {
            this.x = px
            this.y = py
        }
    }

    private fun writeString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(inp: DataInputStream): String {
        val len = inp.readInt().coerceIn(0, 1_000_000)
        val bytes = ByteArray(len)
        inp.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}

/**
 * Atomic file repository (`notes/<uuid>/note.json` + `content.bin` + `content.bak`).
 */
object NoteRepository {
    private const val TAG = "NoteRepository"

    fun noteDir(filesDir: File, uuid: String = "default-notebook"): File =
        File(File(filesDir, "notes"), uuid).apply { mkdirs() }

    fun saveAtomically(
        filesDir: File,
        metadata: NoteMetadata,
        items: List<PersistedItem>
    ): Boolean = runCatching {
        val dir = noteDir(filesDir, metadata.uuid)
        val binFile = File(dir, "content.bin")
        val tmpFile = File(dir, "content.tmp")
        val bakFile = File(dir, "content.bak")

        val encoded = NoteCodec.encodeItems(items)
        FileOutputStream(tmpFile).use { fos ->
            fos.write(encoded)
            fos.fd.sync()
        }
        if (binFile.exists()) {
            if (bakFile.exists()) bakFile.delete()
            binFile.renameTo(bakFile)
        }
        tmpFile.renameTo(binFile)

        val jsonFile = File(dir, "note.json")
        val jsonTmp = File(dir, "note.json.tmp")
        val jsonText = encodeMetadataJson(metadata)
        FileOutputStream(jsonTmp).use { fos ->
            fos.write(jsonText.toByteArray(StandardCharsets.UTF_8))
            fos.fd.sync()
        }
        if (jsonFile.exists()) jsonFile.delete()
        jsonTmp.renameTo(jsonFile)
        true
    }.onFailure { err ->
        Log.w(TAG, "saveAtomically failed: ${err.message}")
    }.getOrDefault(false)

    fun load(filesDir: File, uuid: String = "default-notebook"): Pair<NoteMetadata, List<PersistedItem>>? {
        val dir = File(File(filesDir, "notes"), uuid)
        if (!dir.exists()) return null

        val jsonFile = File(dir, "note.json")
        val meta = if (jsonFile.exists()) {
            runCatching { decodeMetadataJson(jsonFile.readText(StandardCharsets.UTF_8)) }.getOrNull()
        } else {
            null
        } ?: NoteMetadata(uuid = uuid)

        val binFile = File(dir, "content.bin")
        val bakFile = File(dir, "content.bak")
        val items = when {
            binFile.exists() -> runCatching { NoteCodec.decodeItems(binFile.readBytes()) }.getOrNull()
            else -> null
        } ?: when {
            bakFile.exists() -> runCatching { NoteCodec.decodeItems(bakFile.readBytes()) }.getOrNull()
            else -> null
        } ?: emptyList()

        return meta to items
    }

    private fun encodeMetadataJson(meta: NoteMetadata): String {
        val root = JSONObject()
        root.put("uuid", meta.uuid)
        root.put("title", meta.title)
        root.put("layoutMode", meta.layoutMode.name)
        root.put("pageCount", meta.pageCount)
        root.put("autoAddPage", meta.autoAddPage)
        root.put("paperStyle", meta.paperStyle.name)
        root.put("paperTemplate", meta.paperTemplate.name)
        root.put("templateSpacingDp", meta.templateSpacingDp.toDouble())
        root.put("templateOpacity", meta.templateOpacity.toDouble())
        root.put("viewScale", meta.viewScale.toDouble())
        root.put("panX", meta.panX.toDouble())
        root.put("panY", meta.panY.toDouble())
        root.put("activeLayerId", meta.activeLayerId)
        root.put("updatedAtMs", meta.updatedAtMs)

        val layersArr = JSONArray()
        for (l in meta.layers) {
            layersArr.put(
                JSONObject().apply {
                    put("id", l.id)
                    put("name", l.name)
                    put("visible", l.visible)
                    put("locked", l.locked)
                }
            )
        }
        root.put("layers", layersArr)

        val outlinesArr = JSONArray()
        for (o in meta.outlines) {
            outlinesArr.put(
                JSONObject().apply {
                    put("id", o.id)
                    put("title", o.title)
                    put("pageIndex", o.pageIndex)
                    put("worldX", o.worldX.toDouble())
                    put("worldY", o.worldY.toDouble())
                    put("indentLevel", o.indentLevel)
                }
            )
        }
        root.put("outlines", outlinesArr)

        val commentsArr = JSONArray()
        for (c in meta.comments) {
            commentsArr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("text", c.text)
                    put("pageIndex", c.pageIndex)
                    put("worldX", c.worldX.toDouble())
                    put("worldY", c.worldY.toDouble())
                    put("createdAtMs", c.createdAtMs)
                }
            )
        }
        root.put("comments", commentsArr)
        return root.toString(2)
    }

    private fun decodeMetadataJson(text: String): NoteMetadata {
        val root = JSONObject(text)
        val layoutMode = runCatching {
            CanvasLayoutMode.valueOf(root.optString("layoutMode", CanvasLayoutMode.PAGED.name))
        }.getOrDefault(CanvasLayoutMode.PAGED)
        val paperStyle = runCatching {
            PaperStyle.valueOf(root.optString("paperStyle", PaperStyle.LIGHT.name))
        }.getOrDefault(PaperStyle.LIGHT)
        val paperTemplate = runCatching {
            PaperTemplate.valueOf(root.optString("paperTemplate", PaperTemplate.GRID.name))
        }.getOrDefault(PaperTemplate.GRID)

        val layers = mutableListOf<NoteLayer>()
        val layersArr = root.optJSONArray("layers")
        if (layersArr != null) {
            for (i in 0 until layersArr.length()) {
                val obj = layersArr.getJSONObject(i)
                layers.add(
                    NoteLayer(
                        id = obj.optInt("id", i + 1),
                        name = obj.optString("name", "Capa ${i + 1}"),
                        visible = obj.optBoolean("visible", true),
                        locked = obj.optBoolean("locked", false)
                    )
                )
            }
        }
        if (layers.isEmpty()) layers.add(NoteLayer(1, "Capa 1"))

        val outlines = mutableListOf<OutlineEntry>()
        val outlinesArr = root.optJSONArray("outlines")
        if (outlinesArr != null) {
            for (i in 0 until outlinesArr.length()) {
                val obj = outlinesArr.getJSONObject(i)
                outlines.add(
                    OutlineEntry(
                        id = obj.optLong("id", i + 1L),
                        title = obj.optString("title", "Marcador"),
                        pageIndex = obj.optInt("pageIndex", 0),
                        worldX = obj.optDouble("worldX", 0.0).toFloat(),
                        worldY = obj.optDouble("worldY", 0.0).toFloat(),
                        indentLevel = obj.optInt("indentLevel", 0)
                    )
                )
            }
        }

        val comments = mutableListOf<CommentEntry>()
        val commentsArr = root.optJSONArray("comments")
        if (commentsArr != null) {
            for (i in 0 until commentsArr.length()) {
                val obj = commentsArr.getJSONObject(i)
                comments.add(
                    CommentEntry(
                        id = obj.optLong("id", i + 1L),
                        text = obj.optString("text", ""),
                        pageIndex = obj.optInt("pageIndex", 0),
                        worldX = obj.optDouble("worldX", 0.0).toFloat(),
                        worldY = obj.optDouble("worldY", 0.0).toFloat(),
                        createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
                    )
                )
            }
        }

        return NoteMetadata(
            uuid = root.optString("uuid", "default-notebook"),
            title = root.optString("title", "Cuaderno S Pen Lab"),
            layoutMode = layoutMode,
            pageCount = root.optInt("pageCount", 3).coerceIn(1, 500),
            autoAddPage = root.optBoolean("autoAddPage", true),
            paperStyle = paperStyle,
            paperTemplate = paperTemplate,
            templateSpacingDp = root.optDouble("templateSpacingDp", 22.0).toFloat(),
            templateOpacity = root.optDouble("templateOpacity", 1.0).toFloat(),
            viewScale = root.optDouble("viewScale", 1.0).toFloat().coerceIn(0.45f, 4.0f),
            panX = root.optDouble("panX", 0.0).toFloat(),
            panY = root.optDouble("panY", 0.0).toFloat(),
            activeLayerId = root.optInt("activeLayerId", layers.first().id),
            layers = layers,
            outlines = outlines,
            comments = comments,
            updatedAtMs = root.optLong("updatedAtMs", System.currentTimeMillis())
        )
    }
}
