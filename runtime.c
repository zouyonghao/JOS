#include <stddef.h>
#include <stdint.h>

// VGA memory uses 2 bytes per cell: character + attribute
void Kernel_writeMemory_Long_Char(int64_t addr, int32_t _byte) {
  *((uint16_t *)addr) = (uint8_t)_byte;
}

// -----------------------------------------------------------------------------
// Minimal java.lang.String support
// -----------------------------------------------------------------------------

// With our GraalVM modifications, string constants are now passed as direct
// pointers to null-terminated C strings in the binary's read-only data section.
// This greatly simplifies the runtime implementation.

static int32_t safe_strlen(const char *str) {
  if (str == 0) {
    return 0;
  }

  int32_t length = 0;
  while (str[length] != 0 && length < 1024) { // Safety limit to prevent runaway
    length++;
  }
  return length;
}

int32_t java_lang_String_length_retInt(void *string_obj) {
  return safe_strlen((const char *)string_obj);
}

int32_t java_lang_String_charAt_Int_retChar(void *string_obj, int32_t index) {
  if (string_obj == 0 || index < 0) {
    return 0;
  }

  const char *str = (const char *)string_obj;
  int32_t length = safe_strlen(str);

  if (index >= length) {
    return 0;
  }

  return (int32_t)(uint8_t)str[index];
}

// -----------------------------------------------------------------------------
// GraalVM Runtime Stubs
// -----------------------------------------------------------------------------

// Safepoint check - not needed in bare-metal environment
void com_oracle_svm_core_thread_SafepointSlowpath_enterSlowPathSafepointCheck_V() {
}

// Exception handling personality function - not needed in bare-metal
// environment
int com_oracle_svm_core_code_IsolateEnterStub_LLVMExceptionUnwind_personality_YlslbgN6sW6jlo8AQZoycD_Int_Int_IsolateThread_LLVMExceptionUnwind__Unwind_Exception_LLVMExceptionUnwind__Unwind_Context_retInt() {
  return 0;
}