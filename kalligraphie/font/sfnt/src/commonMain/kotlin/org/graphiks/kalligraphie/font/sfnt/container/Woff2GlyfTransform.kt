@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.KalligraphieInternalApi
import org.graphiks.kalligraphie.font.sfnt.readInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt32

/**
 * One reconstructed WOFF2 `glyf` transform result.
 *
 * @property glyf the reconstructed TrueType `glyf` table, each glyph record padded to an even
 * length.
 * @property loca the reconstructed `loca` table matching [indexFormat].
 * @property indexFormat the `loca` offset format (0 for short, 1 for long).
 * @property glyphXMins each glyph's `xMin` in glyph order. The WOFF2 `hmtx` transform reconstructs
 * side bearings from these values, so they are retained for that consumer.
 */
internal class GlyfReconstruction(
    val glyf: ByteArray,
    val loca: ByteArray,
    val indexFormat: Int,
    val glyphXMins: IntArray = IntArray(0),
) {
    /** Number of glyphs described by this reconstruction. */
    val numGlyphs: Int get() = glyphXMins.size

    /** Returns the `xMin` of [glyphIndex]. */
    fun glyphXMin(glyphIndex: Int): Int = glyphXMins[glyphIndex]
}

/**
 * Reconstructs the WOFF2 §5.1 transformed `glyf` table (and its paired `loca`).
 *
 * The transformed table is a 36-byte header (reserved, `optionFlags`, `numGlyphs`, `indexFormat`
 * and seven stream sizes) followed by the nContour, nPoints, flag, glyph, composite, bbox and
 * instruction streams and an optional overlap-simple bitmap. Glyphs reference those streams in
 * order and are re-emitted in the standard TrueType `glyf` encoding:
 *
 * - `nContour` 0 is an empty glyph (a repeated `loca` offset; an explicit bbox is invalid),
 *   positive is simple and `-1` is composite.
 * - A simple glyph reads one `255UInt16` point count per contour, turns their cumulative sum minus
 *   one into `endPtsOfContours`, decodes one flag byte and a 1/2/3/4-byte coordinate triplet per
 *   point (WOFF2 §5.2), then one `255UInt16` instruction length plus its bytes. WOFF2 flag bit 7 is
 *   the *inverted* on-curve bit, so it becomes TrueType flag bit 0.
 * - A composite glyph copies its component records (flags, glyph index, arguments, optional
 *   transform) from the composite stream verbatim and appends instructions when a component asks
 *   for them.
 * - The `bboxBitmap` is `4 * floor((numGlyphs + 31) / 32)` bytes, MSB-first; an explicit bbox is
 *   read from the bbox stream when the glyph's bit is set, a simple glyph without its bit infers
 *   its bounds from all points, and a composite must always carry one.
 * - When `optionFlags` bit 0 is set, each simple glyph's `overlapSimpleBitmap` bit becomes
 *   `OVERLAP_SIMPLE` (bit 6) of that glyph's first output flag byte.
 *
 * Every stream size is charged against [WoffDecodeLimits.maxWorkingBytes], the reconstructed
 * `glyf`/`loca` are bounded by [WoffDecodeLimits.maxDecodedFontBytes], and any structural violation
 * is `font.woff2.transform-failed`.
 */
internal object Woff2GlyfTransform {
    private const val TRANSFORM_FAILED: String = "font.woff2.transform-failed"
    private const val HEADER_BYTES: Int = 36
    private const val STREAM_COUNT: Int = 7
    private const val OVERLAP_SIMPLE_OPTION: Int = 0x01
    private const val OVERLAP_SIMPLE_FLAG: Int = 0x40
    private const val INITIAL_CAPACITY: Int = 256

    /** Reconstructs [transformed], or returns a typed rejection. */
    fun reconstruct(
        transformed: ByteArray,
        limits: WoffDecodeLimits,
    ): FontOperationResult<GlyfReconstruction> {
        if (limits.maxDecodedFontBytes < 0L) {
            return limitExceeded("The WOFF2 decoded-size limit must be non-negative.")
        }
        if (limits.maxWorkingBytes < 0L) {
            return limitExceeded("The WOFF2 working limit must be non-negative.")
        }
        return try {
            FontOperationResult.Success(reconstruct(transformed, limits.maxWorkingBytes, limits.maxDecodedFontBytes))
        } catch (limit: WorkingLimitException) {
            limitExceeded(limit.message ?: "The WOFF2 glyf working limit was exceeded.")
        } catch (limit: DecodedLimitException) {
            limitExceeded(limit.message ?: "The WOFF2 glyf decoded limit was exceeded.")
        } catch (invalid: TransformException) {
            failure(invalid.message ?: "The WOFF2 glyf transform is malformed.")
        }
    }

    private fun reconstruct(
        transformed: ByteArray,
        maxWorkingBytes: Long,
        maxDecodedFontBytes: Long,
    ): GlyfReconstruction {
        if (transformed.size < HEADER_BYTES) {
            throw TransformException("The transformed WOFF2 glyf header is truncated.")
        }
        val reserved = readUInt16(transformed, 0)!!.toInt()
        val optionFlags = readUInt16(transformed, 2)!!.toInt()
        val numGlyphs = readUInt16(transformed, 4)!!.toInt()
        val indexFormat = readUInt16(transformed, 6)!!.toInt()
        if (reserved != 0) throw TransformException("The transformed WOFF2 glyf reserved field must be zero.")
        if (indexFormat != 0 && indexFormat != 1) {
            throw TransformException("The transformed WOFF2 glyf indexFormat $indexFormat is not 0 or 1.")
        }

        val sizes = LongArray(STREAM_COUNT)
        var running = 0L
        for (index in 0 until STREAM_COUNT) {
            val size = readUInt32(transformed, 8 + index * 4)!!.toLong()
            if (size > maxWorkingBytes) {
                throw WorkingLimitException(
                    "A transformed WOFF2 glyf stream of $size bytes exceeds the $maxWorkingBytes-byte working limit.",
                )
            }
            if (running > maxWorkingBytes - size) {
                throw WorkingLimitException(
                    "The transformed WOFF2 glyf streams exceed the $maxWorkingBytes-byte working limit.",
                )
            }
            running += size
            sizes[index] = size
        }
        if (sizes[0] != numGlyphs.toLong() * 2L) {
            throw TransformException("The transformed WOFF2 nContour stream does not match numGlyphs $numGlyphs.")
        }
        val overlapBytes = if (optionFlags and OVERLAP_SIMPLE_OPTION != 0) (numGlyphs + 7) / 8 else 0
        val expectedTotal = HEADER_BYTES.toLong() + running + overlapBytes.toLong()
        if (expectedTotal != transformed.size.toLong()) {
            throw TransformException(
                "The transformed WOFF2 glyf table is ${transformed.size} bytes but its header declares $expectedTotal.",
            )
        }

        var cursor = HEADER_BYTES
        val nContourStream = takeStream(transformed, cursor, sizes[0]); cursor += sizes[0].toInt()
        val nPointsStream = takeStream(transformed, cursor, sizes[1]); cursor += sizes[1].toInt()
        val flagStream = takeStream(transformed, cursor, sizes[2]); cursor += sizes[2].toInt()
        val glyphStream = takeStream(transformed, cursor, sizes[3]); cursor += sizes[3].toInt()
        val compositeStream = takeStream(transformed, cursor, sizes[4]); cursor += sizes[4].toInt()
        val bboxStream = takeStream(transformed, cursor, sizes[5]); cursor += sizes[5].toInt()
        val instructionStream = takeStream(transformed, cursor, sizes[6]); cursor += sizes[6].toInt()
        val overlapSimpleBitmap = if (overlapBytes > 0) {
            transformed.copyOfRange(cursor, cursor + overlapBytes)
        } else {
            null
        }

        val bboxBitmapSize = ((numGlyphs + 31) / 32) * 4
        if (bboxStream.size < bboxBitmapSize) {
            throw TransformException("The transformed WOFF2 bbox stream is too small for its bitmap.")
        }
        val bboxBitmap = bboxStream.copyOfRange(0, bboxBitmapSize)
        val bboxValues = Cursor(bboxStream, bboxBitmapSize)

        val nContours = Cursor(nContourStream)
        val nPoints = Cursor(nPointsStream)
        val flags = Cursor(flagStream)
        val glyph = Cursor(glyphStream)
        val composite = Cursor(compositeStream)
        val instructions = Cursor(instructionStream)

        val sink = ByteSink(maxDecodedFontBytes)
        val offsets = IntArray(numGlyphs + 1)
        val xMins = IntArray(numGlyphs)
        for (glyphIndex in 0 until numGlyphs) {
            offsets[glyphIndex] = sink.size
            val contourCount = nContours.i16()
            when {
                contourCount == 0 -> {
                    if (hasExplicitBBox(bboxBitmap, glyphIndex)) {
                        throw TransformException("The transformed WOFF2 empty glyph $glyphIndex has an explicit bounding box.")
                    }
                    xMins[glyphIndex] = 0
                }
                contourCount > 0 -> xMins[glyphIndex] = writeSimpleGlyph(
                    glyphIndex, contourCount, nPoints, flags, glyph, instructions, bboxBitmap, bboxValues,
                    overlapSimpleBitmap, sink,
                )
                contourCount == -1 -> xMins[glyphIndex] = writeCompositeGlyph(
                    glyphIndex, composite, glyph, instructions, bboxBitmap, bboxValues, sink,
                )
                else -> throw TransformException("The transformed WOFF2 glyph $glyphIndex has invalid contour count $contourCount.")
            }
            if (sink.size and 1 == 1) sink.u8(0)
        }
        offsets[numGlyphs] = sink.size

        val entrySize = if (indexFormat == 0) 2 else 4
        val locaSize = (numGlyphs + 1).toLong() * entrySize.toLong()
        if (locaSize > maxDecodedFontBytes) {
            throw DecodedLimitException("The reconstructed WOFF2 loca table exceeds the $maxDecodedFontBytes-byte limit.")
        }
        val loca = ByteArray(locaSize.toInt())
        for (index in 0..numGlyphs) {
            val offset = offsets[index]
            if (indexFormat == 0) {
                if (offset and 1 != 0) {
                    throw TransformException("A reconstructed WOFF2 glyph offset $offset is not representable in a short loca.")
                }
                val shortOffset = offset / 2
                if (shortOffset > 0xFFFF) {
                    throw TransformException("A reconstructed WOFF2 glyph offset $offset is not representable in a short loca.")
                }
                writeU16(loca, index * 2, shortOffset)
            } else {
                writeU32(loca, index * 4, offset.toUInt())
            }
        }
        return GlyfReconstruction(sink.toByteArray(), loca, indexFormat, xMins)
    }

    private fun writeSimpleGlyph(
        glyphIndex: Int,
        contourCount: Int,
        nPoints: Cursor,
        flags: Cursor,
        glyph: Cursor,
        instructions: Cursor,
        bboxBitmap: ByteArray,
        bboxValues: Cursor,
        overlapSimpleBitmap: ByteArray?,
        sink: ByteSink,
    ): Int {
        val endPtsOfContours = IntArray(contourCount)
        var totalPoints = 0
        for (contour in 0 until contourCount) {
            val points = nPoints.u255()
            if (points <= 0) throw TransformException("The transformed WOFF2 glyph $glyphIndex has an empty contour.")
            totalPoints += points
            endPtsOfContours[contour] = totalPoints - 1
        }

        val pointFlags = flags.take(totalPoints)
        val deltasX = IntArray(totalPoints)
        val deltasY = IntArray(totalPoints)
        val onCurve = BooleanArray(totalPoints)
        var x = 0
        var y = 0
        var minX = 0
        var minY = 0
        var maxX = 0
        var maxY = 0
        for (index in 0 until totalPoints) {
            val flag = pointFlags[index].toInt() and 0xFF
            onCurve[index] = flag and 0x80 == 0
            val code = flag and 0x7F
            val byteCount = when {
                code < 84 -> 1
                code < 120 -> 2
                code < 124 -> 3
                else -> 4
            }
            val triplet = glyph.take(byteCount)
            val deltas = decodeTriplet(code, triplet)
            val deltaX = (deltas ushr 32).toInt()
            val deltaY = deltas.toInt()
            x += deltaX
            y += deltaY
            deltasX[index] = deltaX
            deltasY[index] = deltaY
            if (index == 0) {
                minX = x; maxX = x; minY = y; maxY = y
            } else {
                if (x < minX) minX = x else if (x > maxX) maxX = x
                if (y < minY) minY = y else if (y > maxY) maxY = y
            }
        }

        val instructionLength = glyph.u255()
        val instructionBytes = instructions.take(instructionLength)

        val explicitBBox = hasExplicitBBox(bboxBitmap, glyphIndex)
        val xMin: Int
        val yMin: Int
        val xMax: Int
        val yMax: Int
        if (explicitBBox) {
            xMin = bboxValues.i16(); yMin = bboxValues.i16()
            xMax = bboxValues.i16(); yMax = bboxValues.i16()
        } else {
            xMin = minX; yMin = minY; xMax = maxX; yMax = maxY
        }

        sink.i16(contourCount)
        sink.i16(xMin)
        sink.i16(yMin)
        sink.i16(xMax)
        sink.i16(yMax)
        for (endPoint in endPtsOfContours) sink.u16(endPoint)
        sink.u16(instructionLength)
        sink.bytes(instructionBytes)

        val outputFlags = IntArray(totalPoints)
        for (index in 0 until totalPoints) {
            var flag = if (onCurve[index]) 0x01 else 0x00
            val deltaX = deltasX[index]
            val deltaY = deltasY[index]
            when {
                deltaX == 0 -> flag = flag or 0x10
                deltaX in -255..255 -> {
                    flag = flag or 0x02
                    if (deltaX > 0) flag = flag or 0x10
                }
            }
            when {
                deltaY == 0 -> flag = flag or 0x20
                deltaY in -255..255 -> {
                    flag = flag or 0x04
                    if (deltaY > 0) flag = flag or 0x20
                }
            }
            outputFlags[index] = flag
        }
        if (totalPoints > 0 && overlapSimpleBitmap != null && hasBit(overlapSimpleBitmap, glyphIndex)) {
            outputFlags[0] = outputFlags[0] or OVERLAP_SIMPLE_FLAG
        }

        for (flag in outputFlags) sink.u8(flag)
        for (deltaX in deltasX) {
            when {
                deltaX == 0 -> Unit
                deltaX in -255..255 -> sink.u8(if (deltaX > 0) deltaX else -deltaX)
                else -> sink.u16((((deltaX ushr 8) and 0xFF) shl 8) or (deltaX and 0xFF))
            }
        }
        for (deltaY in deltasY) {
            when {
                deltaY == 0 -> Unit
                deltaY in -255..255 -> sink.u8(if (deltaY > 0) deltaY else -deltaY)
                else -> sink.u16((((deltaY ushr 8) and 0xFF) shl 8) or (deltaY and 0xFF))
            }
        }
        return xMin
    }

    private fun writeCompositeGlyph(
        glyphIndex: Int,
        composite: Cursor,
        glyph: Cursor,
        instructions: Cursor,
        bboxBitmap: ByteArray,
        bboxValues: Cursor,
        sink: ByteSink,
    ): Int {
        if (!hasExplicitBBox(bboxBitmap, glyphIndex)) {
            throw TransformException("The transformed WOFF2 composite glyph $glyphIndex has no explicit bounding box.")
        }
        val xMin = bboxValues.i16()
        val yMin = bboxValues.i16()
        val xMax = bboxValues.i16()
        val yMax = bboxValues.i16()

        sink.i16(-1)
        sink.i16(xMin)
        sink.i16(yMin)
        sink.i16(xMax)
        sink.i16(yMax)

        var haveInstructions = false
        var more = true
        while (more) {
            val componentFlags = composite.u16()
            sink.u16(componentFlags)
            composite.copyTo(sink, 2) // glyph index
            val argumentBytes = if (componentFlags and 0x0001 != 0) 4 else 2
            composite.copyTo(sink, argumentBytes)
            when {
                componentFlags and 0x0008 != 0 -> composite.copyTo(sink, 2)
                componentFlags and 0x0040 != 0 -> composite.copyTo(sink, 4)
                componentFlags and 0x0080 != 0 -> composite.copyTo(sink, 8)
            }
            if (componentFlags and 0x0100 != 0) haveInstructions = true
            more = componentFlags and 0x0020 != 0
        }
        if (haveInstructions) {
            val instructionLength = glyph.u255()
            val instructionBytes = instructions.take(instructionLength)
            sink.u16(instructionLength)
            sink.bytes(instructionBytes)
        }
        return xMin
    }

    /**
     * Decodes one WOFF2 §5.2 triplet code plus its coordinate bytes into a packed `(deltaX, deltaY)`
     * pair. The returned value keeps `deltaX` in the high 32 bits and `deltaY` in the low 32.
     */
    private fun decodeTriplet(code: Int, t: ByteArray): Long {
        fun signed(signBase: Int, magnitude: Int): Int = if (signBase and 1 != 0) magnitude else -magnitude
        val deltaX: Int
        val deltaY: Int
        when {
            code < 10 -> {
                deltaX = 0
                deltaY = signed(code, ((code and 14) shl 7) + (t[0].toInt() and 0xFF))
            }
            code < 20 -> {
                deltaX = signed(code, (((code - 10) and 14) shl 7) + (t[0].toInt() and 0xFF))
                deltaY = 0
            }
            code < 84 -> {
                val b0 = code - 20
                val b1 = t[0].toInt() and 0xFF
                deltaX = signed(code, 1 + (b0 and 0x30) + (b1 ushr 4))
                deltaY = signed(code ushr 1, 1 + ((b0 and 0x0C) shl 2) + (b1 and 0x0F))
            }
            code < 120 -> {
                val b0 = code - 84
                deltaX = signed(code, 1 + ((b0 / 12) shl 8) + (t[0].toInt() and 0xFF))
                deltaY = signed(code ushr 1, 1 + (((b0 % 12) ushr 2) shl 8) + (t[1].toInt() and 0xFF))
            }
            code < 124 -> {
                val b2 = t[1].toInt() and 0xFF
                deltaX = signed(code, ((t[0].toInt() and 0xFF) shl 4) + (b2 ushr 4))
                deltaY = signed(code ushr 1, ((b2 and 0x0F) shl 8) + (t[2].toInt() and 0xFF))
            }
            else -> {
                deltaX = signed(code, ((t[0].toInt() and 0xFF) shl 8) + (t[1].toInt() and 0xFF))
                deltaY = signed(code ushr 1, ((t[2].toInt() and 0xFF) shl 8) + (t[3].toInt() and 0xFF))
            }
        }
        return (deltaX.toLong() shl 32) or (deltaY.toLong() and 0xFFFFFFFFL)
    }

    private fun hasExplicitBBox(bboxBitmap: ByteArray, glyphIndex: Int): Boolean =
        bboxBitmap[glyphIndex ushr 3].toInt() and (0x80 ushr (glyphIndex and 7)) != 0

    private fun hasBit(bitmap: ByteArray, index: Int): Boolean =
        bitmap[index ushr 3].toInt() and (0x80 ushr (index and 7)) != 0

    private fun takeStream(transformed: ByteArray, offset: Int, length: Long): ByteArray =
        transformed.copyOfRange(offset, offset + length.toInt())

    private fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8 and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32(target: ByteArray, offset: Int, value: UInt) {
        target[offset] = (value shr 24 and 0xFFu).toByte()
        target[offset + 1] = (value shr 16 and 0xFFu).toByte()
        target[offset + 2] = (value shr 8 and 0xFFu).toByte()
        target[offset + 3] = (value and 0xFFu).toByte()
    }

    private fun failure(message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(
            FontError.FontDataFailure(TRANSFORM_FAILED, message, FontDiagnosticLocation.Source),
        )

    private fun limitExceeded(message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Source))

    /** A bounds-checked read cursor over one of the transformed substreams. */
    private class Cursor(private val bytes: ByteArray, var position: Int = 0) {
        fun i16(): Int {
            if (position + 2 > bytes.size) throw truncated()
            val value = readInt16(bytes, position)!!
            position += 2
            return value
        }

        fun u16(): Int {
            if (position + 2 > bytes.size) throw truncated()
            val value = readUInt16(bytes, position)!!.toInt()
            position += 2
            return value
        }

        fun u255(): Int {
            val code = u8()
            return when (code) {
                253 -> {
                    if (position + 2 > bytes.size) throw truncated()
                    val value = ((u8() shl 8) or u8())
                    value
                }
                254 -> u8() + 506
                255 -> u8() + 253
                else -> code
            }
        }

        fun u8(): Int {
            if (position >= bytes.size) throw truncated()
            return bytes[position++].toInt() and 0xFF
        }

        fun take(length: Int): ByteArray {
            if (length < 0 || position + length > bytes.size) throw truncated()
            val slice = bytes.copyOfRange(position, position + length)
            position += length
            return slice
        }

        fun copyTo(target: ByteSink, length: Int) {
            if (length < 0 || position + length > bytes.size) throw truncated()
            target.bytes(bytes, position, position + length)
            position += length
        }

        private fun truncated(): TransformException =
            TransformException("The transformed WOFF2 glyf substreams are truncated.")
    }

    /** A growable, limit-bounded output buffer. */
    private class ByteSink(private val limit: Long) {
        private var buffer = ByteArray(INITIAL_CAPACITY)
        var size: Int = 0
            private set

        private fun ensure(extra: Int) {
            val needed = size.toLong() + extra.toLong()
            if (needed > limit) {
                throw DecodedLimitException("The reconstructed WOFF2 glyf table exceeds the $limit-byte limit.")
            }
            if (needed > Int.MAX_VALUE.toLong()) {
                throw DecodedLimitException("The reconstructed WOFF2 glyf table is too large.")
            }
            val required = needed.toInt()
            if (required > buffer.size) {
                var capacity = buffer.size
                while (capacity < required) {
                    capacity = if (capacity > Int.MAX_VALUE / 2) Int.MAX_VALUE else capacity * 2
                }
                buffer = buffer.copyOf(capacity)
            }
        }

        fun u8(value: Int) {
            ensure(1)
            buffer[size++] = value.toByte()
        }

        fun u16(value: Int) {
            ensure(2)
            buffer[size++] = (value ushr 8 and 0xFF).toByte()
            buffer[size++] = (value and 0xFF).toByte()
        }

        fun i16(value: Int) = u16(value and 0xFFFF)

        fun bytes(source: ByteArray) = bytes(source, 0, source.size)

        fun bytes(source: ByteArray, from: Int, to: Int) {
            val length = to - from
            ensure(length)
            source.copyInto(buffer, size, from, to)
            size += length
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }

    private open class TransformException(message: String) : Exception(message)

    private class WorkingLimitException(message: String) : TransformException(message)

    private class DecodedLimitException(message: String) : TransformException(message)
}
