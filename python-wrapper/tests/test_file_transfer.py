"""SFTP/FTP ingest (requires extension built with --features cloud)."""

from __future__ import annotations

import socket
import threading
import time
from pathlib import Path

import pytest
import rust_data_processing as rdp

REPO = Path(__file__).resolve().parents[2]
FIXTURE = REPO / "tests/fixtures/file_transfer"


def _ftp_reply(conn: socket.socket, msg: str) -> None:
    conn.sendall((msg + "\r\n").encode())


def _run_control(conn: socket.socket, data_listener: socket.socket, payload: bytes) -> None:
    _ftp_reply(conn, "220 rdp-test FTP ready")
    buf = b""
    while True:
        chunk = conn.recv(256)
        if not chunk:
            break
        buf += chunk
        if b"\n" not in buf and b"\r" not in buf:
            continue
        line = buf.decode("utf-8", errors="replace").strip().upper()
        buf = b""
        if line.startswith("USER"):
            _ftp_reply(conn, "331 Password required")
        elif line.startswith("PASS"):
            _ftp_reply(conn, "230 User logged in")
        elif line.startswith("CWD"):
            _ftp_reply(conn, "250 CWD ok")
        elif line.startswith("TYPE"):
            _ftp_reply(conn, "200 Type set to I")
        elif line.startswith("PASV"):
            port = data_listener.getsockname()[1]
            p1, p2 = port // 256, port % 256
            _ftp_reply(conn, f"227 Entering Passive Mode (127,0,0,1,{p1},{p2})")
        elif line.startswith("RETR"):
            _ftp_reply(conn, "150 Opening BINARY mode data connection")
            data_conn, _ = data_listener.accept()
            data_conn.sendall(payload)
            data_conn.close()
            _ftp_reply(conn, "226 Transfer complete")
        elif line.startswith("QUIT"):
            _ftp_reply(conn, "221 Goodbye")
            break


def _spawn_loopback_ftp(payload: bytes) -> int:
    control = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    control.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    control.bind(("127.0.0.1", 0))
    control.listen(1)
    control_port = control.getsockname()[1]

    data_listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    data_listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    data_listener.bind(("127.0.0.1", 0))
    data_listener.listen(1)

    ready = threading.Event()

    def serve() -> None:
        ready.set()
        conn, _ = control.accept()
        try:
            _run_control(conn, data_listener, payload)
        finally:
            conn.close()
            control.close()
            data_listener.close()

    threading.Thread(target=serve, daemon=True).start()
    ready.wait(timeout=2)
    time.sleep(0.05)
    return control_port


def _has_file_transfer() -> bool:
    return hasattr(rdp, "ingest_from_file_transfer_uri")


@pytest.mark.skipif(not _has_file_transfer(), reason="rebuild with maturin --features cloud")
def test_ingest_from_ftp_uri_local_server() -> None:
    schema = [
        {"name": "id", "data_type": "int64"},
        {"name": "name", "data_type": "utf8"},
    ]
    payload = (FIXTURE / "data/two_rows.json").read_text(encoding="utf-8").encode()
    port = _spawn_loopback_ftp(payload)
    uri = f"ftp://etl_user:secret@127.0.0.1:{port}/incoming/two_rows.json"
    ds = rdp.ingest_from_file_transfer_uri(uri, schema, {"format": "json"})
    assert ds.row_count() == 2


@pytest.mark.skipif(not _has_file_transfer(), reason="rebuild with maturin --features cloud")
def test_sftp_uri_parse_stub() -> None:
    """Invalid host should fail fast without requiring a live SFTP server."""
    schema = [{"name": "id", "data_type": "int64"}]
    with pytest.raises(ValueError, match="sftp|connect|Engine|Schema"):
        rdp.ingest_from_file_transfer_uri(
            "sftp://nobody:bad@127.0.0.1:9/nonexistent.parquet",
            schema,
            {"format": "parquet"},
        )
