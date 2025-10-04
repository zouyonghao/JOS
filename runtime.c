#include <stddef.h>
#include <stdint.h>

// =============================================================================
// REQUIRED: Native method implementations
// =============================================================================

// Serial port debugging output
#define SERIAL_PORT 0x3F8

static inline unsigned char __inb(unsigned short port) {
  unsigned char value;
  __asm__ volatile("inb %1, %0" : "=a"(value) : "Nd"(port));
  return value;
}

static void serial_write(char c) {
  // Direct write without waiting
  __asm__ volatile("outb %0, %1" : : "a"(c), "Nd"((uint16_t)SERIAL_PORT));
}

// VGA memory uses 2 bytes per cell: character + attribute
void Kernel_writeMemory_Long_Char(int64_t addr, int32_t _byte) {
  // Check bounds carefully before writing to avoid memory corruption
  // VGA text buffer: 0xB8000 to 0xB8F9F (inclusive) = 0xFA0 = 4000 bytes total
  // Each character cell uses 2 consecutive bytes: char at even addr, attr at
  // odd addr
  if (addr >= 0xB8000 &&
      addr <= 0xB8F9F) { // Use <= to allow up to the last valid address
    // Only write characters (even addresses) to serial for debugging
    if ((addr - 0xB8000) % 2 == 0) {
      char c = (char)_byte;
      serial_write(c);
    }
    // Write to VGA memory within safe bounds
    *((uint8_t *)addr) = (uint8_t)_byte;
  }
  // If address is out of VGA range, skip the write to prevent memory corruption
}

// String length field access - needed for str.length() to work properly in
// GraalVM This handles direct field access to the length property of String
// objects Note: GraalVM applies a shift based on the coder field (length >>
// coder)
int32_t java_lang_String_length_Int(const void *java_string_obj) {
  if (java_string_obj == 0)
    return 0;

  // The pointer we receive is to object data (self-pointer already skipped)
  // Read the byte array offset from the start
  const uint64_t *offset_ptr = (const uint64_t *)java_string_obj;
  uint64_t byte_array_offset = *offset_ptr;

  // Calculate pointer to byte array (add offset to object data start)
  const uint8_t *byte_array =
      (const uint8_t *)java_string_obj + byte_array_offset;

  // Length is at offset 12 within the byte array
  const uint32_t *length_ptr = (const uint32_t *)(byte_array + 12);
  uint32_t raw_length = *length_ptr;

  // Get the coder byte from offset 20 in the main object (not the byte array)
  uint8_t coder = *((const uint8_t *)java_string_obj + 20);

  // Apply the shift as GraalVM does: raw_length >> coder
  // For LATIN1 (coder=0): no shift needed (right shift by 0) - returns
  // raw_length For UTF16 (coder=1): shift right by 1 (divide by 2)
  return (int32_t)(raw_length >> coder);
}

// =============================================================================
// REQUIRED: String support via function calls
// =============================================================================

// For kernel builds, we treat Java Strings as plain C strings (i8* pointers).
// GraalVM automatically generates str.charAt() as a function call to
// java_lang_String_charAt_Int_retChar. But str.length() still generates field
// access, so we need a wrapper.

// Helper to extract length from flat Java String constant
static int32_t get_string_length(const void *java_string_obj) {
  if (java_string_obj == 0)
    return 0;

  // The pointer is to object data (self-pointer already skipped)
  // Read the byte array offset from the start
  const uint64_t *offset_ptr = (const uint64_t *)java_string_obj;
  uint64_t byte_array_offset = *offset_ptr;

  // Calculate pointer to byte array (add offset to object data start)
  const uint8_t *byte_array =
      (const uint8_t *)java_string_obj + byte_array_offset;

  // Length is at offset 12 within the byte array
  const uint32_t *length_ptr = (const uint32_t *)(byte_array + 12);
  uint32_t raw_length = *length_ptr;

  // Get the coder byte from offset 20 in the main object (not the byte array)
  uint8_t coder = *((const uint8_t *)java_string_obj + 20);

  // Apply the shift as GraalVM does: raw_length >> coder
  // For LATIN1 (coder=0): no shift needed (right shift by 0) - returns
  // raw_length For UTF16 (coder=1): shift right by 1 (divide by 2)
  return (int32_t)(raw_length >> coder);
}

// Helper to get byte from flat Java String constant
static uint8_t get_string_byte(const void *java_string_obj, int32_t index) {
  if (java_string_obj == 0 || index < 0)
    return 0;

  // The pointer is to object data (self-pointer already skipped)
  // Read the byte array offset from the start
  const uint64_t *offset_ptr = (const uint64_t *)java_string_obj;
  uint64_t byte_array_offset = *offset_ptr;

  // Calculate pointer to byte array (add offset to object data start)
  const uint8_t *byte_array =
      (const uint8_t *)java_string_obj + byte_array_offset;

  // Character data starts at offset 16 within byte array
  // Structure: [4-byte length][12-byte padding/header][character data]
  const uint8_t *data = byte_array + 16;

  // Additional bounds check to prevent reading beyond reasonable string data
  // Check if index is too large to be reasonable
  if (index > 1000)
    return 0;

  return data[index];
}

// charAt implementation - GraalVM generates calls to this for str.charAt()
uint32_t java_lang_String_charAt_Int_retChar(const void *java_string_obj,
                                             int32_t index) {
  // TEMP DEBUG: Output call info to serial
  serial_write('{');
  serial_write('0' + index);
  serial_write('}');

  if (java_string_obj == 0 || index < 0)
    return 0;

  int32_t len = get_string_length(java_string_obj);
  if (index >= len)
    return 0;

  // Additional safety bounds check - make sure we don't access beyond
  // reasonable limits
  if (index > 1000)
    return 0; // Prevent excessive memory access

  // TEMP DEBUG: For index 0, output pointer address to serial
  if (index == 0) {
    serial_write('[');
    uint64_t ptr_val = (uint64_t)java_string_obj;
    // Output hex digits of pointer (lower 16 bits)
    for (int i = 0; i < 4; i++) {
      uint8_t nibble = (ptr_val >> (12 - i * 4)) & 0xF;
      char hex_char = (nibble < 10) ? ('0' + nibble) : ('A' + nibble - 10);
      serial_write(hex_char);
    }

    serial_write(' ');

    // Also output the offset value
    const uint64_t *offset_ptr = (const uint64_t *)java_string_obj;
    uint64_t offset = *offset_ptr;
    for (int i = 0; i < 4; i++) {
      uint8_t nibble = (offset >> (12 - i * 4)) & 0xF;
      char hex_char = (nibble < 10) ? ('0' + nibble) : ('A' + nibble - 10);
      serial_write(hex_char);
    }
    serial_write(']');
  }

  return (uint32_t)get_string_byte(java_string_obj, index);
}

// =============================================================================
// REQUIRED: GraalVM Thread Structure for R15 register
// =============================================================================

// GraalVM LLVM backend generates safepoint check code that reads from the R15
// register. The bootloader must set R15 to point to this structure.
// Without this, the kernel will crash on the first safepoint check.

struct graal_thread_local_struct {
  char pad[16];              // Padding to offset 16
  int32_t safepoint_counter; // At offset 16: safepoint check counter
  char pad2[12];             // Padding to offset 32 (0x20)
  uint64_t tlab_top;         // At offset 32: TLAB allocation limit
  uint64_t tlab_start;       // At offset 40: TLAB current allocation pointer
} __attribute__((aligned(8)));

// Heap buffer for TLAB allocations (if needed in future)
static char heap_buffer[65536] __attribute__((aligned(16)));

// This is the structure that R15 points to
struct graal_thread_local_struct graal_thread_local = {
    .pad = {0},
    .safepoint_counter =
        0x7FFFFFFF, // Max positive value = never trigger safepoints
    .pad2 = {0},
    .tlab_top = (uint64_t)(&heap_buffer[sizeof(heap_buffer)]),
    .tlab_start = (uint64_t)(&heap_buffer[0])};

// =============================================================================
// REQUIRED: GraalVM Runtime Stubs
// =============================================================================

// Safepoint check - not needed in bare-metal environment
void com_oracle_svm_core_thread_SafepointSlowpath_enterSlowPathSafepointCheck_V() {
  // No-op - safepoints not needed in bare-metal
}

// Exception handling personality function - not needed in bare-metal
int com_oracle_svm_core_code_IsolateEnterStub_LLVMExceptionUnwind_personality_YlslbgN6sW6jlo8AQZoycD_Int_Int_IsolateThread_LLVMExceptionUnwind__Unwind_Exception_LLVMExceptionUnwind__Unwind_Context_retInt(
    int a, int b, int64_t c, int64_t d, int64_t e) {
  (void)a;
  (void)b;
  (void)c;
  (void)d;
  (void)e;
  return 0;
}

// Null pointer exception - called by writeString when checking null
void com_oracle_svm_core_snippets_ImplicitExceptions_throwNewNullPointerException_V() {
  // In bare-metal, we can't throw exceptions. Just halt.
  while (1) {
    // Infinite loop on null pointer
  }
}