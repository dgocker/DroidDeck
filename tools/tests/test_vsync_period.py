import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[2]


class VsyncPeriodTest(unittest.TestCase):
    """The refresh period the compositor's release pacer uses (waylandcomp/src/vsync_period.c)."""

    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            raise unittest.SkipTest('A host C compiler is required for the vsync period test')
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-vsync-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.binary = Path(cls.temp.name) / 'vsync-period-test'
        build = subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Werror',
                                '-I' + str(REPO / 'app/src/main/cpp/waylandcomp/src'),
                                str(REPO / 'tools/tests/vsync_period_harness.c'), '-o', str(cls.binary)],
                               capture_output=True, text=True)
        if build.returncode:
            raise RuntimeError(build.stderr)

    def check(self, case):
        result = subprocess.run([str(self.binary), case], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('PASS', result.stdout.strip())

    def test_late_handling_no_longer_stretches_the_period(self):
        self.check('late')

    def test_skipped_ticks_keep_the_period(self):
        self.check('skipped')

    def test_a_panel_changing_rate_is_followed(self):
        self.check('modes')

    def test_pauses_and_bad_times_change_nothing(self):
        self.check('garbage')


if __name__ == '__main__':
    unittest.main()
