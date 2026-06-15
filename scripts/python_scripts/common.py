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
JVM_MAVEN_MAIN = REPO_ROOT / "bindings" / "java" / "rust-data-processing-jvm"
JVM_MAVEN_EXAMPLES = REPO_ROOT / "bindings" / "java" / "rust-data-processing-jvm-examples"
JVM_MAVEN_SPARK = REPO_ROOT / "bindings" / "java" / "rust-data-processing-jvm-spark"
PYTHON_WRAPPER = REPO_ROOT / "python-wrapper"

# Matches .github/workflows/jvm_bindings_ci.yml (Panama + JMH).
JAVA_TOOL_OPTIONS_CI = "--enable-preview --enable-native-access=ALL-UNNAMED"

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


def require_mvn() -> None:
    require_tool("mvn")


def ensure_maven() -> None:
    """Maven is required for JVM CI parity (Spotless + verify on all Java modules)."""
    if shutil.which("mvn"):
        return
    if os.environ.get("BUILD_ALL_NO_AUTO_MAVEN"):
        raise SystemExit(
            "error: `mvn` not found. Install Maven or unset BUILD_ALL_NO_AUTO_MAVEN "
            "to allow apt install on Debian/Ubuntu.",
        )
    if not _is_debian_like_linux():
        raise SystemExit(
            "error: `mvn` not found. Install Apache Maven for your OS, then re-run.",
        )
    banner("Java: installing Maven (sudo apt-get)")
    subprocess.run(["sudo", "apt-get", "update", "-qq"], check=True)
    subprocess.run(
        [
            "sudo",
            "DEBIAN_FRONTEND=noninteractive",
            "apt-get",
            "install",
            "-y",
            "maven",
        ],
        check=True,
    )
    require_mvn()


JVM_MAVEN_MODULE_SPECS: tuple[tuple[Path, str], ...] = (
    (JVM_MAVEN_MAIN, "main"),
    (JVM_MAVEN_EXAMPLES, "examples"),
    (JVM_MAVEN_SPARK, "spark"),
)


def run_jvm_manifest_checks() -> None:
    """Same scripts as .github/workflows/jvm_bindings_ci.yml (fast, no native build)."""
    banner("JVM: ffi manifest + Java version consistency")
    run([sys.executable, "scripts/check_jvm_ffi_manifest.py"], cwd=REPO_ROOT)
    run([sys.executable, "scripts/check_java_version_consistency.py"], cwd=REPO_ROOT)


def run_jvm_spotless(
    *, skip_gradle: bool = False, skip_maven: bool = False, apply: bool = False
) -> None:
    """Gradle (main module) + Maven Spotless on all JVM modules — matches JVM CI validate phase.

    When ``apply`` is true, run ``spotless:apply`` / ``spotlessApply`` before ``check`` (local
    fix-up; CI and default ``build_all`` use check-only).
    """
    if not skip_gradle:
        if apply:
            banner("Java: Spotless apply (Gradle, main module)")
            run(gradlew_argv("spotlessApply", "--no-daemon"), cwd=JVM_GRADLE_DIR)
        banner("Java: Spotless (Gradle, main module)")
        run(gradlew_argv("spotlessCheck", "--no-daemon"), cwd=JVM_GRADLE_DIR)
    if not skip_maven:
        ensure_maven()
        env = java_ci_env()
        for module, label in JVM_MAVEN_MODULE_SPECS:
            if apply:
                banner(f"Java: Spotless apply (Maven, {label})")
                run(mvn_argv("spotless:apply"), cwd=module, env=env)
            banner(f"Java: Spotless (Maven, {label})")
            run(mvn_argv("spotless:check"), cwd=module, env=env)


def java_ci_env(*, native_lib: Path | None = None) -> dict[str, str]:
    """Environment for JVM CI parity (Maven verify, Gradle check, JMH).

    JVM flags use Surefire argLine / Gradle jvmArgs (not JAVA_TOOL_OPTIONS) so
    Windows Surefire forks avoid duplicate --enable-preview (exit -1073741819).
    """
    env: dict[str, str] = {}
    if native_lib is not None:
        resolved = native_lib.resolve()
        env["RDP_JVM_SYS"] = str(resolved)
        if platform.system() == "Windows":
            dll_dir = str(resolved.parent)
            path = os.environ.get("PATH", "")
            env["PATH"] = dll_dir + (os.pathsep + path if path else "")
    gw = os.environ.get("GITHUB_WORKSPACE")
    if gw:
        env["GITHUB_WORKSPACE"] = gw
    elif (REPO_ROOT / "tests" / "fixtures" / "people.csv").is_file():
        env["GITHUB_WORKSPACE"] = str(REPO_ROOT)
    return env


def mvn_argv(*goals: str, skip_spotless: bool = False) -> list[str]:
    cmd = ["mvn", "-B"]
    if skip_spotless:
        cmd.append("-Dspotless.check.skip=true")
    cmd.extend(goals)
    return cmd


def generate_people_xlsx_fixture() -> None:
    """Same as JVM bindings CI — Excel FFI tests and docs."""
    require_tool("cargo")
    banner("Generate tests/fixtures/people.xlsx")
    run(
        [
            "cargo",
            "run",
            "--features",
            "excel_test_writer",
            "--bin",
            "generate_people_xlsx_fixture",
        ],
        cwd=REPO_ROOT,
    )


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


def _libclang_shared_library() -> Path | None:
    """Return a usable libclang.so if already on the system."""
    env_path = os.environ.get("LIBCLANG_PATH", "").strip()
    if env_path:
        path = Path(env_path)
        if path.is_file():
            return path

    search_roots = [
        Path("/usr/lib/x86_64-linux-gnu"),
        Path("/usr/lib64"),
        Path("/usr/lib"),
    ]
    for root in search_roots:
        if not root.is_dir():
            continue
        matches = sorted(root.glob("libclang.so*"))
        for candidate in matches:
            if candidate.is_file() and not candidate.name.endswith(".a"):
                return candidate

    for llvm_lib in sorted(Path("/usr/lib").glob("llvm-*/lib"), reverse=True):
        matches = sorted(llvm_lib.glob("libclang.so*"))
        for candidate in matches:
            if candidate.is_file():
                return candidate
    return None


def _gssapi_header_present() -> bool:
    """``libgssapi-sys`` bindgen needs Kerberos headers (Debian: ``libkrb5-dev``)."""
    return any(
        path.is_file()
        for path in (
            Path("/usr/include/gssapi/gssapi.h"),
            Path("/usr/include/gssapi.h"),
        )
    )


def ensure_libclang_linux() -> None:
    """Native JVM ``--features full`` needs libclang (bindgen) and gssapi headers (ConnectorX)."""
    if platform.system() != "Linux":
        return
    need_libclang = _libclang_shared_library() is None
    need_gssapi = not _gssapi_header_present()
    if not need_libclang and not need_gssapi:
        return
    if os.environ.get("BUILD_ALL_NO_AUTO_LIBCLANG"):
        missing = []
        if need_libclang:
            missing.append("libclang-dev (or LIBCLANG_PATH)")
        if need_gssapi:
            missing.append("libkrb5-dev (gssapi.h)")
        raise SystemExit(
            "error: missing native build deps for rdp_jvm_sys full / ConnectorX: "
            + ", ".join(missing)
            + ". Install on Debian/Ubuntu or unset BUILD_ALL_NO_AUTO_LIBCLANG to allow apt install.",
        )
    if not _is_debian_like_linux():
        raise SystemExit(
            "error: missing native build deps (libclang and/or gssapi.h). "
            "Install libclang-dev and libkrb5-dev for your OS, then re-run.",
        )
    packages: list[str] = []
    if need_libclang:
        packages.append("libclang-dev")
    if need_gssapi:
        packages.extend(["libkrb5-dev", "pkg-config"])
    banner(
        "Linux: installing native JVM deps ("
        + ", ".join(packages)
        + " for bindgen / libgssapi-sys)",
    )
    subprocess.run(["sudo", "apt-get", "update", "-qq"], check=True)
    subprocess.run(
        [
            "sudo",
            "DEBIAN_FRONTEND=noninteractive",
            "apt-get",
            "install",
            "-y",
            *packages,
        ],
        check=True,
    )
    if need_libclang:
        found = _libclang_shared_library()
        if found is None:
            raise SystemExit(
                "error: libclang still not found after installing libclang-dev. "
                "Set LIBCLANG_PATH to your libclang.so and re-run.",
            )
        print(f"  using libclang: {found}", flush=True)
    if need_gssapi and not _gssapi_header_present():
        raise SystemExit(
            "error: gssapi.h still not found after installing libkrb5-dev.",
        )


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


def disk_clean_enabled() -> bool:
    """When false (``BUILD_ALL_NO_DISK_CLEAN=1``), skip pre-phase disk cleanup."""
    return os.environ.get("BUILD_ALL_NO_DISK_CLEAN", "").lower() not in (
        "1",
        "true",
        "yes",
    )


def available_disk_gib(path: Path | str = REPO_ROOT) -> float:
    """Free space on the filesystem containing ``path`` (GiB)."""
    usage = shutil.disk_usage(path)
    return usage.free / (1024**3)


def ensure_min_disk_space(
    *,
    min_gib: float = 8.0,
    path: Path | str = REPO_ROOT,
    context: str = "build",
) -> None:
    """Fail fast with a clear message when the repo disk is too full to link Rust tests."""
    free = available_disk_gib(path)
    if free >= min_gib:
        return
    raise RuntimeError(
        f"Only {free:.1f} GiB free on {path} ({context} needs ~{min_gib:.0f} GiB). "
        "Free space (e.g. `cargo clean`, remove old `target/`, `sudo rm -rf .build_all_runs/run-*`) "
        "then re-run `./build_all_run.sh start`."
    )


def should_clean_between_rust_features() -> bool:
    """Drop default-feature artifacts before ``ci_expanded`` (default on).

    Disable with ``BUILD_ALL_CLEAN_BETWEEN_RUST_FEATURES=0`` when iterating on a large VM.
    """
    raw = os.environ.get("BUILD_ALL_CLEAN_BETWEEN_RUST_FEATURES", "1").lower()
    if raw in ("0", "false", "no"):
        return False
    return True


def report_disk_usage(title: str, paths: list[Path]) -> None:
    banner(f"Disk: {title}")
    run(["df", "-h", "."])
    existing = [p for p in paths if p.exists()]
    if not existing:
        print("  (nothing on disk yet for listed paths)", flush=True)
        return
    print(f"+ du -sh {' '.join(str(p) for p in existing)}", flush=True)
    subprocess.run(["du", "-sh", *[str(p) for p in existing]], check=False)


def _remove_path(path: Path) -> None:
    if not path.exists():
        return
    try:
        display = path.relative_to(REPO_ROOT)
    except ValueError:
        display = path
    print(f"  removing {display}", flush=True)
    if path.is_dir():
        shutil.rmtree(path)
    else:
        path.unlink()


def cleanup_disk_for_python() -> None:
    """Free space before Python wrapper build (runs at start of ``python_build.py``)."""
    if not disk_clean_enabled():
        return
    paths = [
        REPO_ROOT / "target",
        PYTHON_WRAPPER / "target",
        Path.home() / ".cargo" / "registry",
        Path.home() / ".cargo" / "git",
        Path.home() / ".cache" / "uv",
    ]
    report_disk_usage("before Python cleanup", paths)
    banner("Disk: Python phase cleanup")
    for path in paths:
        _remove_path(path)
    gc.collect()


def cleanup_disk_for_jvm() -> None:
    """Free space before JVM build (runs at start of ``java_build.py``)."""
    if not disk_clean_enabled():
        return
    paths = [
        JVM_SYS_DIR / "target",
        JVM_MAVEN_MAIN / "target",
        JVM_MAVEN_EXAMPLES / "target",
        JVM_MAVEN_SPARK / "target",
        JVM_GRADLE_DIR / "build",
        JVM_GRADLE_DIR / ".gradle",
    ]
    if os.environ.get("BUILD_ALL_JVM_CLEAN_M2", "").lower() in ("1", "true", "yes"):
        paths.append(Path.home() / ".m2" / "repository")
    report_disk_usage("before JVM cleanup", paths)
    banner("Disk: JVM phase cleanup")
    for path in paths:
        _remove_path(path)
    gc.collect()


def python_wrapper_cargo_env() -> dict[str, str]:
    """Environment for maturin/cargo under ``python-wrapper/``.

    Default: ``CARGO_TARGET_DIR`` at repo root so build_all reuses Rust compile artifacts.
    Set ``BUILD_ALL_PYTHON_SEPARATE_TARGET=1`` to use ``python-wrapper/target/`` only.
    """
    if os.environ.get("BUILD_ALL_PYTHON_SEPARATE_TARGET", "").lower() in (
        "1",
        "true",
        "yes",
    ):
        return {}
    return {"CARGO_TARGET_DIR": str(REPO_ROOT / "target")}


def python_venv_executable(name: str) -> Path:
    """Binary from ``python-wrapper/.venv`` (dev group sync; no local wheel needed)."""
    if platform.system() == "Windows":
        path = PYTHON_WRAPPER / ".venv" / "Scripts" / f"{name}.exe"
    else:
        path = PYTHON_WRAPPER / ".venv" / "bin" / name
    if not path.is_file():
        raise SystemExit(
            f"Missing {path}. Run: cd python-wrapper && "
            "uv sync --group dev --no-install-project",
        )
    return path


def python_maturin_use_release() -> bool:
    """Release extension build (CI / wheel parity). Default debug for local build_all disk use."""
    return os.environ.get("BUILD_ALL_PYTHON_RELEASE", "").lower() in ("1", "true", "yes")


def cargo_jobs_args() -> list[str]:
    """`-j` for cargo on Linux during build_all (limits concurrent Polars test links).

    Override with ``BUILD_ALL_CARGO_JOBS`` or ``CARGO_BUILD_JOBS``. Set ``BUILD_ALL_CARGO_JOBS=0``
    to omit ``-j`` and use Cargo's default parallelism.
    """
    raw = os.environ.get("BUILD_ALL_CARGO_JOBS", os.environ.get("CARGO_BUILD_JOBS"))
    if raw is not None:
        if raw == "0":
            return []
        return ["-j", str(raw)]
    if platform.system() == "Linux":
        return ["-j", "2"]
    return []


def load_cargo_env() -> None:
    """Ensure ``cargo`` is on PATH (``~/.cargo/bin`` from rustup)."""
    home = Path.home()
    cargo_bin = home / ".cargo" / "bin"
    if cargo_bin.is_dir():
        path = os.environ.get("PATH", "")
        prefix = str(cargo_bin)
        if prefix not in path.split(":"):
            os.environ["PATH"] = f"{prefix}:{path}" if path else prefix


def setup_rust_toolchain_env(*, offline: bool = False) -> None:
    load_cargo_env()
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


def _jvm_sys_lib_basename() -> str:
    if platform.system() == "Windows":
        return "rdp_jvm_sys.dll"
    if platform.system() == "Darwin":
        return "librdp_jvm_sys.dylib"
    return "librdp_jvm_sys.so"


def _jvm_sys_target_roots() -> list[Path]:
    """Cargo output dirs for ``rdp-jvm-sys`` (workspace → repo ``target/``)."""
    roots: list[Path] = []
    cargo_target = os.environ.get("CARGO_TARGET_DIR", "").strip()
    if cargo_target:
        roots.append(Path(cargo_target))
    roots.append(REPO_ROOT / "target")
    roots.append(JVM_SYS_DIR / "target")
    seen: set[Path] = set()
    out: list[Path] = []
    for root in roots:
        if root not in seen:
            seen.add(root)
            out.append(root)
    return out


def native_lib_release() -> Path:
    name = _jvm_sys_lib_basename()
    for root in _jvm_sys_target_roots():
        candidate = root / "release" / name
        if candidate.is_file():
            return candidate
    return REPO_ROOT / "target" / "release" / name


def native_lib_debug() -> Path:
    name = _jvm_sys_lib_basename()
    for root in _jvm_sys_target_roots():
        candidate = root / "debug" / name
        if candidate.is_file():
            return candidate
    return REPO_ROOT / "target" / "debug" / name


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
