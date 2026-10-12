package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.DisplayModePolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayModePolicyTest {
    // A 1.5K panel offering 60/90/120/144 Hz (the shape of a 144 Hz phone like the iQOO Z11 Turbo;
    // the real list is whatever its display reports), plus one mode at another resolution.
    private val m60 = Mode(1, 1260, 2750, 60.0f)
    private val m90 = Mode(2, 1260, 2750, 90.0f)
    private val m120 = Mode(3, 1260, 2750, 120.0f)
    private val m144 = Mode(4, 1260, 2750, 144.0f)
    private val fhd165 = Mode(5, 1080, 2400, 165.0f)
    private val phone = listOf(m60, m90, m120, m144, fhd165)

    private fun chosen(modes: List<Mode>, current: Mode, cap: Int): Mode =
        DisplayModePolicy.pick(modes, current, cap) ?: current

    @Test
    fun eachCapGetsTheFastestRateItDivides() {
        assertEquals(m120, chosen(phone, m144, 30))   // 4 refreshes a frame
        assertEquals(m120, chosen(phone, m144, 40))   // 3
        assertEquals(m90, chosen(phone, m144, 45))    // 2
        assertEquals(m120, chosen(phone, m144, 60))   // 2, not 144's 2-and-3
        assertEquals(m90, chosen(phone, m144, 90))    // 1
        assertEquals(m120, chosen(phone, m144, 120))  // 1
    }

    @Test
    fun noCapTakesTheFastestMode() {
        assertEquals(m144, chosen(phone, m60, 0))
        assertNull(DisplayModePolicy.pick(phone, m144, 0))
    }

    @Test
    fun anotherResolutionIsNeverChosen() {
        assertEquals(m144, chosen(phone, m120, 0))
        assertEquals(m120, chosen(phone, m60, 60))
    }

    @Test
    fun aPanelWithout120Hz() {
        val modes = listOf(m60, m90)
        assertEquals(m60, chosen(modes, m90, 60))     // 90 / 60 = 1.5: 60 Hz shows every frame once
        assertEquals(m90, chosen(modes, m60, 45))
        assertEquals(m90, chosen(modes, m90, 30))     // 90 / 30 = 3: three refreshes a frame
        assertNull(DisplayModePolicy.pick(modes, m90, 120)) // nothing it divides: the fastest, kept
        assertNull(DisplayModePolicy.pick(listOf(m60), m60, 45))
    }

    @Test
    fun aCapNothingDividesTakesTheFastestMode() {
        val modes = listOf(m60, m120, m144)
        assertEquals(m144, chosen(modes, m60, 45))
        assertEquals(m144, chosen(modes, m120, 45))
    }

    @Test
    fun theCurrentModeStaysWhenItIsAlreadyTheChoice() {
        assertNull(DisplayModePolicy.pick(phone, m120, 60))
        assertNull(DisplayModePolicy.pick(phone, m90, 45))
    }

    @Test
    fun nonIntegerRatesStillFit() {
        val ntsc = listOf(Mode(1, 1080, 2400, 59.94f), Mode(2, 1080, 2400, 119.88f))
        assertEquals(119.88f, chosen(ntsc, ntsc[0], 60).hz)
        assertTrue(DisplayModePolicy.cadenceFits(143.99f, 72))
        assertFalse(DisplayModePolicy.cadenceFits(144f, 60))
        assertFalse(DisplayModePolicy.cadenceFits(60f, 120))
    }

    @Test
    fun aNewCapIsAppliedFromWhereverThePanelIs() {
        // Each session (or a re-created session activity) applies the cap it has, from the mode the
        // previous one left: the result depends only on the modes and the cap.
        var current = m60
        for ((cap, want) in listOf(60 to m120, 0 to m144, 45 to m90, 30 to m120, 120 to m120, 0 to m144)) {
            current = chosen(phone, current, cap)
            assertEquals("cap $cap", want, current)
        }
    }

    @Test
    fun theLogNamesTheRatesAtThisSize() {
        assertEquals("60/90/120/144 Hz", DisplayModePolicy.describe(phone, m60))
        assertEquals("165 Hz", DisplayModePolicy.describe(phone, fhd165))
    }
}
