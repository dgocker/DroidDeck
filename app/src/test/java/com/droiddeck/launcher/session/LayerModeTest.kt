package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerModeTest {
    @Test
    fun sdrStaysOnTheCopyPathByDefault() {
        assertFalse(LayerMode.choose(hdr = false, requested = null).on)
        assertNull(LayerMode.requested(emptyList()))
        assertNull(LayerMode.requested(listOf("ZINK_DESCRIPTORS=lazy", "TU_DEBUG=sysmem")))
    }

    @Test
    fun theEnvironmentLineTurnsItOnForSdr() {
        val lines = listOf("TU_DEBUG=sysmem", "DROIDDECK_WAYLAND_ZERO_COPY=1")
        assertEquals(true, LayerMode.requested(lines))
        assertTrue(LayerMode.choose(hdr = false, requested = LayerMode.requested(lines)).on)
    }

    @Test
    fun hdrKeepsItOnWhateverTheLineSays() {
        assertTrue(LayerMode.choose(hdr = true, requested = false).on)
        assertTrue(LayerMode.choose(hdr = true, requested = null).on)
    }

    @Test
    fun theLastLineWinsAndCommentsDoNotCount() {
        assertEquals(false, LayerMode.requested(listOf("DROIDDECK_WAYLAND_ZERO_COPY=1", "DROIDDECK_WAYLAND_ZERO_COPY=0")))
        assertEquals(true, LayerMode.requested(listOf(" DROIDDECK_WAYLAND_ZERO_COPY = on ", "#DROIDDECK_WAYLAND_ZERO_COPY=0")))
    }

    @Test
    fun anythingElseIsNoAnswer() {
        assertNull(LayerMode.requested(listOf("DROIDDECK_WAYLAND_ZERO_COPY=maybe")))
        assertNull(LayerMode.requested(listOf("DROIDDECK_WAYLAND_ZERO_COPY_EXTRA=1")))
        assertNull(LayerMode.requested(listOf("XDROIDDECK_WAYLAND_ZERO_COPY=1")))
        assertFalse(LayerMode.choose(hdr = false, requested = LayerMode.requested(listOf("DROIDDECK_WAYLAND_ZERO_COPY=2"))).on)
    }
}
