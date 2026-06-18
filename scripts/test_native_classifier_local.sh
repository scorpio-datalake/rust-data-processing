#!/usr/bin/env bash
# Local smoke test for the native-classifier patch (run from repo root before merging to main).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${REPO_ROOT}"

banner() { echo ""; echo "== $* =="; }

CLASSIFIER="${RDP_NATIVE_CLASSIFIER:-linux-x86_64}"
JVM_POM="bindings/java/rust-data-processing-jvm/pom.xml"

banner "1/6 Static checks"
python3 scripts/check_java_version_consistency.py
python3 scripts/check_jvm_ffi_manifest.py
python3 scripts/package_rdp_jvm_sys_native_jar.py --help >/dev/null

banner "2/6 Build rdp_jvm_sys (--features full) if missing"
export CARGO_TARGET_DIR="${REPO_ROOT}/bindings/jvm-sys/target"
NATIVE_SO="${CARGO_TARGET_DIR}/release/librdp_jvm_sys.so"
if [[ ! -f "${NATIVE_SO}" ]]; then
  cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features full
fi

banner "3/6 Package classifier JAR"
NATIVE_JAR="$(python3 scripts/package_rdp_jvm_sys_native_jar.py "${CLASSIFIER}" "${NATIVE_SO}")"
echo "Packaged: ${NATIVE_JAR}"
jar tf "${NATIVE_JAR}" | grep -q META-INF/native/ || { echo "META-INF/native missing"; exit 1; }

banner "4/6 Compile JVM module"
export JAVA_TOOL_OPTIONS='--enable-preview --enable-native-access=ALL-UNNAMED'
mvn -q -f "${JVM_POM}" spotless:apply
mvn -q -f "${JVM_POM}" -DskipTests package

banner "5/6 Classpath smoke (no RDP_JVM_SYS)"
env -u RDP_JVM_SYS mvn -q -f "${JVM_POM}" test \
  -Drdp.native.classifier.jar="${NATIVE_JAR}" \
  -Dtest=NativeClassifierClasspathTest

banner "6/6 Optional: full JVM tests with explicit RDP_JVM_SYS (same as CI)"
export RDP_JVM_SYS="${NATIVE_SO}"
mvn -q -f "${JVM_POM}" test -Drdp.jmh.skip=true

banner "All local smoke steps passed"
