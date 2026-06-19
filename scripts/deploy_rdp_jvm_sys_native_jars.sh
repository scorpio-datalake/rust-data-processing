#!/usr/bin/env bash
# Deploy all rdp-jvm-sys-{version}-{classifier}.jar files to Maven Central (Sonatype Portal).
#
# Fresh release: mvn -Pcentral-release with -Drdp.native.jar.<classifier> properties.
# Redeploy (POM already on Central): classifier-only bundle via Portal upload API.
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
MAVEN_PATH="io/github/scorpio-datalake/rust-data-processing/rdp-jvm-sys"
PORTAL_UPLOAD="https://central.sonatype.com/api/v1/publisher/upload"
PORTAL_STATUS="https://central.sonatype.com/api/v1/publisher/status"

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
  "${mvn_args[@]}"
}

deploy_classifiers_via_portal() {
  if [[ -z "${MAVEN_CENTRAL_USERNAME:-}" || -z "${MAVEN_CENTRAL_PASSWORD:-}" ]]; then
    echo "MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD required for classifier-only upload." >&2
    exit 1
  fi
  if [[ -z "${MAVEN_GPG_PASSPHRASE:-}" ]]; then
    echo "MAVEN_GPG_PASSPHRASE required for classifier-only upload." >&2
    exit 1
  fi

  local staging_root="${MODULE}/target/classifier-central-staging"
  local staging_dir="${staging_root}/${MAVEN_PATH}/${VERSION}"
  local bundle="${MODULE}/target/classifier-central-bundle.zip"
  rm -rf "${staging_root}"
  mkdir -p "${staging_dir}"

  echo "POM already on Central — uploading classifier-only bundle (no pom.xml)."
  for jar in "${jars[@]}"; do
    local base dest
    base="$(basename "${jar}")"
    dest="${staging_dir}/${base}"
    cp "${jar}" "${dest}"
    gpg --batch --pinentry-mode loopback --passphrase "${MAVEN_GPG_PASSPHRASE}" \
      --armor --detach-sign --output "${dest}.asc" "${dest}"
    (cd "${staging_dir}" && md5sum "${base}" | awk '{print $1}' > "${base}.md5")
    (cd "${staging_dir}" && sha1sum "${base}" | awk '{print $1}' > "${base}.sha1")
    echo "Staged ${base}"
  done

  rm -f "${bundle}"
  (cd "${staging_root}" && zip -rq "${bundle}" "${MAVEN_PATH}")

  local bearer deployment_id state
  bearer="$(printf '%s:%s' "${MAVEN_CENTRAL_USERNAME}" "${MAVEN_CENTRAL_PASSWORD}" | base64 | tr -d '\n')"
  deployment_id="$(
    curl -fsS --request POST \
      --header "Authorization: Bearer ${bearer}" \
      --form "bundle=@${bundle}" \
      "${PORTAL_UPLOAD}?name=rdp-jvm-sys-${VERSION}-classifiers&publishingType=AUTOMATIC"
  )"
  echo "Uploaded bundle; deploymentId=${deployment_id}"

  for _ in $(seq 1 360); do
    state="$(
      curl -fsS --request POST \
        --header "Authorization: Bearer ${bearer}" \
        "${PORTAL_STATUS}?id=${deployment_id}" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin).get("deploymentState",""))'
    )"
    echo "Deployment state: ${state}"
    case "${state}" in
      PUBLISHED) return 0 ;;
      FAILED)
        curl -fsS --request POST \
          --header "Authorization: Bearer ${bearer}" \
          "${PORTAL_STATUS}?id=${deployment_id}" >&2 || true
        echo "::error::Classifier deployment ${deployment_id} failed." >&2
        exit 1
        ;;
    esac
    sleep 5
  done
  echo "::error::Timed out waiting for deployment ${deployment_id} to publish." >&2
  exit 1
}

if [[ "${SKIP_EXISTING}" == true ]] && pom_on_central; then
  deploy_classifiers_via_portal
else
  deploy_classifiers_via_maven
fi

echo "Deployed ${#jars[@]} native classifier JAR(s) to Maven Central."
