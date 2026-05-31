//! Kafka streaming ELT over FFI — Extract / Load / sink (JSON envelopes like ingest_path).

use crate::parity_support::{json_err, json_ok, write_slice, RdpJsonSlice};
use serde::Deserialize;
use std::ffi::CStr;
use std::os::raw::c_char;

unsafe fn cstr_to_str<'a>(ptr: *const c_char, label: &str) -> Result<&'a str, String> {
    if ptr.is_null() {
        return Err(format!("{label}: null pointer"));
    }
    unsafe { CStr::from_ptr(ptr) }
        .to_str()
        .map_err(|e| format!("{label}: invalid UTF-8: {e}"))
}

#[cfg(all(feature = "link-main", feature = "kafka"))]
fn parse_schema_json(json: &str) -> Result<rust_data_processing::types::Schema, String> {
    serde_json::from_str(json).map_err(|e| format!("schema JSON: {e}"))
}

#[cfg(all(feature = "link-main", feature = "kafka"))]
fn parse_dataset_json(json: &str) -> Result<rust_data_processing::types::DataSet, String> {
    serde_json::from_str(json).map_err(|e| format!("dataset JSON: {e}"))
}

#[cfg(all(feature = "link-main", feature = "kafka"))]
fn dataset_envelope(kind: &str, ds: &rust_data_processing::types::DataSet) -> RdpJsonSlice {
    match serde_json::to_value(ds) {
        Ok(dataset) => json_ok(serde_json::json!({
            "kind": kind,
            "dataset": dataset,
        })),
        Err(e) => json_err(format!("serialize DataSet: {e}")),
    }
}

#[cfg(all(feature = "link-main", feature = "kafka"))]
#[derive(Deserialize)]
struct KafkaConsumerConfig {
    brokers: String,
    group_id: String,
    topic: String,
    #[serde(default = "default_max_records")]
    max_records: usize,
    #[serde(default = "default_auto_offset_reset")]
    auto_offset_reset: String,
    session_timeout_ms: Option<u32>,
}

#[cfg(all(feature = "link-main", feature = "kafka"))]
fn default_max_records() -> usize {
    500
}

#[cfg(all(feature = "link-main", feature = "kafka"))]
fn default_auto_offset_reset() -> String {
    "earliest".to_string()
}

#[cfg(all(feature = "link-main", feature = "kafka"))]
fn consumer_from_config(cfg: &KafkaConsumerConfig) -> rust_data_processing::kafka::KafkaConsumerBuilder {
    use rust_data_processing::kafka::KafkaConsumerBuilder;
    let mut b = KafkaConsumerBuilder::new(&cfg.brokers, &cfg.group_id, &cfg.topic)
        .auto_offset_reset(&cfg.auto_offset_reset);
    if let Some(ms) = cfg.session_timeout_ms {
        b = b.session_timeout_ms(ms);
    }
    b
}

#[cfg(all(feature = "link-main", feature = "kafka"))]
#[derive(Deserialize)]
struct KafkaProducerConfig {
    brokers: String,
    topic: String,
    #[serde(default = "default_message_timeout_ms")]
    message_timeout_ms: u64,
    key_column: Option<String>,
    value_column: Option<String>,
    #[serde(default)]
    headers: Vec<(String, String)>,
}

#[cfg(all(feature = "link-main", feature = "kafka"))]
fn default_message_timeout_ms() -> u64 {
    5_000
}

#[cfg(all(feature = "link-main", feature = "kafka"))]
fn producer_from_config(cfg: &KafkaProducerConfig) -> rust_data_processing::kafka::KafkaProducerBuilder {
    use rust_data_processing::kafka::KafkaProducerBuilder;
    let mut b = KafkaProducerBuilder::new(&cfg.brokers, &cfg.topic)
        .message_timeout_ms(cfg.message_timeout_ms);
    if let Some(col) = &cfg.key_column {
        b = b.key_column(col.clone());
    }
    if let Some(col) = &cfg.value_column {
        b = b.value_column(col.clone());
    }
    for (name, val) in &cfg.headers {
        b = b.header(name.clone(), val.clone());
    }
    b
}

/// **Load:** map a JSON poll window into a landing `DataSet`.
///
/// C strings: `records_json` (`{ "records": [ … ] }` or bare array), `schema_json` (landing schema).
#[no_mangle]
pub unsafe extern "C" fn rdp_kafka_elt_load_records_json(
    out: *mut RdpJsonSlice,
    records_json_ptr: *const c_char,
    schema_json_ptr: *const c_char,
) {
    write_slice(out, kafka_elt_load_records_json_impl(records_json_ptr, schema_json_ptr));
}

#[cfg(all(feature = "link-main", feature = "kafka"))]
fn kafka_elt_load_records_json_impl(
    records_json_ptr: *const c_char,
    schema_json_ptr: *const c_char,
) -> RdpJsonSlice {
    use rust_data_processing::kafka::elt_load_kafka_records_json;

    let records_json = match unsafe { cstr_to_str(records_json_ptr, "records_json") } {
        Ok(s) => s,
        Err(e) => return json_err(e),
    };
    let schema_json = match unsafe { cstr_to_str(schema_json_ptr, "schema_json") } {
        Ok(s) => s,
        Err(e) => return json_err(e),
    };
    let schema = match parse_schema_json(schema_json) {
        Ok(s) => s,
        Err(e) => return json_err(e),
    };
    match elt_load_kafka_records_json(records_json, &schema) {
        Ok(ds) => dataset_envelope("kafka_elt_load", &ds),
        Err(e) => json_err(e.to_string()),
    }
}

#[cfg(all(feature = "link-main", not(feature = "kafka")))]
fn kafka_elt_load_records_json_impl(
    _records_json_ptr: *const c_char,
    _schema_json_ptr: *const c_char,
) -> RdpJsonSlice {
    json_err("kafka support disabled; rebuild rdp_jvm_sys with --features kafka")
}

/// **Extract:** poll up to `max_records` from a topic (config JSON).
///
/// Returns `{ "records": [ …KafkaStreamRecord… ] }` under `interchange`.
#[no_mangle]
pub unsafe extern "C" fn rdp_kafka_poll_window_json(
    out: *mut RdpJsonSlice,
    config_json_ptr: *const c_char,
) {
    write_slice(out, kafka_poll_window_json_impl(config_json_ptr));
}

#[cfg(all(feature = "link-main", feature = "kafka"))]
fn kafka_poll_window_json_impl(config_json_ptr: *const c_char) -> RdpJsonSlice {
    use rust_data_processing::kafka::poll_kafka_window;

    let config_json = match unsafe { cstr_to_str(config_json_ptr, "config_json") } {
        Ok(s) => s,
        Err(e) => return json_err(e),
    };
    let cfg: KafkaConsumerConfig = match serde_json::from_str(config_json) {
        Ok(c) => c,
        Err(e) => return json_err(format!("config JSON: {e}")),
    };
    let builder = consumer_from_config(&cfg);
    match poll_kafka_window(&builder, cfg.max_records) {
        Ok(records) => match serde_json::to_value(&records) {
            Ok(records_json) => json_ok(serde_json::json!({
                "kind": "kafka_poll_window",
                "records": records_json,
            })),
            Err(e) => json_err(format!("serialize records: {e}")),
        },
        Err(e) => json_err(e.to_string()),
    }
}

#[cfg(all(feature = "link-main", not(feature = "kafka")))]
fn kafka_poll_window_json_impl(_config_json_ptr: *const c_char) -> RdpJsonSlice {
    json_err("kafka support disabled; rebuild rdp_jvm_sys with --features kafka")
}

/// **Extract + Load:** poll a window and map to a landing `DataSet`.
#[no_mangle]
pub unsafe extern "C" fn rdp_kafka_poll_window_loaded_json(
    out: *mut RdpJsonSlice,
    config_json_ptr: *const c_char,
    schema_json_ptr: *const c_char,
) {
    write_slice(
        out,
        kafka_poll_window_loaded_json_impl(config_json_ptr, schema_json_ptr),
    );
}

#[cfg(all(feature = "link-main", feature = "kafka"))]
fn kafka_poll_window_loaded_json_impl(
    config_json_ptr: *const c_char,
    schema_json_ptr: *const c_char,
) -> RdpJsonSlice {
    use rust_data_processing::kafka::poll_kafka_window_loaded;

    let config_json = match unsafe { cstr_to_str(config_json_ptr, "config_json") } {
        Ok(s) => s,
        Err(e) => return json_err(e),
    };
    let schema_json = match unsafe { cstr_to_str(schema_json_ptr, "schema_json") } {
        Ok(s) => s,
        Err(e) => return json_err(e),
    };
    let cfg: KafkaConsumerConfig = match serde_json::from_str(config_json) {
        Ok(c) => c,
        Err(e) => return json_err(format!("config JSON: {e}")),
    };
    let schema = match parse_schema_json(schema_json) {
        Ok(s) => s,
        Err(e) => return json_err(e),
    };
    let builder = consumer_from_config(&cfg);
    match poll_kafka_window_loaded(&builder, &schema, cfg.max_records) {
        Ok(ds) => dataset_envelope("kafka_poll_window_loaded", &ds),
        Err(e) => json_err(e.to_string()),
    }
}

#[cfg(all(feature = "link-main", not(feature = "kafka")))]
fn kafka_poll_window_loaded_json_impl(
    _config_json_ptr: *const c_char,
    _schema_json_ptr: *const c_char,
) -> RdpJsonSlice {
    json_err("kafka support disabled; rebuild rdp_jvm_sys with --features kafka")
}

/// **Sink:** publish a `DataSet` to Kafka (config + dataset JSON).
#[no_mangle]
pub unsafe extern "C" fn rdp_kafka_export_dataset_json(
    out: *mut RdpJsonSlice,
    config_json_ptr: *const c_char,
    dataset_json_ptr: *const c_char,
) {
    write_slice(
        out,
        kafka_export_dataset_json_impl(config_json_ptr, dataset_json_ptr),
    );
}

#[cfg(all(feature = "link-main", feature = "kafka"))]
fn kafka_export_dataset_json_impl(
    config_json_ptr: *const c_char,
    dataset_json_ptr: *const c_char,
) -> RdpJsonSlice {
    use rust_data_processing::kafka::export_dataset_to_kafka;

    let config_json = match unsafe { cstr_to_str(config_json_ptr, "config_json") } {
        Ok(s) => s,
        Err(e) => return json_err(e),
    };
    let dataset_json = match unsafe { cstr_to_str(dataset_json_ptr, "dataset_json") } {
        Ok(s) => s,
        Err(e) => return json_err(e),
    };
    let cfg: KafkaProducerConfig = match serde_json::from_str(config_json) {
        Ok(c) => c,
        Err(e) => return json_err(format!("config JSON: {e}")),
    };
    let dataset = match parse_dataset_json(dataset_json) {
        Ok(d) => d,
        Err(e) => return json_err(e),
    };
    let builder = producer_from_config(&cfg);
    match export_dataset_to_kafka(&builder, &dataset) {
        Ok(sent) => json_ok(serde_json::json!({
            "kind": "kafka_export",
            "topic": cfg.topic,
            "row_count": sent,
        })),
        Err(e) => json_err(e.to_string()),
    }
}

#[cfg(all(feature = "link-main", not(feature = "kafka")))]
fn kafka_export_dataset_json_impl(
    _config_json_ptr: *const c_char,
    _dataset_json_ptr: *const c_char,
) -> RdpJsonSlice {
    json_err("kafka support disabled; rebuild rdp_jvm_sys with --features kafka")
}
