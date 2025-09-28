#include <stdint.h>

// VGA memory uses 2 bytes per cell: character + attribute
void Kernel_writeMemory_Long_Char(int64_t addr, int32_t _byte) {
  *((uint16_t *)addr) = (uint8_t)_byte;
}

// -----------------------------------------------------------------------------
// Minimal java.lang.String support
// -----------------------------------------------------------------------------

// Graal native-image encodes compile-time constant strings using a compressed
// pointer scheme. For the constants that survive our LLVM reduction, the
// "pointer" passed to java_lang_String_* helpers actually contains the string
// bytes packed directly into the integer value (little-endian order) and does
// not reference valid memory. Dereferencing it causes the #GP faults observed
// in QEMU. We therefore extract characters by decoding the integer value
// instead of treating it as an address.

static inline uint64_t decode_string_handle(void *string_obj) {
  return (uint64_t)(uintptr_t)string_obj;
}

int32_t java_lang_String_length_retInt(void *string_obj) {
  if (string_obj == 0) {
    return 0;
  }

  uint64_t raw = decode_string_handle(string_obj);
  int length = 0;

  while (raw && length < 8) {
    uint8_t ch = raw & 0xFF;
    if (ch == 0) {
      break;
    }
    length++;
    raw >>= 8;
  }

  return length;
}

int32_t java_lang_String_charAt_Int_retChar(void *string_obj, int32_t index) {
  if (string_obj == 0 || index < 0) {
    return 0;
  }

  uint64_t raw = decode_string_handle(string_obj);
  uint64_t shifted = raw >> (index * 8);
  return (int32_t)(shifted & 0xFF);
}

void com_oracle_svm_core_thread_SafepointSlowpath_enterSlowPathSafepointCheck_V() {
}

int com_oracle_svm_core_code_IsolateEnterStub_LLVMExceptionUnwind_personality_YlslbgN6sW6jlo8AQZoycD_Int_Int_IsolateThread_LLVMExceptionUnwind__Unwind_Exception_LLVMExceptionUnwind__Unwind_Context_retInt() {
  return 0;
}