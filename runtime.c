#include <stddef.h>
#include <stdint.h>

// =============================================================================
// REQUIRED: Native method implementations
// =============================================================================

// VGA memory uses 2 bytes per cell: character + attribute
void Kernel_writeMemory_Long_Char(int64_t addr, int32_t _byte) {
  *((uint16_t *)addr) = (uint8_t)_byte;
}

// =============================================================================
// REQUIRED: String support
// =============================================================================

// GraalVM generates string constants as internal globals in the LLVM IR.
// These constants are passed to Java code as direct pointers to null-terminated
// C strings. We just need this simple charAt implementation to read them.

uint32_t java_lang_String_charAt_Int_retChar(void *string_obj, int32_t index) {
  if (string_obj == 0 || index < 0) {
    return 0;
  }

  const char *str = (const char *)string_obj;
  return (uint32_t)(unsigned char)str[index];
}

// =============================================================================
// REQUIRED: GraalVM Thread Structure for R15 register
// =============================================================================

// GraalVM LLVM backend generates safepoint check code that reads from the R15
// register. The bootloader must set R15 to point to this structure.
// Without this, the kernel will crash on the first safepoint check.

struct graal_thread_local_struct {
    char pad[16];                   // Padding to offset 16
    int32_t safepoint_counter;      // At offset 16: safepoint check counter
    char pad2[12];                  // Padding to offset 32 (0x20)
    uint64_t tlab_top;              // At offset 32: TLAB allocation limit
    uint64_t tlab_start;            // At offset 40: TLAB current allocation pointer
} __attribute__((aligned(8)));

// Heap buffer for TLAB allocations (if needed in future)
static char heap_buffer[65536] __attribute__((aligned(16)));

// This is the structure that R15 points to
struct graal_thread_local_struct graal_thread_local = {
    .pad = {0},
    .safepoint_counter = 0x7FFFFFFF,  // Max positive value = never trigger safepoints
    .pad2 = {0},
    .tlab_top = (uint64_t)(&heap_buffer[sizeof(heap_buffer)]),
    .tlab_start = (uint64_t)(&heap_buffer[0])
};

// =============================================================================
// REQUIRED: GraalVM Runtime Stubs
// =============================================================================

// Safepoint check - not needed in bare-metal environment
void com_oracle_svm_core_thread_SafepointSlowpath_enterSlowPathSafepointCheck_V() {
  // No-op - safepoints not needed in bare-metal
}

// Helper to convert untracked pointer to tracked object pointer.
// In bare-metal without GC, just return the same pointer.
void *__llvm_load_object_from_untracked_pointer(const char *ptr) {
  return (void *)ptr;
}

// Exception handling personality function - not needed in bare-metal
int com_oracle_svm_core_code_IsolateEnterStub_LLVMExceptionUnwind_personality_YlslbgN6sW6jlo8AQZoycD_Int_Int_IsolateThread_LLVMExceptionUnwind__Unwind_Exception_LLVMExceptionUnwind__Unwind_Context_retInt(
    int a, int b, int64_t c, int64_t d, int64_t e) {
  (void)a; (void)b; (void)c; (void)d; (void)e;
  return 0;
}