#!/usr/bin/env bash
# Deploy all rdp-jvm-sys-{version}-{classifier}.jar files to Maven Central (Sonatype Portal).
#
# Sets -Drdp.native.jar.<classifier> for each JAR; pom profiles attach them at package
# phase, then central-publishing-maven-plugin deploys the bundle (same as JVM API JAR).
set -euo pipefail

VERSION="${1:?version}"
JAR_DIR="${2:?jar directory}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="${REPO_ROOT}/bindings/java/rdp-jvm-sys"

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

shopt -s nullglob
jars=("${JAR_DIR}/rdp-jvm-sys-${VERSION}-"*.jar)
if [[ ${#jars[@]} -eq 0 ]]; then
  echo "No classifier JARs in ${JAR_DIR}" >&2
  exit 1
fi

cd "${MODULE}"

mvn_args=(mvn -B -Pcentral-release)
for jar in "${jars[@]}"; do
  base="$(basename "${jar}" .jar)"
  classifier="${base#rdp-jvm-sys-${VERSION}-}"
  echo "Attach rdp-jvm-sys:${VERSION}:${classifier} (${jar})"
  mvn_args+=("-Drdp.native.jar.${classifier}=${jar}")
done
mvn_args+=(deploy)

echo "Deploying ${#jars[@]} classifier(s) via central-publishing-maven-plugin..."
if [[ -n "${MAVEN_GPG_PASSPHRASE:-}" ]]; then
  export MAVEN_GPG_PASSPHRASE
  mvn_args+=("-Dgpg.passphrase=${MAVEN_GPG_PASSPHRASE}")
fi

"${mvn_args[@]}"

echo "Deployed ${#jars[@]} native classifier JAR(s) to Maven Central."
