"""Load shared schema / pipeline / payload JSON from tests/fixtures (Rust PipelineBundle parity)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from tests.conftest import FIXTURES


def bundle_root(name: str) -> Path:
    root = FIXTURES / name
    if not root.is_dir():
        raise FileNotFoundError(f"fixture bundle missing: {root}")
    return root


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_schema(path: Path) -> dict[str, Any]:
    return _read_json(path)


def load_schema_fields(*parts: str, bundle: str | None = None) -> list[dict[str, str]]:
    """Serde schema JSON → list of fields for ``ingest_from_path`` (lowercase ``data_type``)."""
    if bundle is not None:
        path = bundle_root(bundle).joinpath(*parts)
    else:
        path = FIXTURES.joinpath(*parts)
    data = load_schema(path)
    return [
        {"name": f["name"], "data_type": f["data_type"].lower()}
        for f in data["fields"]
    ]


def _expand_schema_refs(node: Any, root: Path) -> None:
    if isinstance(node, dict):
        ref_keys = [k for k in node if k.endswith("_ref")]
        for ref_key in ref_keys:
            rel = node.pop(ref_key)
            target = ref_key[: -len("_ref")]
            node[target] = _read_json(root / rel)
        for v in node.values():
            _expand_schema_refs(v, root)
    elif isinstance(node, list):
        for item in node:
            _expand_schema_refs(item, root)


def _bind_placeholders(node: Any, bindings: dict[str, str]) -> None:
    if isinstance(node, str):
        return
    if isinstance(node, dict):
        for k, v in list(node.items()):
            if isinstance(v, str):
                out = v
                for key, repl in bindings.items():
                    out = out.replace(f"{{{{{key}}}}}", repl)
                node[k] = out
            else:
                _bind_placeholders(v, bindings)
    elif isinstance(node, list):
        for i, item in enumerate(node):
            if isinstance(item, str):
                out = item
                for key, repl in bindings.items():
                    out = out.replace(f"{{{{{key}}}}}", repl)
                node[i] = out
            else:
                _bind_placeholders(item, bindings)


def resolve_pipeline_json(
    bundle: str, pipeline_rel: str, bindings: dict[str, str]
) -> str:
    root = bundle_root(bundle)
    doc = _read_json(root / pipeline_rel)
    _expand_schema_refs(doc, root)
    _bind_placeholders(doc, bindings)
    return json.dumps(doc)


def resolve_payload_json(bundle: str, payload_rel: str, bindings: dict[str, str]) -> str:
    root = bundle_root(bundle)
    doc = _read_json(root / payload_rel)
    _expand_schema_refs(doc, root)
    _bind_placeholders(doc, bindings)
    return json.dumps(doc)


def pipeline_transform_sql(bundle: str, pipeline_rel: str) -> str:
    """Read ``transform.sql`` from a pipeline template (no path binding)."""
    doc = _read_json(bundle_root(bundle) / pipeline_rel)
    return doc["transform"]["sql"]
