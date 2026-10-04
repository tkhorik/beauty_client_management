package com.beauty.app.ui.client

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoCaptureTest {
    @Test
    fun `sample size keeps at least the target width`() {
        assertEquals(1, sampleSizeFor(1200, 1200))
        assertEquals(1, sampleSizeFor(2399, 1200))
        assertEquals(2, sampleSizeFor(2400, 1200))
        // A 12 MP portrait frame (3000 px across once upright) decodes at half size.
        assertEquals(2, sampleSizeFor(3000, 1200))
        assertEquals(4, sampleSizeFor(4800, 1200))
        assertEquals(1, sampleSizeFor(640, 1200))
    }

    @Test
    fun `default tag fills the first free before or after slot`() {
        assertEquals("BEFORE", defaultPhotoTag(emptyList()))
        assertEquals("AFTER", defaultPhotoTag(listOf("BEFORE")))
        assertEquals("BEFORE", defaultPhotoTag(listOf("AFTER")))
        assertEquals("PROCEDURE", defaultPhotoTag(listOf("BEFORE", "AFTER")))
        assertEquals("AFTER", defaultPhotoTag(listOf("BEFORE", "PROCEDURE")))
    }
}
