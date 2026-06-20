#!/usr/bin/env bash
# Deploy all rdp-jvm-sys-{version}-{classifier}.jar files to Maven Central (Sonatype Portal).
#
# Fresh release: mvn -Pcentral-release with -Drdp.native.jar.<classifier> properties.
# Partial redeploy (POM already on Central, classifiers missing) is not supported by the
# Portal API — publish a new VERSION instead (see partial_release_on_central error below).
#
# Usage:
#   deploy_rdp_jvm_sys_native_jars.sh [--skip-existing] VERSION JAR_DIR
set -euo pipefail

SKIP_EXISTING=false
while [[ $# -gt 0 && "$1" == --* ]]; do
  case "$1" in
    --skip-existing) SKIP_EXISTING=true ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

VERSION="${1:?version}"
JAR_DIR="${2:?jar directory}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="${REPO_ROOT}/bindings/java/rdp-jvm-sys"
CENTRAL_BASE="https://repo1.maven.org/maven2/io/github/scorpio-datalake/rust-data-processing/rdp-jvm-sys"

CLASSIFIER_ORDER=(
  linux-x86_64
  linux-aarch64
  windows-x86_64
  osx-aarch64
  osx-x86_64
)

if [[ ! -f "${MODULE}/pom.xml" ]]; then
  echo "Missing POM: ${MODULE}/pom.xml" >&2
  exit 1
fi

if [[ "${JAR_DIR}" != /* ]]; then
  JAR_DIR="${REPO_ROOT}/${JAR_DIR}"
fi
if [[ ! -d "${JAR_DIR}" ]]; then
  echo "JAR directory not found: ${JAR_DIR}" >&2
  exit 1
fi
JAR_DIR="$(cd "${JAR_DIR}" && pwd)"

http_code() {
  curl -sS -o /dev/null -w "%{http_code}" "$1" 2>/dev/null || echo "000"
}

classifier_on_central() {
  local classifier="$1"
  [[ "$(http_code "${CENTRAL_BASE}/${VERSION}/rdp-jvm-sys-${VERSION}-${classifier}.jar")" == "200" ]]
}

pom_on_central() {
  [[ "$(http_code "${CENTRAL_BASE}/${VERSION}/rdp-jvm-sys-${VERSION}.pom")" == "200" ]]
}

jars=()
for classifier in "${CLASSIFIER_ORDER[@]}"; do
  jar="${JAR_DIR}/rdp-jvm-sys-${VERSION}-${classifier}.jar"
  [[ -f "${jar}" ]] || continue
  if [[ "${SKIP_EXISTING}" == true ]] && classifier_on_central "${classifier}"; then
    echo "Skipping ${classifier} (already on Maven Central)"
    continue
  fi
  jars+=("${jar}")
done

if [[ ${#jars[@]} -eq 0 ]]; then
  echo "No classifier JARs to deploy."
  exit 0
fi

if [[ "${SKIP_EXISTING}" == true ]] && pom_on_central; then
  cat >&2 <<EOF
::error::rdp-jvm-sys:${VERSION} is partially published on Maven Central.

Sonatype rejected supplemental classifier uploads:
  - Portal bundle without pom.xml → "Bundle has content that does NOT have a .pom file"
  - Portal bundle with pom.xml    → "type=pom already exists"
  - Maven ignorePublishedComponents → stages zero files (entire GAV treated as published)

Maven Central releases are immutable once the POM is live
(https://central.sonatype.org/faq/can-i-change-a-component/).

Fix options:
  1. Bump bindings/java/VERSION (e.g. 0.3.6), publish a fresh GitHub Release, and run
     jvm_native_maven_release.yml so all classifiers deploy together.
  2. Open a Sonatype Central support ticket for deployment c1072cc7-5eb1-4a09-9af8-667e4a315475.
  3. Local workaround: export RDP_JVM_SYS=/path/to/librdp_jvm_sys.so

Do not re-run deploy_only for ${VERSION}; it cannot succeed via the Portal API.
EOF
  exit 1
fi

deploy_classifiers_via_maven() {
  cd "${MODULE}"
  local mvn_args=(mvn -B -Pcentral-release)
  for jar in "${jars[@]}"; do
    local base classifier
    base="$(basename "${jar}" .jar)"
    classifier="${base#rdp-jvm-sys-${VERSION}-}"
    echo "Attach rdp-jvm-sys:${VERSION}:${classifier} (${jar})"
    mvn_args+=("-Drdp.native.jar.${classifier}=${jar}")
  done
  mvn_args+=(verify deploy)
  echo "Deploying ${#jars[@]} classifier(s) via central-publishing-maven-plugin..."
  if [[ -n "${MAVEN_GPG_PASSPHRASE:-}" ]]; then
    export MAVEN_GPG_PASSPHRASE
    mvn_args+=("-Dgpg.passphrase=${MAVEN_GPG_PASSPHRASE}")
  fi
  local log="${MODULE}/target/deploy-maven.log"
  if ! "${mvn_args[@]}" 2>&1 | tee "${log}"; then
    echo "::error::Maven deploy failed." >&2
    exit 1
  fi
  if grep -q "No files to stage for artifact" "${log}"; then
    echo "::error::Maven staged no files (likely ignorePublishedComponents or GAV already complete)." >&2
    exit 1
  fi
}

deploy_classifiers_via_maven

echo "Deployed ${#jars[@]} native classifier JAR(s) to Maven Central."
