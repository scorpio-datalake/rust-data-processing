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

# Feature flags for CONNECTORS.md batch connectors (see scripts/connector_features.py).
INTEGRATION_RUST_FEATURES = "integration_full"
INTEGRATION_JVM_FEATURES = "full"
INTEGRATION_PYTHON_FEATURES = "integration_full"

INTEGRATION_BUILD_HELP = f"""Build libraries and data first:
  python3 integration_testing/scripts/build_libs/build_all_libs.py
    Rust  → --features {INTEGRATION_RUST_FEATURES} (db_connectorx + cloud_connectors + excel)
    Java  → rdp_jvm_sys --features {INTEGRATION_JVM_FEATURES} (all batch connectors + PG/Oracle sinks)
    Python → maturin --features {INTEGRATION_PYTHON_FEATURES} (db + cloud; load uses librdp_jvm_sys)
  python3 integration_testing/scripts/data_download/download_uber_data.py --sample"""


def log(msg: str) -> None:
    print(f"[integration] {msg}", flush=True)


def die(msg: str, code: int = 1) -> None:
    print(f"[integration] ERROR: {msg}", file=sys.stderr, flush=True)
    raise SystemExit(code)


def require_integration_libs(*, require_data: bool = True) -> None:
    """Fail fast when integration_testing/libs/ or Uber CSV is missing."""
    missing = False
    for leg, rel in (
        ("rust", "rust/env.sh"),
        ("java", "java/env.sh"),
        ("python", "python/env.sh"),
    ):
        if not (LIBS_DIR / rel).is_file():
            log(f"missing libs/{leg} — run build_libs/build_{leg}_lib.py or build_all_libs.py")
            missing = True
    if require_data:
        sample = DATA_DIR / "uber_nyc_pickups_sample.csv"
        full = DATA_DIR / "uber_nyc_pickups_apr2014.csv"
        if not sample.is_file() and not full.is_file():
            log("missing Uber CSV — run data_download/download_uber_data.py")
            missing = True
    if missing:
        die(INTEGRATION_BUILD_HELP)


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


def cleanup_disk_before_integration_build(
    *,
    force_integr_target: bool = False,
    force_repo_target: bool = False,
) -> None:
    """Drop stale build artifacts before integration lib builds.

    Removes ``integration_testing/.target/`` only when ``force_integr_target`` (``--force``).
    Keeps repo ``target/`` when the integration Rust lib stamp exists unless ``force_repo_target``.
    """
    if not disk_clean_enabled():
        return
    assert_safe_to_clean_repo_target()
    paths: list[Path] = []
    if force_integr_target:
        paths.append(INTEG_TARGET_DIR)
    repo_target = REPO_ROOT / "target"
    if force_repo_target:
        paths.append(repo_target)
    elif not (RUST_STAMP.is_file() and repo_target.exists()):
        paths.append(repo_target)
    else:
        log("Disk: keeping repo target/ (integration lib built; use --force to remove)")
    paths.extend(
        [
            REPO_ROOT / "bindings" / "jvm-sys" / "target",
            REPO_ROOT / "python-wrapper" / "target",
            REPO_ROOT / "bindings" / "java" / "rust-data-processing-jvm" / "target",
        ]
    )
    report_disk_usage("before integration cleanup", paths)
    log("Disk: integration phase cleanup")
    for path in paths:
        _remove_path(path)
    gc.collect()


def prepare_integration_disk(*, force: bool = False) -> None:
    """Free conflicting artifacts, then verify minimum free space."""
    if os.environ.get("INTEG_DISK_PREPARED") == "1":
        return
    cleanup_disk_before_integration_build(
        force_integr_target=force,
        force_repo_target=force,
    )
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


# Smoke-test binary name per connector folder (``integration_testing/<Name>/rust/``).
# Pre-built by build_rust_lib.py; run_tests.py only executes (incremental link at most).
INTEGRATION_RUST_TEST_FILTER: dict[str, str] = {
    "Oracle": "oracle_import_uber_csv",
    "PostgreSQL": "postgresql_import_uber_csv",
}

INTEGRATION_RUST_PACKAGES: dict[str, str] = {
    "Oracle": "rdp-oracle-integration-test",
    "PostgreSQL": "rdp-postgresql-integration-test",
}

INTEGRATION_PREBUILD_PROFILE = "integration"
INTEGRATION_RUN_PROFILE = "release"


def integration_rust_manifests() -> list[Path]:
    """``integration_testing/<Connector>/rust/Cargo.toml`` for each connector leg."""
    return sorted(p for p in INTEG_ROOT.glob("*/rust/Cargo.toml") if p.is_file())


def integration_rust_test_filter(connector_dir_name: str) -> str | None:
    return INTEGRATION_RUST_TEST_FILTER.get(connector_dir_name)


def skip_prebuild_enabled() -> bool:
    return os.environ.get("INTEG_SKIP_PREBUILD", "").lower() in ("1", "true", "yes")


def integration_prebuild_profile() -> str:
    return os.environ.get("RDP_INTEGRATION_CARGO_PROFILE", INTEGRATION_PREBUILD_PROFILE)


def integration_rust_package_name(connector_dir_name: str) -> str:
    try:
        return INTEGRATION_RUST_PACKAGES[connector_dir_name]
    except KeyError:
        die(f"unknown connector for Rust prebuild: {connector_dir_name}")


def connector_prebuild_stamp(connector_dir_name: str) -> Path:
    slug = connector_dir_name.lower()
    return LIBS_DIR / "rust" / f".{slug}_test_built_at"


def connector_prebuild_watch_paths(manifest: Path) -> list[str]:
    rel = manifest.parent.relative_to(REPO_ROOT)
    return [
        "Cargo.toml",
        "Cargo.lock",
        str(rel / "Cargo.toml"),
        str(rel / "src"),
    ]


def needs_connector_prebuild(connector_dir_name: str, manifest: Path) -> bool:
    if skip_prebuild_enabled():
        return False
    return needs_rebuild(
        connector_prebuild_stamp(connector_dir_name),
        connector_prebuild_watch_paths(manifest),
    )


def integration_rust_prebuild_cmd(
    connector_dir_name: str,
    test_filter: str | None,
    *,
    profile: str | None = None,
) -> list[str]:
    """Argv for ``cargo test --no-run`` on a workspace connector crate (prebuild only)."""
    prof = profile or integration_prebuild_profile()
    cmd = [
        "cargo",
        "test",
        f"--profile={prof}",
        "--locked",
        "--no-run",
        "-p",
        integration_rust_package_name(connector_dir_name),
    ]
    if test_filter:
        cmd.append(test_filter)
    return cmd


def integration_rust_test_cmd(manifest: Path, test_filter: str) -> list[str]:
    """Argv for ``cargo test`` when executing connector tests (release profile)."""
    connector = manifest.parent.parent.name
    return [
        "cargo",
        "test",
        f"--profile={INTEGRATION_RUN_PROFILE}",
        "--locked",
        "-p",
        integration_rust_package_name(connector),
        test_filter,
        "--",
        "--nocapture",
    ]


def integration_rust_watch_paths() -> list[str]:
    """Repo-relative paths that should invalidate the Rust integration stamp."""
    paths: list[str] = ["Cargo.toml", "Cargo.lock", "src"]
    for manifest in integration_rust_manifests():
        rel = manifest.parent.relative_to(REPO_ROOT)
        paths.extend([str(rel / "Cargo.toml"), str(rel / "src")])
    return paths


def prebuild_integration_rust_tests() -> None:
    """Compile connector integration test binaries once (``cargo test --no-run``).

    Uses ``integration_testing/.target/`` (same as run_tests.py) so Polars / rust-data-processing
    artifacts are shared across Oracle, PostgreSQL, and future ``*/rust/`` connectors.
    """
    if skip_prebuild_enabled():
        log("Skipping connector prebuild (INTEG_SKIP_PREBUILD=1).")
        return
    profile = integration_prebuild_profile()
    for manifest in integration_rust_manifests():
        connector = manifest.parent.parent.name
        if not needs_connector_prebuild(connector, manifest):
            log(f"Rust integration tests ({connector}) up to date (skip prebuild).")
            continue
        test_filter = integration_rust_test_filter(connector)
        log(f"Pre-building Rust integration tests ({connector}, profile={profile})...")
        run(integration_rust_prebuild_cmd(connector, test_filter, profile=profile))
        mark_built(connector_prebuild_stamp(connector))


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
export RDP_INTEGRATION_JVM_FEATURES={INTEGRATION_JVM_FEATURES}
export RDP_INTEGRATION_JAVA_BUILT=1
""",
        encoding="utf-8",
    )


def write_rust_env() -> None:
    (LIBS_DIR / "rust" / "env.sh").write_text(
        f"""# Source before Rust integration tests: source integration_testing/libs/rust/env.sh
# Connector test crates (integration_testing/*/rust/) are pre-built by build_rust_lib.py into:
#   {INTEG_TARGET_DIR}
export RDP_REPO_ROOT="{REPO_ROOT}"
export CARGO_TARGET_DIR="{INTEG_TARGET_DIR}"
export RDP_INTEGRATION_RUST_FEATURES={INTEGRATION_RUST_FEATURES}
export RDP_INTEGRATION_JVM_FEATURES={INTEGRATION_JVM_FEATURES}
export RDP_INTEGRATION_PYTHON_FEATURES={INTEGRATION_PYTHON_FEATURES}
export RDP_INTEGRATION_CARGO_PROFILE={INTEGRATION_PREBUILD_PROFILE}
export RDP_INTEGRATION_RUST_BUILT=1
""",
        encoding="utf-8",
    )


def write_python_env(ext_path: Path) -> None:
    (LIBS_DIR / "python" / "env.sh").write_text(
        f"""# Source before Python integration tests: source integration_testing/libs/python/env.sh
export RDP_INTEGRATION_PYTHON_EXT="{ext_path}"
export RDP_INTEGRATION_PYTHON_FEATURES={INTEGRATION_PYTHON_FEATURES}
export RDP_INTEGRATION_PYTHON_BUILT=1
export PYTHONPATH="{REPO_ROOT / 'python-wrapper'}:${{PYTHONPATH:-}}"
""",
        encoding="utf-8",
    )


def stage_integration_python_ext() -> Path:
    """Copy prebuilt extension into python-wrapper so pytest does not trigger maturin."""
    ext_var = os.environ.get("RDP_INTEGRATION_PYTHON_EXT")
    if not ext_var:
        die("RDP_INTEGRATION_PYTHON_EXT not set — run build_libs/build_python_lib.py")
    src = Path(ext_var)
    if not src.is_file():
        die(f"Integration Python extension missing: {src}")
    dest_dir = REPO_ROOT / "python-wrapper" / "rust_data_processing"
    dest_dir.mkdir(parents=True, exist_ok=True)
    for old in dest_dir.glob("_rust_data_processing*.so"):
        if old.name != src.name:
            old.unlink()
    dest = dest_dir / src.name
    if not dest.exists() or src.stat().st_size != dest.stat().st_size:
        shutil.copy2(src, dest)
        log(f"Staged Python extension → {dest} ({dest.stat().st_size} bytes)")
    # uv/maturin may install a non-db .so in site-packages that shadows the staged copy.
    site_root = REPO_ROOT / "python-wrapper" / ".venv" / "lib"
    for site in site_root.glob("python*/site-packages/_rust_data_processing*.so"):
        if site.stat().st_size != src.stat().st_size:
            site.unlink()
            log(f"Removed non-integration extension {site}")
    return dest


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


