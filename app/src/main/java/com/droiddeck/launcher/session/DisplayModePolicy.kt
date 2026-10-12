package com.droiddeck.launcher.session

/**
 * Which display mode a session asks for ([PerfMode]), from the modes the display itself reports -
 * never from a list of rates assumed to exist (a panel may have no 120 Hz mode at all).
 *
 * Only modes of the panel's current size are candidates: a mode switch never changes the resolution.
 * With a frame cap, the fastest mode the cap divides evenly ([cadenceFits]), so every capped frame is
 * shown for the same whole number of refreshes - 60 fps on 120 Hz is two refreshes each, where 144 Hz
 * alternates two and three (13.9 / 20.8 ms). When no mode divides the cap, the fastest mode, as without
 * a cap. The current mode stays when it is already the choice, or when it fits and nothing faster does.
 *
 * A refresh rate changes when frames can be shown, not how fast the CPU or GPU makes them.
 */
object DisplayModePolicy {
    data class Mode(val id: Int, val width: Int, val height: Int, val hz: Float)

    /** The mode to switch to, or null to keep [current]. */
    fun pick(modes: List<Mode>, current: Mode, fpsLimit: Int): Mode? {
        val sameSize = modes.filter { it.width == current.width && it.height == current.height }
        val best = sameSize.filter { cadenceFits(it.hz, fpsLimit) }.maxByOrNull { it.hz }
            ?: sameSize.maxByOrNull { it.hz }
            ?: return null
        if (best.id == current.id) return null
        return if (best.hz > current.hz + 0.5f || !cadenceFits(current.hz, fpsLimit)) best else null
    }

    /** A panel rate that shows every capped frame for a whole number of refreshes (no cap: any). */
    fun cadenceFits(hz: Float, fpsLimit: Int): Boolean {
        if (fpsLimit <= 0) return true
        val ratio = hz / fpsLimit
        return ratio >= 0.98f && Math.abs(ratio - Math.round(ratio)) < 0.02f
    }

    /** "60/90/120/144 Hz": the rates of [modes] at the current size, for the session log. */
    fun describe(modes: List<Mode>, current: Mode): String =
        modes.filter { it.width == current.width && it.height == current.height }
            .map { Math.round(it.hz) }.distinct().sorted().joinToString("/") + " Hz"
}
