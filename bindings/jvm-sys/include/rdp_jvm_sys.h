#ifndef RDP_JVM_SYS_H
#define RDP_JVM_SYS_H

#include <stddef.h>
#include <stdint.h>

/**
 * Tabular / diagnostic payloads cross FFI as UTF-8 JSON. Java maps them with {@code org.json}
 * or row {@code List<Map<String,Object>>} after parsing the envelope field {@code interchange}.
 * Arrow IPC remains a future interchange (see Rust JSON envelope {@code notes.arrow_ipc}).
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
