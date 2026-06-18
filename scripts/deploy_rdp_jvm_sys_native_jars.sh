#!/usr/bin/env bash
# Deploy all rdp-jvm-sys-{version}-{classifier}.jar files to Maven Central (Sonatype server id: central).
set -euo pipefail

VERSION="${1:?version}"
JAR_DIR="${2:?jar directory}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
POM="${REPO_ROOT}/bindings/java/rdp-jvm-sys/pom.xml"

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

for jar in "${jars[@]}"; do
  base="$(basename "${jar}" .jar)"
  # rdp-jvm-sys-0.3.4-linux-x86_64 → linux-x86_64
  classifier="${base#rdp-jvm-sys-${VERSION}-}"
  echo "Deploying rdp-jvm-sys:${VERSION}:${classifier}"
  mvn -B gpg:sign-and-deploy-file \
    -DgroupId=io.github.scorpio-datalake.rust-data-processing \
    -DartifactId=rdp-jvm-sys \
    -Dversion="${VERSION}" \
    -Dclassifier="${classifier}" \
    -Dpackaging=jar \
    -Dfile="${jar}" \
    -DpomFile="${POM}" \
    -DrepositoryId=central \
    -Durl=https://central.sonatype.com/api/v1/publisher \
    -Dgpg.passphrase="${MAVEN_GPG_PASSPHRASE:-}"
done

echo "Deployed ${#jars[@]} native classifier JAR(s)."
