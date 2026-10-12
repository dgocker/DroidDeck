package com.droiddeck.launcher.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuFactsTest {
    @Test
    fun featuresAndEachDistinctCorePairComeFromCpuinfo() {
        val cpuinfo = (0..7).joinToString("\n\n") { n ->
            "processor\t: $n\nBogoMIPS\t: 38.40\n" +
                "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp uscat ilrcpc flagm\n" +
                "CPU implementer\t: 0x51\nCPU architecture: 8\nCPU variant\t: 0x0\nCPU part\t: ${if (n < 2) "0x002" else "0x003"}\nCPU revision\t: 2"
        }
        val facts = cpuFacts(cpuinfo)
        assertEquals(listOf("0x51:0x002", "0x51:0x003"), facts["cpu_parts"])
        @Suppress("UNCHECKED_CAST")
        val features = facts["cpu_features"] as List<String>
        assertEquals("fp", features.first())
        assertEquals(true, "ilrcpc" in features && "uscat" in features && "sve2" !in features)
    }

    @Test
    fun anUnreadableCpuinfoGivesNothing() {
        val facts = cpuFacts("")
        assertNull(facts["cpu_features"])
        assertNull(facts["cpu_parts"])
    }
}
