import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[2]


class InputQueueTest(unittest.TestCase):
    """Input from the app's threads to the compositor thread (waylandcomp/src/input_queue.c)."""

    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            raise unittest.SkipTest('A host C compiler is required for the input queue test')
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-input-queue-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.binary = Path(cls.temp.name) / 'input-queue-test'
        build = subprocess.run([compiler, '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-pthread',
                                '-I' + str(REPO / 'app/src/main/cpp/waylandcomp/src'),
                                str(REPO / 'tools/tests/input_queue_harness.c'), '-o', str(cls.binary)],
                               capture_output=True, text=True)
        if build.returncode:
            raise RuntimeError(build.stderr)

    def check(self, case):
        result = subprocess.run([str(self.binary), case], capture_output=True, text=True, timeout=120)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('PASS', result.stdout.strip())

    def test_keys_survive_a_full_pipe_in_order(self):
        self.check('keys')

    def test_motion_merges_without_reordering(self):
        self.check('merge')

    def test_a_full_pipe_keeps_only_the_latest_tick(self):
        self.check('ticks')

    def test_what_is_dropped_past_the_cap_is_counted(self):
        self.check('cap')

    def test_concurrent_writers_keep_their_order(self):
        self.check('threads')


if __name__ == '__main__':
    unittest.main()
