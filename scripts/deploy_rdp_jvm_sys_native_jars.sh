#!/usr/bin/env bash
# Deploy all rdp-jvm-sys-{version}-{classifier}.jar files to Maven Central (Sonatype Portal).
#
# Uses central-publishing-maven-plugin (same as rust-data-processing-jvm), NOT
# gpg:sign-and-deploy-file — the Portal publisher URL is not a Maven repo endpoint.
set -euo pipefail

VERSION="${1:?version}"
JAR_DIR="${2:?jar directory}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="${REPO_ROOT}/bindings/java/rdp-jvm-sys"
POM="${MODULE}/pom.xml"
STAGING="${MODULE}/target/central-staging"
GROUP_PATH="io/github/scorpio-datalake/rust-data-processing/rdp-jvm-sys/${VERSION}"
OUT="${STAGING}/${GROUP_PATH}"

if [[ ! -f "${POM}" ]]; then
  echo "Missing POM: ${POM}" >&2
  exit 1
fi

shopt -s nullglob
jars=("${JAR_DIR}/rdp-jvm-sys-${VERSION}-"*.jar)
if [[ ${#jars[@]} -eq 0 ]]; then
  echo "No classifier JARs in ${JAR_DIR}" >&2
  exit 1
fi

rm -rf "${STAGING}"
mkdir -p "${OUT}"

cp "${POM}" "${OUT}/rdp-jvm-sys-${VERSION}.pom"
for jar in "${jars[@]}"; do
  base="$(basename "${jar}" .jar)"
  classifier="${base#rdp-jvm-sys-${VERSION}-}"
  echo "Staging rdp-jvm-sys:${VERSION}:${classifier}"
  cp "${jar}" "${OUT}/rdp-jvm-sys-${VERSION}-${classifier}.jar"
done

if [[ -z "${MAVEN_GPG_PASSPHRASE:-}" ]]; then
  echo "MAVEN_GPG_PASSPHRASE is required for signing." >&2
  exit 1
fi

export GPG_TTY=""
for f in "${OUT}/rdp-jvm-sys-${VERSION}.pom" "${OUT}/rdp-jvm-sys-${VERSION}-"*.jar; do
  gpg --batch --yes --pinentry-mode loopback \
    --passphrase "${MAVEN_GPG_PASSPHRASE}" \
    --detach-sign --armor "${f}"
done

echo "Publishing bundle via central-publishing-maven-plugin (${#jars[@]} classifier(s))..."
cd "${MODULE}"
mvn -B -Pcentral-release \
  org.sonatype.central:central-publishing-maven-plugin:0.10.0:publish

echo "Deployed ${#jars[@]} native classifier JAR(s) to Maven Central."
