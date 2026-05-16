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


def _prepend_path(directory: Path) -> None:
    s = str(directory)
    path = os.environ.get("PATH", "")
    if s not in path.split(os.pathsep):
        os.environ["PATH"] = f"{s}{os.pathsep}{path}" if path else s


def require_uv() -> None:
    """Python wrapper scripts use uv; install via astral.sh when missing on fresh VMs."""
    _prepend_path(Path.home() / ".local" / "bin")
    if shutil.which("uv"):
        return
    if os.environ.get("BUILD_ALL_NO_AUTO_UV"):
        raise SystemExit(
            "error: `uv` not found. Install from https://docs.astral.sh/uv/ "
            "or unset BUILD_ALL_NO_AUTO_UV to allow the astral installer.",
        )
    banner("Python: installing uv (https://astral.sh/uv/install.sh)")
    subprocess.run(
        ["bash", "-c", "curl -LsSf https://astral.sh/uv/install.sh | sh"],
        check=True,
    )
    _prepend_path(Path.home() / ".local" / "bin")
    require_tool("uv")


def pause(seconds: float, reason: str) -> None:
    """Let the OS reclaim disk and memory between heavy compile/test steps."""
    if seconds <= 0:
        return
    banner(f"Pause {seconds:g}s — {reason}")
    time.sleep(seconds)
    gc.collect()


def _linux_os_release() -> dict[str, str]:
    out: dict[str, str] = {}
    path = Path("/etc/os-release")
    if not path.is_file():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        out[key] = val.strip().strip('"')
    return out


def _is_debian_like_linux() -> bool:
    if platform.system() != "Linux":
        return False
    data = _linux_os_release()
    if data.get("ID") in {"debian", "ubuntu"}:
        return True
    return "debian" in data.get("ID_LIKE", "")


def _ensure_native_linker_linux() -> None:
    """Many crates run `cc` in build.rs; minimal cloud images often omit a C toolchain."""
    if platform.system() != "Linux":
        return
    if shutil.which("cc") or shutil.which("gcc"):
        return
    if os.environ.get("BUILD_ALL_NO_AUTO_BUILD_ESSENTIAL"):
        raise SystemExit(
            "error: C linker `cc` not found. Install a toolchain (e.g. "
            "`sudo apt install build-essential` on Ubuntu) or unset "
            "BUILD_ALL_NO_AUTO_BUILD_ESSENTIAL to allow auto-install on Debian/Ubuntu.",
        )
    if not _is_debian_like_linux():
        raise SystemExit(
            "error: C linker `cc` not found. Install gcc or clang for your OS, then re-run.",
        )
    banner("Linux: installing build-essential (provides `cc` for Rust build scripts)")
    subprocess.run(["sudo", "apt-get", "update", "-qq"], check=True)
    subprocess.run(
        [
            "sudo",
            "DEBIAN_FRONTEND=noninteractive",
            "apt-get",
            "install",
            "-y",
            "build-essential",
        ],
        check=True,
    )
    if not (shutil.which("cc") or shutil.which("gcc")):
        raise SystemExit("error: `cc` still not on PATH after installing build-essential.")


def _prefetch_cargo_for_offline_workflow() -> None:
    """Ensure the local crate cache can satisfy --offline; fetch from the network once if needed."""
    if os.environ.get("BUILD_ALL_NO_CARGO_PREFETCH"):
        return
    r = subprocess.run(
        ["cargo", "fetch", "--locked", "--offline"],
        cwd=REPO_ROOT,
        capture_output=True,
    )
    if r.returncode == 0:
        return
    banner(
        "Cargo: fetch dependencies (one-time network; --offline needs a populated registry cache)",
    )
    env = os.environ.copy()
    env.pop("CARGO_NET_OFFLINE", None)
    env.pop("RUSTUP_OFFLINE", None)
    subprocess.run(["cargo", "fetch", "--locked"], cwd=REPO_ROOT, env=env, check=True)


def setup_rust_toolchain_env(*, offline: bool = False) -> None:
    _ensure_native_linker_linux()
    os.environ["RUSTUP_NO_UPDATE_CHECK"] = "1"
    if offline:
        os.environ["RUSTUP_OFFLINE"] = "1"
        os.environ["CARGO_NET_OFFLINE"] = "true"
    else:
        os.environ.pop("CARGO_NET_OFFLINE", None)
        os.environ.pop("RUSTUP_OFFLINE", None)
    if shutil.which("sccache"):
        os.environ["RUSTC_WRAPPER"] = "sccache"
    if offline:
        _prefetch_cargo_for_offline_workflow()


def native_lib_release() -> Path:
    if platform.system() == "Windows":
        return JVM_SYS_DIR / "target" / "release" / "rdp_jvm_sys.dll"
    if platform.system() == "Darwin":
        return JVM_SYS_DIR / "target" / "release" / "librdp_jvm_sys.dylib"
    return JVM_SYS_DIR / "target" / "release" / "librdp_jvm_sys.so"


def gradlew_path() -> Path:
    name = "gradlew.bat" if platform.system() == "Windows" else "gradlew"
    return JVM_GRADLE_DIR / name


def gradlew_argv(*gradle_args: str) -> list[str]:
    """Command prefix to run the Gradle wrapper (fixes non-executable gradlew on fresh checkouts)."""
    gw = gradlew_path()
    if not gw.is_file():
        raise SystemExit(f"Gradle wrapper not found: {gw}")
    if platform.system() == "Windows":
        return [str(gw), *gradle_args]
    if not os.access(gw, os.X_OK):
        try:
            gw.chmod(gw.stat().st_mode | 0o111)
        except OSError:
            return ["bash", str(gw), *gradle_args]
    return [str(gw), *gradle_args]
