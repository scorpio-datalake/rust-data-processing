#!/usr/bin/env bash
# Deploy all rdp-jvm-sys-{version}-{classifier}.jar files to Maven Central (Sonatype Portal).
#
# Sets -Drdp.native.jar.<classifier> for each JAR; pom profiles attach them at package
# phase, then central-publishing-maven-plugin deploys the bundle (same as JVM API JAR).
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

classifier_on_central() {
  local classifier="$1"
  local url="${CENTRAL_BASE}/${VERSION}/rdp-jvm-sys-${VERSION}-${classifier}.jar"
  local code
  code="$(curl -sS -o /dev/null -w "%{http_code}" "${url}" 2>/dev/null || echo "000")"
  [[ "${code}" == "200" ]]
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

cd "${MODULE}"

mvn_args=(mvn -B -Pcentral-release)
for jar in "${jars[@]}"; do
  base="$(basename "${jar}" .jar)"
  classifier="${base#rdp-jvm-sys-${VERSION}-}"
  echo "Attach rdp-jvm-sys:${VERSION}:${classifier} (${jar})"
  mvn_args+=("-Drdp.native.jar.${classifier}=${jar}")
done
mvn_args+=(verify deploy)

echo "Deploying ${#jars[@]} classifier(s) via central-publishing-maven-plugin..."
if [[ "${SKIP_EXISTING}" == true ]]; then
  # POM (and any classifiers already on Central) must not be re-bundled — Sonatype rejects
  # "Component ... type=pom already exists" when adding missing classifiers to 0.3.5.
  mvn_args+=("-DignorePublishedComponents=true")
fi
if [[ -n "${MAVEN_GPG_PASSPHRASE:-}" ]]; then
  export MAVEN_GPG_PASSPHRASE
  mvn_args+=("-Dgpg.passphrase=${MAVEN_GPG_PASSPHRASE}")
fi

"${mvn_args[@]}"

echo "Deployed ${#jars[@]} native classifier JAR(s) to Maven Central."
