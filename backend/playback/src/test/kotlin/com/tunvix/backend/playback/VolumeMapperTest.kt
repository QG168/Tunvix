package com.tunvix.backend.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeMapperTest {

    @Test
    fun `percent maps to real steps`() {
        assertEquals(0, VolumeMapper.percentToStep(0, 15))
        assertEquals(8, VolumeMapper.percentToStep(50, 15))
        assertEquals(15, VolumeMapper.percentToStep(100, 15))
        assertEquals(50, VolumeMapper.percentToStep(50, 100))
        assertEquals(100, VolumeMapper.percentToStep(100, 100))
    }

    @Test
    fun `volume boundaries never exceed`() {
        assertEquals(0, VolumeMapper.percentToStep(-10, 15))
        assertEquals(15, VolumeMapper.percentToStep(150, 15))
        assertEquals(0, VolumeMapper.stepToPercent(-1, 15))
        assertEquals(100, VolumeMapper.stepToPercent(15, 15))
        assertEquals(0, VolumeMapper.percentToStep(100, 0))
        assertEquals(0, VolumeMapper.stepToPercent(10, 0))
    }

    @Test
    fun `steps map back to percent`() {
        assertEquals(0, VolumeMapper.stepToPercent(0, 15))
        assertEquals(53, VolumeMapper.stepToPercent(8, 15))
        assertEquals(100, VolumeMapper.stepToPercent(15, 15))
        assertEquals(20, VolumeMapper.stepToPercent(3, 15))
    }
}
