//! Parity with `docs/java/examples/ExcelSnippets.java` and `tests/fixtures/people/`.
//!
//! Run with Excel enabled: `cargo test --features excel --test excel_snippets_fixtures`

#![cfg(feature = "excel")]

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use rust_data_processing::ingestion::{
    ingest_from_ordered_paths, ingest_from_path, ingest_from_path_infer, ExcelSheetSelection,
    IngestionFormat, IngestionOptions,
};
use rust_data_processing::pipeline_spec::PipelineBundle;
use rust_data_processing::types::{Schema, Value};
use serde::Deserialize;

const DEFAULT_SHEET: &str = "Sheet1";

fn people_bundle() -> PipelineBundle {
    PipelineBundle::from_repo_fixture("people")
}

fn people_xlsx() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/people.xlsx")
}

fn require_people_xlsx() -> PathBuf {
    let path = people_xlsx();
    assert!(
        path.is_file(),
        "missing {} — run: cargo run --features excel_test_writer --bin generate_people_xlsx_fixture",
        path.display()
    );
    path
}

#[derive(Debug, Deserialize)]
struct OrderedPayload {
    paths: Vec<String>,
    schema: Schema,
    options: serde_json::Value,
}

fn ingest_from_resolved_excel_payload(payload_json: &str) -> rust_data_processing::types::DataSet {
    let req: OrderedPayload =
        serde_json::from_str(payload_json).expect("resolved excel payload JSON");
    let paths: Vec<&Path> = req.paths.iter().map(Path::new).collect();
    let sheet = req
        .options
        .get("sheet_name")
        .and_then(|v| v.as_str())
        .unwrap_or(DEFAULT_SHEET);
    let opts = IngestionOptions {
        format: Some(IngestionFormat::Excel),
        excel_sheet_selection: ExcelSheetSelection::Sheet(sheet.to_string()),
        ..Default::default()
    };
    let (ds, _) = ingest_from_ordered_paths(&paths, &req.schema, &opts).unwrap();
    ds
}

#[test]
fn people_excel_sheet_dataset_payload_resolves() {
    let bundle = people_bundle();
    let xlsx = require_people_xlsx();
    let json = bundle
        .resolve_payload_json(
            "payloads/excel_sheet_dataset.payload.json",
            &HashMap::from([
                (
                    "SOURCE_PATH".into(),
                    xlsx.to_string_lossy().into_owned(),
                ),
                ("SHEET_NAME".into(), DEFAULT_SHEET.into()),
            ]),
        )
        .unwrap();
    let v: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(v["options"]["format"], "excel");
    assert_eq!(v["options"]["sheet_name"], DEFAULT_SHEET);
    assert!(v["schema"]["fields"].is_array());
}

#[test]
fn people_excel_ordered_ingest_via_resolved_payload() {
    let bundle = people_bundle();
    let xlsx = require_people_xlsx();
    let payload = bundle
        .resolve_payload_json(
            "payloads/excel_sheet_dataset.payload.json",
            &HashMap::from([
                (
                    "SOURCE_PATH".into(),
                    xlsx.to_string_lossy().into_owned(),
                ),
                ("SHEET_NAME".into(), DEFAULT_SHEET.into()),
            ]),
        )
        .unwrap();
    let ds = ingest_from_resolved_excel_payload(&payload);
    assert_eq!(ds.row_count(), 2);
    assert_eq!(ds.rows[0][0], Value::Int64(1));
    assert_eq!(ds.rows[0][1], Value::Utf8("Ada".to_string()));
}

#[test]
fn people_excel_path_ingest_sheet1_matches_doc_example() {
    let xlsx = require_people_xlsx();
    let schema = people_bundle()
        .load_schema("schemas/people_flat.schema.json")
        .unwrap();
    let opts = IngestionOptions {
        format: Some(IngestionFormat::Excel),
        excel_sheet_selection: ExcelSheetSelection::Sheet(DEFAULT_SHEET.to_string()),
        ..Default::default()
    };
    let ds = ingest_from_path(&xlsx, &schema, &opts).unwrap();
    assert_eq!(ds.row_count(), 2);
    assert_eq!(ds.rows[1][1], Value::Utf8("Grace".to_string()));
}

/// Mirrors JVM {@code rdp_excel_ingest_path_sheet} (schema inferred in Rust).
#[test]
fn people_excel_inferred_schema_matches_path_sheet_ffi() {
    let xlsx = require_people_xlsx();
    let opts = IngestionOptions {
        format: Some(IngestionFormat::Excel),
        excel_sheet_selection: ExcelSheetSelection::Sheet(DEFAULT_SHEET.to_string()),
        ..Default::default()
    };
    let ds = ingest_from_path_infer(&xlsx, &opts).unwrap();
    assert_eq!(ds.row_count(), 2);
    assert_eq!(ds.rows[0][0], Value::Int64(1));
}
