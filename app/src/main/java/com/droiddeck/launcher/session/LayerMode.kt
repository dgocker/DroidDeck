package com.droiddeck.launcher.session

/**
 * Whether a session's fullscreen game goes on its own display layer - the compositor's layer mode
 * (waylandcomp/src/sc_layer.c, ahb_swapchain.c) - instead of being copied into the app's swapchain at
 * the panel's size every frame.
 *
 * HDR sessions always use it: that is how an HDR frame reaches the panel tagged. For SDR it is an
 * experiment, off unless asked for with a line in droiddeck-env ([SessionService.envSwitchFile]):
 * `DROIDDECK_WAYLAND_ZERO_COPY=1`, the name the compositor's log and the guest's Wayland Turnip already
 * use. Nothing else changes: every frame a layer cannot take (frame generation, a window above the game
 * on a display that cannot afford a second layer, no layer at all on this device) is copied as before.
 * `=0` (or nothing) is the copy path; HDR keeps the layer whatever the line says.
 */
object LayerMode {
    const val ENV = "DROIDDECK_WAYLAND_ZERO_COPY"

    class Choice(val on: Boolean, val reason: String)

    fun choose(hdr: Boolean, requested: Boolean?): Choice = when {
        hdr -> Choice(true, "HDR session")
        requested == true -> Choice(true, "$ENV=1 in droiddeck-env (an experiment for SDR)")
        requested == false -> Choice(false, "$ENV=0 in droiddeck-env: copy path")
        else -> Choice(false, "copy path (the default for SDR)")
    }

    /** The last `DROIDDECK_WAYLAND_ZERO_COPY=` line of [lines]: true for 1/true/on/yes, false for
     *  0/false/off/no, null when there is none or its value is neither. Comments are skipped. */
    fun requested(lines: List<String>): Boolean? = lines.asSequence()
        .map { it.trim() }
        .filter { !it.startsWith("#") && it.substringBefore('=', "").trim() == ENV }
        .lastOrNull()
        ?.substringAfter('=')?.trim()?.lowercase()
        ?.let { value ->
            when (value) {
                "1", "true", "on", "yes" -> true
                "0", "false", "off", "no" -> false
                else -> null
            }
        }
}
