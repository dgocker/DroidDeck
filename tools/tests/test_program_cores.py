import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


def cores_function(sysfs):
    match = re.search(r"^program_cores\(\) \{\n.*?^\}\n", SESSION.read_text(), re.M | re.S)
    return match.group(0).replace("/sys/devices/system/cpu/", "%s/" % sysfs)


class ProgramCoresTest(unittest.TestCase):
    """The cores the desktop and a program from the rail are pinned to (droiddeck-session)."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        taskset = self.bin / "taskset"
        taskset.write_text("#!/bin/sh\nexit 0\n")
        taskset.chmod(0o755)

    def cores(self, ceilings_khz, **env):
        sysfs = self.root / "cpu"
        shutil.rmtree(sysfs, True)
        for n, khz in enumerate(ceilings_khz):
            folder = sysfs / ("cpu%d" % n) / "cpufreq"
            folder.mkdir(parents=True)
            (folder / "cpuinfo_max_freq").write_text("%d\n" % khz)
        environment = {k: v for k, v in os.environ.items() if k not in ("BL_GAME_CPUS", "BL_PIN_CORES")}
        environment.update(env, PATH="%s:%s" % (self.bin, os.environ["PATH"]))
        result = subprocess.run(["bash", "-c", "set -u\n" + cores_function(sysfs) + "program_cores"],
                                env=environment, capture_output=True, text=True, timeout=30, check=True)
        return result.stdout.strip()

    def test_two_plus_six_oryon_keeps_every_core(self):
        # Snapdragon 8 Gen 5 (SM8845, iQOO Z11 Turbo): 6 x 3.32 GHz + 2 x 3.8 GHz. Leaving the
        # six out would put the desktop and every emulator on the two prime cores.
        self.assertEqual("", self.cores([3321600] * 6 + [3801600] * 2))

    def test_snapdragon_8_elite_gen_5_keeps_every_core(self):
        self.assertEqual("", self.cores([3628800] * 6 + [4608000] * 2))

    def test_six_little_two_big_keeps_every_core(self):
        # A 2 + 6 phone whose six really are little cores: two cores would still be too few.
        self.assertEqual("", self.cores([2000000] * 6 + [2200000] * 2))

    def test_little_cluster_is_left_out(self):
        # Snapdragon 8 Gen 2: 3 x A510, 4 x A715/A710, 1 x X3.
        self.assertEqual("3,4,5,6,7", self.cores([2016000] * 3 + [2803200] * 4 + [3187200]))

    def test_snapdragon_8_gen_3_leaves_out_its_two_littles(self):
        self.assertEqual("2,3,4,5,6,7",
                         self.cores([2265600] * 2 + [3148800] * 3 + [2956800] * 2 + [3302400]))

    def test_exactly_four_left_is_enough(self):
        # Snapdragon 865: 4 x A55, 3 x A77, 1 x A77 prime.
        self.assertEqual("4,5,6,7", self.cores([1804800] * 4 + [2419200] * 3 + [2841600]))

    def test_one_cluster_is_not_pinned(self):
        self.assertEqual("", self.cores([2400000] * 8))

    def test_the_users_game_cores_win(self):
        self.assertEqual("6,7", self.cores([3321600] * 6 + [3801600] * 2, BL_GAME_CPUS="6,7"))

    def test_pinning_can_be_turned_off(self):
        self.assertEqual("", self.cores([2016000] * 3 + [2803200] * 4 + [3187200], BL_PIN_CORES="0"))


if __name__ == "__main__":
    unittest.main()
