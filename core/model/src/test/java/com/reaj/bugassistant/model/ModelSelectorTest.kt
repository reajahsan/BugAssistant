package com.reaj.bugassistant.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectorTest {
    private val gb = 1024L * 1024 * 1024
    private val large = Models.LARGE
    private val small = Models.SMALL

    private fun res(ramGb: Double, storageGb: Double, lowRam: Boolean = false, files: Map<String, Long> = emptyMap()) =
        DeviceResources((ramGb * gb).toLong(), lowRam, (storageGb * gb).toLong(), files)

    @Test fun capableDeviceGetsLargeModel() {
        assertEquals(ModelChoice.Use(large, ModelChoice.Reason.CAPABLE_DEVICE), ModelSelector.select(res(7.5, 20.0)))
    }

    @Test fun lowRamGetsSmallModel() {
        assertEquals(ModelChoice.Use(small, ModelChoice.Reason.LOW_RAM), ModelSelector.select(res(3.6, 20.0)))
        assertEquals(ModelChoice.Use(small, ModelChoice.Reason.LOW_RAM), ModelSelector.select(res(7.5, 20.0, lowRam = true)))
    }

    @Test fun lowStorageGetsSmallModel() {
        assertEquals(ModelChoice.Use(small, ModelChoice.Reason.LOW_STORAGE), ModelSelector.select(res(7.5, 2.0)))
    }

    @Test fun noRoomForEitherModel() {
        val choice = ModelSelector.select(res(7.5, 0.5))
        assertEquals(small, choice.spec)
        assertEquals(small.sizeBytes + ModelSelector.STORAGE_HEADROOM - gb / 2, (choice as ModelChoice.NotEnoughStorage).neededBytes)
    }

    @Test fun alreadyDownloadedLargeModelIsKeptEvenWithLittleFreeSpace() {
        val choice = ModelSelector.select(res(7.5, 0.1, files = mapOf(large.fileName to large.sizeBytes)))
        assertEquals(large, choice.spec)
    }

    @Test fun partialDownloadAndStaleModelCountTowardSpace() {
        // 1.0 GB free + 0.8 GB stale 1B file + 1.2 GB already downloaded of 3B (~1.88 GiB) -> fits.
        val files = mapOf(small.fileName to small.sizeBytes, large.fileName + ".part" to (1.2 * gb).toLong())
        assertEquals(large, ModelSelector.select(res(7.5, 1.0, files = files)).spec)
    }
}
