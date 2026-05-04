//! Ordered multi-file ingest + batch watermark semantics.

use std::fs;
use std::path::PathBuf;

use rust_data_processing::ingestion::{
    IngestionOptions, ingest_from_ordered_paths, paths_from_directory_scan,
};
use rust_data_processing::types::{DataType, Field, Schema, Value};

fn schema_ts() -> Schema {
    Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("ts", DataType::Int64),
    ])
}

#[test]
fn ordered_paths_concat_then_watermark_not_per_file() {
    let dir = std::env::temp_dir().join(format!(
        "rdp_ordered_{}_{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&dir).unwrap();
    let p1 = dir.join("first.csv");
    let p2 = dir.join("second.csv");
    // File 1 is entirely below the floor; file 2 supplies rows above 100. Batch watermark runs after concat.
    fs::write(&p1, "id,ts\n1,10\n2,20\n").unwrap();
    fs::write(&p2, "id,ts\n3,150\n4,160\n").unwrap();

    let schema = schema_ts();
    let opts = IngestionOptions {
        watermark_column: Some("ts".to_string()),
        watermark_exclusive_above: Some(Value::Int64(100)),
        ..Default::default()
    };

    let paths = vec![p1.clone(), p2.clone()];
    let (ds, meta) = ingest_from_ordered_paths(&paths, &schema, &opts).unwrap();

    assert_eq!(ds.row_count(), 2);
    assert_eq!(meta.paths, paths);
    assert_eq!(meta.last_path, Some(p2));
    assert_eq!(meta.max_watermark_value, Some(Value::Int64(160)));

    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn directory_scan_matches_sorted_glob() {
    let dir = std::env::temp_dir().join(format!(
        "rdp_dscan_{}_{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    // Create nested files so lexical order differs from creation order.
    fs::create_dir_all(dir.join("z")).unwrap();
    fs::write(dir.join("z").join("late.csv"), "").unwrap();
    fs::create_dir_all(dir.join("a")).unwrap();
    fs::write(dir.join("a").join("early.csv"), "").unwrap();

    let paths = paths_from_directory_scan(&dir, Some("**/*.csv")).unwrap();
    assert_eq!(paths.len(), 2);
    let a: PathBuf = dir.join("a").join("early.csv");
    let z: PathBuf = dir.join("z").join("late.csv");
    assert_eq!(paths[0], a);
    assert_eq!(paths[1], z);

    fs::remove_dir_all(&dir).unwrap();
}
