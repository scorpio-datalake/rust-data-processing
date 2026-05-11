#ifndef RDP_JVM_SYS_H
#define RDP_JVM_SYS_H

#include <stddef.h>
#include <stdint.h>

/**
 * Tabular / diagnostic payloads cross FFI as UTF-8 JSON. Java maps them with {@code org.json}
 * or row {@code List<Map<String,Object>>} after parsing the envelope field {@code interchange}.
 * Arrow IPC temp files: {@code rdp_export_arrow_ipc_temp} (see JSON envelope {@code notes.arrow_ipc}).
 */
typedef struct RdpJsonSlice {
    uint8_t *ptr;
    size_t len;
    size_t cap;
} RdpJsonSlice;

/**
 * JNI/Panama export: Rust ABI revision for JVM loaders.
 * Bump with every breaking change to exported symbols / calling conventions.
 */
uint32_t rdp_ffi_abi_version(void);

/** Release buffer allocated by a parity export (matches {@code Vec::from_raw_parts}). */
void rdp_json_slice_free(RdpJsonSlice s);

/** Sample DataSet → temp Parquet; JSON envelope includes filesystem path (see docs/java). */
void rdp_export_parquet_temp(RdpJsonSlice *out);

/** Sample DataSet → temp Arrow IPC file; JSON {@code interchange.kind} = {@code arrow_ipc_export_temp}. */
void rdp_export_arrow_ipc_temp(RdpJsonSlice *out);

/** Polars SQL sample → temp Parquet (no embedded JSON rows); {@code polars_parquet_export_temp}. */
void rdp_export_polars_parquet_temp(RdpJsonSlice *out);

/** Excel workbook path + UTF-8 sheet name → JSON {@code interchange.dataset}. */
void rdp_excel_ingest_path_sheet(RdpJsonSlice *out, const char *path, const char *sheet_name);

/** Single-file ingest: path + {@code Schema} JSON + {@code IngestionOptions} JSON → {@code interchange.dataset}. */
void rdp_ingest_csv_path(RdpJsonSlice *out, const char *path, const char *schema_json, const char *options_json);
void rdp_ingest_json_path(RdpJsonSlice *out, const char *path, const char *schema_json, const char *options_json);
void rdp_ingest_parquet_path(RdpJsonSlice *out, const char *path, const char *schema_json, const char *options_json);

/** Multi-file ingest: NUL-terminated UTF-8 JSON payload (paths, schema, options, response). */
void rdp_ingest_ordered_paths_json(RdpJsonSlice *out, const char *payload_json);

/** Ordered ingest + Polars SQL + sinks from one UTF-8 JSON document (see docs/java examples). */
void rdp_run_pipeline_json(RdpJsonSlice *out, const char *payload_json);

void rdp_parity_benchmark_smoke_mirror(RdpJsonSlice *out);
void rdp_parity_bindings_mirror(RdpJsonSlice *out);
void rdp_parity_cdc(RdpJsonSlice *out);
void rdp_parity_deep_seattle_mirror(RdpJsonSlice *out);
void rdp_parity_export_privacy_reports(RdpJsonSlice *out);
void rdp_parity_ingestion(RdpJsonSlice *out);
void rdp_parity_kafka(RdpJsonSlice *out);
void rdp_parity_mapping_spec_mirror(RdpJsonSlice *out);
void rdp_parity_observability_mirror(RdpJsonSlice *out);
void rdp_parity_outliers(RdpJsonSlice *out);
void rdp_parity_partition_discovery_mirror(RdpJsonSlice *out);
void rdp_parity_pipeline_sql(RdpJsonSlice *out);
void rdp_parity_processing(RdpJsonSlice *out);
void rdp_parity_profiling(RdpJsonSlice *out);
void rdp_parity_sft_sample_mirror(RdpJsonSlice *out);
void rdp_parity_sql_suite_mirror(RdpJsonSlice *out);
void rdp_parity_transform(RdpJsonSlice *out);
void rdp_parity_types_dataset(RdpJsonSlice *out);
void rdp_parity_validation(RdpJsonSlice *out);
void rdp_parity_watermark_mirror(RdpJsonSlice *out);

#endif /* RDP_JVM_SYS_H */
