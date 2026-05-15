//! Row-oriented XML interchange (`<rdp_records><record>…</record></rdp_records>`).
//!
//! Field names in XML elements match [`Schema`] field names. Values are encoded as UTF-8 text.

use std::collections::HashMap;
use std::fs::File;
use std::io::{BufReader, Write};
use std::path::Path;

use quick_xml::Writer;
use quick_xml::events::{BytesDecl, BytesEnd, BytesStart, BytesText, Event};

use crate::error::{IngestionError, IngestionResult};
use crate::types::{DataSet, DataType, Schema, Value};

const ROOT: &str = "rdp_records";
const RECORD: &str = "record";

fn xml_err(message: impl Into<String>) -> IngestionError {
    IngestionError::SchemaMismatch {
        message: message.into(),
    }
}

fn value_to_text(v: &Value) -> String {
    match v {
        Value::Null => String::new(),
        Value::Int64(i) => i.to_string(),
        Value::Float64(f) => f.to_string(),
        Value::Bool(b) => b.to_string(),
        Value::Utf8(s) => s.clone(),
    }
}

fn parse_text_to_value(raw: &str, dt: &DataType) -> IngestionResult<Value> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Ok(Value::Null);
    }
    match dt {
        DataType::Int64 => trimmed
            .parse::<i64>()
            .map(Value::Int64)
            .map_err(|_| xml_err(format!("invalid Int64 in XML: {trimmed:?}"))),
        DataType::Float64 => trimmed
            .parse::<f64>()
            .map(Value::Float64)
            .map_err(|_| xml_err(format!("invalid Float64 in XML: {trimmed:?}"))),
        DataType::Bool => match trimmed.to_ascii_lowercase().as_str() {
            "true" | "1" | "yes" => Ok(Value::Bool(true)),
            "false" | "0" | "no" => Ok(Value::Bool(false)),
            _ => Err(xml_err(format!("invalid Bool in XML: {trimmed:?}"))),
        },
        DataType::Utf8 => Ok(Value::Utf8(trimmed.to_string())),
    }
}

fn row_from_field_map(
    schema: &Schema,
    fields: &HashMap<String, String>,
) -> IngestionResult<Vec<Value>> {
    let mut row = Vec::with_capacity(schema.fields.len());
    for field in &schema.fields {
        let raw = fields.get(&field.name).map(String::as_str).unwrap_or("");
        row.push(parse_text_to_value(raw, &field.data_type)?);
    }
    Ok(row)
}

/// Write a [`DataSet`] to a single XML file (see module docs for layout).
pub fn export_dataset_to_xml(path: &Path, ds: &DataSet) -> IngestionResult<()> {
    let mut file = File::create(path)?;
    let mut writer = Writer::new_with_indent(&mut file, b' ', 2);
    writer
        .write_event(Event::Decl(BytesDecl::new("1.0", Some("UTF-8"), None)))
        .map_err(|e| xml_err(format!("write xml decl: {e}")))?;
    writer
        .write_event(Event::Start(BytesStart::new(ROOT)))
        .map_err(|e| xml_err(format!("write xml root: {e}")))?;

    for row in &ds.rows {
        writer
            .write_event(Event::Start(BytesStart::new(RECORD)))
            .map_err(|e| xml_err(format!("write record start: {e}")))?;
        for (field, cell) in ds.schema.fields.iter().zip(row.iter()) {
            writer
                .write_event(Event::Start(BytesStart::new(field.name.as_str())))
                .map_err(|e| xml_err(format!("write field start: {e}")))?;
            let text = value_to_text(cell);
            if !text.is_empty() {
                writer
                    .write_event(Event::Text(BytesText::new(&text)))
                    .map_err(|e| xml_err(format!("write field text: {e}")))?;
            }
            writer
                .write_event(Event::End(BytesEnd::new(field.name.as_str())))
                .map_err(|e| xml_err(format!("write field end: {e}")))?;
        }
        writer
            .write_event(Event::End(BytesEnd::new(RECORD)))
            .map_err(|e| xml_err(format!("write record end: {e}")))?;
    }

    writer
        .write_event(Event::End(BytesEnd::new(ROOT)))
        .map_err(|e| xml_err(format!("write xml root end: {e}")))?;
    writer
        .write_event(Event::Eof)
        .map_err(|e| xml_err(format!("write xml eof: {e}")))?;
    file.flush()?;
    Ok(())
}

/// Ingest row-oriented XML into a [`DataSet`] using the provided [`Schema`].
pub fn ingest_xml_from_path(path: impl AsRef<Path>, schema: &Schema) -> IngestionResult<DataSet> {
    let file = File::open(path.as_ref())?;
    let reader = BufReader::new(file);
    let mut xml = quick_xml::Reader::from_reader(reader);
    xml.config_mut().trim_text(true);

    let mut rows: Vec<Vec<Value>> = Vec::new();
    let mut in_record = false;
    let mut field_values: HashMap<String, String> = HashMap::new();
    let mut active_field: Option<String> = None;
    let mut buf = Vec::new();

    loop {
        buf.clear();
        match xml.read_event_into(&mut buf) {
            Ok(Event::Start(e)) => {
                let name = String::from_utf8_lossy(e.name().as_ref()).into_owned();
                if name == RECORD {
                    in_record = true;
                    field_values.clear();
                } else if in_record && name != ROOT {
                    active_field = Some(name);
                }
            }
            Ok(Event::Text(e)) => {
                if let Some(ref field) = active_field {
                    let text = e
                        .unescape()
                        .map_err(|err| xml_err(format!("xml text: {err}")))?;
                    field_values
                        .entry(field.clone())
                        .and_modify(|v| {
                            v.push(' ');
                            v.push_str(&text);
                        })
                        .or_insert_with(|| text.into_owned());
                }
            }
            Ok(Event::End(e)) => {
                let name = String::from_utf8_lossy(e.name().as_ref()).into_owned();
                if name == RECORD && in_record {
                    rows.push(row_from_field_map(schema, &field_values)?);
                    in_record = false;
                    active_field = None;
                } else if active_field.as_deref() == Some(name.as_str()) {
                    active_field = None;
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => return Err(xml_err(format!("xml parse: {e}"))),
            _ => {}
        }
    }

    Ok(DataSet::new(schema.clone(), rows))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::Field;

    fn sample_schema() -> Schema {
        Schema::new(vec![
            Field::new("stationCode", DataType::Utf8),
            Field::new("lat", DataType::Float64),
            Field::new("label", DataType::Utf8),
        ])
    }

    #[test]
    fn xml_roundtrip_preserves_rows() {
        let schema = sample_schema();
        let ds = DataSet::new(
            schema.clone(),
            vec![vec![
                Value::Utf8("ACW00011604".into()),
                Value::Float64(17.1167),
                Value::Utf8("ST JOHNS".into()),
            ]],
        );
        let dir = std::env::temp_dir().join(format!("rdp_xml_test_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("stations.xml");
        export_dataset_to_xml(&path, &ds).unwrap();
        let back = ingest_xml_from_path(&path, &schema).unwrap();
        assert_eq!(back.row_count(), 1);
        assert_eq!(back.rows[0][0], Value::Utf8("ACW00011604".into()));
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn xml_ingest_reads_committed_fixture_shape() {
        let manifest = Path::new(env!("CARGO_MANIFEST_DIR"));
        let path = manifest.join("tests/fixtures/ghcn/ghcn_stations_sample.json");
        if !path.exists() {
            return;
        }
        let schema = Schema::new(vec![
            Field::new("id", DataType::Utf8),
            Field::new("latitude", DataType::Float64),
            Field::new("longitude", DataType::Float64),
            Field::new("elevation", DataType::Float64),
            Field::new("name", DataType::Utf8),
            Field::new("state", DataType::Utf8),
        ]);
        let json_ds = crate::ingestion::json::ingest_json_from_path(&path, &schema).unwrap();
        let xml_path = manifest.join("tests/fixtures/ghcn/_tmp_roundtrip.xml");
        export_dataset_to_xml(&xml_path, &json_ds).unwrap();
        let xml_schema = Schema::new(vec![
            Field::new("stationCode", DataType::Utf8),
            Field::new("lat", DataType::Float64),
            Field::new("lon", DataType::Float64),
            Field::new("elev_m", DataType::Float64),
            Field::new("label", DataType::Utf8),
            Field::new("region", DataType::Utf8),
        ]);
        let xml_ds = DataSet::new(
            xml_schema.clone(),
            json_ds
                .rows
                .iter()
                .map(|row| {
                    vec![
                        row[0].clone(),
                        row[1].clone(),
                        row[2].clone(),
                        row[3].clone(),
                        row[4].clone(),
                        row[5].clone(),
                    ]
                })
                .collect(),
        );
        export_dataset_to_xml(&xml_path, &xml_ds).unwrap();
        let read = ingest_xml_from_path(&xml_path, &xml_schema).unwrap();
        assert_eq!(read.row_count(), json_ds.row_count());
        std::fs::remove_file(xml_path).ok();
    }
}
