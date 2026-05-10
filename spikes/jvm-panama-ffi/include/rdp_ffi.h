#ifndef RDP_FFI_SPIKE_H
#define RDP_FFI_SPIKE_H

/*
 * Spike C ABI for Phase 3 (Project Panama / jextract).
 *
 * Manual header kept in sync with `src/lib.rs`. Optional regen:
 *   cbindgen -c spikes/jvm-panama-ffi/cbindgen.toml -o spikes/jvm-panama-ffi/include/rdp_ffi.h spikes/jvm-panama-ffi
 */
#include <stdint.h>

#define RDP_FFI_OK ((int32_t)0)
#define RDP_FFI_NULL_ARG ((int32_t)-1)

uint32_t rdp_ffi_abi_version(void);

/*
 * Sum `xs[0..len]` into `out` (i64). Returns `RDP_FFI_OK` or `RDP_FFI_NULL_ARG`.
 */
int32_t rdp_ffi_sum_i32(const int32_t *xs, uintptr_t len, int64_t *out);

#endif /* RDP_FFI_SPIKE_H */
