#ifndef RDP_JVM_SYS_H
#define RDP_JVM_SYS_H

#include <stdint.h>

/**
 * JNI/Panama export: Rust ABI revision for JVM loaders.
 * Bump with every breaking change to exported symbols / calling conventions.
 */
uint32_t rdp_ffi_abi_version(void);

#endif /* RDP_JVM_SYS_H */
