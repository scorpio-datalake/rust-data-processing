#!/usr/bin/env python3
"""JVM CI parity: Maven verify (all modules), Gradle check + JMH + publishToMavenLocal.

Mirrors .github/workflows/jvm_bindings_ci.yml. Requires ``java_build.py`` (native lib +
``RDP_JVM_SYS``) and ``mvn`` on PATH.
"""

from __future__ import annotations

import argparse
import os
import platform
import sys
from pathlib import Path

from common import (
    JVM_GRADLE_DIR,
    JVM_MAVEN_EXAMPLES,
    JVM_MAVEN_MAIN,
    JVM_MAVEN_SPARK,
    banner,
    ensure_maven,
    gradlew_argv,
    java_ci_env,
    mvn_argv,
    native_lib_release,
    run,
)


def _resolve_native_lib(native_lib: Path | None) -> Path:
    lib = native_lib
    if lib is None:
        env_lib = os.environ.get("RDP_JVM_SYS")
        if env_lib:
            lib = Path(env_lib)
        else:
            lib = native_lib_release()

    if not lib.is_file():
        raise SystemExit(
            "Native library not found. Set RDP_JVM_SYS or run java_build.py first.\n"
            f"  Expected release artifact: {native_lib_release()}"
        )
    return lib


def maven_modules(*, native_lib: Path, skip_spotless: bool) -> None:
    ensure_maven()
    env = java_ci_env(native_lib=native_lib)

    banner("Maven verify + install (main JVM bindings)")
    run(
        mvn_argv("verify", "install", skip_spotless=skip_spotless),
        cwd=JVM_MAVEN_MAIN,
        env=env,
    )

    banner("Maven verify (pytest-mirror examples module)")
    run(
        mvn_argv("verify", skip_spotless=skip_spotless),
        cwd=JVM_MAVEN_EXAMPLES,
        env=env,
    )

    banner("Maven package (Spark materializer bridge, compile + Spotless)")
    run(
        mvn_argv("-DskipTests", "package", skip_spotless=skip_spotless),
        cwd=JVM_MAVEN_SPARK,
        env=env,
    )
    if platform.system() != "Linux":
        print(
            "  Note: GitHub JVM CI runs Spark `mvn package` on Linux only; "
            "local build-all still compiles Spark here to catch Java errors early.",
            flush=True,
        )


def gradle_modules(*, native_lib: Path) -> None:
    env = java_ci_env(native_lib=native_lib)

    banner("Gradle jar + check")
    run(
        gradlew_argv("jar", "check", "--no-daemon", "--stacktrace"),
        cwd=JVM_GRADLE_DIR,
        env=env,
    )

    banner("Gradle JMH benchmarks")
    run(
        gradlew_argv("jmh", "--no-daemon", "--stacktrace"),
        cwd=JVM_GRADLE_DIR,
        env=env,
    )

    banner("Gradle publishToMavenLocal (smoke)")
    run(
        gradlew_argv("publishToMavenLocal", "--no-daemon"),
        cwd=JVM_GRADLE_DIR,
        env=env,
    )


def verify_jvm_jar_artifacts() -> None:
    banner("Verify JVM JAR artifacts (Maven + Gradle)")
    maven_jars = list((JVM_MAVEN_MAIN / "target").glob("rust-data-processing-jvm-*.jar"))
    gradle_jars = list((JVM_GRADLE_DIR / "build" / "libs").glob("rust-data-processing-jvm-*.jar"))
    if not maven_jars:
        raise SystemExit(f"No Maven JAR under {JVM_MAVEN_MAIN / 'target'}")
    if not gradle_jars:
        raise SystemExit(f"No Gradle JAR under {JVM_GRADLE_DIR / 'build' / 'libs'}")
    for path in sorted(maven_jars + gradle_jars):
        print(f"  ok {path}", flush=True)


def _jvm_ci_platform_note() -> None:
    sys_name = platform.system()
    if sys_name == "Windows":
        print(
            "  JVM CI also runs on windows-latest (Surefire + native FFI). "
            "This run exercises the Windows code paths.",
            flush=True,
        )
    elif sys_name == "Darwin":
        print(
            "  JVM CI also runs on macos-latest. Linux/Windows-only failures "
            "require pushing or running build-all on those OSes.",
            flush=True,
        )
    else:
        print(
            "  JVM CI matrix: ubuntu + windows + macos. Run "
            "`pwsh -File scripts/build_all.ps1 --java-only --no-clean` on Windows "
            "before pushing if you changed JNI/FFI or native code.",
            flush=True,
        )


def test(*, native_lib: Path | None = None, skip_spotless: bool = False) -> None:
    lib = _resolve_native_lib(native_lib)
    _jvm_ci_platform_note()
    maven_modules(native_lib=lib, skip_spotless=skip_spotless)
    gradle_modules(native_lib=lib)
    verify_jvm_jar_artifacts()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--native-lib",
        type=Path,
        default=None,
        help="Path to rdp_jvm_sys (default: RDP_JVM_SYS env or release build).",
    )
    parser.add_argument(
        "--skip-fmt",
        action="store_true",
        help="Pass -Dspotless.check.skip=true to Maven (Gradle spotless skipped in java_build).",
    )
    args = parser.parse_args(argv)
    test(native_lib=args.native_lib, skip_spotless=args.skip_fmt)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
