use rdp_spark_integration_test::rdp_pipeline::{import_csv_spark, verify_spark_sql};
use std::path::PathBuf;

fn csv_path() -> PathBuf {
    let root =
        std::env::var("RDP_INTEGRATION_ROOT").unwrap_or_else(|_| "integration_testing".into());
    let sample = PathBuf::from(&root).join("data/uber_nyc_pickups_sample.csv");
    if sample.is_file() {
        sample
    } else {
        PathBuf::from(&root).join("data/uber_nyc_pickups_apr2014.csv")
    }
}

#[test]
fn spark_import_uber_csv() {
    if std::env::var("RUN_SPARK_INTEGRATION").ok().as_deref() != Some("1") {
        eprintln!("skip spark_import_uber_csv");
        return;
    }
    let csv = csv_path();
    assert!(csv.is_file());
    let max_rows = std::env::var("INTEG_MAX_IMPORT_ROWS")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(500);
    let (ingested, loaded) = import_csv_spark(&csv, max_rows).expect("import");
    assert!(ingested > 0);
    assert_eq!(loaded, ingested);
    verify_spark_sql(ingested).expect("spark SQL verify");
}
