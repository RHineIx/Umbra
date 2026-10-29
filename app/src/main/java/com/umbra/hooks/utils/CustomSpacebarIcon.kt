package com.umbra.hooks.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.util.Xml
import com.caverock.androidsvg.PreserveAspectRatio
import com.caverock.androidsvg.SVG
import com.umbra.hooks.R
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser

class IconImportException(val messageRes: Int) : IllegalArgumentException()

data class ImportedSpacebarIcon(val image: String, val tint: Boolean)

/** Import once in Umbra; the keyboard only decodes a small, self-contained PNG. */
object CustomSpacebarIcon {
    const val MAX_FILE_BYTES = 1024 * 1024
    private const val SIZE = 128
    private const val MAX_STORED_BYTES = 96 * 1024
    private val pngSignature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    fun importIcon(input: InputStream): ImportedSpacebarIcon {
        val bytes = input.readBytesLimited()
        if (bytes.isEmpty()) invalid()
        val (bitmap, tint) = if (isPng(bytes)) renderPng(bytes) to false else renderSvg(bytes)
        try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) invalid()
            val png = output.toByteArray()
            if (png.size > MAX_STORED_BYTES) throw IconImportException(R.string.spacebar_file_too_large)
            return ImportedSpacebarIcon(Base64.encodeToString(png, Base64.NO_WRAP), tint)
        } finally {
            bitmap.recycle()
        }
    }

    fun decodeStored(encoded: String?): Bitmap? {
        if (encoded.isNullOrEmpty() || encoded.length > (MAX_STORED_BYTES + 2) / 3 * 4) return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            if (bytes.size > MAX_STORED_BYTES || !isPng(bytes)) return null
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
            if (dimensions.outWidth !in 1..SIZE || dimensions.outHeight !in 1..SIZE) return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    private fun renderPng(bytes: ByteArray): Bitmap {
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
        if (dimensions.outWidth !in 1..8192 || dimensions.outHeight !in 1..8192) invalid()
        var sample = 1
        while (maxOf(dimensions.outWidth, dimensions.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: invalid()
        try {
            val output = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val scale = minOf(SIZE.toFloat() / source.width, SIZE.toFloat() / source.height)
            val width = source.width * scale
            val height = source.height * scale
            val left = (SIZE - width) / 2f
            val top = (SIZE - height) / 2f
            Canvas(output).drawBitmap(source, null, RectF(left, top, left + width, top + height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return output
        } finally {
            source.recycle()
        }
    }

    private fun renderSvg(bytes: ByteArray): Pair<Bitmap, Boolean> {
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> bytes.toString(Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> bytes.toString(Charsets.UTF_16BE)
            else -> bytes.toString(Charsets.UTF_8)
        }.removePrefix("\uFEFF").replace(Regex("^\\s*<\\?xml[^?]*\\?>", RegexOption.IGNORE_CASE), "")
        validateSvg(text)
        SVG.setInternalEntitiesEnabled(false)
        SVG.deregisterExternalFileResolver()
        val svg = try { SVG.getFromString(text) } catch (_: Exception) { invalid() }
        if (svg.documentViewBox == null) {
            val width = svg.documentWidth
            val height = svg.documentHeight
            if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) invalid()
            svg.setDocumentViewBox(0f, 0f, width, height)
        }
        val box = svg.documentViewBox ?: invalid()
        if (!box.width().isFinite() || !box.height().isFinite() || box.width() <= 0f || box.height() <= 0f) invalid()
        svg.setDocumentWidth(SIZE.toFloat())
        svg.setDocumentHeight(SIZE.toFloat())
        svg.documentPreserveAspectRatio = PreserveAspectRatio.LETTERBOX
        val output = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        try {
            svg.renderToCanvas(Canvas(output))
        } catch (_: Exception) {
            output.recycle()
            invalid()
        } catch (_: StackOverflowError) {
            output.recycle()
            invalid()
        }
        return output to text.contains("currentColor", ignoreCase = true)
    }

    private fun validateSvg(text: String) {
        if (Regex("<!\\s*(DOCTYPE|ENTITY)|<\\?xml-stylesheet|@import\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) invalid()
        try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(text.reader())
            }
            var rootSeen = false
            var elements = 0
            val parents = ArrayDeque<Int>()
            val children = mutableListOf<MutableList<Int>>()
            val ids = mutableMapOf<String, Int>()
            val references = mutableListOf<Pair<Int, String>>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    if (!rootSeen) {
                        if (parser.name != "svg") invalid()
                        rootSeen = true
                    }
                    if (++elements > 4096 || parser.name.lowercase() in setOf("script", "image", "foreignobject")) invalid()
                    val node = children.size
                    children.add(mutableListOf())
                    parents.lastOrNull()?.let { children[it].add(node) }
                    parents.addLast(node)
                    for (index in 0 until parser.attributeCount) {
                        val name = parser.getAttributeName(index).substringAfter(':')
                        val value = parser.getAttributeValue(index).trim()
                        if (name == "id" && (value.isEmpty() || ids.put(value, node) != null)) invalid()
                        if (name == "href") {
                            if (!value.startsWith('#')) invalid()
                            references.add(node to value.substring(1))
                        }
                        Regex("url\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE).findAll(value).forEach {
                            val target = it.groupValues[1].trim().trim('\'', '"')
                            if (!target.startsWith('#')) invalid()
                            references.add(node to target.substring(1))
                        }
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG) {
                    parents.removeLast()
                }
                parser.next()
            }
            if (!rootSeen) invalid()
            validateReferences(children, ids, references)
        } catch (e: IconImportException) {
            throw e
        } catch (_: Exception) {
            invalid()
        }
    }

    /** Check containment and local references before the SVG renderer can recurse into them. */
    private fun validateReferences(children: List<MutableList<Int>>, ids: Map<String, Int>, references: List<Pair<Int, String>>) {
        references.forEach { (source, id) -> ids[id]?.let { children[source].add(it) } }
        val incoming = IntArray(children.size)
        val depth = IntArray(children.size)
        children.forEach { targets -> targets.forEach { incoming[it]++ } }
        val pending = ArrayDeque<Int>()
        incoming.indices.filterTo(pending) { incoming[it] == 0 }
        var visited = 0
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (depth[node] > 64) invalid()
            visited++
            children[node].forEach { target ->
                depth[target] = maxOf(depth[target], depth[node] + 1)
                if (--incoming[target] == 0) pending.addLast(target)
            }
        }
        if (visited != children.size) invalid()
    }

    private fun InputStream.readBytesLimited(): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (result.size() + count > MAX_FILE_BYTES) throw IconImportException(R.string.spacebar_file_too_large)
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    private fun isPng(bytes: ByteArray): Boolean = bytes.size >= pngSignature.size && pngSignature.indices.all { bytes[it] == pngSignature[it] }
    private fun invalid(): Nothing = throw IconImportException(R.string.spacebar_file_invalid)
}
