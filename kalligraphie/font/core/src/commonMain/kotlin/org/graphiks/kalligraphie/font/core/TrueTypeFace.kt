package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontFaceMetadata
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontInstanceKey
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceId

internal class TrueTypeFace(
    sourceId: FontSourceId,
    override val metadata: FontFaceMetadata,
) : FontFace {
    override val id: FontFaceId = FontFaceId("${sourceId.value}#0")

    override fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance> =
        FontOperationResult.Success(TrueTypeFontInstance(FontInstanceKey("${id.value}@${descriptor.layoutSize.value}")))
}

private data class TrueTypeFontInstance(
    override val key: FontInstanceKey,
) : FontInstance
