#!/usr/bin/env bash
# Re-point an existing release tag at current main and re-publish the GitHub Release
# to re-trigger jvm_maven_central_release.yml — without bumping Rust/Python/JVM versions.
#
# Use when Maven Central deploy failed (e.g. Javadoc) but crates.io/PyPI for the same
# version are already published. Moving the tag re-fires rust_release.yml and
# python_release.yml too (Rust usually errors "already published"; PyPI may skip-existing).
#
# Prerequisites:
#   - Fixes merged on main; bindings/java/VERSION still matches the release (no -SNAPSHOT)
#   - gh CLI: gh auth login
#   - GitHub secrets for Maven Central already configured
#
# Example (redeploy JVM 0.3.4 from current main):
#   ./scripts/redeploy_jvm_maven_release.sh
#   ./scripts/redeploy_jvm_maven_release.sh 0.3.4 -y
#   ./scripts/redeploy_jvm_maven_release.sh --dry-run

if [ -z "${BASH_VERSION:-}" ]; then
  exec /usr/bin/env bash "$0" "$@"
fi

set -euo pipefail

REPO_SLUG="scorpio-datalake/rust-data-processing"
REMOTE="origin"
BRANCH="main"
VERSION=""
YES=false
DRY_RUN=false
PUSH_MAIN=true

usage() {
  cat <<'EOF'
Usage: redeploy_jvm_maven_release.sh [OPTIONS] [VERSION]

Re-tag v{VERSION} at HEAD, force-push the tag, delete and recreate the GitHub Release
to re-run JVM (Maven Central) publish only — no version bump.

Arguments:
  VERSION          SemVer (default: bindings/java/VERSION)

Options:
  --remote NAME    Git remote (default: origin)
  --branch NAME    Main branch (default: main)
  --no-push-main   Do not push local main before retagging
  -y, --yes        Skip confirmation prompts
  --dry-run        Print actions without changing remotes/releases
  -h, --help       Show this help

EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    -y|--yes)
      YES=true
      shift
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    --remote)
      REMOTE="${2:?--remote requires a value}"
      shift 2
      ;;
    --branch)
      BRANCH="${2:?--branch requires a value}"
      shift 2
      ;;
    --no-push-main)
      PUSH_MAIN=false
      shift
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      if [[ -n "${VERSION}" ]]; then
        echo "Unexpected argument: $1" >&2
        usage >&2
        exit 1
      fi
      VERSION="$1"
      shift
      ;;
  esac
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"
cd "${repo_root}"

die() {
  echo "error: $*" >&2
  exit 1
}

run() {
  if [[ "${DRY_RUN}" == true ]]; then
    printf '[dry-run]'; printf ' %q' "$@"; printf '\n'
  else
    "$@"
  fi
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Missing required command: $1"
}

require_cmd git
require_cmd gh

python_cmd=""
for candidate in python3 python; do
  if command -v "${candidate}" >/dev/null 2>&1; then
    python_cmd="${candidate}"
    break
  fi
done
[[ -n "${python_cmd}" ]] || die "Python not found (need python3 for version checks)."

git rev-parse --git-dir >/dev/null 2>&1 || die "Not a git repository."

version_file="${repo_root}/bindings/java/VERSION"
[[ -f "${version_file}" ]] || die "Missing ${version_file}"

if [[ -z "${VERSION}" ]]; then
  VERSION="$(tr -d '\r\n' < "${version_file}")"
fi
[[ -n "${VERSION}" ]] || die "VERSION is empty"
[[ "${VERSION}" != *"-SNAPSHOT"* ]] || die "bindings/java/VERSION is ${VERSION}; must be a release version"

tag_name="v${VERSION}"

if [[ "$(git branch --show-current)" != "${BRANCH}" ]]; then
  die "Must be on branch ${BRANCH} (currently $(git branch --show-current))."
fi

if [[ -n "$(git status --porcelain)" ]]; then
  die "Working tree is dirty. Commit or stash changes first."
fi

run git fetch "${REMOTE}" "${BRANCH}" --tags

file_ver="$(tr -d '\r\n' < "${version_file}")"
[[ "${file_ver}" == "${VERSION}" ]] || die "bindings/java/VERSION (${file_ver}) != requested ${VERSION}"

if ! "${python_cmd}" "${script_dir}/check_java_version_consistency.py"; then
  die "check_java_version_consistency.py failed"
fi

local_head="$(git rev-parse HEAD)"
remote_ref="${REMOTE}/${BRANCH}"
if ! git rev-parse --verify "${remote_ref}" >/dev/null 2>&1; then
  die "Remote branch ${remote_ref} not found after fetch."
fi
remote_head="$(git rev-parse "${remote_ref}")"

if ! git merge-base --is-ancestor "${remote_head}" "${local_head}"; then
  die "Local ${BRANCH} is not based on ${remote_ref}. Pull --ff-only or rebase before redeploying."
fi

ahead=0
if [[ "${local_head}" != "${remote_head}" ]]; then
  ahead=1
fi

old_tag_sha=""
if git rev-parse --verify "refs/tags/${tag_name}" >/dev/null 2>&1; then
  old_tag_sha="$(git rev-parse "refs/tags/${tag_name}")"
fi

release_notes="$("${python_cmd}" - "${VERSION}" <<'PY'
import re
import sys
from pathlib import Path

ver = sys.argv[1]
text = Path("CHANGELOG.md").read_text(encoding="utf-8")
pattern = rf"^## \[{re.escape(ver)}\][^\n]*\n(.*?)(?=^## \[|\Z)"
m = re.search(pattern, text, re.M | re.S)
if m and m.group(1).strip():
    print(f"Release v{ver}\n\n{m.group(1).strip()}")
else:
    print(f"Release v{ver}")
PY
)"

cat <<EOF
== JVM Maven redeploy (no version bump) ==
  Version:     ${VERSION}
  Tag:         ${tag_name}
  Local HEAD:  ${local_head}
  ${REMOTE}/${BRANCH}: ${remote_head}
EOF
if [[ -n "${old_tag_sha}" ]]; then
  echo "  Old ${tag_name}: ${old_tag_sha}"
  if [[ "${old_tag_sha}" == "${local_head}" ]]; then
    echo "  (tag already points at HEAD — will still recreate GitHub Release)"
  fi
else
  echo "  Old ${tag_name}: (not found locally; will create)"
fi
cat <<'EOF'

This will:
  1. Push main to origin (if local is ahead)
  2. git tag -f + git push -f origin TAG  (re-triggers rust_release + python_release on tag push)
  3. gh release delete + gh release create (re-triggers jvm_maven_central_release.yml)

Side effects: Rust publish may fail "already exists"; PyPI may no-op (skip-existing).
EOF

if [[ "${YES}" != true && "${DRY_RUN}" != true ]]; then
  read -r -p "Proceed? [y/N] " ans
  case "${ans}" in
    y|Y|yes|Yes|YES) ;;
    *) echo "Aborted."; exit 0 ;;
  esac
fi

if [[ "${ahead}" -eq 1 && "${PUSH_MAIN}" == true ]]; then
  echo "== Pushing ${BRANCH} to ${REMOTE} =="
  run git push "${REMOTE}" "${BRANCH}"
elif [[ "${ahead}" -eq 1 ]]; then
  die "Local ${BRANCH} is ahead of ${REMOTE}/${BRANCH}. Push main or drop --no-push-main."
fi

echo "== Moving tag ${tag_name} to ${local_head} =="
run git tag -f -a "${tag_name}" -m "Release ${tag_name} (JVM Maven redeploy)"
run git push -f "${REMOTE}" "${tag_name}"

echo "== Recreating GitHub Release ${tag_name} =="
if [[ "${DRY_RUN}" == true ]]; then
  echo "[dry-run] gh release delete ${tag_name} --yes  # if exists"
  echo "[dry-run] gh release create ${tag_name} --verify-tag --title Release ${tag_name} --notes ..."
else
  if gh release view "${tag_name}" >/dev/null 2>&1; then
    gh release delete "${tag_name}" --yes
  fi
  gh release create "${tag_name}" \
    --verify-tag \
    --repo "${REPO_SLUG}" \
    --title "Release ${tag_name}" \
    --notes "${release_notes}"
fi

cat <<EOF

Done.
  Monitor: https://github.com/${REPO_SLUG}/actions/workflows/jvm_maven_central_release.yml
  Expect rust_release / python_release workflows on the force-pushed tag (usually harmless noise).
EOF
