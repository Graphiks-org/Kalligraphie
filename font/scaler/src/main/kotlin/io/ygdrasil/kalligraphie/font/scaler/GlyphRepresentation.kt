package io.ygdrasil.kalligraphie.font.scaler

sealed interface GlyphRepresentation {
    data class Outline(val commands: List<OutlineCommand>) : GlyphRepresentation
    data class Bitmap(
        val pngData: ByteArray,
        val originX: Float,
        val originY: Float,
        val pixelWidth: Int,
        val pixelHeight: Int,
    ) : GlyphRepresentation {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bitmap) return false
            return pngData.contentEquals(other.pngData) &&
                originX == other.originX && originY == other.originY &&
                pixelWidth == other.pixelWidth && pixelHeight == other.pixelHeight
        }
        override fun hashCode(): Int {
            var result = pngData.contentHashCode()
            result = 31 * result + originX.hashCode()
            result = 31 * result + originY.hashCode()
            result = 31 * result + pixelWidth
            result = 31 * result + pixelHeight
            return result
        }
    }
    data class ColorLayers(val layers: List<ColorLayerEntry>) : GlyphRepresentation
    data class ColorLayersV1(
        val paintGraph: io.ygdrasil.kalligraphie.font.colr.COLRPaintGraph,
    ) : GlyphRepresentation
    data class SvgDocument(
        val svgData: ByteArray,
        val docWidth: Float,
        val docHeight: Float,
    ) : GlyphRepresentation {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SvgDocument) return false
            return svgData.contentEquals(other.svgData) &&
                docWidth == other.docWidth && docHeight == other.docHeight
        }
        override fun hashCode(): Int {
            var result = svgData.contentHashCode()
            result = 31 * result + docWidth.hashCode()
            result = 31 * result + docHeight.hashCode()
            return result
        }
    }
}

data class ColorLayerEntry(val glyphId: Int, val paletteColorArgb: Int)
