#include <stddef.h>
#include <stdint.h>

// =============================================================================
// Native method: Kernel.writeMemory(long, char)
// =============================================================================

#define SERIAL_PORT 0x3F8

static void serial_write(char c) {
  __asm__ volatile("outb %0, %1" : : "a"(c), "Nd"((uint16_t)SERIAL_PORT));
}

void Kernel_writeMemory_Long_Char(int64_t addr, int32_t _byte) {
  if (addr >= 0xB8000 && addr <= 0xB8F9F) {
    if ((addr - 0xB8000) % 2 == 0) {
      serial_write((char)_byte);
    }
    *((uint8_t *)addr) = (uint8_t)_byte;
  }
}

// =============================================================================
// String operations (plain null-terminated C strings)
// =============================================================================

int32_t java_lang_String_length_Int(const char *str) {
  if (str == 0)
    return 0;
  int32_t len = 0;
  while (str[len] != '\0')
    len++;
  return len;
}

uint32_t java_lang_String_charAt_Int_retChar(const char *str, int32_t index) {
  if (str == 0 || index < 0)
    return 0;
  for (int32_t i = 0; i < index; i++) {
    if (str[i] == '\0')
      return 0;
  }
  return (uint32_t)(unsigned char)str[index];
}
