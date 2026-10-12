import os
import runpy
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parents[1] / "fexdiff"
RUN = runpy.run_path(str(HERE / "run.py"))


class FexDiffTest(unittest.TestCase):
    def test_comparison_ignores_information_lines_and_pairs_missing_ones(self):
        self.assertEqual(RUN["compared"]("# cpuid x\nint.add 1\n\nend\n"), ["int.add 1", "end"])
        self.assertEqual(RUN["diff"](["a", "b", "c"], ["a", "x"]), [("b", "x"), ("c", "")])
        self.assertEqual(RUN["diff"](["a"], ["a"]), [])

    @unittest.skipUnless(os.uname().machine == "x86_64" and (shutil.which("musl-gcc") or shutil.which("gcc")), "needs an x86-64 compiler")
    def test_the_reference_program_builds_and_is_reproducible(self):
        # The reference side of the comparison: the same output on every native run, or a translator's
        # differences could not be told from the program's own.
        with tempfile.TemporaryDirectory() as tmp:
            compiler = shutil.which("musl-gcc") or shutil.which("gcc")
            try:
                binary = RUN["build"](Path(tmp), compiler)
            except subprocess.CalledProcessError:
                self.skipTest("static linking is not available here")
            for section in ("x87", "smc", "sig", "jit"):
                first = subprocess.run([str(binary), section], capture_output=True, text=True, timeout=120, check=True).stdout
                second = subprocess.run([str(binary), section], capture_output=True, text=True, timeout=120, check=True).stdout
                self.assertEqual(RUN["compared"](first), RUN["compared"](second), section)
                self.assertTrue(first.rstrip().endswith("end"), section)
            sig = subprocess.run([str(binary), "sig"], capture_output=True, text=True, timeout=120, check=True).stdout
            # x86 raises a divide error for every case the translator is checked on.
            self.assertEqual(sig.count("sig=8 code=1"), 9)


if __name__ == "__main__":
    unittest.main()
