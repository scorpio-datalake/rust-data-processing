"""Shared helpers for scripts/python_scripts build, test, and doc modules."""

from __future__ import annotations

import gc
import sys
import os
import platform
import shutil
import subprocess
import time
from pathlib import Path

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

REPO_ROOT = Path(__file__).resolve().parents[2]
JVM_SYS_DIR = REPO_ROOT / "bindings" / "jvm-sys"
JVM_GRADLE_DIR = REPO_ROOT / "bindings" / "java" / "rust-data-processing-jvm"
PYTHON_WRAPPER = REPO_ROOT / "python-wrapper"

DEFAULT_WAIT_SECONDS = 10
DEFAULT_RUST_BUILD_TEST_WAIT_SECONDS = 30


def banner(title: str) -> None:
    print(f"\n== {title} ==", flush=True)


def run(
    cmd: list[str],
    *,
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
) -> None:
    cwd = cwd or REPO_ROOT
    print(f"+ {' '.join(cmd)}  (cwd={cwd})", flush=True)
    merged = os.environ.copy()
    if env:
        merged.update(env)
    subprocess.run(cmd, cwd=cwd, env=merged, check=True)


def require_tool(name: str) -> None:
    if shutil.which(name) is None:
        raise SystemExit(f"Required tool not on PATH: {name}")


def pause(seconds: float, reason: str) -> None:
    """Let the OS reclaim disk and memory between heavy compile/test steps."""
    if seconds <= 0:
        return
    banner(f"Pause {seconds:g}s — {reason}")
    time.sleep(seconds)
    gc.collect()


def setup_rust_toolchain_env(*, offline: bool = False) -> None:
    os.environ["RUSTUP_NO_UPDATE_CHECK"] = "1"
    if offline:
        os.environ["RUSTUP_OFFLINE"] = "1"
        os.environ["CARGO_NET_OFFLINE"] = "true"
    else:
        os.environ.pop("CARGO_NET_OFFLINE", None)
        os.environ.pop("RUSTUP_OFFLINE", None)
    if shutil.which("sccache"):
        os.environ["RUSTC_WRAPPER"] = "sccache"


def native_lib_release() -> Path:
    if platform.system() == "Windows":
        return JVM_SYS_DIR / "target" / "release" / "rdp_jvm_sys.dll"
    if platform.system() == "Darwin":
        return JVM_SYS_DIR / "target" / "release" / "librdp_jvm_sys.dylib"
    return JVM_SYS_DIR / "target" / "release" / "librdp_jvm_sys.so"


def gradlew_path() -> Path:
    name = "gradlew.bat" if platform.system() == "Windows" else "gradlew"
    return JVM_GRADLE_DIR / name
