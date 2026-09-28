package org.graphiks.kalligraphie.font.sfnt

import okio.Buffer
import okio.GzipSource
import okio.Inflater
import okio.InflaterSource
import okio.use

internal actual fun platformInflateSupport(): InflateSupport = OkioInflateSupport

private object OkioInflateSupport : InflateSupport {
    override fun inflateZlib(compressed: ByteArray, maxOutputBytes: Long): InflateOutcome =
        pump(compressed, maxOutputBytes) { source -> InflaterSource(source, Inflater()) }

    override fun gunzip(compressed: ByteArray, maxOutputBytes: Long): InflateOutcome =
        pump(compressed, maxOutputBytes) { source -> GzipSource(source) }

    private inline fun pump(
        compressed: ByteArray,
        maxOutputBytes: Long,
        reader: (Buffer) -> okio.Source,
    ): InflateOutcome {
        val sink = Buffer()
        return try {
            reader(Buffer().write(compressed)).use { source ->
                while (true) {
                    val read = source.read(sink, DECOMPRESS_CHUNK_BYTES)
                    if (read == -1L) break
                    if (sink.size > maxOutputBytes) {
                        return InflateOutcome.LimitExceeded(sink.size, maxOutputBytes)
                    }
                }
            }
            InflateOutcome.Success(sink.readByteArray())
        } catch (_: Exception) {
            InflateOutcome.Malformed("decompression failed")
        } finally {
            sink.close()
        }
    }
}

private const val DECOMPRESS_CHUNK_BYTES: Long = 8_192L
