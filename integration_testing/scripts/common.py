"""Shared helpers for integration_testing/scripts (library module — not a CLI entrypoint)."""

from __future__ import annotations

import gc
import os
import platform
import shlex
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

INTEG_ROOT = Path(__file__).resolve().parent.parent
REPO_ROOT = INTEG_ROOT.parent
LIBS_DIR = INTEG_ROOT / "libs"
DATA_DIR = INTEG_ROOT / "data"
SCRIPTS_DIR = INTEG_ROOT / "scripts"
# Isolated from repo ``target/`` so integration builds do not race with ``build_all`` / clippy.
INTEG_TARGET_DIR = INTEG_ROOT / ".target"

RUST_STAMP = LIBS_DIR / "rust" / ".built_at"
JAVA_STAMP = LIBS_DIR / "java" / ".built_at"
PYTHON_STAMP = LIBS_DIR / "python" / ".built_at"
FAILURE_FLAG = LIBS_DIR / ".last_test_failed"

for sub in ("rust", "java", "python"):
    (LIBS_DIR / sub).mkdir(parents=True, exist_ok=True)
DATA_DIR.mkdir(parents=True, exist_ok=True)


def log(msg: str) -> None:
    print(f"[integration] {msg}", flush=True)


def die(msg: str, code: int = 1) -> None:
    print(f"[integration] ERROR: {msg}", file=sys.stderr, flush=True)
    raise SystemExit(code)


def require_tool(name: str) -> None:
    if shutil.which(name) is None:
        die(f"{name} not found")


def run(cmd: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    cwd = cwd or REPO_ROOT
    log(f"+ {' '.join(cmd)}  (cwd={cwd})")
    merged = os.environ.copy()
    if env:
        merged.update(env)
    subprocess.run(cmd, cwd=cwd, env=merged, check=True)


def load_cargo_env() -> None:
    """Ensure ``cargo`` is on PATH (``~/.cargo/bin`` from rustup)."""
    cargo_bin = Path.home() / ".cargo" / "bin"
    if cargo_bin.is_dir():
        path = os.environ.get("PATH", "")
        prefix = str(cargo_bin)
        if prefix not in path.split(":"):
            os.environ["PATH"] = f"{prefix}:{path}" if path else prefix


def setup_integration_build_env() -> None:
    """Cargo + isolated target dir (safe alongside ``build_all`` on the same machine)."""
    load_cargo_env()
    INTEG_TARGET_DIR.mkdir(parents=True, exist_ok=True)
    os.environ["CARGO_TARGET_DIR"] = str(INTEG_TARGET_DIR)
    # Limit parallel link/native compiles; override with INTEGR_CARGO_JOBS if needed.
    if "CARGO_BUILD_JOBS" not in os.environ:
        jobs = os.environ.get("INTEG_CARGO_JOBS", "2")
        os.environ["CARGO_BUILD_JOBS"] = jobs


def disk_clean_enabled() -> bool:
    """When false (``INTEG_NO_DISK_CLEAN=1``), skip pre-build disk cleanup."""
    return os.environ.get("INTEG_NO_DISK_CLEAN", "").lower() not in (
        "1",
        "true",
        "yes",
    )


def available_disk_gib(path: Path | str = INTEG_ROOT) -> float:
    """Free space on the filesystem containing ``path`` (GiB)."""
    return shutil.disk_usage(path).free / (1024**3)


def ensure_min_disk_space(
    *,
    min_gib: float | None = None,
    path: Path | str = INTEG_ROOT,
    context: str = "integration build",
) -> None:
    """Fail fast when the disk is too full to link release Polars artifacts."""
    if min_gib is None:
        raw = os.environ.get("INTEG_MIN_DISK_GIB", "6")
        min_gib = float(raw)
    free = available_disk_gib(path)
    if free >= min_gib:
        return
    die(
        f"Only {free:.1f} GiB free on {path} ({context} needs ~{min_gib:.0f} GiB). "
        "Free space (e.g. remove repo target/, run build_all_libs after build_all finishes) "
        "or set INTEGR_NO_DISK_CLEAN=1 only if you manage disk manually."
    )


def report_disk_usage(title: str, paths: list[Path]) -> None:
    log(f"Disk: {title}")
    subprocess.run(["df", "-h", str(INTEG_ROOT)], check=False)
    existing = [p for p in paths if p.exists()]
    if not existing:
        log("  (nothing on disk yet for listed paths)")
        return
    subprocess.run(["du", "-sh", *[str(p) for p in existing]], check=False)


def _remove_path(path: Path) -> None:
    if not path.exists():
        return
    try:
        display = path.relative_to(REPO_ROOT)
    except ValueError:
        display = path
    log(f"  removing {display}")
    if path.is_dir():
        shutil.rmtree(path)
    else:
        path.unlink()


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def build_all_run_in_progress() -> bool:
    """True when ``build_all_run.sh`` still has a live launcher or build shell."""
    runs_dir = REPO_ROOT / ".build_all_runs"
    if not runs_dir.is_dir():
        return False
    for run_dir in runs_dir.glob("run-*"):
        if not run_dir.is_dir():
            continue
        for name in ("run.pid", "launcher.pid"):
            pid_file = run_dir / name
            if not pid_file.is_file():
                continue
            try:
                pid = int(pid_file.read_text(encoding="utf-8").strip())
            except ValueError:
                continue
            if _pid_alive(pid):
                return True
    return False


def repo_cargo_build_in_progress() -> bool:
    """True when ``cargo``/``rustc`` is compiling in this repo (any ``target/`` dir)."""
    try:
        proc = subprocess.run(
            ["pgrep", "-af", "cargo|rustc"],
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return False
    root = str(REPO_ROOT)
    for line in proc.stdout.splitlines():
        if root in line and "integration_testing" not in line:
            return True
    return False


def assert_safe_to_clean_repo_target() -> None:
    """Refuse to delete repo ``target/`` while ``build_all`` or repo ``cargo`` is active."""
    if build_all_run_in_progress():
        die(
            "build_all is still running (check ./build_all_run.sh status). "
            "Wait for it to finish before integration lib builds — removing repo target/ "
            "mid-build causes linker 'No such file or directory' errors."
        )
    if repo_cargo_build_in_progress():
        die(
            "cargo/rustc is still compiling under the repo. "
            "Wait for it to finish before integration lib builds."
        )


def cleanup_disk_before_integration_build(*, force_integr_target: bool = False) -> None:
    """Drop repo ``build_all`` artifacts and stale JVM/Python targets before integration builds.

    Always removes repo ``target/`` so integration libs do not stack on a full ``build_all`` tree.
    Removes ``integration_testing/.target/`` only when ``force_integr_target`` (``--force``).
    """
    if not disk_clean_enabled():
        return
    assert_safe_to_clean_repo_target()
    paths = [
        REPO_ROOT / "target",
        REPO_ROOT / "bindings" / "jvm-sys" / "target",
        REPO_ROOT / "python-wrapper" / "target",
        REPO_ROOT / "bindings" / "java" / "rust-data-processing-jvm" / "target",
    ]
    if force_integr_target:
        paths.insert(0, INTEG_TARGET_DIR)
    report_disk_usage("before integration cleanup", paths)
    log("Disk: integration phase cleanup")
    for path in paths:
        _remove_path(path)
    gc.collect()


def prepare_integration_disk(*, force: bool = False) -> None:
    """Free conflicting artifacts, then verify minimum free space."""
    if os.environ.get("INTEG_DISK_PREPARED") == "1":
        return
    cleanup_disk_before_integration_build(force_integr_target=force)
    ensure_min_disk_space(context="integration lib build")
    os.environ["INTEG_DISK_PREPARED"] = "1"


def cargo_target_dir() -> Path:
    return Path(os.environ.get("CARGO_TARGET_DIR", REPO_ROOT / "target"))


def load_env_sh(path: Path) -> dict[str, str]:
    """Parse simple ``export KEY=value`` env files written by build scripts."""
    out: dict[str, str] = {}
    if not path.is_file():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:]
        if "=" not in line:
            continue
        key, _, val = line.partition("=")
        val = val.strip().strip('"').strip("'")
        if "${" in val:
            val = os.path.expandvars(val)
        out[key] = val
    return out


def apply_env_sh(path: Path) -> None:
    for key, val in load_env_sh(path).items():
        if key == "PYTHONPATH" and key in os.environ:
            os.environ[key] = f"{val}:{os.environ[key]}"
        else:
            os.environ[key] = val


def needs_rebuild(stamp_file: Path, watch_paths: list[str]) -> bool:
    if os.environ.get("INTEG_FORCE_REBUILD") == "1":
        return True
    if FAILURE_FLAG.is_file():
        return True
    if not stamp_file.is_file():
        return True
    stamp_mtime = stamp_file.stat().st_mtime
    for rel in watch_paths:
        root = REPO_ROOT / rel
        if not root.exists():
            continue
        if root.is_file():
            if root.stat().st_mtime > stamp_mtime:
                return True
            continue
        for path in root.rglob("*"):
            if path.is_file() and path.stat().st_mtime > stamp_mtime:
                return True
    return False


def mark_built(stamp_file: Path) -> None:
    stamp_file.parent.mkdir(parents=True, exist_ok=True)
    stamp_file.write_text(
        datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        encoding="utf-8",
    )
    if FAILURE_FLAG.is_file():
        FAILURE_FLAG.unlink()
    try:
        rev = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "rev-parse", "--short", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    except subprocess.CalledProcessError:
        rev = "unknown"
    (stamp_file.parent / ".git_rev").write_text(rev, encoding="utf-8")


def mark_test_failed() -> None:
    FAILURE_FLAG.parent.mkdir(parents=True, exist_ok=True)
    FAILURE_FLAG.write_text(
        datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        encoding="utf-8",
    )


def _jvm_lib_basename() -> str:
    system = platform.system()
    if system == "Darwin":
        return "librdp_jvm_sys.dylib"
    if system.startswith("MINGW") or system.endswith("_NT"):
        return "rdp_jvm_sys.dll"
    return "librdp_jvm_sys.so"


def native_jvm_src() -> Path:
    return cargo_target_dir() / "release" / _jvm_lib_basename()


def jvm_lib_dest() -> Path:
    return LIBS_DIR / "java" / _jvm_lib_basename()


def find_python_extension() -> Path | None:
    for pattern in (
        REPO_ROOT / "python-wrapper" / "rust_data_processing" / "_rust_data_processing*.so",
        REPO_ROOT / "python-wrapper" / ".venv" / "lib" / "python*" / "site-packages" / "_rust_data_processing*.so",
    ):
        if "*" in str(pattern):
            for match in pattern.parent.glob(pattern.name):
                if match.is_file():
                    return match
        elif pattern.is_file():
            return pattern
    return None


def write_java_env() -> None:
    dest = jvm_lib_dest()
    (LIBS_DIR / "java" / "env.sh").write_text(
        f"""# Source before Java integration tests: source integration_testing/libs/java/env.sh
export RDP_JVM_SYS="{dest}"
export RDP_INTEGRATION_JAVA_BUILT=1
""",
        encoding="utf-8",
    )


def write_rust_env() -> None:
    (LIBS_DIR / "rust" / "env.sh").write_text(
        f"""# Source before Rust integration tests: source integration_testing/libs/rust/env.sh
export RDP_REPO_ROOT="{REPO_ROOT}"
export RDP_INTEGRATION_RUST_FEATURES=db_connectorx
export RDP_INTEGRATION_RUST_BUILT=1
""",
        encoding="utf-8",
    )


def write_python_env(ext_path: Path) -> None:
    (LIBS_DIR / "python" / "env.sh").write_text(
        f"""# Source before Python integration tests: source integration_testing/libs/python/env.sh
export RDP_INTEGRATION_PYTHON_EXT="{ext_path}"
export RDP_INTEGRATION_PYTHON_BUILT=1
export PYTHONPATH="{REPO_ROOT / 'python-wrapper'}:${{PYTHONPATH:-}}"
""",
        encoding="utf-8",
    )


def ensure_linux_native_deps() -> None:
    if platform.system() != "Linux":
        return
    if shutil.which("pkg-config") is None:
        log("WARN: pkg-config not found; db_connectorx build may fail on Linux.")
        return
    if not Path("/usr/include/gssapi.h").is_file() and not Path(
        "/usr/include/gssapi/gssapi.h"
    ).is_file():
        log("WARN: gssapi.h missing — install libkrb5-dev for ConnectorX (Oracle) builds.")


def count_lines(path: Path) -> int:
    with path.open(encoding="utf-8", errors="replace") as f:
        return sum(1 for _ in f)


def find_rdctl() -> Path | None:
    if shutil.which("rdctl"):
        return Path(shutil.which("rdctl"))  # type: ignore[arg-type]
    for candidate in (
        Path.home() / ".rd" / "bin" / "rdctl",
        Path("/usr/local/bin/rdctl"),
        Path("/opt/rancher-desktop/bin/rdctl"),
    ):
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate
    return None


def rdctl_json_setting(rdctl: Path, jq_key: str) -> str:
    if shutil.which("jq") is None:
        return "unknown (jq not installed)"
    try:
        proc = subprocess.run(
            [str(rdctl), "list-settings"],
            capture_output=True,
            text=True,
            check=True,
        )
        proc2 = subprocess.run(
            ["jq", "-r", jq_key],
            input=proc.stdout,
            capture_output=True,
            text=True,
            check=True,
        )
        val = proc2.stdout.strip()
        return val if val else "unknown"
    except subprocess.CalledProcessError:
        return "unknown"


def use_native_docker() -> bool:
    """Headless Linux servers use Docker Engine when Rancher Desktop / rdctl is unavailable."""
    if find_rdctl() is not None:
        return False
    return platform.system() == "Linux" and shutil.which("docker") is not None


def _user_in_docker_group() -> bool:
    try:
        import grp
        import pwd

        docker = grp.getgrnam("docker")
        user = pwd.getpwuid(os.getuid()).pw_name
        if user in docker.gr_mem:
            return True
        return docker.gr_gid in os.getgroups()
    except KeyError:
        return False


def _docker_via_sg_ok() -> bool:
    if shutil.which("sg") is None:
        return False
    try:
        subprocess.run(
            ["sg", "docker", "-c", "docker info"],
            capture_output=True,
            check=True,
        )
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False


def docker_info_ok() -> bool:
    try:
        subprocess.run(["docker", "info"], capture_output=True, check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass
    return _docker_via_sg_ok()


def docker_command(args: list[str]) -> list[str]:
    """Return argv prefix to run docker (handles fresh group membership via sg)."""
    if shutil.which("docker") is None:
        die("docker not found")
    try:
        subprocess.run(["docker", "info"], capture_output=True, check=True)
        return ["docker", *args]
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass
    if _docker_via_sg_ok():
        inner = " ".join(shlex.quote(x) for x in ["docker", *args])
        return ["sg", "docker", "-c", inner]
    die(
        "Docker installed but not usable. Log out and back in, or run: newgrp docker"
    )


def run_docker(args: list[str], *, cwd: Path | None = None) -> None:
    run(docker_command(args), cwd=cwd)


def ensure_docker_running() -> None:
    """Start system Docker on Linux when not using Rancher Desktop."""
    if docker_info_ok():
        return
    if platform.system() != "Linux":
        die("Docker is not running and Rancher Desktop (rdctl) is not installed.")
    log("Starting system Docker service...")
    run(["sudo", "systemctl", "start", "docker"])
    if not docker_info_ok():
        die(
            "Docker installed but not usable. Log out and back in after: "
            "sudo usermod -aG docker $USER"
        )


