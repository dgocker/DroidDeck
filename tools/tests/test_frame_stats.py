import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[2]


class FrameStatsTest(unittest.TestCase):
    """The compositor's frame-pacing percentiles (waylandcomp/src/frame_stats.c)."""

    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            raise unittest.SkipTest('A host C compiler is required for the frame-pacing test')
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-frame-stats-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.binary = Path(cls.temp.name) / 'frame-stats-test'
        build = subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Werror',
                                '-I' + str(REPO / 'app/src/main/cpp/waylandcomp/src'),
                                str(REPO / 'tools/tests/frame_stats_harness.c'),
                                '-lm', '-o', str(cls.binary)], capture_output=True, text=True)
        if build.returncode:
            raise RuntimeError(build.stderr)

    def check(self, case):
        result = subprocess.run([str(self.binary), case], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('PASS', result.stdout.strip())

    def test_steady_frames_have_one_interval(self):
        self.check('steady')

    def test_60_fps_on_144_hz_shows_its_judder(self):
        self.check('judder')

    def test_one_percent_of_hitches_is_counted(self):
        self.check('hitches')

    def test_a_pause_longer_than_the_histogram_is_kept(self):
        self.check('overflow')

    def test_windows_and_owners_never_mix(self):
        self.check('owners')

    def test_no_samples_and_negative_samples(self):
        self.check('empty')


if __name__ == '__main__':
    unittest.main()
