import re
import runpy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOL = Path(__file__).resolve().parents[1] / "pacing_report.py"
MODULE = runpy.run_path(str(TOOL))


COMPOSITOR = Path(__file__).resolve().parents[2] / "app/src/main/cpp/waylandcomp/src/compositor.c"


def log_format(tag):
    """The format string compositor.c logs `tag` lines with (adjacent C literals joined), so these
    lines can never drift from what the compositor writes."""
    source = COMPOSITOR.read_text()
    m = re.search(r'droiddeck_log\("%s",\s*((?:"(?:[^"\\]|\\.)*"\s*)+),' % tag, source)
    return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1)))


def line(tag, values):
    # droiddeck_log's own layout: time, the tag padded to 9, the message.
    return "12:00:00.000  %-9s %s\n" % (tag, log_format(tag) % values)


def pacing(commits, c50, c99, shown, never, s50, s99, hz=120.0, cap=60, layer="off"):
    return line("pacing", (commits, c50, c50, c99, 40.0, 1, shown, never, s50, s50, s99, 41.0, 2,
                           4.1, 8.0, 12.3, hz, 8.33, cap, layer))


def perf(copy=600, zero=0):
    return line("perf", (1200, 600, 600, copy, zero, 0, 1.2, 3.4, 0, 0, 0.1, 0.5, 0.2, 0.6, 600,
                         0.8, 2.5, 600, 0, 1.0, 8.0, 590, 3, 0, 0, 0, 0))


PERF = perf()


class PacingReportTest(unittest.TestCase):
    def test_reads_the_compositors_lines_and_skips_the_warm_up(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "wayland.log"
            log.write_text("".join([
                pacing(300, 33.4, 50.0, 290, 10, 33.4, 60.0),           # warm-up: shader compiles
                PERF,
                pacing(600, 16.7, 17.0, 600, 0, 16.7, 17.1), PERF,
                pacing(598, 16.7, 25.0, 596, 2, 16.7, 25.1), PERF,
                pacing(600, 16.6, 16.9, 600, 0, 16.7, 16.9), PERF,
                "12:00:00.000  stats     last 10 s: 600 frames on screen (60.0 fps)\n",
            ]))
            pacing_rows, perf_rows = MODULE["read"](str(log))
            self.assertEqual((len(pacing_rows), len(perf_rows)), (4, 4))
            run = MODULE["summarise"](pacing_rows, perf_rows, skip=1)
        self.assertEqual(run["windows"], 3)
        self.assertEqual((run["committed"], run["shown"], run["never_shown"]), (1798, 1796, 2))
        self.assertAlmostEqual(run["committed_per_s"], 1798 / 30)
        self.assertEqual(run["commit_p50"], 16.7)
        self.assertEqual(run["commit_p99"], 17.0)            # the median window
        self.assertEqual(run["commit_p99_worst"], 25.0)
        self.assertEqual(run["shown_hitches"], 6)
        self.assertEqual(run["copy"], 1800)
        self.assertEqual(run["fence_max"], 2.5)
        self.assertEqual((run["panel_hz"], run["fps_limit"], run["layer_mode"]), (["120.0"], ["60"], ["off"]))

    def test_side_by_side_table(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a.log", Path(tmp) / "b.log"
            a.write_text(pacing(600, 16.7, 17.0, 600, 0, 16.7, 17.1) + PERF)
            b.write_text(pacing(600, 16.7, 17.0, 600, 0, 16.7, 17.1, layer="on") + perf(copy=0, zero=600))
            out = subprocess.run([sys.executable, str(TOOL), str(a), str(b)], capture_output=True, text=True, check=True).stdout
        rows = {line.split("  ")[0].strip(): line.split() for line in out.splitlines()[1:]}
        self.assertEqual(rows["layer mode"][-2:], ["off", "on"])
        self.assertEqual(rows["zero-copy frames"][-2:], ["0", "600"])

    def test_a_log_without_pacing_lines_says_so(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "wayland.log"
            log.write_text(PERF)
            result = subprocess.run([sys.executable, str(TOOL), "--skip", "3", str(log)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn("none left after --skip 3", result.stderr)


if __name__ == "__main__":
    unittest.main()
