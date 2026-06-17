#!/usr/bin/env bash
# Interactive release: show last tag and versions, bump Rust/Python/JVM + lockfiles/CHANGELOG,
# commit, push main, tag v*, push tag, and publish a GitHub Release (Maven Central).
#
# Delegates to scripts/release.py (Python 3.10+). Run from repo root, or this script
# will cd to the repository root.
#
# For flags (dry-run, non-interactive, etc.) see:  python3 scripts/release.py --help
#
# Example:
#   ./scripts/release_tag.sh

# Re-exec under bash when invoked as `sh release_tag.sh` (dash lacks pipefail).
if [ -z "${BASH_VERSION:-}" ]; then
  exec /usr/bin/env bash "$0" "$@"
fi

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"
cd "${repo_root}"

release_py="${script_dir}/release.py"
if [[ ! -f "${release_py}" ]]; then
  echo "Missing ${release_py}" >&2
  exit 1
fi

python_cmd=""
for candidate in python3 python; do
  if command -v "${candidate}" >/dev/null 2>&1; then
    python_cmd="${candidate}"
    break
  fi
done

if [[ -z "${python_cmd}" ]]; then
  echo 'Python not found. Install Python 3.10+ and ensure `python3` or `python` is on PATH.' >&2
  exit 1
fi

exec "${python_cmd}" "${release_py}" "$@"
