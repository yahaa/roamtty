package io.itermux.app.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDecoderTest {
    @Test fun splitsAnnexBWithBothStartCodeLengths() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2)
        val pps = byteArrayOf(0, 0, 1, 0x68, 3)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 4, 5, 6)
        val units = VideoDecoder.nalUnits(byteArrayOf(9, 9) + sps + pps + idr, 2)
        assertEquals(listOf(7, 8, 5), units.map { it.second })
        assertArrayEquals(sps, units[0].first)
        assertArrayEquals(pps, units[1].first)
        assertArrayEquals(idr, units[2].first)
    }
}
