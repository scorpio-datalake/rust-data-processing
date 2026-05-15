//! Parity with `docs/java/examples/ParquetSnippets.java` and `tests/fixtures/people/`.

use std::collections::HashMap;
use std::path::PathBuf;

use rust_data_processing::ingestion::parquet::ingest_parquet_from_path;
use rust_data_processing::ingestion::{
    IngestionFormat, IngestionOptions, export_dataset_to_parquet, ingest_from_path,
};
use rust_data_processing::pipeline_spec::PipelineBundle;
use rust_data_processing::types::Value;

fn people_bundle() -> PipelineBundle {
    PipelineBundle::from_repo_fixture("people")
}

fn people_csv_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/people.csv")
}

fn tmp_parquet(name: &str) -> PathBuf {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    std::env::temp_dir().join(format!("rdp_parquet_snippets_{name}_{nanos}.parquet"))
}

#[test]
fn people_csv_to_parquet_pipeline_template_resolves() {
    let bundle = people_bundle();
    let out = tmp_parquet("resolve");
    let json = bundle
        .resolve_pipeline_json(
            "pipelines/csv_to_parquet.pipeline.json",
            &HashMap::from([
                (
                    "SOURCE_PATH".into(),
                    people_csv_path().to_string_lossy().into_owned(),
                ),
                ("SINK_PATH".into(), out.to_string_lossy().into_owned()),
            ]),
        )
        .unwrap();
    let v: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(v["sinks"][0]["kind"], "parquet_file");
    assert!(v["sources"]["schema"]["fields"].is_array());
    let _ = std::fs::remove_file(out);
}

/// CSV → Parquet via Rust export, then read back with `people_flat` schema (JVM: pipeline + path ingest).
#[test]
fn people_csv_parquet_round_trip_matches_parquet_snippets() {
    let bundle = people_bundle();
    let csv_schema = bundle
        .load_schema("schemas/people_csv.schema.json")
        .unwrap();
    let flat_schema = bundle
        .load_schema("schemas/people_flat.schema.json")
        .unwrap();
    let csv = people_csv_path();
    let opts = IngestionOptions {
        format: Some(IngestionFormat::Csv),
        ..Default::default()
    };

    let ds = ingest_from_path(&csv, &csv_schema, &opts).unwrap();
    assert_eq!(ds.row_count(), 2);

    let parquet = tmp_parquet("roundtrip");
    export_dataset_to_parquet(&parquet, &ds).unwrap();
    assert!(parquet.is_file());

    let back = ingest_parquet_from_path(&parquet, &flat_schema).unwrap();
    assert_eq!(back.row_count(), 2);
    assert_eq!(back.rows[0][0], Value::Int64(1));
    assert_eq!(back.rows[1][0], Value::Int64(2));

    let _ = std::fs::remove_file(parquet);
}
