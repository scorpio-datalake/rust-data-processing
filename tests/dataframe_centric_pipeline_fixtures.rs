//! Parity with `docs/java/examples/DataFrameCentricPipeline.java` and `tests/fixtures/jvm_contract/`.

use std::collections::HashMap;
use std::path::PathBuf;

use rust_data_processing::ingestion::parquet::ingest_parquet_from_path;
use rust_data_processing::ingestion::{
    IngestionFormat, IngestionOptions, export_dataset_to_parquet, ingest_from_path,
};
use rust_data_processing::pipeline::DataFrame;
use rust_data_processing::pipeline_spec::PipelineBundle;
use rust_data_processing::sql;
use rust_data_processing::types::Value;

fn jvm_contract_bundle() -> PipelineBundle {
    PipelineBundle::from_repo_fixture("jvm_contract")
}

fn three_rows_json() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/jvm_contract_three_rows.json")
}

fn tmp_parquet() -> PathBuf {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    std::env::temp_dir().join(format!("rdp_dataframe_centric_{nanos}.parquet"))
}

#[test]
fn jvm_contract_dataframe_centric_pipeline_template_resolves() {
    let bundle = jvm_contract_bundle();
    let json = bundle
        .resolve_pipeline_json(
            "pipelines/dataframe_centric_sql.pipeline.json",
            &HashMap::from([
                (
                    "SOURCE_PATH".into(),
                    three_rows_json().to_string_lossy().into_owned(),
                ),
                ("SINK_PATH".into(), "/tmp/out.parquet".into()),
            ]),
        )
        .unwrap();
    let v: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(v["sinks"][0]["kind"], "parquet_file");
    assert!(v["sources"]["schema"]["fields"].is_array());
    assert!(
        v["transform"]["sql"]
            .as_str()
            .unwrap()
            .contains("score * 2.0")
    );
}

/// Polars SQL from the pipeline template (JVM runs the same via `rdp_run_pipeline_json`).
#[test]
fn dataframe_centric_sql_filter_and_multiply_matches_doc_example() {
    let bundle = jvm_contract_bundle();
    let schema = bundle
        .load_schema("schemas/three_rows.schema.json")
        .unwrap();
    let opts = IngestionOptions {
        format: Some(IngestionFormat::Json),
        ..Default::default()
    };
    let ds = ingest_from_path(&three_rows_json(), &schema, &opts).unwrap();
    assert_eq!(ds.row_count(), 3);

    let sql = bundle
        .pipeline_transform_sql("pipelines/dataframe_centric_sql.pipeline.json")
        .unwrap();
    let out = sql::query(&DataFrame::from_dataset(&ds).unwrap(), &sql)
        .unwrap()
        .collect()
        .unwrap();

    assert_eq!(out.row_count(), 2);
    assert_eq!(out.rows[0][0], Value::Int64(1));
    assert_eq!(out.rows[0][2], Value::Float64(20.0));
    assert_eq!(out.rows[1][0], Value::Int64(2));
    assert_eq!(out.rows[1][2], Value::Float64(40.0));
}

#[test]
fn dataframe_centric_sql_then_parquet_round_trip() {
    let bundle = jvm_contract_bundle();
    let schema = bundle
        .load_schema("schemas/three_rows.schema.json")
        .unwrap();
    let opts = IngestionOptions {
        format: Some(IngestionFormat::Json),
        ..Default::default()
    };
    let ds = ingest_from_path(&three_rows_json(), &schema, &opts).unwrap();
    let sql = bundle
        .pipeline_transform_sql("pipelines/dataframe_centric_sql.pipeline.json")
        .unwrap();
    let transformed = sql::query(&DataFrame::from_dataset(&ds).unwrap(), &sql)
        .unwrap()
        .collect()
        .unwrap();

    let parquet = tmp_parquet();
    export_dataset_to_parquet(&parquet, &transformed).unwrap();
    let back = ingest_parquet_from_path(&parquet, &transformed.schema).unwrap();
    assert_eq!(back.row_count(), 2);
    assert_eq!(back.rows[0][2], Value::Float64(20.0));

    let _ = std::fs::remove_file(parquet);
}
