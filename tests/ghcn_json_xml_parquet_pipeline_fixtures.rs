//! Parity with `docs/java/examples/GhcnJsonXmlParquetPipeline.java` and `tests/fixtures/ghcn/`.

use std::collections::HashMap;
use std::path::PathBuf;

use rust_data_processing::ingestion::parquet::ingest_parquet_from_path;
use rust_data_processing::ingestion::xml::ingest_xml_from_path;
use rust_data_processing::ingestion::{
    IngestionFormat, IngestionOptions, export_dataset_to_parquet, export_dataset_to_xml,
    ingest_from_path,
};
use rust_data_processing::pipeline::DataFrame;
use rust_data_processing::pipeline_spec::PipelineBundle;
use rust_data_processing::sql;
use rust_data_processing::types::Value;

const EXPECTED_ROW_COUNT: usize = 5;
const EXPECTED_FIRST_STATION_ID: &str = "ACW00011604";

fn ghcn_bundle() -> PipelineBundle {
    PipelineBundle::from_repo_fixture("ghcn")
}

fn ghcn_json_sample() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/ghcn/ghcn_stations_sample.json")
}

fn tmp_sink(ext: &str) -> PathBuf {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    std::env::temp_dir().join(format!("rdp_ghcn_pipeline_{nanos}.{ext}"))
}

#[test]
fn ghcn_json_to_xml_pipeline_template_resolves() {
    let bundle = ghcn_bundle();
    let json = bundle
        .resolve_pipeline_json(
            "pipelines/json_to_xml.pipeline.json",
            &HashMap::from([
                (
                    "SOURCE_PATH".into(),
                    ghcn_json_sample().to_string_lossy().into_owned(),
                ),
                ("SINK_PATH".into(), "/tmp/out.xml".into()),
            ]),
        )
        .unwrap();
    let v: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(v["sinks"][0]["kind"], "xml_file");
    assert!(v["sources"]["schema"]["fields"].is_array());
    assert!(
        v["transform"]["sql"]
            .as_str()
            .unwrap()
            .contains("stationCode")
    );
}

#[test]
fn ghcn_xml_to_parquet_pipeline_template_resolves() {
    let bundle = ghcn_bundle();
    let json = bundle
        .resolve_pipeline_json(
            "pipelines/xml_to_parquet.pipeline.json",
            &HashMap::from([
                ("SOURCE_PATH".into(), "/tmp/in.xml".into()),
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
            .contains("station_id")
    );
}

#[test]
fn ghcn_json_xml_parquet_pipeline_matches_doc_example() {
    let bundle = ghcn_bundle();
    let json_schema = bundle
        .load_schema("schemas/json_source.schema.json")
        .unwrap();
    let xml_schema = bundle
        .load_schema("schemas/xml_intermediate.schema.json")
        .unwrap();
    let lake_schema = bundle
        .load_schema("schemas/parquet_lake.schema.json")
        .unwrap();

    let json_opts = IngestionOptions {
        format: Some(IngestionFormat::Json),
        ..Default::default()
    };
    let json_ds = ingest_from_path(&ghcn_json_sample(), &json_schema, &json_opts).unwrap();
    assert_eq!(json_ds.row_count(), EXPECTED_ROW_COUNT);

    let sql_json_to_xml = bundle
        .pipeline_transform_sql("pipelines/json_to_xml.pipeline.json")
        .unwrap();
    let intermediate = sql::query(
        &DataFrame::from_dataset(&json_ds).unwrap(),
        &sql_json_to_xml,
    )
    .unwrap()
    .collect()
    .unwrap();
    assert_eq!(intermediate.row_count(), EXPECTED_ROW_COUNT);
    assert_eq!(
        intermediate.rows[0][0],
        Value::Utf8(EXPECTED_FIRST_STATION_ID.to_string())
    );

    let xml_path = tmp_sink("xml");
    export_dataset_to_xml(&xml_path, &intermediate).unwrap();
    assert!(xml_path.is_file());

    let xml_ds = ingest_xml_from_path(&xml_path, &xml_schema).unwrap();
    assert_eq!(xml_ds.row_count(), EXPECTED_ROW_COUNT);

    let sql_xml_to_parquet = bundle
        .pipeline_transform_sql("pipelines/xml_to_parquet.pipeline.json")
        .unwrap();
    let lake = sql::query(
        &DataFrame::from_dataset(&xml_ds).unwrap(),
        &sql_xml_to_parquet,
    )
    .unwrap()
    .collect()
    .unwrap();
    assert_eq!(lake.schema.fields[0].name, "station_id");

    let parquet_path = tmp_sink("parquet");
    export_dataset_to_parquet(&parquet_path, &lake).unwrap();
    assert!(parquet_path.is_file());

    let back = ingest_parquet_from_path(&parquet_path, &lake_schema).unwrap();
    assert_eq!(back.row_count(), EXPECTED_ROW_COUNT);
    assert_eq!(
        back.rows[0][0],
        Value::Utf8(EXPECTED_FIRST_STATION_ID.to_string())
    );

    let _ = std::fs::remove_file(xml_path);
    let _ = std::fs::remove_file(parquet_path);
}
