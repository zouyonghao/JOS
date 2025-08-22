#include <stdint.h>

void Kernel_writeMemory_Long_Char(
    int64_t addr, int32_t _byte) {
  *((uint16_t *)addr) = (uint8_t)_byte;
}

void com_oracle_svm_core_thread_SafepointSlowpath_enterSlowPathSafepointCheck_V() {
}

int com_oracle_svm_core_code_IsolateEnterStub_LLVMExceptionUnwind_personality_YlslbgN6sW6jlo8AQZoycD_Int_Int_IsolateThread_LLVMExceptionUnwind__Unwind_Exception_LLVMExceptionUnwind__Unwind_Context_retInt() {
  return 0;
}