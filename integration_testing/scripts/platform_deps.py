"""Install optional Python deps for platform connector SQL tests (python-wrapper venv via uv)."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent
_REPO = _SCRIPTS.parent.parent
_PY_WRAPPER = _REPO / "python-wrapper"
_REQ = _SCRIPTS / "requirements-platform.txt"
_VENV_PYTHON = _PY_WRAPPER / ".venv" / "bin" / "python"


def platform_python() -> Path:
    """Interpreter that runs platform_sql.py (venv after ensure_platform_sql_deps)."""
    override = os.environ.get("RDP_PLATFORM_PYTHON")
    if override:
        return Path(override)
    if _VENV_PYTHON.is_file():
        return _VENV_PYTHON
    return Path(sys.executable)


def ensure_platform_sql_deps() -> None:
    """Ensure python-wrapper venv exists for platform_sql.py (stdlib only; no snowflake-connector)."""
    if shutil.which("uv") is None:
        raise SystemExit(
            "[integration] uv not found — install uv or run: "
            "cd python-wrapper && uv sync --group dev"
        )
    subprocess.run(
        ["uv", "sync", "--group", "dev", "--quiet"],
        cwd=_PY_WRAPPER,
        check=True,
    )
    if _REQ.is_file() and _REQ.read_text(encoding="utf-8").strip():
        subprocess.run(
            ["uv", "pip", "install", "-q", "-r", str(_REQ)],
            cwd=_PY_WRAPPER,
            check=True,
        )
    if not _VENV_PYTHON.is_file():
        raise SystemExit(f"[integration] venv python missing after uv pip: {_VENV_PYTHON}")
    os.environ["RDP_PLATFORM_PYTHON"] = str(_VENV_PYTHON)


if __name__ == "__main__":
    ensure_platform_sql_deps()
    print(f"[integration] platform SQL deps OK ({_VENV_PYTHON})")
