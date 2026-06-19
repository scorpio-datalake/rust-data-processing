#!/usr/bin/env bash
# Deploy all rdp-jvm-sys-{version}-{classifier}.jar files to Maven Central (Sonatype Portal).
#
# Attaches each prebuilt classifier to the pom-packaged module, then `mvn deploy` via
# central-publishing-maven-plugin (same flow as rust-data-processing-jvm).
set -euo pipefail

VERSION="${1:?version}"
JAR_DIR="${2:?jar directory}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="${REPO_ROOT}/bindings/java/rdp-jvm-sys"

if [[ ! -f "${MODULE}/pom.xml" ]]; then
  echo "Missing POM: ${MODULE}/pom.xml" >&2
  exit 1
fi

shopt -s nullglob
jars=("${JAR_DIR}/rdp-jvm-sys-${VERSION}-"*.jar)
if [[ ${#jars[@]} -eq 0 ]]; then
  echo "No classifier JARs in ${JAR_DIR}" >&2
  exit 1
fi

cd "${MODULE}"

# One deploy bundles pom + all classifiers. build-helper attach-artifact is per goal.
mvn_args=(mvn -B -Pcentral-release)
for jar in "${jars[@]}"; do
  base="$(basename "${jar}" .jar)"
  classifier="${base#rdp-jvm-sys-${VERSION}-}"
  echo "Attach rdp-jvm-sys:${VERSION}:${classifier}"
  mvn_args+=(
    org.codehaus.mojo:build-helper-maven-plugin:3.6.0:attach-artifact
    "-Dfile=${jar}"
    "-Dclassifier=${classifier}"
    -Dtype=jar
  )
done
mvn_args+=(deploy)

echo "Deploying ${#jars[@]} classifier(s) via central-publishing-maven-plugin..."
if [[ -n "${MAVEN_GPG_PASSPHRASE:-}" ]]; then
  export MAVEN_GPG_PASSPHRASE
  mvn_args+=("-Dgpg.passphrase=${MAVEN_GPG_PASSPHRASE}")
fi

"${mvn_args[@]}"

echo "Deployed ${#jars[@]} native classifier JAR(s) to Maven Central."
