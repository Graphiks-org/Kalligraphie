package org.graphiks.kalligraphie.font.scaler.cff

internal fun testDictInt(value: Int): ByteArray = byteArrayOf(
    29,
    (value shr 24).toByte(),
    (value shr 16).toByte(),
    (value shr 8).toByte(),
    value.toByte(),
)

internal fun testCffIndex(items: List<ByteArray>): ByteArray {
    require(items.isNotEmpty()) { "CFF test INDEX helper only builds non-empty INDEXes." }
    val out = ArrayList<Byte>()
    out += 0
    out += items.size.toByte()
    out += 2
    var offset = 1
    for (item in items) {
        out += ((offset shr 8) and 0xFF).toByte()
        out += (offset and 0xFF).toByte()
        offset += item.size
    }
    out += ((offset shr 8) and 0xFF).toByte()
    out += (offset and 0xFF).toByte()
    for (item in items) out.addAll(item.toList())
    return out.toByteArray()
}

/** Builds a CFF2 INDEX (uint32 count, uint8 offSize) from non-empty [items]. */
internal fun testCff2Index(items: List<ByteArray>): ByteArray {
    require(items.isNotEmpty()) { "CFF2 test INDEX helper only builds non-empty INDEXes." }
    val out = ArrayList<Byte>()
    val count = items.size
    out += ((count shr 24) and 0xFF).toByte()
    out += ((count shr 16) and 0xFF).toByte()
    out += ((count shr 8) and 0xFF).toByte()
    out += (count and 0xFF).toByte()
    out += 2
    var offset = 1
    for (item in items) {
        out += ((offset shr 8) and 0xFF).toByte()
        out += (offset and 0xFF).toByte()
        offset += item.size
    }
    out += ((offset shr 8) and 0xFF).toByte()
    out += (offset and 0xFF).toByte()
    for (item in items) out.addAll(item.toList())
    return out.toByteArray()
}

/** Builds a minimal CFF1 with one glyph whose CharStrings INDEX holds [charString]. */
internal fun minimalCff1WithGlyph(charString: ByteArray, topDictPrefix: ByteArray = ByteArray(0)): ByteArray {
    val header = byteArrayOf(1, 0, 4, 4)
    val nameIndex = testCffIndex(listOf("Test".encodeToByteArray()))
    val charStringsIndex = testCffIndex(listOf(charString))
    fun topDict(charStringsOffset: Int): ByteArray = topDictPrefix + testDictInt(charStringsOffset) + byteArrayOf(17)
    val probe = testCffIndex(listOf(topDict(0))).size
    val charStringsOffset = header.size + nameIndex.size + probe + 2 + 2
    val topIndex = testCffIndex(listOf(topDict(charStringsOffset)))
    return header + nameIndex + topIndex + byteArrayOf(0, 0) + byteArrayOf(0, 0) + charStringsIndex
}

/**
 * Builds a minimal CFF1 with [charStrings] glyphs and an explicit format-0 charset
 * whose SIDs are [sids] (one per glyph after `.notdef`).
 */
internal fun buildCff1WithGlyphs(charStrings: List<ByteArray>, sids: List<Int>): ByteArray {
    require(sids.size == charStrings.size - 1) { "Charset needs one SID per glyph after .notdef." }
    val header = byteArrayOf(1, 0, 4, 4)
    val nameIndex = testCffIndex(listOf("Test".encodeToByteArray()))
    val charStringsIndex = testCffIndex(charStrings)
    val charset = ArrayList<Byte>()
    charset += 0
    for (sid in sids) {
        charset += ((sid shr 8) and 0xFF).toByte()
        charset += (sid and 0xFF).toByte()
    }
    val charsetBytes = charset.toByteArray()
    fun topDict(charsetOffset: Int, charStringsOffset: Int): ByteArray =
        testDictInt(charsetOffset) + byteArrayOf(15) + testDictInt(charStringsOffset) + byteArrayOf(17)
    val probe = testCffIndex(listOf(topDict(0, 0))).size
    val charsetOffset = header.size + nameIndex.size + probe + 2 + 2
    val charStringsOffset = charsetOffset + charsetBytes.size
    val topIndex = testCffIndex(listOf(topDict(charsetOffset, charStringsOffset)))
    return header + nameIndex + topIndex + byteArrayOf(0, 0) + byteArrayOf(0, 0) + charsetBytes + charStringsIndex
}

/** Builds a minimal CID-keyed CFF1 with one glyph routed through one Font DICT. */
internal fun buildCidCff1(charString: ByteArray, defaultWidthX: Int = 100): ByteArray {
    val header = byteArrayOf(1, 0, 4, 4)
    val nameIndex = testCffIndex(listOf("Test".encodeToByteArray()))
    val stringIndex = byteArrayOf(0, 0)
    val globalSubr = byteArrayOf(0, 0)
    val charStringsIndex = testCffIndex(listOf(charString))
    val fdSelect = byteArrayOf(0, 0)
    val privateDictData = testDictInt(defaultWidthX) + byteArrayOf(20)
    fun fontDict(privateOffset: Int): ByteArray =
        testDictInt(privateDictData.size) + testDictInt(privateOffset) + byteArrayOf(18)

    fun topDict(fdArrayOffset: Int, fdSelectOffset: Int, charStringsOffset: Int): ByteArray =
        testDictInt(0) + testDictInt(0) + byteArrayOf(12, 30) +
            testDictInt(fdArrayOffset) + byteArrayOf(12, 36) +
            testDictInt(fdSelectOffset) + byteArrayOf(12, 37) +
            testDictInt(charStringsOffset) + byteArrayOf(17)

    val topIndexLength = testCffIndex(listOf(topDict(0, 0, 0))).size
    val fdSelectOffset = header.size + nameIndex.size + topIndexLength + stringIndex.size + globalSubr.size
    val charStringsOffset = fdSelectOffset + fdSelect.size
    val fdArrayOffset = charStringsOffset + charStringsIndex.size
    val fdArrayLength = testCffIndex(listOf(fontDict(0))).size
    val privateOffset = fdArrayOffset + fdArrayLength
    val fdArray = testCffIndex(listOf(fontDict(privateOffset)))
    val topIndex = testCffIndex(listOf(topDict(fdArrayOffset, fdSelectOffset, charStringsOffset)))
    return header + nameIndex + topIndex + stringIndex + globalSubr + fdSelect + charStringsIndex + fdArray + privateDictData
}

/**
 * Builds a minimal CFF2 with one glyph, one Font DICT, an optional Private DICT and a
 * Card16-prefixed variation store. [privateData] is emitted between the FDArray and the store,
 * and the Font DICT's `Private` ref points at it.
 */
internal fun buildCff2WithVariation(
    charString: ByteArray,
    variationStore: ByteArray,
    privateData: ByteArray = ByteArray(0),
): ByteArray {
    val header = byteArrayOf(2, 0, 5, 0, 0)
    val globalSubr = byteArrayOf(0, 0, 0, 0)
    val fdSelect = byteArrayOf(0, 0)
    val charStringsIndex = testCff2Index(listOf(charString))
    fun fontDict(privateOffset: Int): ByteArray =
        testDictInt(privateData.size) + testDictInt(privateOffset) + byteArrayOf(18)
    fun topDict(fdArrayOffset: Int, fdSelectOffset: Int, charStringsOffset: Int, vstoreOffset: Int): ByteArray =
        testDictInt(charStringsOffset) + byteArrayOf(17) +
            testDictInt(fdArrayOffset) + byteArrayOf(12, 36) +
            testDictInt(fdSelectOffset) + byteArrayOf(12, 37) +
            testDictInt(vstoreOffset) + byteArrayOf(24)

    val probeTop = topDict(0, 0, 0, 0)
    header[3] = ((probeTop.size shr 8) and 0xFF).toByte()
    header[4] = (probeTop.size and 0xFF).toByte()
    val globalSubrOffset = header.size + probeTop.size
    val fdSelectOffset = globalSubrOffset + globalSubr.size
    val charStringsOffset = fdSelectOffset + fdSelect.size
    val fdArrayOffset = charStringsOffset + charStringsIndex.size
    val fdArrayLength = testCff2Index(listOf(fontDict(0))).size
    val privateOffset = fdArrayOffset + fdArrayLength
    val vstoreOffset = privateOffset + privateData.size
    val topDictBytes = topDict(fdArrayOffset, fdSelectOffset, charStringsOffset, vstoreOffset)
    val fdArray = testCff2Index(listOf(fontDict(privateOffset)))
    return header + topDictBytes + globalSubr + fdSelect + charStringsIndex + fdArray + privateData + variationStore
}

/** Builds a Card16-prefixed CFF2 ItemVariationStore with one axis, one region and one item data. */
internal fun testCff2VariationStore(
    regionStart: Int = 0x0000,
    regionPeak: Int = 0x4000,
    regionEnd: Int = 0x4000,
): ByteArray {
    val store = ArrayList<Byte>()
    fun u16(value: Int) {
        store += ((value shr 8) and 0xFF).toByte()
        store += (value and 0xFF).toByte()
    }
    fun u32(value: Int) {
        store += ((value shr 24) and 0xFF).toByte()
        store += ((value shr 16) and 0xFF).toByte()
        store += ((value shr 8) and 0xFF).toByte()
        store += (value and 0xFF).toByte()
    }
    u16(1)
    u32(12)
    u16(1)
    u32(22)
    u16(1)
    u16(1)
    u16(regionStart)
    u16(regionPeak)
    u16(regionEnd)
    u16(1)
    u16(0)
    u16(1)
    u16(0)
    val out = ArrayList<Byte>()
    out += ((store.size shr 8) and 0xFF).toByte()
    out += (store.size and 0xFF).toByte()
    out.addAll(store)
    return out.toByteArray()
}

/**
 * `0 hmoveto 100 hlineto -100 200 100 1 blend rlineto`: the third vertex's y is `200 + 100` times
 * the active region scalar.
 */
internal fun blendTriangleCharString(): ByteArray = byteArrayOf(
    139.toByte(), 22, 239.toByte(), 6, 39, 247.toByte(), 92, 239.toByte(), 140.toByte(), 16, 5,
)

/**
 * Card16-prefixed CFF2 ItemVariationStore with two item data subtables: item data 0 references a
 * region that is zero at positive coordinates, item data 1 references `(0, 1, 1)`.
 */
internal fun testCff2TwoRegionStore(): ByteArray {
    val store = ArrayList<Byte>()
    fun u16(value: Int) { store += ((value shr 8) and 0xFF).toByte(); store += (value and 0xFF).toByte() }
    fun u32(value: Int) {
        store += ((value shr 24) and 0xFF).toByte()
        store += ((value shr 16) and 0xFF).toByte()
        store += ((value shr 8) and 0xFF).toByte()
        store += (value and 0xFF).toByte()
    }
    u16(1)
    u32(16)
    u16(2)
    u32(32)
    u32(40)
    u16(1)
    u16(2)
    u16(0xC000); u16(0xC000); u16(0x0000)
    u16(0x0000); u16(0x4000); u16(0x4000)
    u16(1); u16(0); u16(1); u16(0)
    u16(1); u16(0); u16(1); u16(1)
    val out = ArrayList<Byte>()
    out += ((store.size shr 8) and 0xFF).toByte()
    out += (store.size and 0xFF).toByte()
    out.addAll(store)
    return out.toByteArray()
}
