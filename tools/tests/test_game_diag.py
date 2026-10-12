import json
import os
import runpy
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
DIAG = runpy.run_path(str(BIN / "droiddeck-game-diag"))


class FakeChild:
    """What run() needs of a started process: a pid. Never a real game."""
    next_pid = 40000

    def __init__(self):
        FakeChild.next_pid += 1
        self.pid = FakeChild.next_pid
        self.returncode = None


def fake_popen(log, fail=()):
    def popen(argv, **kwargs):
        log.append((list(argv), kwargs))
        if os.path.basename(argv[0]) in fail:
            raise FileNotFoundError(2, "No such file or directory", argv[0])
        stderr = kwargs.get("stderr")
        if stderr is not None:
            os.write(stderr, b"wine: fake output from %s\n" % os.path.basename(argv[0]).encode())
        return FakeChild()
    return popen


def fake_wait(status):
    # The launcher waits for any child (-1): the one it started is the last FakeChild made.
    return lambda pid, options: (FakeChild.next_pid if pid == -1 else pid, status)


def records(directory):
    files = sorted(os.listdir(directory))
    start = [f for f in files if f.endswith(".start.json")]
    end = [f for f in files if f.endswith(".exit.json")]
    load = lambda name: json.loads((Path(directory) / name).read_text())
    return [load(f) for f in start], [load(f) for f in end], files


class GameDiagTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = self.tmp.name
        # The tee passes the child's stderr on to fd 2: a file here, so the test output stays clean.
        self.err = tempfile.TemporaryFile()
        self.addCleanup(self.err.close)
        self.saved = os.dup(2)
        os.dup2(self.err.fileno(), 2)
        self.addCleanup(self.restore)

    def restore(self):
        os.dup2(self.saved, 2)
        os.close(self.saved)

    def passed_through(self):
        self.err.seek(0)
        return self.err.read()

    def run_fake(self, status, meta=None, **kwargs):
        log = []
        code = DIAG["run"](["/proton", "waitforexitandrun", "Game.exe"], {"BL_DIAG_DIR": self.dir}, meta or {"app_id": "42"},
                           self.dir, popen=fake_popen(log, kwargs.pop("fail", ())), wait=fake_wait(status), **kwargs)
        return code, log

    def test_a_normal_exit_is_recorded_with_code_zero(self):
        code, log = self.run_fake(0, meta={"app_id": "42", "name": "Game"})
        self.assertEqual(code, 0)
        (start,), (end,), files = records(self.dir)
        self.assertEqual((start["app_id"], start["name"], start["schema"]), ("42", "Game", 1))
        self.assertEqual(start["launch_id"], end["launch_id"])
        self.assertTrue(start["launch_id"].startswith(time.strftime("%Y", time.gmtime())))
        self.assertIsInstance(start["started_boot_ns"], int)
        self.assertEqual((start["wrapper_pid"], start["wrapper_start_time"]), (os.getpid(), DIAG["start_time"](os.getpid())))
        self.assertEqual((end["exited"], end["exit_code"], end["signal"], end["launch_failed"]), (True, 0, None, False))
        self.assertGreaterEqual(end["duration_ms"], 0)
        self.assertEqual(log[0][1]["close_fds"], False)
        self.assertEqual(log[0][1]["restore_signals"], False)
        self.assertFalse([f for f in files if f.endswith(".tmp")])

    def test_an_error_exit_keeps_its_code(self):
        code, _ = self.run_fake(3 << 8)
        self.assertEqual(code, 3)
        _, (end,), _ = records(self.dir)
        self.assertEqual((end["exited"], end["exit_code"], end["reported_status"]), (True, 3, 3))

    def test_a_crash_signal_and_its_core_dump_flag(self):
        code, _ = self.run_fake(signal.SIGSEGV | 0x80)
        self.assertEqual(code, 128 + signal.SIGSEGV)
        _, (end,), _ = records(self.dir)
        self.assertEqual((end["exited"], end["signal"], end["signal_name"], end["core_dumped"]), (False, 11, "SIGSEGV", True))

    def test_a_kill_is_a_signal_not_an_exit_code(self):
        code, _ = self.run_fake(signal.SIGKILL)
        self.assertEqual(code, 137)
        _, (end,), _ = records(self.dir)
        self.assertEqual((end["signal_name"], end["forwarded_signals"]), ("SIGKILL", []))

    def test_a_pack_that_cannot_start_falls_back_to_the_stock_launch(self):
        stocked = []
        log = []
        code = DIAG["run"](["/pack/wine-launch", "x"], {}, {"app_id": "42"}, self.dir,
                           stock=(["/proton", "waitforexitandrun"], {"STOCK": "1"}), on_stock=lambda: stocked.append(1),
                           popen=fake_popen(log, fail=("wine-launch",)), wait=fake_wait(0))
        self.assertEqual(code, 0)
        self.assertEqual(stocked, [1])
        self.assertEqual([argv[0] for argv, _ in log], ["/pack/wine-launch", "/proton"])
        (start,), (end,), _ = records(self.dir)
        self.assertTrue(start["stock_fallback"])
        self.assertEqual(start["program"], "proton")
        self.assertEqual(end["exec_errors"][0]["program"], "wine-launch")
        self.assertFalse(end["launch_failed"])

    def test_a_launch_that_cannot_start_at_all_is_a_launch_failure(self):
        code, _ = self.run_fake(0, fail=("proton",))
        self.assertEqual(code, 127)
        (start,), (end,), _ = records(self.dir)
        self.assertIsNone(start["child_pid"])
        self.assertTrue(end["launch_failed"])
        self.assertIsNone(end["exit_code"])
        self.assertEqual(end["exec_errors"][0]["errno"], 2)

    def test_stderr_is_copied_and_still_passed_on(self):
        code, _ = self.run_fake(0)
        self.assertEqual(code, 0)
        (start,), (end,), _ = records(self.dir)
        copy = (Path(self.dir) / start["output_file"]).read_bytes()
        self.assertEqual(copy, b"wine: fake output from proton\n")
        self.assertIn(b"wine: fake output from proton\n", self.passed_through())
        self.assertEqual((end["output"]["bytes_kept"], end["output"]["complete"], end["output"]["capped"]), (len(copy), True, False))

    def test_the_copy_stops_at_its_cap_and_the_passthrough_does_not(self):
        script = "import os\nfor i in range(400): os.write(2, b'x' * 499 + b'\\n')\n"
        code = DIAG["run"]([sys.executable, "-c", script], dict(os.environ), {"app_id": "42"}, self.dir, out_cap=1000)
        self.assertEqual(code, 0)
        (start,), (end,), _ = records(self.dir)
        copy = (Path(self.dir) / start["output_file"]).read_bytes()
        self.assertTrue(copy.startswith(b"x" * 499 + b"\n" + b"x" * 499 + b"\n"))
        self.assertIn(b"output capped at 1000 bytes", copy)
        self.assertEqual((end["output"]["bytes_kept"], end["output"]["bytes_seen"], end["output"]["capped"]), (1000, 200000, True))
        self.assertEqual(self.passed_through().count(b"x" * 499 + b"\n"), 400)

    def test_a_signal_from_outside_is_passed_on_and_named(self):
        script = "import os, sys, time\nos.write(2, b'ready\\n')\ntime.sleep(30)\n"
        timer = threading.Timer(0.5, lambda: os.kill(os.getpid(), signal.SIGTERM))
        timer.start()
        try:
            started = time.monotonic()
            code = DIAG["run"]([sys.executable, "-c", script], dict(os.environ), {"app_id": "42"}, self.dir)
        finally:
            timer.cancel()
        self.assertLess(time.monotonic() - started, 20)
        self.assertEqual(code, 128 + signal.SIGTERM)
        _, (end,), _ = records(self.dir)
        self.assertEqual((end["signal_name"], end["forwarded_signals"]), ("SIGTERM", ["SIGTERM"]))
        # The launcher's own handler is put back.
        self.assertEqual(signal.getsignal(signal.SIGTERM), signal.SIG_DFL)

    def test_a_launcher_started_with_sigchld_ignored_still_sees_the_status(self):
        previous = signal.signal(signal.SIGCHLD, signal.SIG_IGN)
        try:
            code = DIAG["run"]([sys.executable, "-c", "import signal, sys; sys.exit(9 if signal.getsignal(signal.SIGCHLD) == signal.SIG_IGN else 1)"],
                               dict(os.environ), {"app_id": "42"}, self.dir)
            self.assertEqual(signal.getsignal(signal.SIGCHLD), signal.SIG_IGN)
        finally:
            signal.signal(signal.SIGCHLD, previous)
        # The child still started with SIGCHLD ignored, as an exec'd launch would have.
        self.assertEqual(code, 9)
        _, (end,), _ = records(self.dir)
        self.assertEqual(end["exit_code"], 9)

    def test_an_unwritable_output_file_does_not_stop_the_launch(self):
        log = []
        real_open = os.open

        def refuse(path, *args, **kwargs):
            if str(path).endswith(".out.log"):
                raise PermissionError(13, "Permission denied", path)
            return real_open(path, *args, **kwargs)

        with mock.patch.object(DIAG["run"].__globals__["os"], "open", refuse):
            code = DIAG["run"](["/proton"], {}, {"app_id": "42"}, self.dir, popen=fake_popen(log), wait=fake_wait(0))
        self.assertEqual(code, 0)
        (start,), (end,), _ = records(self.dir)
        self.assertIsNone(start["output_file"])
        self.assertIn("output file", end["output"]["errors"][0])
        self.assertIn(b"fake output", self.passed_through())

    def test_records_are_skipped_not_fatal_when_the_disk_refuses_them(self):
        with mock.patch.object(DIAG["write_record"].__globals__["json"], "dump", side_effect=OSError(28, "No space left on device")):
            code, _ = self.run_fake(5 << 8)
        self.assertEqual(code, 5)
        start, end, files = records(self.dir)
        self.assertEqual((start, end), ([], []))
        self.assertFalse([f for f in files if f.endswith(".tmp")])
        self.assertIn(b"not written (", self.passed_through())

    def test_switches(self):
        self.assertEqual(DIAG["enabled"]({"BL_DIAG_DIR": self.dir}), self.dir)
        self.assertIsNone(DIAG["enabled"]({"BL_DIAG_DIR": self.dir, "DROIDDECK_DIAGNOSTICS": "0"}))
        self.assertIsNone(DIAG["enabled"]({"BL_DIAG_DIR": os.path.join(self.dir, "missing")}))
        self.assertIsNone(DIAG["enabled"]({}))
        self.assertEqual(DIAG["output_cap"]({"BL_DIAG_OUTPUT_CAP": "nope"}), DIAG["DEFAULT_OUTPUT_CAP"])
        self.assertEqual(DIAG["output_cap"]({"BL_DIAG_OUTPUT_CAP": str(10**12)}), DIAG["MAX_OUTPUT_CAP"])

    def test_credentials_in_launch_arguments_are_blanked(self):
        args = ["-AUTH_LOGIN=unused", "-AUTH_PASSWORD=abc123", "-AUTH_TYPE=exchangecode", "-epicapp=Fortnite",
                "-epicovt=/tmp/ovt.json", "--password", "hunter2", "-windowed", "https://example.com/a?token=x#y",
                "-SteamSessionKey=deadbeef"]
        out = DIAG["redact_args"](args)
        self.assertNotIn("abc123", " ".join(out))
        self.assertNotIn("hunter2", " ".join(out))
        self.assertNotIn("token=x", " ".join(out))
        self.assertNotIn("deadbeef", " ".join(out))
        self.assertIn("-AUTH_PASSWORD=<redacted>", out)
        self.assertIn("-epicapp=Fortnite", out)
        self.assertIn("-windowed", out)
        self.assertIn("https://example.com/a?<redacted>", out)
        self.assertEqual(len(DIAG["redact_args"](["-x"] * 100)), DIAG["MAX_ARGS"] + 1)

    def test_names_come_from_the_library_and_paths_stay_out(self):
        steamapps = Path(self.dir) / "lib/steamapps"
        (steamapps / "common/Some Game").mkdir(parents=True)
        (steamapps / "appmanifest_42.acf").write_text('"AppState"\n{\n\t"appid"\t\t"42"\n\t"name"\t\t"Some \\"Game\\""\n}\n')
        env = {"STEAM_COMPAT_INSTALL_PATH": str(steamapps / "common/Some Game"), "HOME": self.dir}
        self.assertEqual(DIAG["game_name"]("42", env, "Game.exe"), ('Some "Game"', "appmanifest"))
        self.assertEqual(DIAG["game_name"]("43", env, "Z:\\games\\Other.exe"), ("Other", "executable"))
        self.assertEqual(DIAG["game_name"](None, env, None), (None, "unknown"))
        self.assertEqual(DIAG["exe_label"]("/mnt/droiddeck/steamapps/common/Some Game/bin/Game.exe"), "Some Game/bin/Game.exe")
        self.assertEqual(DIAG["exe_label"]("/root/Storage/Users/me/Game.exe"), "Game.exe")


def fex_stats(threads, version=b"FEX-2609-137-g0df84d3", app_type=2):
    """A stats file as FEX writes it (SHMStats.h, version 2): header, then threads linked by offset."""
    header = DIAG["FEX_STATS_HEADER"]
    thread = DIAG["FEX_THREAD"]
    data = bytearray(header.size + thread.size * len(threads))
    first = header.size if threads else 0
    header.pack_into(data, 0, 2, app_type, thread.size, version, first, len(data), 0)
    for i, (tid, counters) in enumerate(threads):
        offset = header.size + i * thread.size
        nxt = offset + thread.size if i + 1 < len(threads) else 0
        thread.pack_into(data, offset, nxt, tid, *(list(counters) + [0] * (13 - len(counters))))
    return bytes(data)


class CpuIdsTest(unittest.TestCase):
    def test_distinct_midrs_and_why_one_was_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            cpus = Path(tmp) / "devices/system/cpu"
            for n, midr in ((0, "0x00000000510f0020"), (1, "0x00000000510f0020"), (2, "0x00000000510f0030")):
                (cpus / ("cpu%d" % n) / "regs/identification").mkdir(parents=True)
                (cpus / ("cpu%d" % n) / "regs/identification/midr_el1").write_text(midr + "\n")
            (cpus / "cpu3").mkdir()
            (cpus / "cpufreq").mkdir()
            ids = DIAG["cpu_ids"](tmp)
            self.assertEqual(ids["midr_el1"], ["0x00000000510f0020", "0x00000000510f0030"])
            self.assertIn("cpu3", ids["error"])
            self.assertEqual(DIAG["cpu_ids"](str(Path(tmp) / "none"))["midr_el1"], [])


class SubreaperTest(unittest.TestCase):
    def test_orphans_wine_hands_over_are_reaped_and_the_game_is_found_in_the_tree(self):
        # Run in a child process: becoming a subreaper sticks to the process that asks.
        script = r"""
import os, runpy, sys, time
DIAG = runpy.run_path(sys.argv[1])
assert DIAG["become_subreaper"](), "prctl"
child = os.fork()
if child == 0:
    # Wine's spawn_process: fork, fork again, the middle one exits at once.
    if os.fork() == 0:
        time.sleep(0.5)
        os._exit(7)
    os._exit(0)
time.sleep(0.2)
# The grandchild was handed to this process, as the game is to the launcher.
grandchildren = [int(p) for p in os.listdir("/proc") if p.isdigit() and DIAG["parent_pid"](int(p)) == os.getpid()]
assert grandchildren, "no orphan was handed over"
assert DIAG["in_tree"](grandchildren[0], os.getpid(), {}), "not in the launcher's tree"
status = DIAG["wait_for"](child)
assert os.WIFEXITED(status) and os.WEXITSTATUS(status) == 0
time.sleep(0.6)
# The orphan exited meanwhile; waiting for the child reaped it on the way or now, never a zombie left.
DIAG["wait_for"](-2)
leftover = [p for p in os.listdir("/proc") if p.isdigit() and DIAG["parent_pid"](int(p)) == os.getpid()]
assert not leftover, leftover
print("ok")
"""
        out = subprocess.run([sys.executable, "-c", script, str(BIN / "droiddeck-game-diag")],
                             capture_output=True, text=True, timeout=30)
        self.assertEqual(out.stdout.strip(), "ok", out.stderr)

    def test_waiting_reaps_others_until_the_child(self):
        calls = iter([(11, 0), (12, 256), (42, 3 << 8)])
        self.assertEqual(DIAG["wait_for"](42, lambda pid, options: next(calls)), 3 << 8)
        def gone(pid, options):
            raise ChildProcessError()
        self.assertIsNone(DIAG["wait_for"](42, gone))


class SamplerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.shm = root / "shm"
        self.shm.mkdir()
        self.sys = root / "sys"
        for cpu, khz in ((0, 1800000), (1, 3300000)):
            d = self.sys / ("devices/system/cpu/cpu%d/cpufreq" % cpu)
            d.mkdir(parents=True)
            (d / "scaling_cur_freq").write_text("%d\n" % khz)
        for policy, (cpus, hw, cap) in (("policy0", ("0", 3300000, 1900800)), ("policy1", ("1", 3800000, 3800000))):
            d = self.sys / ("devices/system/cpu/cpufreq/%s" % policy)  # not a cpuN entry
            d.mkdir(parents=True)
            (d / "related_cpus").write_text(cpus + "\n")
            (d / "cpuinfo_max_freq").write_text("%d\n" % hw)
            (d / "scaling_max_freq").write_text("%d\n" % cap)
            (d / "scaling_governor").write_text("walt\n")
        for z, (kind, temp) in enumerate((("cpu-0-0", 41000), ("gpu", 47500), ("sys-therm-5", 110334))):
            d = self.sys / ("class/thermal/thermal_zone%d" % z)
            d.mkdir(parents=True)
            (d / "temp").write_text("%d\n" % temp)
            (d / "type").write_text(kind + "\n")
        self.base = str(root / "launch")

    def rows(self, name):
        lines = Path("%s.%s.csv" % (self.base, name)).read_text().splitlines()
        head = lines[0].split(",")
        return [dict(zip(head, line.split(","))) for line in lines[1:]]

    def test_the_stats_format_is_read_as_fex_writes_it(self):
        header, threads = DIAG["read_fex_stats"](fex_stats([(11, [5, 1, 2]), (12, [7, 0, 0, 3])]))
        self.assertEqual(header, {"version": "FEX-2609-137-g0df84d3", "app_type": "arm64ec"})
        self.assertEqual(threads[11][:3], (5, 1, 2))
        self.assertEqual(threads[12][3], 3)
        self.assertIsNone(DIAG["read_fex_stats"](b"short"))
        wrong = bytearray(fex_stats([(1, [1])]))
        wrong[0] = 3  # another version of the format
        self.assertIsNone(DIAG["read_fex_stats"](bytes(wrong)))
        # A loop in the list does not hang the reader.
        looped = bytearray(fex_stats([(1, [1]), (2, [2])]))
        DIAG["FEX_THREAD"].pack_into(looped, DIAG["FEX_STATS_HEADER"].size + DIAG["FEX_THREAD"].size, DIAG["FEX_STATS_HEADER"].size, 2, *([2] + [0] * 12))
        self.assertEqual(sorted(DIAG["read_fex_stats"](bytes(looped))[1]), [1, 2])

    def test_counters_add_up_across_threads_and_outlive_a_thread(self):
        me = os.getpid()
        stats = self.shm / ("fex-%d-stats" % me)
        other = self.shm / "fex-1-stats"  # not in the game's tree
        other.write_bytes(fex_stats([(1, [999])]))
        sampler = DIAG["Sampler"](self.base, me, interval=3600, shm=str(self.shm), sys_root=str(self.sys))
        stats.write_bytes(fex_stats([(100, [10, 0, 1]), (101, [5, 0, 0])]))
        sampler.sample()
        stats.write_bytes(fex_stats([(100, [30, 0, 4])]))   # thread 101 exited
        sampler.sample()
        stats.write_bytes(fex_stats([(100, [35, 0, 4]), (101, [2, 0, 0])]))  # slot 101 reused by a new thread
        sampler.sample()
        sampler.finish()
        rows = self.rows("fex")
        self.assertEqual([r["pid"] for r in rows], [str(me)] * 3)
        self.assertEqual([r["jit_ticks"] for r in rows], ["15", "35", "42"])
        self.assertEqual([r["sigbus"] for r in rows], ["1", "4", "4"])
        self.assertEqual(rows[0]["app_type"], "arm64ec")
        self.assertEqual(rows[0]["fex_version"], "FEX-2609-137-g0df84d3")
        cpu = self.rows("cpu")
        self.assertEqual((cpu[0]["cpu0_khz"], cpu[0]["cpu1_khz"]), ("1800000", "3300000"))
        # The clusters' clock limits: policy0 held at 1.9 GHz under its 3.3 GHz maximum.
        self.assertEqual((cpu[0]["policy0_max_khz"], cpu[0]["policy1_max_khz"]), ("1900800", "3800000"))
        # A board zone that reads 110 C does not hide the CPU's and GPU's own.
        self.assertEqual((cpu[0]["cpu_temp_max_millic"], cpu[0]["gpu_temp_max_millic"]), ("41000", "47500"))
        self.assertEqual((cpu[0]["thermal_max_millic"], cpu[0]["thermal_max_zone"]), ("110334", "sys-therm-5"))

    def test_the_start_record_names_the_clusters_limits_and_every_zone(self):
        info = DIAG["host_cpu"](str(self.sys))
        self.assertEqual(info["policies"]["policy0"], {"cpus": "0", "cpuinfo_max_khz": 3300000, "cpuinfo_min_khz": None,
                                                       "scaling_max_khz": 1900800, "scaling_min_khz": None, "governor": "walt"})
        self.assertEqual(info["thermal_zones"], [["cpu-0-0", 41000], ["gpu", 47500], ["sys-therm-5", 110334]])
        self.assertEqual(DIAG["host_cpu"](str(Path(self.tmp.name) / "none")), {"policies": {}, "thermal_zones": []})

    def test_rows_stop_at_the_limit_and_unreadable_sources_are_skipped(self):
        sampler = DIAG["Sampler"](self.base, os.getpid(), interval=3600, limit=100, shm=str(self.shm / "missing"),
                                  sys_root=str(self.sys))
        for _ in range(20):
            sampler.sample()
        written = sampler.finish()
        self.assertLessEqual(written["cpu"], 100 + 40)
        self.assertEqual(written["fex"], 0)
        self.assertEqual(written["interval_s"], 3600)
        self.assertFalse(Path(self.base + ".fex.csv").exists())

    def test_the_interval_switch(self):
        self.assertEqual(DIAG["sample_interval"]({}), DIAG["SAMPLE_INTERVAL"])
        self.assertEqual(DIAG["sample_interval"]({"BL_DIAG_SAMPLE_INTERVAL": "0"}), 0.0)
        self.assertEqual(DIAG["sample_interval"]({"BL_DIAG_SAMPLE_INTERVAL": "0.1"}), 1.0)
        self.assertEqual(DIAG["sample_interval"]({"BL_DIAG_SAMPLE_INTERVAL": "x"}), DIAG["SAMPLE_INTERVAL"])


class GameEnvRecordsTest(unittest.TestCase):
    """droiddeck-game-env end to end, with a fake Proton (a script) standing in for the game."""

    def launch(self, tmp, exit_code, verb="waitforexitandrun", diag=True):
        home = Path(tmp)
        inbox = home / "inbox"
        inbox.mkdir(exist_ok=True)
        steamapps = home / "lib/steamapps"
        (steamapps / "common/Game").mkdir(parents=True, exist_ok=True)
        (steamapps / "appmanifest_42.acf").write_text('"AppState" { "name" "Test Game" }\n'.replace(" { ", "\n{\n\t").replace(' }', "\n}"))
        probe = home / "fake-proton"
        probe.write_text("#!/usr/bin/python3\nimport sys\nprint('err: from the game', file=sys.stderr)\nsys.exit(%d)\n" % exit_code)
        probe.chmod(0o755)
        env = {**os.environ, "HOME": tmp, "STEAM_COMPAT_DATA_PATH": "/compatdata/42",
               "STEAM_COMPAT_INSTALL_PATH": str(steamapps / "common/Game")}
        if diag:
            env["BL_DIAG_DIR"] = str(inbox)
        result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), verb, "Game.exe", "-AUTH_PASSWORD=secret"],
                                env=env, text=True, capture_output=True)
        return result, records(inbox)

    def test_a_recorded_launch_reports_the_games_status_and_writes_both_records(self):
        with tempfile.TemporaryDirectory() as tmp:
            result, ((start,), (end,), files) = self.launch(tmp, 7)
            self.assertEqual(result.returncode, 7, result.stderr)
            self.assertIn("err: from the game", result.stderr)
            self.assertEqual((start["launcher"], start["app_id"], start["game_key"], start["name"]), ("proton", "42", "steam-42", "Test Game"))
            self.assertEqual(start["exe"], "Game.exe")
            self.assertIn("-AUTH_PASSWORD=<redacted>", start["args"])
            self.assertNotIn("secret", json.dumps(start))
            self.assertIn("TU_DEBUG", start["graphics"])
            self.assertEqual((end["exit_code"], end["reported_status"]), (7, 7))
            out = (Path(tmp) / "inbox" / start["output_file"]).read_text()
            self.assertIn("err: from the game", out)

    def test_other_verbs_and_a_switched_off_recorder_are_exec_d_as_before(self):
        with tempfile.TemporaryDirectory() as tmp:
            result, (start, end, files) = self.launch(tmp, 4, verb="run")
            self.assertEqual(result.returncode, 4)
            self.assertEqual(files, [])
        with tempfile.TemporaryDirectory() as tmp:
            result, (start, end, files) = self.launch(tmp, 4, diag=False)
            self.assertEqual(result.returncode, 4)
            self.assertEqual(files, [])


if __name__ == "__main__":
    unittest.main()
