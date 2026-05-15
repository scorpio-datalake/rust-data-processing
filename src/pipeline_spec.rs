//! Load shared pipeline + schema JSON from a fixture bundle (e.g. `tests/fixtures/ghcn/`).
//!
//! Bundles are language-neutral: Java, Python, and Rust tests read the same files. Use
//! `{{PLACEHOLDER}}` in pipeline templates for paths resolved at runtime.
//!
//! # Layout
//!
//! ```text
//! tests/fixtures/<bundle>/
//!   schemas/*.schema.json          — serde [`crate::types::Schema`] JSON
//!   pipelines/*.pipeline.json      — `rdp_run_pipeline_json` control plane
//!   payloads/*.payload.json        — `rdp_ingest_ordered_paths_json` bodies
//! ```
//!
//! Bundles: `ghcn`, `jvm_contract`, `student_etl`, `people`, `watermark`, `deep`, …
//!
//! Pipelines may use `"schema_ref": "schemas/foo.schema.json"` (relative to the bundle root)
//! instead of an inline `"schema"` object.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use crate::error::{IngestionError, IngestionResult};
use crate::types::Schema;

/// Root directory for a fixture bundle (`tests/fixtures/ghcn`, etc.).
#[derive(Debug, Clone)]
pub struct PipelineBundle {
    root: PathBuf,
}

impl PipelineBundle {
    /// Bundle rooted at `tests/fixtures/<name>` under the repository (via `CARGO_MANIFEST_DIR`).
    pub fn from_repo_fixture(name: &str) -> Self {
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("tests")
            .join("fixtures")
            .join(name);
        Self { root }
    }

    /// Bundle with an explicit filesystem root (JVM tests pass `tests/fixtures/ghcn`).
    pub fn from_root(root: impl AsRef<Path>) -> Self {
        Self {
            root: root.as_ref().to_path_buf(),
        }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    /// Load a serde [`Schema`] from `rel` (e.g. `schemas/json_source.schema.json`).
    pub fn load_schema(&self, rel: &str) -> IngestionResult<Schema> {
        let path = self.root.join(rel);
        let text = std::fs::read_to_string(&path).map_err(|e| IngestionError::Io(e))?;
        serde_json::from_str(&text).map_err(|e| IngestionError::SchemaMismatch {
            message: format!("schema JSON {}: {e}", path.display()),
        })
    }

    /// Like [`Self::load_schema`], but panics with bundle path context (integration tests).
    pub fn expect_schema(&self, rel: &str) -> Schema {
        self.load_schema(rel).unwrap_or_else(|e| {
            panic!(
                "fixture schema {}: {e}",
                self.root.join(rel).display()
            )
        })
    }

    /// Load a pipeline template, expand `*_ref` schema pointers, substitute `{{KEY}}` placeholders.
    pub fn resolve_pipeline_json(
        &self,
        pipeline_rel: &str,
        bindings: &HashMap<String, String>,
    ) -> IngestionResult<String> {
        let mut doc = self.load_json_value(pipeline_rel)?;
        resolve_schema_refs(&mut doc, self)?;
        bind_placeholders(&mut doc, bindings)?;
        serde_json::to_string(&doc).map_err(|e| IngestionError::SchemaMismatch {
            message: format!("serialize resolved pipeline: {e}"),
        })
    }

    /// Load an `rdp_ingest_ordered_paths_json` (or similar) payload template.
    pub fn resolve_payload_json(
        &self,
        payload_rel: &str,
        bindings: &HashMap<String, String>,
    ) -> IngestionResult<String> {
        let mut doc = self.load_json_value(payload_rel)?;
        resolve_schema_refs(&mut doc, self)?;
        bind_placeholders(&mut doc, bindings)?;
        serde_json::to_string(&doc).map_err(|e| IngestionError::SchemaMismatch {
            message: format!("serialize resolved payload: {e}"),
        })
    }

    fn load_json_value(&self, rel: &str) -> IngestionResult<serde_json::Value> {
        let path = self.root.join(rel);
        let text = std::fs::read_to_string(&path).map_err(|e| IngestionError::Io(e))?;
        serde_json::from_str(&text).map_err(|e| IngestionError::SchemaMismatch {
            message: format!("JSON {}: {e}", path.display()),
        })
    }

    /// Schema JSON string for path ingest FFI (`rdp_ingest_xml_path`, etc.).
    pub fn schema_json(&self, schema_rel: &str) -> IngestionResult<String> {
        let schema = self.load_schema(schema_rel)?;
        serde_json::to_string(&schema).map_err(|e| IngestionError::SchemaMismatch {
            message: format!("serialize schema: {e}"),
        })
    }

    /// Polars SQL from a pipeline template (`transform.sql`), e.g. `SQLQueries.java` / pytest parity.
    pub fn pipeline_transform_sql(&self, pipeline_rel: &str) -> IngestionResult<String> {
        let doc = self.load_json_value(pipeline_rel)?;
        doc.get("transform")
            .and_then(|t| t.get("sql"))
            .and_then(|s| s.as_str())
            .map(str::to_string)
            .ok_or_else(|| IngestionError::SchemaMismatch {
                message: format!(
                    "pipeline {} missing transform.sql string",
                    self.root.join(pipeline_rel).display()
                ),
            })
    }

    /// SQL text from `queries/*.sql.json` (`{ "sql": "..." }`), e.g. `sql_parity` JOIN fixtures.
    pub fn load_query_sql(&self, query_rel: &str) -> IngestionResult<String> {
        let doc = self.load_json_value(query_rel)?;
        doc.get("sql")
            .and_then(|s| s.as_str())
            .map(str::to_string)
            .ok_or_else(|| IngestionError::SchemaMismatch {
                message: format!(
                    "query {} missing sql string",
                    self.root.join(query_rel).display()
                ),
            })
    }
}

fn resolve_schema_refs(value: &mut serde_json::Value, bundle: &PipelineBundle) -> IngestionResult<()> {
    match value {
        serde_json::Value::Object(map) => {
            let ref_keys: Vec<String> = map
                .keys()
                .filter(|k| k.ends_with("_ref"))
                .cloned()
                .collect();
            for old_key in ref_keys {
                let rel = map.remove(&old_key).ok_or_else(|| IngestionError::SchemaMismatch {
                    message: format!("missing key {old_key} while expanding schema refs"),
                })?;
                let rel_str = rel.as_str().ok_or_else(|| IngestionError::SchemaMismatch {
                    message: format!("{old_key} must be a string path relative to the bundle root"),
                })?;
                let new_key = old_key
                    .strip_suffix("_ref")
                    .expect("keys end with _ref")
                    .to_string();
                let schema = bundle.load_schema(rel_str)?;
                map.insert(
                    new_key,
                    serde_json::to_value(&schema).map_err(|e| IngestionError::SchemaMismatch {
                        message: format!("embed schema from {rel_str}: {e}"),
                    })?,
                );
            }
            for child in map.values_mut() {
                resolve_schema_refs(child, bundle)?;
            }
        }
        serde_json::Value::Array(items) => {
            for child in items {
                resolve_schema_refs(child, bundle)?;
            }
        }
        _ => {}
    }
    Ok(())
}

/// Replace `{{NAME}}` in every string leaf of `value`.
pub fn bind_placeholders(
    value: &mut serde_json::Value,
    bindings: &HashMap<String, String>,
) -> IngestionResult<()> {
    match value {
        serde_json::Value::String(s) => {
            for (key, replacement) in bindings {
                let needle = format!("{{{{{key}}}}}");
                if s.contains(&needle) {
                    *s = s.replace(&needle, replacement);
                }
            }
        }
        serde_json::Value::Array(items) => {
            for child in items {
                bind_placeholders(child, bindings)?;
            }
        }
        serde_json::Value::Object(map) => {
            for child in map.values_mut() {
                bind_placeholders(child, bindings)?;
            }
        }
        _ => {}
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ghcn_bundle_resolves_json_to_xml_pipeline() {
        let bundle = PipelineBundle::from_repo_fixture("ghcn");
        let json = bundle
            .resolve_pipeline_json(
                "pipelines/json_to_xml.pipeline.json",
                &HashMap::from([
                    ("SOURCE_PATH".into(), "/data/in.json".into()),
                    ("SINK_PATH".into(), "/tmp/out.xml".into()),
                ]),
            )
            .unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(v["sources"]["paths"][0], "/data/in.json");
        assert_eq!(v["sinks"][0]["path"], "/tmp/out.xml");
        assert!(v["sources"]["schema"]["fields"].is_array());
        assert!(v["sources"].get("schema_ref").is_none());
    }

    #[test]
    fn ghcn_schemas_load() {
        let bundle = PipelineBundle::from_repo_fixture("ghcn");
        let s = bundle.load_schema("schemas/parquet_lake.schema.json").unwrap();
        assert_eq!(s.fields.len(), 6);
        assert_eq!(s.fields[0].name, "station_id");
    }

    #[test]
    fn jvm_contract_resolves_ordered_paths_payload() {
        let bundle = PipelineBundle::from_repo_fixture("jvm_contract");
        let json = bundle
            .resolve_payload_json(
                "payloads/ordered_paths_dataset.payload.json",
                &HashMap::from([
                    ("PATH_A".into(), "/a.csv".into()),
                    ("PATH_B".into(), "/b.csv".into()),
                ]),
            )
            .unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(v["paths"][0], "/a.csv");
        assert!(v["schema"]["fields"].is_array());
    }

    #[test]
    fn jvm_contract_sql_query_dataset_pipeline_has_transform_sql() {
        let bundle = PipelineBundle::from_repo_fixture("jvm_contract");
        let sql = bundle
            .pipeline_transform_sql("pipelines/sql_query_dataset.pipeline.json")
            .unwrap();
        assert!(sql.contains("active = TRUE"));
        assert!(sql.contains("ORDER BY id DESC"));
    }

    #[test]
    fn sql_parity_join_query_json_loads() {
        let bundle = PipelineBundle::from_repo_fixture("sql_parity");
        let sql = bundle
            .load_query_sql("queries/join_people_scores.sql.json")
            .unwrap();
        assert!(sql.contains("JOIN scores"));
        assert!(sql.contains("people p"));
    }

    #[test]
    fn student_etl_legacy_pipeline_expands_schema_refs() {
        let bundle = PipelineBundle::from_repo_fixture("student_etl");
        let json = bundle
            .resolve_pipeline_json(
                "pipelines/legacy_student_etl.pipeline.json",
                &HashMap::from([("SOURCE_PATH".into(), "/part-0.json".into())]),
            )
            .unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert!(v["schema_student_json"]["fields"].is_array());
        assert!(v.get("schema_student_json_ref").is_none());
    }
}
