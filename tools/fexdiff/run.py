#!/usr/bin/env python3
"""Differential test of an x86-64 translator against this x86-64 machine.

Builds diffx86.c as a static x86-64 program (musl: its startup does not depend on brk, which a
translator under emulation may not provide the way glibc's static TLS setup wants), runs each
section natively - the reference - and under the translator command, and compares the outputs
line by line. Lines starting with '#' (CPUID, information) are not compared.

    python3 tools/fexdiff/run.py --translator "qemu-aarch64-static -L /usr/aarch64-linux-gnu -cpu max,sve=off,sme=off build/Bin/FEX" \
        --env FEX_HOSTFEATURES=disablelrcpc2 --out /tmp/fexdiff

Under qemu, the emulated CPU decides which of the translator's code paths run: pin it (QEMU_CPU) to
the host being stood in for - "max,sve=off,sme=off" for a CPU without SVE (Oryon), or
"max,sme=off,sve-default-vector-length=16" for SVE2 at 128 bits (Cortex-X/A7xx). summary.txt names it.

Exit status: 0 when every section matches, 1 when any differs or fails.
"""

from __future__ import annotations

import argparse
import os
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
SECTIONS = ("int", "sse", "avx", "x87", "atomic", "smc", "sig", "sys", "jit")


def build(out: Path, compiler: str) -> Path:
    binary = out / "diffx86"
    cmd = [compiler, "-O1", "-static", "-pthread", "-Wall", "-Wno-unused-function", "-Wno-overflow",
           "-idirafter", "/usr/include", "-idirafter", "/usr/include/x86_64-linux-gnu",
           "-o", str(binary), str(HERE / "diffx86.c"), "-lm"]
    subprocess.run(cmd, check=True)
    return binary


def run(cmd: list[str], env: dict, timeout: int) -> tuple[int, str, str, float]:
    started = time.monotonic()
    try:
        p = subprocess.run(cmd, env=env, capture_output=True, text=True, errors="replace", timeout=timeout)
        return p.returncode, p.stdout, p.stderr, time.monotonic() - started
    except subprocess.TimeoutExpired as e:
        out = e.stdout.decode(errors="replace") if isinstance(e.stdout, bytes) else (e.stdout or "")
        return -1, out[:100000], "timeout after %d s" % timeout, time.monotonic() - started


def compared(text: str) -> list[str]:
    return [line for line in text.splitlines() if line and not line.startswith("#")]


def diff(reference: list[str], actual: list[str]) -> list[tuple[str, str]]:
    """Pairs of (reference, actual) lines that differ; missing lines pair with ''."""
    out = []
    for i in range(max(len(reference), len(actual))):
        r = reference[i] if i < len(reference) else ""
        a = actual[i] if i < len(actual) else ""
        if r != a:
            out.append((r, a))
    return out


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--translator", required=True, help="command that runs an x86-64 program, the program path appended")
    parser.add_argument("--env", action="append", default=[], help="NAME=VALUE for the translated run (repeatable)")
    parser.add_argument("--sections", default=",".join(SECTIONS))
    parser.add_argument("--out", default="fexdiff-out")
    parser.add_argument("--compiler", default=shutil.which("musl-gcc") or "gcc")
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--binary", help="use this diffx86 build instead of building one")
    args = parser.parse_args(argv)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    binary = Path(args.binary) if args.binary else build(out, args.compiler)
    env = dict(os.environ)
    for item in args.env:
        name, _, value = item.partition("=")
        env[name] = value
    translator = shlex.split(args.translator)
    failed = False
    summary = ["# translator: %s" % args.translator,
               "# env: %s" % " ".join(args.env + ["QEMU_CPU=%s" % os.environ.get("QEMU_CPU", "")])]
    for section in args.sections.split(","):
        rc_n, native, _, t_n = run([str(binary), section], dict(os.environ), args.timeout)
        rc_t, translated, err, t_t = run(translator + [str(binary), section], env, args.timeout)
        (out / ("native_%s.txt" % section)).write_text(native)
        (out / ("translated_%s.txt" % section)).write_text(translated)
        (out / ("translated_%s.err" % section)).write_text(err)
        pairs = diff(compared(native), compared(translated))
        status = "ok" if not pairs and rc_t == 0 and rc_n == 0 else "DIFF"
        failed |= status != "ok"
        summary.append("%-7s %-4s lines=%d differing=%d native_rc=%d translated_rc=%d native=%.1fs translated=%.1fs"
                       % (section, status, len(compared(native)), len(pairs), rc_n, rc_t, t_n, t_t))
        with open(out / ("diff_%s.txt" % section), "w") as f:
            for r, a in pairs:
                f.write("- %s\n+ %s\n" % (r, a))
    print("\n".join(summary))
    (out / "summary.txt").write_text("\n".join(summary) + "\n")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
