"""Load a PyDataSet into Oracle (integration_testing/Oracle only — not RDP product API)."""

from __future__ import annotations

import os


def connectorx_url_to_python(url: str) -> str:
    """oracle://user:pass@host:1521/SVC → user/pass@host:1521/SVC for python-oracledb."""
    if not url.startswith("oracle://"):
        raise ValueError(f"expected oracle:// URL, got {url}")
    return url[len("oracle://") :]


def load_dataset(url: str, ds, *, table: str = "UBER_PICKUPS") -> int:
    try:
        import oracledb
    except ImportError as e:
        raise RuntimeError(
            "python-oracledb required for Oracle integration tests: pip install oracledb"
        ) from e

    connect = connectorx_url_to_python(url)
    inserted = 0
    with oracledb.connect(connect) as conn:
        with conn.cursor() as cur:
            sql = (
                f"INSERT INTO {table} (pickup_time, lat, lon, base_code) "
                "VALUES (:1, :2, :3, :4)"
            )
            col_map = {
                "Date/Time": 0,
                "Lat": 1,
                "Lon": 2,
                "Base": 3,
            }
            for row in ds.to_rows():
                vals: list[object | None] = [None, None, None, None]
                for field, value in zip(ds.schema(), row, strict=False):
                    idx = col_map.get(field["name"])
                    if idx is not None:
                        vals[idx] = value
                cur.execute(sql, vals)
                inserted += 1
        conn.commit()
    return inserted


def reset_table(url: str, *, table: str = "UBER_PICKUPS") -> None:
    try:
        import oracledb
    except ImportError as e:
        raise RuntimeError("pip install oracledb for Oracle integration tests") from e

    connect = connectorx_url_to_python(url)
    with oracledb.connect(connect) as conn:
        with conn.cursor() as cur:
            try:
                cur.execute(f"DROP TABLE {table} PURGE")
            except oracledb.DatabaseError:
                pass
            cur.execute(
                f"""
                CREATE TABLE {table} (
                    pickup_time VARCHAR2(64),
                    lat NUMBER,
                    lon NUMBER,
                    base_code VARCHAR2(32)
                )
                """
            )
        conn.commit()
