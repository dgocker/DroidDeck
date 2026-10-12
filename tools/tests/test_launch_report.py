import io
import json
import runpy
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

REPORT = runpy.run_path(str(Path(__file__).resolve().parents[1] / "launch_report.py"))

# Made-up launches in the folder layout the app writes; the numbers mean nothing beyond the arithmetic.
FEX_HEADER = "t_s,pid,app_type,fex_version,threads,jit_ticks,signal_ticks,sigbus,smc,float_fallbacks,cache_misses,cache_read_lock_ticks,cache_write_lock_ticks,jit_blocks,disk_cache_hits,disk_cache_misses,disk_cache_lookup_ticks"


def launch(root: Path, name: str, fps: float, env=None, fex_rows=None, counter_hz=1000):
    d = root / "steam-42" / name
    d.mkdir(parents=True)
    (d / "manifest.json").write_text(json.dumps({
        "game": {"key": "steam-42", "name": "Test Game"},
        "status": {"status": "NORMAL_EXIT"},
        "time": {"duration_ms": 120000},
        "device": {"counter_hz": counter_hz, "sve_vector_bits": 0},
        "launch": {"fex": {"build": {"libarm64ecfex.dll": "FEX-2609-137-g0df84d384"}, "env": env or {}},
                   "host_cpu": {"policies": {"policy0": {"cpuinfo_max_khz": 3321600}, "policy6": {"cpuinfo_max_khz": 3801600}}}},
        "graphics": {"session": {"fps_limit": 60}},
    }))
    if fex_rows is not None:
        (d / "fex_stats.csv").write_text(FEX_HEADER + "\n" + "\n".join(fex_rows) + "\n")
    (d / "cpu.csv").write_text("t_s,cpu0_khz,cpu1_khz,policy0_max_khz,policy6_max_khz,cpu_temp_max_millic,gpu_temp_max_millic,thermal_max_millic,thermal_max_zone\n"
                               "30.0,2000000,3000000,3321600,3801600,40000,38000,110000,sys-therm-5\n"
                               "90.0,1000000,3000000,1900800,3801600,52000,44000,110300,sys-therm-5\n")
    (d / "performance.csv").write_text("window_end_utc,seconds_since_launch,onscreen_fps,commit_p99_ms\n"
                                       "x,30,10,100\nx,70,%s,20\nx,80,%s,25\n" % (fps, fps))
    return d


def fex_row(t, pid, jit, sigbus, blocks):
    cells = [str(t), str(pid), "arm64ec", "FEX-2609", "4", str(jit), "0", str(sigbus), "0", "0", "0", "0", "0", str(blocks), "0", "0", "0"]
    return ",".join(cells)


class LaunchReportTest(unittest.TestCase):
    def test_cold_and_warm_phases_are_apart_and_per_second(self):
        with tempfile.TemporaryDirectory() as tmp:
            # Process 500: 6000 ticks (6000 ms at 1000 Hz) of compiling by 60 s, 600 more by 120 s.
            # Process 501 starts after the warm-up: everything it does counts as warm.
            d = launch(Path(tmp), "a", 60.0, fex_rows=[
                fex_row(30, 500, 3000, 0, 100), fex_row(60, 500, 6000, 10, 200), fex_row(120, 500, 6600, 70, 260),
                fex_row(90, 501, 0, 0, 0), fex_row(120, 501, 60, 0, 30)])
            s = REPORT["summarise"](d, 60.0)
        self.assertAlmostEqual(s["cold_jit_ms_s"], 6000 / 60)
        self.assertAlmostEqual(s["warm_jit_ms_s"], 660 / 60)
        self.assertAlmostEqual(s["warm_sigbus_s"], 60 / 60)
        self.assertAlmostEqual(s["warm_jit_blocks_s"], 90 / 60)
        self.assertEqual(s["fps"], 60.0)
        self.assertAlmostEqual(s["low1"], 1000 / 22.5)
        self.assertEqual((s["clk_max"], s["clk_min"]), (3000.0, 1000.0))
        self.assertEqual((s["temp_max"], s["temp_zone"]), (110.3, "sys-therm-5"))
        # The warm phase saw policy0 held at 1.9 GHz under its 3.3 GHz hardware maximum.
        self.assertEqual((s["cap"], s["hw_max"]), (1900.8, "3322/3802"))
        self.assertEqual((s["cpu_temp"], s["gpu_temp"]), (52.0, 44.0))

    def test_missing_samples_leave_gaps_not_zeros(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = launch(Path(tmp), "a", 60.0, fex_rows=None, counter_hz=None)
            s = REPORT["summarise"](d, 60.0)
        self.assertIsNone(s["warm_jit_ms_s"])
        self.assertIsNone(s["warm_sigbus_s"])

    def test_repetitions_of_the_same_setup_get_a_median_and_setups_stay_apart(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name, fps in (("a1", 50.0), ("a2", 54.0), ("a3", 58.0)):
                launch(root, name, fps)
            launch(root, "b1", 40.0, env={"FEX_TSOENABLED": "0"})
            out = io.StringIO()
            with redirect_stdout(out):
                self.assertEqual(REPORT["main"]([str(root), "--warmup", "60"]), 0)
        text = out.getvalue()
        self.assertEqual(text.count("## Test Game"), 2)
        self.assertIn("FEX_TSOENABLED=0", text)
        median = [l for l in text.splitlines() if l.startswith("median")]
        self.assertEqual(len(median), 1)
        self.assertEqual(median[0].split("\t")[3], "54.0")
        self.assertTrue(median[0].endswith("(n=3)"))


if __name__ == "__main__":
    unittest.main()
