#include <stdint.h>

// The native method implementation that Java code will call
void Kernel_writeMemory_Long_Char(int64_t addr, int32_t _byte) {
  *((uint16_t *)addr) = (uint8_t)_byte;
}

void com_oracle_svm_core_thread_SafepointSlowpath_enterSlowPathSafepointCheck_V() {
}

int com_oracle_svm_core_code_IsolateEnterStub_LLVMExceptionUnwind_personality_YlslbgN6sW6jlo8AQZoycD_Int_Int_IsolateThread_LLVMExceptionUnwind__Unwind_Exception_LLVMExceptionUnwind__Unwind_Context_retInt() {
  return 0;
}

// Remove the complex constants since we're providing the function directly
// The GraalVM-generated code should directly call our native method

void* com_oracle_svm_core_jni_access_JNINativeLinkage_getOrFindEntryPoint_V_retPointerBase(void* method_info) {
  // Return the address of our native function
  return (void*)Kernel_writeMemory_Long_Char;
}

int com_oracle_svm_core_jni_JNIObjectHandles_pushLocalFrame_Int_retInt(int capacity) {
  return 0;
}

void* com_oracle_svm_core_jni_JNIObjectHandles_createLocal_Object_retJNIObjectHandle(void* object) {
  return 0;
}

void com_oracle_svm_core_thread_SafepointSlowpath_enterSlowPathTransitionFromNativeToNewStatus_Int_Bool(int status, int check) {
}

void com_oracle_svm_core_jni_JNIGeneratedMethodSupport_nativeCallEpilogue_Int(int result) {
}

void com_oracle_svm_core_jni_JNIGeneratedMethodSupport_rethrowPendingException_V() {
}