#!/usr/bin/env python3
"""SCRIPT: integration tooling — not a pytest/cargo/junit test target."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent.parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from common import (  # noqa: E402
    die,
    docker_command,
    docker_info_ok,
    ensure_docker_running,
    find_rdctl,
    log,
    rdctl_json_setting,
    use_native_docker,
)

RECOMMENDED = """
Recommended for integration testing (on-demand only):
  - application.autoStart=false          — do not start Rancher at OS login
  - application.startInBackground=false    — do not hide in background tray at startup
  - application.window.quitOnClose=true    — quit Rancher when the window closes (optional)

Start Rancher only when running integration tests:
  python3 integration_testing/scripts/rancher/start_rancher_desktop.py

Stop Rancher when finished:
  python3 integration_testing/scripts/rancher/stop_rancher_desktop.py
"""


def check_native_docker() -> int:
    if not shutil.which("docker"):
        print(
            """ERROR: Neither Rancher Desktop (rdctl) nor Docker Engine found.

On a headless Linux server (no GUI / KVM), install Docker Engine:
  https://docs.docker.com/engine/install/ubuntu/

On a workstation with a desktop, install Rancher Desktop:
  https://docs.rancherdesktop.io/getting-started/installation/
""",
            file=sys.stderr,
        )
        return 1
    try:
        ensure_docker_running()
    except SystemExit as exc:
        return int(exc.code) if exc.code is not None else 1
    print("Native Docker Engine mode (headless Linux — no Rancher Desktop).")
    try:
        ver = subprocess.run(
            docker_command(["version", "--format", "{{.Server.Version}}"]),
            capture_output=True,
            text=True,
            check=True,
        )
        print(f"  docker server version: {ver.stdout.strip()}")
    except subprocess.CalledProcessError:
        print("  docker server version: (unable to query)")
    print("\ncheck_rancher_desktop: OK (native Docker)")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Verify Rancher Desktop is installed and optional on-demand settings."
    )
    parser.add_argument(
        "--configure",
        action="store_true",
        help="Apply recommended settings (disable autostart, etc.).",
    )
    args = parser.parse_args(argv)

    rdctl = find_rdctl()
    if rdctl is None:
        if use_native_docker() or shutil.which("docker"):
            if args.configure:
                log("NOTE: --configure applies to Rancher Desktop only; skipped in native Docker mode.")
            return check_native_docker()
        print(
            """ERROR: Rancher Desktop / rdctl not found.

Install Rancher Desktop (Linux desktop with KVM):
  https://docs.rancherdesktop.io/getting-started/installation/

On this headless server, use Docker Engine instead:
  https://docs.docker.com/engine/install/ubuntu/
""",
            file=sys.stderr,
        )
        return 1

    print(f"Rancher Desktop CLI: {rdctl}")
    try:
        subprocess.run([str(rdctl), "version"], check=True)
    except subprocess.CalledProcessError:
        print(
            "WARN: rdctl found but 'rdctl version' failed (Rancher Desktop may not be installed fully).",
            file=sys.stderr,
        )

    if not shutil.which("jq"):
        print("NOTE: install jq to inspect rdctl list-settings JSON (optional).", file=sys.stderr)

    auto_start = rdctl_json_setting(rdctl, ".application.autoStart // empty")
    start_in_background = rdctl_json_setting(rdctl, ".application.startInBackground // empty")
    quit_on_close = rdctl_json_setting(rdctl, ".application.window.quitOnClose // empty")

    print("\nCurrent behavior settings (from rdctl list-settings):")
    print(f"  application.autoStart          = {auto_start or 'unknown'}")
    print(f"  application.startInBackground  = {start_in_background or 'unknown'}")
    print(f"  application.window.quitOnClose = {quit_on_close or 'unknown'}")

    linux_autostart = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "autostart" / "rancher-desktop.desktop"
    if linux_autostart.is_file():
        print(f"\nLinux autostart desktop entry exists: {linux_autostart}")
        print("  (Rancher may start at login unless autoStart is false.)")

    if not args.configure:
        print(RECOMMENDED)
        if auto_start == "true" or start_in_background == "true":
            print(
                "\nWARN: autostart/background may be enabled. Re-run with --configure to apply recommended settings.",
                file=sys.stderr,
            )
            return 2
        print("\ncheck_rancher_desktop: OK")
        return 0

    print("\nApplying recommended Rancher Desktop settings...")
    subprocess.run([str(rdctl), "set", "--application.auto-start=false"], check=True)
    subprocess.run([str(rdctl), "set", "--application.start-in-background=false"], check=True)
    subprocess.run([str(rdctl), "set", "--application.window.quit-on-close=true"], check=True)

    auto_start = rdctl_json_setting(rdctl, ".application.autoStart // empty")
    start_in_background = rdctl_json_setting(rdctl, ".application.startInBackground // empty")
    quit_on_close = rdctl_json_setting(rdctl, ".application.window.quitOnClose // empty")

    print("\nUpdated settings:")
    print(f"  application.autoStart          = {auto_start or 'unknown'}")
    print(f"  application.startInBackground  = {start_in_background or 'unknown'}")
    print(f"  application.window.quitOnClose = {quit_on_close or 'unknown'}")

    if auto_start == "true":
        die("autoStart is still true after rdctl set.")

    print(RECOMMENDED)
    print("\ncheck_rancher_desktop: configured OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
