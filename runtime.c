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
  } else if (addr == SERIAL_PORT) {
    serial_write((char)_byte);
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

// =============================================================================
// Interrupt Handling (consolidated from interrupts.c)
// =============================================================================

// External assembly functions
extern void idt_set_gate(int vector, void* handler, uint8_t type_attr);
extern void idt_load(void);
extern void enable_interrupts(void);
extern void disable_interrupts(void);
extern void pic_send_eoi(uint8_t irq);

// ISR handler addresses from assembly
extern void* isr_stub_table[];

// Global state
static uint64_t timer_ticks = 0;

// Port I/O
static inline void outb(uint16_t port, uint8_t value) {
  __asm__ volatile("outb %0, %1" : : "a"(value), "Nd"(port));
}

static inline uint8_t inb(uint16_t port) {
  uint8_t value;
  __asm__ volatile("inb %1, %0" : "=a"(value) : "Nd"(port));
  return value;
}

// External Java method for interrupt handling - Java does ALL dispatch logic
extern void Kernel_handleInterrupt_Int(int32_t vector);

// Called from assembly - forwards to Java
void interrupt_dispatch(uint64_t vector) {
  Kernel_handleInterrupt_Int((int32_t)vector);
}

// =============================================================================
// Native methods exposed to Java (direct implementations)
// =============================================================================

int32_t Kernel_inb_Int(int32_t port) {
  return (int32_t)inb((uint16_t)port);
}

void Kernel_outb_Int_Char(int32_t port, int32_t data) {
  outb((uint16_t)port, (uint8_t)data);
}

void Kernel_setIDTGate_Int_Long_Char(int32_t vector, int64_t handlerAddr, int32_t typeAttr) {
  (void)handlerAddr;  // Unused - we get addresses from isr_stub_table
  if (vector >= 0 && vector < 48) {
    idt_set_gate(vector, isr_stub_table[vector], (uint8_t)typeAttr);
  }
}

void Kernel_loadIDT_V(void) {
  idt_load();
}

void Kernel_sendEOI_Int(int32_t irq) {
  pic_send_eoi((uint8_t)irq);
}

void Kernel_enableInterrupts_V(void) {
  enable_interrupts();
}

void Kernel_disableInterrupts_V(void) {
  disable_interrupts();
}

int64_t Kernel_getTicks_V(void) {
  return timer_ticks;
}

void Kernel_incTicks_V(void) {
  timer_ticks++;
}
