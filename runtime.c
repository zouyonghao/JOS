#include <stdint.h>

void Kernel_writeMemory_6270829245fa537989e2e2b8ddb7075d4c724a03(
    int64_t addr, int32_t _byte) {
  *((uint16_t *)addr) = (uint8_t)_byte;
}

// void main()
// {
// 	_ZN10HelloWorld4mainEJvP6JArrayIPN4java4lang6StringEE();
// }

/* runtime functions in libgcj */
void _ZN4java4lang6ObjectC1Ev() {}
void _Jv_InitClass() {}
void _Jv_RegisterResource() {}
void _ZN4java4lang6Object8finalizeEJvv() {}
void _ZN4java4lang6Object8hashCodeEJiv() {}
void _ZN4java4lang6Object6equalsEJbPS1_() {}
void _ZN4java4lang6Object8toStringEJPNS0_6StringEv() {}
void _ZN4java4lang6Object5cloneEJPS1_v() {}
void _ZN4java4lang6Object22throwNoSuchMethodErrorEJvv() {}
void _ZTVN4java4lang5ClassE() {}
void _ZN4java4lang6Object6class$E() {}

void Safepoint_enterSlowPathSafepointCheck_c39040d70cf6aa8104bda7eae589ec9601192610() {
}

void StackOverflowCheckImpl_throwNewStackOverflowError_31341960d080a71e3dff8d322e20c16c7dc860eb() {
}

uint32_t
IsolateEnterStub_LLVMExceptionUnwind_personality_6715a663d94f995518d057e92a9c4ae4293ffca2_ffb6d22876bfcdf2a2b3e345df497710802e992f(
    uint32_t a, uint32_t b, uint64_t c, uint64_t d, uint64_t e) {
  return 0;
}