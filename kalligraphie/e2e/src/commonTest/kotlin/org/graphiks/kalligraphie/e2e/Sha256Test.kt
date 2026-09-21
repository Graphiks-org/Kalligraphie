package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun emptyInputMatchesTheKnownVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun abcMatchesTheKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun twoBlockMessageMatchesTheKnownVector() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun highBitBytesMatchTheKnownVector() {
        assertEquals(
            "40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880",
            sha256Hex(ByteArray(256) { it.toByte() }),
        )
    }

    @Test
    fun fiftyFiveByteMessageMatchesTheKnownVector() {
        assertEquals(
            "d5e285683cd4efc02d021a5c62014694958901005d6f71e89e0989fac77e4072",
            sha256Hex(ByteArray(55) { 'x'.code.toByte() }),
        )
    }

    @Test
    fun fiftySixByteMessageMatchesTheKnownVector() {
        assertEquals(
            "04c26261370ee7541549d16dee320c723e3fd14671e66a099afe0a377c16888e",
            sha256Hex(ByteArray(56) { 'x'.code.toByte() }),
        )
    }

    @Test
    fun sixtyFourByteMessageMatchesTheKnownVector() {
        assertEquals(
            "7ce100971f64e7001e8fe5a51973ecdfe1ced42befe7ee8d5fd6219506b5393c",
            sha256Hex(ByteArray(64) { 'x'.code.toByte() }),
        )
    }

    @Test
    fun oneMillionAsMatchTheKnownVector() {
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            sha256Hex(ByteArray(1_000_000) { 'a'.code.toByte() }),
        )
    }
}
