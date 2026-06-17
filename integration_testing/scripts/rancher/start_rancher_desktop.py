#!/usr/bin/env python3
"""SCRIPT: integration tooling — not a pytest/cargo/junit test target."""

from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent.parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from common import docker_info_ok, ensure_docker_running, find_rdctl, use_native_docker  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    if use_native_docker():
        if docker_info_ok():
            print("Native Docker Engine already up.")
            return 0
        ensure_docker_running()
        print("Native Docker Engine ready (docker info OK).")
        return 0

    rdctl = find_rdctl()
    if rdctl is None:
        print(
            "ERROR: rdctl not found and Docker is not installed. Run:\n"
            "  python3 integration_testing/scripts/rancher/check_rancher_desktop.py",
            file=sys.stderr,
        )
        return 1

    try:
        subprocess.run([str(rdctl), "shell", "docker", "info"], check=True, capture_output=True)
        print("Rancher Desktop container runtime already up.")
        return 0
    except subprocess.CalledProcessError:
        pass

    print("Starting Rancher Desktop (this may take a minute on first boot)...")
    subprocess.run([str(rdctl), "start"], check=True)

    deadline = time.time() + 300
    while time.time() < deadline:
        try:
            subprocess.run(
                [str(rdctl), "shell", "docker", "info"],
                check=True,
                capture_output=True,
            )
            print("Rancher Desktop ready (docker info OK).")
            return 0
        except subprocess.CalledProcessError:
            time.sleep(5)

    print("ERROR: timed out waiting for Rancher Desktop docker engine.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
