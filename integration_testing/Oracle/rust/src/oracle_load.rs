//! Oracle integration helpers (Oracle folder only — not part of the main crate).

use oracle::Connection;
use rust_data_processing::error::{IngestionError, IngestionResult};
use rust_data_processing::ingestion::{ingest_from_db, IngestionOptions};
use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};

pub const TABLE_NAME: &str = "UBER_PICKUPS";

pub fn oracle_column(field: &str) -> Option<&'static str> {
    match field {
        "Date/Time" => Some("pickup_time"),
        "Lat" => Some("lat"),
        "Lon" => Some("lon"),
        "Base" => Some("base_code"),
        _ => None,
    }
}

pub fn connectorx_url_to_oracle_connect(url: &str) -> Result<(String, String, String), String> {
    let rest = url
        .strip_prefix("oracle://")
        .ok_or_else(|| format!("expected oracle:// URL, got {url}"))?;
    let (auth, hostpart) = rest
        .split_once('@')
        .ok_or_else(|| format!("invalid oracle URL (missing @): {url}"))?;
    let (user, pass) = auth
        .split_once(':')
        .ok_or_else(|| format!("invalid oracle URL (missing password): {url}"))?;
    Ok((user.to_string(), pass.to_string(), hostpart.to_string()))
}

pub fn reset_table(url: &str) -> Result<(), String> {
    let (user, pass, hostpart) = connectorx_url_to_oracle_connect(url)?;
    let conn = Connection::connect(&user, &pass, &hostpart).map_err(|e| e.to_string())?;
    let _ = conn.execute(&format!("DROP TABLE {TABLE_NAME} PURGE"), &[]);
    conn.execute(
        &format!(
            "CREATE TABLE {TABLE_NAME} (
                pickup_time VARCHAR2(64),
                lat NUMBER,
                lon NUMBER,
                base_code VARCHAR2(32)
            )"
        ),
        &[],
    )
    .map_err(|e| format!("create table: {e}"))?;
    conn.commit().map_err(|e| e.to_string())?;
    Ok(())
}

pub fn load_dataset(url: &str, ds: &DataSet) -> Result<usize, String> {
    let (user, pass, hostpart) = connectorx_url_to_oracle_connect(url)?;
    let conn = Connection::connect(&user, &pass, &hostpart).map_err(|e| e.to_string())?;
    let sql = format!(
        "INSERT INTO {TABLE_NAME} (pickup_time, lat, lon, base_code) VALUES (:1, :2, :3, :4)"
    );

    let mut inserted = 0usize;
    for row in &ds.rows {
        let mut vals: Vec<Option<String>> = vec![None, None, None, None];
        for (field, value) in ds.schema.fields.iter().zip(row.iter()) {
            let Some(col_idx) = oracle_column(&field.name).and_then(|c| match c {
                "pickup_time" => Some(0),
                "lat" => Some(1),
                "lon" => Some(2),
                "base_code" => Some(3),
                _ => None,
            }) else {
                continue;
            };
            vals[col_idx] = Some(value_to_oracle_string(value, &field.data_type)?);
        }
        conn.execute(
            &sql,
            &[&vals[0], &vals[1], &vals[2], &vals[3]],
        )
        .map_err(|e| format!("insert row {inserted}: {e}"))?;
        inserted += 1;
    }
    conn.commit().map_err(|e| e.to_string())?;
    Ok(inserted)
}

fn value_to_oracle_string(v: &Value, dt: &DataType) -> Result<String, String> {
    match (v, dt) {
        (Value::Null, _) => Ok(String::new()),
        (Value::Utf8(s), _) => Ok(s.clone()),
        (Value::Int64(i), DataType::Int64) => Ok(i.to_string()),
        (Value::Float64(f), DataType::Float64) => Ok(f.to_string()),
        (Value::Bool(b), DataType::Bool) => Ok(if *b { "1" } else { "0" }.to_string()),
        other => Err(format!("unsupported value for Oracle load: {other:?}")),
    }
}

pub fn verify_row_count(url: &str, expected: usize) -> IngestionResult<usize> {
    let schema = Schema::new(vec![Field::new("cnt", DataType::Int64)]);
    let ds = ingest_from_db(
        url,
        &format!("SELECT COUNT(*) AS cnt FROM {TABLE_NAME}"),
        &schema,
        &IngestionOptions::default(),
    )?;
    let count = match ds.rows.first().and_then(|r| r.first()) {
        Some(Value::Int64(n)) if *n >= 0 => *n as usize,
        _ => {
            return Err(IngestionError::SchemaMismatch {
                message: "COUNT(*) did not return Int64".into(),
            });
        }
    };
    if count != expected {
        return Err(IngestionError::SchemaMismatch {
            message: format!("expected {expected} rows in {TABLE_NAME}, got {count}"),
        });
    }
    Ok(count)
}

pub fn uber_csv_schema() -> Schema {
    Schema::new(vec![
        Field::new("Date/Time", DataType::Utf8),
        Field::new("Lat", DataType::Float64),
        Field::new("Lon", DataType::Float64),
        Field::new("Base", DataType::Utf8),
    ])
}

pub fn ingest_uber_csv(path: &std::path::Path) -> IngestionResult<DataSet> {
    use rust_data_processing::ingestion::{ingest_from_path, IngestionFormat, IngestionOptions};
    let schema = uber_csv_schema();
    let mut opts = IngestionOptions::default();
    opts.format = Some(IngestionFormat::Csv);
    ingest_from_path(path, &schema, &opts)
}
