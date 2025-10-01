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

// GraalVM String object layout (simplified for bare-metal):
// Offset 0-7:   Object header (ignored in bare-metal)
// Offset 8:     Pointer to value (char array object)
// Offset 16-19: hash field
// Offset 20:    coder field (0 = Latin1, 1 = UTF16)
//
// Char array object layout:
// Offset 0-11:  Object header
// Offset 12:    Length field
// Offset 16+:   Actual character data

struct string_value_object {
    char header[12];        // Offsets 0-11: object header
    int32_t length;         // Offset 12: array length
    char pad[4];            // Padding to offset 16
    char data[];            // Offset 16+: actual string data
} __attribute__((packed));

struct string_object {
    char header[8];                         // Offset 0-7: object header
    struct string_value_object *value;      // Offset 8: pointer to value
    char pad[4];                            // Offset 16-19: hash
    int8_t coder;                           // Offset 20: coder field
    char pad2[3];                           // Padding
} __attribute__((packed));

// charAt implementation: reads from GraalVM String object
uint32_t java_lang_String_charAt_Int_retChar(void *string_obj, int32_t index) {
  if (string_obj == 0 || index < 0) {
    return 0;
  }

  struct string_object *str = (struct string_object *)string_obj;
  if (str->value == 0) {
    return 0;
  }

  if (index >= str->value->length) {
    return 0;
  }

  // Data starts at offset 16 in the value object
  return (uint32_t)(unsigned char)str->value->data[index];
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

// Helper to convert untracked pointer (C string) to tracked String object pointer.
// We need to wrap C strings in GraalVM String object structures.
void *__llvm_load_object_from_untracked_pointer(const char *c_str) {
  if (c_str == 0) {
    return 0;
  }

  // Calculate string length
  int len = 0;
  while (c_str[len] != '\0') {
    len++;
  }

  // Allocate space for value object + string data
  // We'll use a simple static buffer approach for now
  static char buffer[4096];
  static int buffer_offset = 0;

  // Allocate value object
  struct string_value_object *value = (struct string_value_object *)(buffer + buffer_offset);
  buffer_offset += sizeof(struct string_value_object) + len;

  // Initialize value object
  for (int i = 0; i < 12; i++) value->header[i] = 0;
  value->length = len;
  for (int i = 0; i < 4; i++) value->pad[i] = 0;

  // Copy string data
  for (int i = 0; i < len; i++) {
    value->data[i] = c_str[i];
  }

  // Allocate String object
  struct string_object *str = (struct string_object *)(buffer + buffer_offset);
  buffer_offset += sizeof(struct string_object);

  // Initialize String object
  for (int i = 0; i < 8; i++) str->header[i] = 0;
  str->value = value;
  for (int i = 0; i < 4; i++) str->pad[i] = 0;
  str->coder = 0;  // Latin1
  for (int i = 0; i < 3; i++) str->pad2[i] = 0;

  return (void *)str;
}

// Exception handling personality function - not needed in bare-metal
int com_oracle_svm_core_code_IsolateEnterStub_LLVMExceptionUnwind_personality_YlslbgN6sW6jlo8AQZoycD_Int_Int_IsolateThread_LLVMExceptionUnwind__Unwind_Exception_LLVMExceptionUnwind__Unwind_Context_retInt(
    int a, int b, int64_t c, int64_t d, int64_t e) {
  (void)a; (void)b; (void)c; (void)d; (void)e;
  return 0;
}

// Null pointer exception - called by writeString when checking null
void com_oracle_svm_core_snippets_ImplicitExceptions_throwNewNullPointerException_V() {
  // In bare-metal, we can't throw exceptions. Just halt.
  while (1) {
    // Infinite loop on null pointer
  }
}