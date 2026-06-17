#!/usr/bin/env python3
"""SCRIPT: integration tooling — not a pytest/cargo/junit test target."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent.parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from common import find_rdctl, log, run, use_native_docker  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Stop Rancher Desktop after integration tests, or optionally stop system Docker."
    )
    parser.add_argument(
        "--stop-docker",
        action="store_true",
        help="On headless Linux (native Docker mode), stop the systemd docker service.",
    )
    args = parser.parse_args(argv)

    if use_native_docker():
        if args.stop_docker:
            log("Stopping system Docker service...")
            run(["sudo", "systemctl", "stop", "docker"])
            log("Docker service stopped.")
        else:
            log(
                "Native Docker mode — leaving system Docker service running. "
                "Use --stop-docker to stop it, or: sudo systemctl stop docker"
            )
        return 0

    rdctl = find_rdctl()
    if rdctl is None:
        print("rdctl not found; nothing to stop.")
        return 0

    print("Shutting down Rancher Desktop...")
    subprocess.run([str(rdctl), "shutdown"], check=False)
    print("Rancher Desktop shutdown requested.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
