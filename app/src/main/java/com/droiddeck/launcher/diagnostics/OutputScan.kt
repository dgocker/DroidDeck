package com.droiddeck.launcher.diagnostics

import java.io.BufferedWriter
import java.io.File

/**
 * One pass over a launch's stderr copy (Proton, Wine, DXVK/VKD3D-Proton, FEX and Mesa all write
 * there): the lines that name FEX into fex.log, the Vulkan and driver lines into vulkan.log, the
 * first crash report and the lines after it into crash/, and what the translation layers said about
 * the GPU (device, driver, DXVK and VKD3D-Proton versions) as they said it. Every output is a subset
 * of real lines; a file nothing matched is not left behind.
 */
object OutputScan {
    private val FEX = Regex("""(\bFEX\b|FEXCore|droiddeck-fex:|\bfex-emu\b|libarm64ecfex|wowbox64)""")
    private val VULKAN = Regex(
        """(?i)(\bdxvk\b|vkd3d|\bvulkan\b|\bvk_[a-z0-9_]+|\bmesa\b|turnip|freedreno|\bkgsl\b|adreno|\bdriver\s*:|found device|\bspir-?v\b|winevulkan|\bicd\b)""",
    )
    private val DXVK_VERSION = Regex("""\bDXVK: (v[\w.+\-]+)""")
    private val VKD3D_VERSION = Regex("""vkd3d-proton[^\s]*\s+(v?\d+\.\d+[\w.+\-]*)""", RegexOption.IGNORE_CASE)
    private val DEVICE = Regex("""Found device: (.+?)\s*$""")
    private val DRIVER = Regex("""\bDriver\s*:\s*(.+?)\s*$""")
    const val CRASH_CONTEXT_LINES = 80

    class Result(
        val lines: Long,
        val fexLines: Int,
        val vulkanLines: Int,
        val crashEvidence: String?,
        val crashReportLines: Int,
        val observed: Map<String, String?>,
    )

    fun scan(src: File, fexOut: File, vulkanOut: File, crashOut: File, cap: Long): Result {
        var lines = 0L
        var fex: BufferedWriter? = null
        var vulkan: BufferedWriter? = null
        var crash: BufferedWriter? = null
        var fexBytes = 0L
        var vulkanBytes = 0L
        var fexLines = 0
        var vulkanLines = 0
        var crashLines = 0
        var evidence: String? = null
        val observed = linkedMapOf<String, String?>("vulkan_device" to null, "driver" to null, "dxvk_version" to null, "vkd3d_proton_version" to null)
        try {
            src.bufferedReader().useLines { all ->
                for (line in all) {
                    lines++
                    val size = line.length + 1L
                    if (FEX.containsMatchIn(line) && fexBytes + size <= cap) {
                        if (fex == null) { fexOut.parentFile?.mkdirs(); fex = fexOut.bufferedWriter() }
                        fex!!.write(line); fex!!.write("\n"); fexBytes += size; fexLines++
                    }
                    if (VULKAN.containsMatchIn(line)) {
                        if (vulkanBytes + size <= cap) {
                            if (vulkan == null) { vulkanOut.parentFile?.mkdirs(); vulkan = vulkanOut.bufferedWriter() }
                            vulkan!!.write(line); vulkan!!.write("\n"); vulkanBytes += size; vulkanLines++
                        }
                        if (observed["dxvk_version"] == null) DXVK_VERSION.find(line)?.let { observed["dxvk_version"] = it.groupValues[1] }
                        if (observed["vkd3d_proton_version"] == null) VKD3D_VERSION.find(line)?.let { observed["vkd3d_proton_version"] = it.groupValues[1] }
                        if (observed["vulkan_device"] == null) DEVICE.find(line)?.let { observed["vulkan_device"] = it.groupValues[1].take(200) }
                        if (observed["driver"] == null) DRIVER.find(line)?.let { observed["driver"] = it.groupValues[1].take(200) }
                    }
                    if (evidence == null) {
                        ExitClassifier.crashEvidence(sequenceOf(line))?.let {
                            evidence = it
                            crashOut.parentFile?.mkdirs()
                            crash = crashOut.bufferedWriter()
                        }
                    }
                    if (crash != null && crashLines < CRASH_CONTEXT_LINES) {
                        crash!!.write(line); crash!!.write("\n"); crashLines++
                    }
                }
            }
        } finally {
            listOf(fex, vulkan, crash).forEach { runCatching { it?.close() } }
        }
        return Result(lines, fexLines, vulkanLines, evidence, crashLines, observed)
    }
}
