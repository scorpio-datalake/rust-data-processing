#!/usr/bin/env bash
# Detached full-repo build: survives SSH logout and network blips on the client.
# The build keeps running on the machine until it finishes or you stop it.
#
#   ./build_all_run.sh start [build_all.sh args...]   # default command
#   ./build_all_run.sh status
#   ./build_all_run.sh logs [-f]
#   ./build_all_run.sh failures
#   ./build_all_run.sh stop
#
# Artifacts: .build_all_runs/<run-id>/build.log, failures.txt, exit.code, meta.txt

export BUILD_ALL_CLEAN_BETWEEN_RUST_FEATURES=1


# Re-exec under bash when invoked as `sh build_all_run.sh` (dash lacks pipefail).
if [ -z "${BASH_VERSION:-}" ]; then
  exec /usr/bin/env bash "$0" "$@"
fi

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNS_DIR="${REPO_ROOT}/.build_all_runs"
LATEST_LINK="${RUNS_DIR}/latest"
BUILD_SCRIPT="${REPO_ROOT}/scripts/build_all.sh"
LEGACY_LOG="${REPO_ROOT}/build_all.log"

clean_build_run_logs() {
  if [[ -n "${BUILD_ALL_KEEP_LOGS:-}" ]]; then
    return 0
  fi
  rm -f "${LEGACY_LOG}" 2>/dev/null || true
  [[ -d "${RUNS_DIR}" ]] || return 0
  local d pid
  for d in "${RUNS_DIR}"/run-*; do
    [[ -d "${d}" ]] || continue
    if [[ -f "${d}/run.pid" ]]; then
      pid="$(<"${d}/run.pid")"
      if kill -0 "${pid}" 2>/dev/null; then
        continue
      fi
    fi
    if [[ -f "${d}/launcher.pid" ]]; then
      pid="$(<"${d}/launcher.pid")"
      if kill -0 "${pid}" 2>/dev/null; then
        continue
      fi
    fi
    if rm -rf "${d}" 2>/dev/null; then
      continue
    fi
    echo "WARN: could not remove ${d} (permission denied)." >&2
    echo "      Fix: sudo rm -rf ${d}   or run as the owner of that run directory." >&2
  done
}

extract_failures_from_log() {
  local log_file="$1"
  local out_file="$2"
  if [[ ! -s "${log_file}" ]]; then
    echo "(log empty or missing — build may not have started yet)" >"${out_file}"
    return
  fi
  {
    echo "=== Failure summary (grep heuristics) ==="
    echo "Log: ${log_file}"
    echo ""
    grep -n -E -i \
      '^(error|warning:.*error)|error\[E[0-9]+\]:|BUILD FAILURE| FAILED |^FAIL |pytest.*FAILED|panicked at|thread .* panicked|Caused by:|mvn.*FAILURE|Execution failed|SystemExit|command failed|non-zero exit|exit code [1-9]' \
      "${log_file}" 2>/dev/null || true
    echo ""
    echo "=== Last 80 lines of log ==="
    tail -n 80 "${log_file}"
  } >"${out_file}"
}

write_run_wrapper() {
  local run_dir="$1"
  shift
  local -a build_args=("$@")
  local args_quoted=""
  local arg
  for arg in "${build_args[@]}"; do
    args_quoted+=$(printf '%q ' "${arg}")
  done

  cat >"${run_dir}/run.sh" <<EOF
#!/usr/bin/env bash
set -uo pipefail
REPO_ROOT=$(printf '%q' "${REPO_ROOT}")
RUN_DIR=$(printf '%q' "${run_dir}")
BUILD_SCRIPT=$(printf '%q' "${BUILD_SCRIPT}")
BUILD_ARGS=(${args_quoted})

ensure_cargo_on_path() {
  if [[ -z "\${HOME:-}" ]]; then
    HOME="\$(getent passwd "\$(id -un)" 2>/dev/null | cut -d: -f6 || true)"
    export HOME
  fi
  if [[ -z "\${HOME:-}" ]]; then
    HOME="/home/\$(id -un)"
    export HOME
  fi
  if [[ -f "\${HOME}/.cargo/env" ]]; then
    # shellcheck source=/dev/null
    source "\${HOME}/.cargo/env"
  fi
  local cargo_bin="\${HOME}/.cargo/bin"
  if [[ -x "\${cargo_bin}/cargo" ]]; then
    case ":\${PATH}:" in
      *":\${cargo_bin}:"*) ;;
      *) export PATH="\${cargo_bin}:\${PATH}" ;;
    esac
  fi
}

cd "\${REPO_ROOT}"
ensure_cargo_on_path
echo \$\$ >"\${RUN_DIR}/run.pid"
echo "started=\$(date -Is)" >"\${RUN_DIR}/meta.txt"
echo "host=\$(hostname)" >>"\${RUN_DIR}/meta.txt"
echo "cmd=\${BUILD_SCRIPT} \${BUILD_ARGS[*]}" >>"\${RUN_DIR}/meta.txt"

set +e
"\${BUILD_SCRIPT}" "\${BUILD_ARGS[@]}" 2>&1 | tee "\${RUN_DIR}/build.log"
exit_code=\${PIPESTATUS[0]}
set -e

echo "finished=\$(date -Is)" >>"\${RUN_DIR}/meta.txt"
echo "\${exit_code}" >"\${RUN_DIR}/exit.code"
rm -f "\${RUN_DIR}/run.pid"

$(declare -f extract_failures_from_log)
extract_failures_from_log "\${RUN_DIR}/build.log" "\${RUN_DIR}/failures.txt"

if [[ \${exit_code} -eq 0 ]]; then
  echo "status=ok" >>"\${RUN_DIR}/meta.txt"
else
  echo "status=failed exit=\${exit_code}" >>"\${RUN_DIR}/meta.txt"
fi
exit "\${exit_code}"
EOF
  chmod +x "${run_dir}/run.sh"
}

cmd_start() {
  if [[ ! -x "${BUILD_SCRIPT}" ]]; then
    echo "missing or not executable: ${BUILD_SCRIPT}" >&2
    exit 1
  fi

  mkdir -p "${RUNS_DIR}"
  clean_build_run_logs
  if [[ -L "${LATEST_LINK}" ]] || [[ -d "${LATEST_LINK}" ]]; then
    local old_pid_file="${LATEST_LINK}/run.pid"
    if [[ -f "${old_pid_file}" ]]; then
      local old_pid
      old_pid="$(<"${old_pid_file}")"
      if kill -0 "${old_pid}" 2>/dev/null; then
        echo "A build is already running (pid ${old_pid})." >&2
        echo "  ./build_all_run.sh status" >&2
        echo "  ./build_all_run.sh stop   # to cancel, then start again" >&2
        exit 1
      fi
    fi
  fi

  local run_id
  run_id="$(date -u +%Y%m%d-%H%M%S)"
  local run_dir="${RUNS_DIR}/run-${run_id}"
  mkdir -p "${run_dir}"

  ln -sfn "run-${run_id}" "${LATEST_LINK}"

  write_run_wrapper "${run_dir}" "$@"

  # New session + no SIGHUP on disconnect; stdin detached from terminal.
  : >"${run_dir}/nohup.out"
  nohup setsid "${run_dir}/run.sh" </dev/null >>"${run_dir}/nohup.out" 2>&1 &
  local launcher_pid=$!
  echo "${launcher_pid}" >"${run_dir}/launcher.pid"

  sleep 0.3
  if [[ ! -f "${run_dir}/meta.txt" ]] && ! kill -0 "${launcher_pid}" 2>/dev/null; then
    echo "Build failed to start. See ${run_dir}/nohup.out" >&2
    exit 1
  fi

  cat <<EOF
Build started in background (survives SSH logout).

  Run dir:  ${run_dir}
  Log:      ${run_dir}/build.log
  Follow:   ./build_all_run.sh logs -f
  Status:   ./build_all_run.sh status
  Failures: ./build_all_run.sh failures   # after completion

EOF
}

latest_run_dir() {
  if [[ -L "${LATEST_LINK}" ]]; then
    readlink -f "${LATEST_LINK}"
    return 0
  fi
  local newest
  newest="$(find "${RUNS_DIR}" -maxdepth 1 -type d -name 'run-*' 2>/dev/null | sort | tail -n 1)"
  if [[ -n "${newest}" ]]; then
    echo "${newest}"
    return 0
  fi
  echo "No runs in ${RUNS_DIR}. Start one with: ./build_all_run.sh start" >&2
  return 1
}

cmd_status() {
  local run_dir
  run_dir="$(latest_run_dir)"

  echo "Run: ${run_dir}"
  if [[ -f "${run_dir}/meta.txt" ]]; then
    cat "${run_dir}/meta.txt"
    echo ""
  fi

  local pid=""
  if [[ -f "${run_dir}/run.pid" ]]; then
    pid="$(<"${run_dir}/run.pid")"
    if kill -0 "${pid}" 2>/dev/null; then
      echo "State: RUNNING (pid ${pid})"
      echo "Log:   ${run_dir}/build.log"
      return 0
    fi
  fi

  if [[ -f "${run_dir}/launcher.pid" ]]; then
    local lp
    lp="$(<"${run_dir}/launcher.pid")"
    if kill -0 "${lp}" 2>/dev/null; then
      echo "State: STARTING (launcher pid ${lp})"
      return 0
    fi
  fi

  if [[ -f "${run_dir}/exit.code" ]]; then
    local code
    code="$(<"${run_dir}/exit.code")"
    if [[ "${code}" == "0" ]]; then
      echo "State: FINISHED OK"
    else
      echo "State: FINISHED WITH ERRORS (exit ${code})"
      echo "Failures: ${run_dir}/failures.txt"
      echo "Hint: ./build_all_run.sh failures"
    fi
    return 0
  fi

  echo "State: UNKNOWN (no exit.code yet; check ${run_dir}/build.log)"
}

cmd_logs() {
  local run_dir
  run_dir="$(latest_run_dir)"
  local log="${run_dir}/build.log"
  if [[ ! -f "${log}" ]]; then
    echo "No log yet: ${log}" >&2
    exit 1
  fi
  if [[ "${1:-}" == "-f" ]]; then
    tail -f "${log}"
  else
    less +G "${log}" 2>/dev/null || cat "${log}"
  fi
}

cmd_failures() {
  local run_dir
  run_dir="$(latest_run_dir)"
  local failures="${run_dir}/failures.txt"
  local log="${run_dir}/build.log"

  if [[ ! -f "${failures}" ]] && [[ -f "${log}" ]]; then
    extract_failures_from_log "${log}" "${failures}"
  fi

  if [[ -f "${failures}" ]]; then
    cat "${failures}"
  else
    echo "No failures file yet. Build may still be running." >&2
    echo "  ./build_all_run.sh status" >&2
    exit 1
  fi
}

cmd_stop() {
  local run_dir
  run_dir="$(latest_run_dir)"
  local stopped=false

  if [[ -f "${run_dir}/run.pid" ]]; then
    local pid
    pid="$(<"${run_dir}/run.pid")"
    if kill -0 "${pid}" 2>/dev/null; then
      kill -TERM "${pid}" 2>/dev/null || true
      stopped=true
      echo "Sent SIGTERM to build pid ${pid}"
    fi
  fi

  if [[ -f "${run_dir}/launcher.pid" ]]; then
    local lp
    lp="$(<"${run_dir}/launcher.pid")"
    if kill -0 "${lp}" 2>/dev/null; then
      kill -TERM "${lp}" 2>/dev/null || true
      stopped=true
      echo "Sent SIGTERM to launcher pid ${lp}"
    fi
  fi

  if [[ "${stopped}" == "false" ]]; then
    echo "No running build found for ${run_dir}"
  fi
}

usage() {
  sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
}

main() {
  local cmd="${1:-start}"
  if [[ $# -gt 0 ]]; then
    shift
  fi

  case "${cmd}" in
    start|"")
      cmd_start "$@"
      ;;
    status)
      cmd_status
      ;;
    logs)
      cmd_logs "$@"
      ;;
    failures|fail)
      cmd_failures
      ;;
    stop)
      cmd_stop
      ;;
    -h|--help|help)
      usage
      ;;
    *)
      # Back-compat: ./build_all_run.sh --rust-only  →  start --rust-only
      cmd_start "${cmd}" "$@"
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
