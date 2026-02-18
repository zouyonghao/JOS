#include <stddef.h>
#include <stdint.h>

// =============================================================================
// Malloc/Free - using kernel's heap allocator
// =============================================================================

extern int64_t kernel_Memory_heapAlloc_Long(int64_t size);
extern void kernel_Memory_heapFree_Long(int64_t ptr);

void* malloc(size_t size) {
  // The kernel's heapAlloc adds an 8-byte header, but for arrays
  // the translator expects: [8-byte length | elements]
  // So we just allocate the requested size + 8 for the length header
  int64_t ptr = kernel_Memory_heapAlloc_Long((int64_t)size + 8);
  if (ptr == 0) return NULL;
  // Return pointer to the element area (after length header)
  return (void*)(ptr + 8);
}

void free(void* ptr) {
  if (ptr == NULL) return;
  // Go back to the actual allocation start (before the length header)
  kernel_Memory_heapFree_Long((int64_t)ptr - 8);
}

// =============================================================================
// Native method: Kernel.writeMemory(long, char)
// =============================================================================

#define SERIAL_PORT 0x3F8

static void serial_write(char c) {
  __asm__ volatile("outb %0, %1" : : "a"(c), "Nd"((uint16_t)SERIAL_PORT));
}

void kernel_Native_writeMemory_Long_Char(int64_t addr, int32_t _byte) {
  // VGA memory: write with serial output for debugging
  if (addr >= 0xB8000 && addr <= 0xB8F9F) {
    if ((addr - 0xB8000) % 2 == 0) {
      serial_write((char)_byte);
    }
  }
  // Serial port
  if (addr == SERIAL_PORT) {
    serial_write((char)_byte);
  }
  // Write to any address (including heap)
  *((uint8_t *)addr) = (uint8_t)_byte;
}

// =============================================================================
// Native method: Kernel.writeSerial(char)
// =============================================================================

void kernel_Native_writeSerial_Char(int32_t c) {
  serial_write((char)c);
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
extern void* get_syscall_handler(void);

// Global state
static uint64_t timer_ticks = 0;

// =============================================================================
// Syscall Interface - Globals for passing args between asm and Java
// =============================================================================

// External Java globals for syscall handling
extern int64_t syscallNum;
extern int64_t syscallArg1;
extern int64_t syscallArg2;
extern int64_t syscallArg3;
extern int64_t syscallRet;

// External Java method for syscall handling
extern int64_t Kernel_handleSyscall_Long_Long_Long_Long(int64_t num, int64_t arg1, int64_t arg2, int64_t arg3);

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
extern void kernel_Interrupts_handleInterrupt_Int(int32_t vector);

// External Java globals for syscall args
extern int64_t Kernel_syscallNum;
extern int64_t Kernel_syscallArg1;
extern int64_t Kernel_syscallArg2;
extern int64_t Kernel_syscallArg3;
extern int64_t Kernel_syscallRet;

// Called from assembly - forwards to Java
// NOTE: For syscalls (vector 0x80), the assembly stub in interrupts.asm
// saves RAX, RDI, RSI, RDX to Java globals BEFORE calling this function.
// The assembly stub also restores the return value from Java back to RAX
// AFTER this function returns (in the interrupt epilogue).
void interrupt_dispatch(uint64_t vector) {
  kernel_Interrupts_handleInterrupt_Int((int32_t)vector);
}

// =============================================================================
// Native methods exposed to Java (direct implementations)
// =============================================================================

int32_t kernel_Native_inb_Int(int32_t port) {
  return (int32_t)inb((uint16_t)port);
}

int32_t kernel_Native_inw_Int(int32_t port) {
  uint16_t value;
  __asm__ volatile("inw %1, %0" : "=a"(value) : "Nd"((uint16_t)port));
  return (int32_t)value;
}

void kernel_Native_outb_Int_Char(int32_t port, int32_t data) {
  outb((uint16_t)port, (uint8_t)data);
}

void kernel_Native_outw_Int_Int(int32_t port, int32_t data) {
  __asm__ volatile("outw %0, %w1" : : "a"((uint16_t)data), "d"((uint16_t)port));
}

void kernel_Native_outl_Int_Int(int32_t port, int32_t data) {
  // outl requires port in dx register, data in eax
  uint16_t port16 = (uint16_t)port;
  __asm__ volatile("outl %0, %w1" : : "a"(data), "d"(port16));
}

// =============================================================================
// Memory access (64-bit)
// =============================================================================

int64_t kernel_Native_readMemoryLong_Long(int64_t addr) {
  return *(int64_t*)addr;
}

void kernel_Native_writeMemoryLong_Long_Long(int64_t addr, int64_t data) {
  *(int64_t*)addr = data;
}

// =============================================================================
// Paging control
// =============================================================================

int64_t kernel_Native_getCR3_V(void) {
  int64_t val;
  __asm__ volatile("mov %%cr3, %0" : "=r"(val));
  return val;
}

void kernel_Native_setCR3_Long(int64_t val) {
  __asm__ volatile("mov %0, %%cr3" : : "r"(val));
}

void kernel_Native_enablePaging_V(void) {
  int64_t cr0;
  __asm__ volatile("mov %%cr0, %0" : "=r"(cr0));
  cr0 |= 0x80000000;  // Set PG bit
  __asm__ volatile("mov %0, %%cr0" : : "r"(cr0));
}

void kernel_Native_setIDTGate_Int_Long_Char(int32_t vector, int64_t handlerAddr, int32_t typeAttr) {
  (void)handlerAddr;  // Unused - we get addresses from isr_stub_table
  if (vector >= 0 && vector < 48) {
    idt_set_gate(vector, isr_stub_table[vector], (uint8_t)typeAttr);
  } else if (vector == 0x80) {
    // Syscall vector - use getter function to obtain handler address
    void* handler = get_syscall_handler();
    idt_set_gate(vector, handler, (uint8_t)typeAttr);
  }
}

void kernel_Native_loadIDT_V(void) {
  idt_load();
}

void kernel_Native_sendEOI_Int(int32_t irq) {
  pic_send_eoi((uint8_t)irq);
}

void kernel_Native_enableInterrupts_V(void) {
  enable_interrupts();
}

void kernel_Native_disableInterrupts_V(void) {
  disable_interrupts();
}

int64_t kernel_Native_getTicks_V(void) {
  return timer_ticks;
}

void kernel_Native_incTicks_V(void) {
  timer_ticks++;
}

// =============================================================================
// Native method: Kernel.callProgram(long entryPoint)
// Call a loaded binary program at the given entry point
// =============================================================================

void kernel_Native_callProgram_Long(int64_t entryPoint) {
  // Cast entryPoint to function pointer and call it
  void (*prog)() = (void (*)())entryPoint;
  prog();
}
