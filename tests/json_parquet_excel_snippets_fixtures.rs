//! Parity with `docs/java/examples/JsonParquetExcelSnippets.java` and `tests/fixtures/people/`.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use rust_data_processing::ingestion::{
    ingest_from_ordered_paths, ingest_from_path, IngestionFormat, IngestionOptions,
};
use rust_data_processing::pipeline_spec::PipelineBundle;
use rust_data_processing::types::{Schema, Value};
use serde::Deserialize;

fn people_bundle() -> PipelineBundle {
    PipelineBundle::from_repo_fixture("people")
}

fn people_json_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/people.json")
}

fn people_csv_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/people.csv")
}

#[derive(Debug, Deserialize)]
struct OrderedPayload {
    paths: Vec<String>,
    schema: Schema,
    options: serde_json::Value,
}

fn ingest_from_resolved_payload(payload_json: &str) -> rust_data_processing::types::DataSet {
    let req: OrderedPayload =
        serde_json::from_str(payload_json).expect("resolved people payload JSON");
    let paths: Vec<&Path> = req.paths.iter().map(Path::new).collect();
    let format = req
        .options
        .get("format")
        .and_then(|v| v.as_str())
        .map(|s| match s {
            "json" => IngestionFormat::Json,
            "csv" => IngestionFormat::Csv,
            other => panic!("unexpected format in payload: {other}"),
        });
    let opts = IngestionOptions {
        format,
        ..Default::default()
    };
    let (ds, _) = ingest_from_ordered_paths(&paths, &req.schema, &opts).unwrap();
    ds
}

#[test]
fn people_json_path_dataset_payload_resolves() {
    let bundle = people_bundle();
    let json = bundle
        .resolve_payload_json(
            "payloads/json_path_dataset.payload.json",
            &HashMap::from([(
                "SOURCE_PATH".into(),
                people_json_path().to_string_lossy().into_owned(),
            )]),
        )
        .unwrap();
    let v: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(v["paths"][0].as_str().unwrap(), people_json_path().to_str().unwrap());
    assert!(v["schema"]["fields"].is_array());
    assert_eq!(v["options"]["format"], "json");
}

#[test]
fn people_csv_path_dataset_payload_resolves() {
    let bundle = people_bundle();
    let json = bundle
        .resolve_payload_json(
            "payloads/csv_path_dataset.payload.json",
            &HashMap::from([(
                "SOURCE_PATH".into(),
                people_csv_path().to_string_lossy().into_owned(),
            )]),
        )
        .unwrap();
    let v: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(v["paths"][0].as_str().unwrap(), people_csv_path().to_str().unwrap());
    assert!(v["schema"]["fields"].is_array());
    assert_eq!(v["options"]["format"], "csv");
}

#[test]
fn people_json_ordered_ingest_via_resolved_payload() {
    let bundle = people_bundle();
    let payload = bundle
        .resolve_payload_json(
            "payloads/json_path_dataset.payload.json",
            &HashMap::from([(
                "SOURCE_PATH".into(),
                people_json_path().to_string_lossy().into_owned(),
            )]),
        )
        .unwrap();
    let ds = ingest_from_resolved_payload(&payload);
    assert_eq!(ds.row_count(), 2);
    assert_eq!(ds.rows[0][0], Value::Int64(1));
    assert_eq!(ds.rows[0][1], Value::Utf8("Ada".to_string()));
}

#[test]
fn people_csv_ordered_ingest_via_resolved_payload() {
    let bundle = people_bundle();
    let payload = bundle
        .resolve_payload_json(
            "payloads/csv_path_dataset.payload.json",
            &HashMap::from([(
                "SOURCE_PATH".into(),
                people_csv_path().to_string_lossy().into_owned(),
            )]),
        )
        .unwrap();
    let ds = ingest_from_resolved_payload(&payload);
    assert_eq!(ds.row_count(), 2);
    assert_eq!(ds.rows[0][1], Value::Utf8("Ada".to_string()));
}

#[test]
fn people_json_path_ingest_with_committed_options() {
    let bundle = people_bundle();
    let schema = bundle
        .load_schema("schemas/people_json.schema.json")
        .unwrap();
    let opts = IngestionOptions {
        format: Some(IngestionFormat::Json),
        ..Default::default()
    };
    let ds = ingest_from_path(&people_json_path(), &schema, &opts).unwrap();
    assert_eq!(ds.row_count(), 2);
}

#[test]
fn people_csv_path_ingest_with_committed_options() {
    let bundle = people_bundle();
    let schema = bundle
        .load_schema("schemas/people_csv.schema.json")
        .unwrap();
    let opts = IngestionOptions {
        format: Some(IngestionFormat::Csv),
        ..Default::default()
    };
    let ds = ingest_from_path(&people_csv_path(), &schema, &opts).unwrap();
    assert_eq!(ds.row_count(), 2);
    assert_eq!(ds.rows[0][0], Value::Int64(1));
}
