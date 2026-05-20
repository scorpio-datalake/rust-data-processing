//! Parity with `docs/java/examples/OrderedPaths.java` and `tests/fixtures/watermark/`.

use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;

use rust_data_processing::ingestion::{
    IngestionFormat, IngestionOptions, ingest_from_ordered_paths, paths_from_directory_scan,
};
use rust_data_processing::pipeline_spec::PipelineBundle;
use rust_data_processing::types::Value;

fn watermark_bundle() -> PipelineBundle {
    PipelineBundle::from_repo_fixture("watermark")
}

fn events_schema() -> rust_data_processing::types::Schema {
    watermark_bundle().expect_schema("schemas/events.schema.json")
}

fn watermark_opts_exclusive_above_100() -> IngestionOptions {
    IngestionOptions {
        format: Some(IngestionFormat::Csv),
        watermark_column: Some("ts".to_string()),
        watermark_exclusive_above: Some(Value::Int64(100)),
        ..Default::default()
    }
}

/// Same temp layout as `PathFromDirectoryScan.demonstrate`: `a.csv` (ts ≤ 100) + `nested/b.csv` (ts > 100).
fn java_demo_incoming_dir() -> PathBuf {
    let dir = std::env::temp_dir().join(format!(
        "rdp_path_scan_demo_{}_{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let nested = dir.join("nested");
    fs::create_dir_all(&nested).unwrap();
    fs::write(dir.join("a.csv"), "id,ts\n1,50\n2,99\n").unwrap();
    fs::write(nested.join("b.csv"), "id,ts\n3,150\n4,200\n").unwrap();
    dir
}

#[test]
fn watermark_csv_watermark_ingest_body_payload_resolves() {
    let bundle = watermark_bundle();
    let json = bundle
        .resolve_payload_json("payloads/csv_watermark_ingest.body.json", &HashMap::new())
        .unwrap();
    let v: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert!(v["schema"]["fields"].is_array());
    assert_eq!(v["options"]["watermark_column"], "ts");
    assert_eq!(v["options"]["watermark_exclusive_above"], 100);
    assert_eq!(v["response"]["mode"], "dataset");
}

#[test]
fn directory_scan_then_batch_watermark_matches_path_from_directory_scan_java() {
    let dir = java_demo_incoming_dir();
    let paths = paths_from_directory_scan(&dir, Some("**/*.csv")).unwrap();
    assert_eq!(paths.len(), 2);
    assert_eq!(paths[0].file_name().unwrap().to_string_lossy(), "a.csv");
    assert!(
        paths[1].to_string_lossy().contains("nested") && paths[1].file_name().unwrap() == "b.csv"
    );

    let schema = events_schema();
    let opts = watermark_opts_exclusive_above_100();
    let (ds, meta) = ingest_from_ordered_paths(&paths, &schema, &opts).unwrap();

    assert_eq!(ds.row_count(), 2);
    assert_eq!(
        ds.rows
            .iter()
            .map(|r| match r[0] {
                Value::Int64(i) => i,
                _ => panic!("id"),
            })
            .collect::<Vec<_>>(),
        vec![3, 4]
    );
    assert_eq!(meta.max_watermark_value, Some(Value::Int64(200)));
    assert_eq!(meta.paths.len(), 2);
    assert_eq!(
        meta.last_path.as_ref().unwrap().file_name().unwrap(),
        "b.csv"
    );

    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn directory_scan_two_csv_payload_matches_fixed_paths() {
    let bundle = watermark_bundle();
    let dir = java_demo_incoming_dir();
    let path_a = dir.join("a.csv");
    let path_b = dir.join("nested").join("b.csv");
    let json = bundle
        .resolve_payload_json(
            "payloads/directory_scan_two_csv.payload.json",
            &HashMap::from([
                ("PATH_A".into(), path_a.to_string_lossy().into_owned()),
                ("PATH_B".into(), path_b.to_string_lossy().into_owned()),
            ]),
        )
        .unwrap();
    let v: serde_json::Value = serde_json::from_str(&json).unwrap();
    assert_eq!(v["paths"].as_array().unwrap().len(), 2);
    let schema = events_schema();
    let opts = watermark_opts_exclusive_above_100();
    let paths: Vec<PathBuf> = v["paths"]
        .as_array()
        .unwrap()
        .iter()
        .map(|p| PathBuf::from(p.as_str().unwrap()))
        .collect();
    let (ds, _) = ingest_from_ordered_paths(&paths, &schema, &opts).unwrap();
    assert_eq!(ds.row_count(), 2);
    let _ = fs::remove_dir_all(&dir);
}
