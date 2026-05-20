//! Parity with `docs/java/examples/RDPOnlyETLExample.java` and `student_etl` bundle fixtures.

use std::collections::HashMap;
use std::path::PathBuf;

use rust_data_processing::ingestion::{IngestionOptions, ingest_from_ordered_paths};
use rust_data_processing::pipeline_spec::PipelineBundle;
use rust_data_processing::types::Value;

fn student_bundle() -> PipelineBundle {
    PipelineBundle::from_repo_fixture("student_etl")
}

fn committed_part(name: &str) -> PathBuf {
    student_bundle().root().join("data").join(name)
}

#[test]
fn student_etl_schemas_load() {
    let bundle = student_bundle();
    let student = bundle
        .load_schema("schemas/student_source.schema.json")
        .unwrap();
    assert_eq!(student.fields[0].name, "student_id");
    let lake = bundle
        .load_schema("schemas/lake_grade_stats.schema.json")
        .unwrap();
    assert!(!lake.fields.is_empty());
    let pg = bundle
        .load_schema("schemas/postgres_courses.schema.json")
        .unwrap();
    assert!(!pg.fields.is_empty());
}

#[test]
fn student_etl_s3_paths_sketch_loads() {
    let bundle = student_bundle();
    let text =
        std::fs::read_to_string(bundle.root().join("data/example_s3_json_source_paths.json"))
            .unwrap();
    let paths: Vec<String> = serde_json::from_str(&text).unwrap();
    assert_eq!(paths.len(), 3);
    assert!(paths[0].starts_with("s3://"));
}

#[test]
fn student_etl_legacy_three_paths_pipeline_resolves_committed_data() {
    let bundle = student_bundle();
    let p0 = committed_part("part-00000.json");
    let p1 = committed_part("part-00001.json");
    let p2 = committed_part("part-00002.json");
    let json = bundle
        .resolve_pipeline_json(
            "pipelines/legacy_student_etl_three_paths.pipeline.json",
            &HashMap::from([
                ("PATH_A".into(), p0.to_string_lossy().into_owned()),
                ("PATH_B".into(), p1.to_string_lossy().into_owned()),
                ("PATH_C".into(), p2.to_string_lossy().into_owned()),
            ]),
        )
        .unwrap();
    let v: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(v["json_source_paths"].as_array().unwrap().len(), 3);
    assert!(v["schema_student_json"]["fields"].is_array());
    assert!(v.get("schema_student_json_ref").is_none());
    assert_eq!(v["engine"], "rdp_rust_only_etl");
}

#[test]
fn student_etl_ordered_ingest_two_committed_parts() {
    let bundle = student_bundle();
    let schema = bundle
        .load_schema("schemas/student_source.schema.json")
        .unwrap();
    let p0 = committed_part("part-00000.json");
    let p1 = committed_part("part-00001.json");
    let paths = vec![p0.as_path(), p1.as_path()];
    let opts = IngestionOptions {
        format: Some(rust_data_processing::ingestion::IngestionFormat::Json),
        ..Default::default()
    };
    let (ds, meta) = ingest_from_ordered_paths(&paths, &schema, &opts).unwrap();
    assert_eq!(ds.row_count(), 2);
    assert_eq!(meta.paths.len(), 2);
    assert_eq!(ds.rows[0][0], Value::Int64(1));
    assert_eq!(ds.rows[1][0], Value::Int64(2));
}

#[test]
fn student_etl_ordered_payload_template_resolves_two_paths() {
    let bundle = student_bundle();
    let p0 = committed_part("part-00000.json");
    let p1 = committed_part("part-00001.json");
    let json = bundle
        .resolve_payload_json(
            "payloads/ordered_ingest_dataset_2paths.payload.json",
            &HashMap::from([
                ("PATH_A".into(), p0.to_string_lossy().into_owned()),
                ("PATH_B".into(), p1.to_string_lossy().into_owned()),
            ]),
        )
        .unwrap();
    let v: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(v["paths"].as_array().unwrap().len(), 2);
    assert!(v["schema"]["fields"].is_array());
    assert_eq!(v["response"]["mode"], "dataset");
}
