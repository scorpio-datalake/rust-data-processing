use rdp_kafka_integration_test::rdp_kafka::verify_uber_kafka_stream;
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
fn kafka_stream_uber_csv() {
    if std::env::var("RUN_KAFKA_INTEGRATION").ok().as_deref() != Some("1") {
        eprintln!("skip kafka_stream_uber_csv");
        return;
    }
    let csv = csv_path();
    assert!(csv.is_file(), "missing Uber CSV at {}", csv.display());
    let max_rows = std::env::var("INTEG_MAX_IMPORT_ROWS")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(500);
    verify_uber_kafka_stream(&csv, max_rows).expect("kafka stream verify");
}
