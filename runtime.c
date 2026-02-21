#include <stddef.h>
#include <stdint.h>
#include <stdarg.h>

// =============================================================================
// Malloc/Free - using kernel's heap allocator
// =============================================================================

extern int64_t kernel_Memory_heapAlloc_Long(int64_t size);
extern void kernel_Memory_heapFree_Long(int64_t ptr);

void* malloc(size_t size) {
  int64_t ptr = kernel_Memory_heapAlloc_Long((int64_t)size + 8);
  if (ptr == 0) return NULL;
  return (void*)(ptr + 8);
}

void free(void* ptr) {
  if (ptr == NULL) return;
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
  if (addr >= 0xB8000 && addr <= 0xB8F9F) {
    if ((addr - 0xB8000) % 2 == 0) {
      serial_write((char)_byte);
    }
  }
  if (addr == SERIAL_PORT) {
    serial_write((char)_byte);
  }
  *((uint8_t *)addr) = (uint8_t)_byte;
}

// =============================================================================
// Native method: Kernel.writeSerial(char)
// =============================================================================

void kernel_Native_writeSerial_Char(int32_t c) {
  serial_write((char)c);
}

// =============================================================================
// Native memory operations: memcpy, memset
// =============================================================================

void kernel_Native_memcpy_Long_Long_Long(int64_t dst, int64_t src, int64_t len) {
  uint8_t *d = (uint8_t *)dst;
  const uint8_t *s = (const uint8_t *)src;
  for (int64_t i = 0; i < len; i++) {
    d[i] = s[i];
  }
}

void kernel_Native_memset_Long_Int_Long(int64_t dst, int32_t val, int64_t len) {
  uint8_t *d = (uint8_t *)dst;
  uint8_t v = (uint8_t)val;
  for (int64_t i = 0; i < len; i++) {
    d[i] = v;
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

extern void idt_set_gate(int vector, void* handler, uint8_t type_attr);
extern void idt_load(void);
extern void enable_interrupts(void);
extern void disable_interrupts(void);
extern void pic_send_eoi(uint8_t irq);

extern void* isr_stub_table[];
extern void* get_syscall_handler(void);

static uint64_t timer_ticks = 0;

// =============================================================================
// Syscall Interface
// =============================================================================

extern int64_t syscallNum;
extern int64_t syscallArg1;
extern int64_t syscallArg2;
extern int64_t syscallArg3;
extern int64_t syscallRet;

extern int64_t Kernel_handleSyscall_Long_Long_Long_Long(int64_t num, int64_t arg1, int64_t arg2, int64_t arg3);

static inline void outb(uint16_t port, uint8_t value) {
  __asm__ volatile("outb %0, %1" : : "a"(value), "Nd"(port));
}

static inline uint8_t inb(uint16_t port) {
  uint8_t value;
  __asm__ volatile("inb %1, %0" : "=a"(value) : "Nd"(port));
  return value;
}

extern void kernel_Interrupts_handleInterrupt_Int(int32_t vector);

extern int64_t Kernel_syscallNum;
extern int64_t Kernel_syscallArg1;
extern int64_t Kernel_syscallArg2;
extern int64_t Kernel_syscallArg3;
extern int64_t Kernel_syscallRet;

void interrupt_dispatch(uint64_t vector) {
  kernel_Interrupts_handleInterrupt_Int((int32_t)vector);
}

// =============================================================================
// Native methods exposed to Java
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
  cr0 |= 0x80000000;
  __asm__ volatile("mov %0, %%cr0" : : "r"(cr0));
}

void kernel_Native_setIDTGate_Int_Long_Char(int32_t vector, int64_t handlerAddr, int32_t typeAttr) {
  (void)handlerAddr;
  if (vector >= 0 && vector < 48) {
    idt_set_gate(vector, isr_stub_table[vector], (uint8_t)typeAttr);
  } else if (vector == 0x80) {
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
// Native method: callProgram
// =============================================================================

void kernel_Native_callProgram_Long(int64_t entryPoint) {
  void (*prog)() = (void (*)())entryPoint;
  prog();
}

// =============================================================================
// Windows Syscall MSR Initialization
// =============================================================================

extern void init_syscall_msr(void);

void kernel_Native_initSyscallMSR_V(void) {
  init_syscall_msr();
}

// =============================================================================
// CR2 read (for page fault address)
// =============================================================================

int64_t kernel_Native_readCR2_V(void) {
  int64_t val;
  __asm__ volatile("mov %%cr2, %0" : "=r"(val));
  return val;
}

// =============================================================================
// GS Base MSR (for Windows TEB emulation)
// =============================================================================

void kernel_Native_setGSBase_Long(int64_t addr) {
  uint32_t lo = (uint32_t)(addr & 0xFFFFFFFF);
  uint32_t hi = (uint32_t)((addr >> 32) & 0xFFFFFFFF);
  // MSR 0xC0000101 = IA32_GS_BASE
  __asm__ volatile("wrmsr" : : "c"(0xC0000101), "a"(lo), "d"(hi));
}

// =============================================================================
// MSVCRT EMULATION - C Runtime Functions for Windows PE Programs
// These are called directly via jmp stubs (not through int 0x80)
// =============================================================================

// Console output helper - calls Java Console.writeChar for proper VGA+serial output
extern void kernel_Console_writeChar_Char(int32_t c);

static void console_write_char(char c) {
  kernel_Console_writeChar_Char((int32_t)(unsigned char)c);
}

static void console_write_string(const char *s) {
  while (*s) {
    console_write_char(*s);
    s++;
  }
}

// --- printf implementation ---

static void print_unsigned(char *buf, int *pos, int max, uint64_t val, int base, int uppercase) {
  char digits[20];
  int len = 0;
  const char *hexchars = uppercase ? "0123456789ABCDEF" : "0123456789abcdef";

  if (val == 0) {
    digits[len++] = '0';
  } else {
    while (val > 0 && len < 20) {
      digits[len++] = hexchars[val % base];
      val /= base;
    }
  }
  // Reverse
  for (int i = len - 1; i >= 0; i--) {
    if (*pos < max - 1) {
      buf[(*pos)++] = digits[i];
    }
  }
}

static void print_signed(char *buf, int *pos, int max, int64_t val, int base) {
  if (val < 0) {
    if (*pos < max - 1) buf[(*pos)++] = '-';
    val = -val;
  }
  print_unsigned(buf, pos, max, (uint64_t)val, base, 0);
}

static int do_vsnprintf(char *buf, int max, const char *fmt, va_list args) {
  int pos = 0;
  if (max <= 0) return 0;

  while (*fmt && pos < max - 1) {
    if (*fmt != '%') {
      buf[pos++] = *fmt++;
      continue;
    }
    fmt++; // skip '%'

    // Flags
    int zero_pad = 0;
    int left_align = 0;
    while (*fmt == '0' || *fmt == '-') {
      if (*fmt == '0') zero_pad = 1;
      if (*fmt == '-') left_align = 1;
      fmt++;
    }

    // Width
    int width = 0;
    while (*fmt >= '0' && *fmt <= '9') {
      width = width * 10 + (*fmt - '0');
      fmt++;
    }

    // Length modifier
    int is_long = 0;
    int is_size_t = 0;
    if (*fmt == 'l') {
      is_long = 1;
      fmt++;
      if (*fmt == 'l') { is_long = 2; fmt++; }
    } else if (*fmt == 'z') {
      is_size_t = 1;
      fmt++;
    } else if (*fmt == 'h') {
      fmt++;
      if (*fmt == 'h') fmt++;
    }

    // Specifier
    char spec = *fmt;
    if (spec == 0) break;
    fmt++;

    switch (spec) {
      case '%':
        buf[pos++] = '%';
        break;

      case 'c': {
        char c = (char)va_arg(args, int);
        buf[pos++] = c;
        break;
      }

      case 's': {
        const char *s = va_arg(args, const char*);
        if (s == NULL) s = "(null)";
        while (*s && pos < max - 1) {
          buf[pos++] = *s++;
        }
        break;
      }

      case 'd':
      case 'i': {
        int64_t val;
        if (is_long >= 2 || is_size_t) val = (int64_t)va_arg(args, long long);
        else if (is_long == 1) val = (int64_t)va_arg(args, long);
        else val = (int64_t)va_arg(args, int);

        // Handle width with zero padding
        if (width > 0 && zero_pad) {
          char tmp[24];
          int tpos = 0;
          print_signed(tmp, &tpos, 24, val, 10);
          tmp[tpos] = 0;
          int pad = width - tpos;
          while (pad > 0 && pos < max - 1) { buf[pos++] = '0'; pad--; }
          for (int i = 0; i < tpos && pos < max - 1; i++) buf[pos++] = tmp[i];
        } else {
          print_signed(buf, &pos, max, val, 10);
        }
        break;
      }

      case 'u': {
        uint64_t val;
        if (is_long >= 2 || is_size_t) val = (uint64_t)va_arg(args, unsigned long long);
        else if (is_long == 1) val = (uint64_t)va_arg(args, unsigned long);
        else val = (uint64_t)va_arg(args, unsigned int);
        print_unsigned(buf, &pos, max, val, 10, 0);
        break;
      }

      case 'x':
      case 'X': {
        uint64_t val;
        if (is_long >= 2 || is_size_t) val = (uint64_t)va_arg(args, unsigned long long);
        else if (is_long == 1) val = (uint64_t)va_arg(args, unsigned long);
        else val = (uint64_t)va_arg(args, unsigned int);
        print_unsigned(buf, &pos, max, val, 16, (spec == 'X'));
        break;
      }

      case 'p': {
        void *p = va_arg(args, void*);
        if (pos < max - 1) buf[pos++] = '0';
        if (pos < max - 1) buf[pos++] = 'x';
        print_unsigned(buf, &pos, max, (uint64_t)p, 16, 0);
        break;
      }

      default:
        // Unknown specifier - just print it
        buf[pos++] = spec;
        break;
    }
  }
  buf[pos] = '\0';
  return pos;
}

// --- msvcrt functions (called from PE user programs) ---

static int msvcrt_printf(const char *fmt, ...) {
  char buf[512];
  va_list args;
  va_start(args, fmt);
  int n = do_vsnprintf(buf, sizeof(buf), fmt, args);
  va_end(args);
  console_write_string(buf);
  return n;
}

static int msvcrt_sprintf(char *dst, const char *fmt, ...) {
  va_list args;
  va_start(args, fmt);
  int n = do_vsnprintf(dst, 4096, fmt, args);
  va_end(args);
  return n;
}

static int msvcrt_snprintf(char *dst, size_t max, const char *fmt, ...) {
  va_list args;
  va_start(args, fmt);
  int n = do_vsnprintf(dst, (int)max, fmt, args);
  va_end(args);
  return n;
}

static int msvcrt_fprintf(void *stream, const char *fmt, ...) {
  // For now, all fprintf goes to console
  char buf[512];
  va_list args;
  va_start(args, fmt);
  int n = do_vsnprintf(buf, sizeof(buf), fmt, args);
  va_end(args);
  console_write_string(buf);
  return n;
}

static int msvcrt_puts(const char *s) {
  if (s == NULL) return -1;
  console_write_string(s);
  console_write_char('\n');
  return 0;
}

static int msvcrt_putchar(int c) {
  console_write_char((char)c);
  return c;
}

static int msvcrt_fflush(void *stream) {
  (void)stream;
  return 0;
}

// --- Memory allocation ---

static void* msvcrt_malloc(size_t size) {
  int64_t ptr = kernel_Memory_heapAlloc_Long((int64_t)size);
  return (ptr == 0) ? NULL : (void*)ptr;
}

static void msvcrt_free(void *ptr) {
  if (ptr) kernel_Memory_heapFree_Long((int64_t)ptr);
}

static void* msvcrt_calloc(size_t nmemb, size_t size) {
  size_t total = nmemb * size;
  void *p = msvcrt_malloc(total);
  if (p) {
    uint8_t *d = (uint8_t*)p;
    for (size_t i = 0; i < total; i++) d[i] = 0;
  }
  return p;
}

static void* msvcrt_realloc(void *ptr, size_t size) {
  if (ptr == NULL) return msvcrt_malloc(size);
  if (size == 0) { msvcrt_free(ptr); return NULL; }
  void *np = msvcrt_malloc(size);
  if (np) {
    // Copy old data - we don't know the old size, so copy up to new size
    uint8_t *d = (uint8_t*)np;
    uint8_t *s = (uint8_t*)ptr;
    for (size_t i = 0; i < size; i++) d[i] = s[i];
    msvcrt_free(ptr);
  }
  return np;
}

// --- String functions ---

static size_t msvcrt_strlen(const char *s) {
  size_t len = 0;
  while (s[len]) len++;
  return len;
}

static char* msvcrt_strcpy(char *dst, const char *src) {
  char *d = dst;
  while ((*d++ = *src++) != '\0');
  return dst;
}

static char* msvcrt_strncpy(char *dst, const char *src, size_t n) {
  size_t i;
  for (i = 0; i < n && src[i]; i++) dst[i] = src[i];
  for (; i < n; i++) dst[i] = '\0';
  return dst;
}

static int msvcrt_strcmp(const char *a, const char *b) {
  while (*a && *a == *b) { a++; b++; }
  return (int)(unsigned char)*a - (int)(unsigned char)*b;
}

static int msvcrt_strncmp(const char *a, const char *b, size_t n) {
  for (size_t i = 0; i < n; i++) {
    if (a[i] != b[i]) return (int)(unsigned char)a[i] - (int)(unsigned char)b[i];
    if (a[i] == '\0') return 0;
  }
  return 0;
}

static char* msvcrt_strcat(char *dst, const char *src) {
  char *d = dst;
  while (*d) d++;
  while ((*d++ = *src++) != '\0');
  return dst;
}

static char* msvcrt_strchr(const char *s, int c) {
  while (*s) {
    if (*s == (char)c) return (char*)s;
    s++;
  }
  return (c == '\0') ? (char*)s : NULL;
}

static char* msvcrt_strrchr(const char *s, int c) {
  const char *last = NULL;
  while (*s) {
    if (*s == (char)c) last = s;
    s++;
  }
  if (c == '\0') return (char*)s;
  return (char*)last;
}

static long msvcrt_strtol(const char *s, char **endptr, int base) {
  long result = 0;
  int neg = 0;
  while (*s == ' ' || *s == '\t') s++;
  if (*s == '-') { neg = 1; s++; }
  else if (*s == '+') s++;

  if (base == 0) {
    if (*s == '0' && (s[1] == 'x' || s[1] == 'X')) { base = 16; s += 2; }
    else if (*s == '0') { base = 8; s++; }
    else base = 10;
  }
  if (base == 16 && *s == '0' && (s[1] == 'x' || s[1] == 'X')) s += 2;

  while (*s) {
    int digit;
    if (*s >= '0' && *s <= '9') digit = *s - '0';
    else if (*s >= 'a' && *s <= 'f') digit = *s - 'a' + 10;
    else if (*s >= 'A' && *s <= 'F') digit = *s - 'A' + 10;
    else break;
    if (digit >= base) break;
    result = result * base + digit;
    s++;
  }
  if (endptr) *endptr = (char*)s;
  return neg ? -result : result;
}

// --- Memory functions ---

static int msvcrt_memcmp(const void *a, const void *b, size_t n) {
  const uint8_t *pa = (const uint8_t*)a;
  const uint8_t *pb = (const uint8_t*)b;
  for (size_t i = 0; i < n; i++) {
    if (pa[i] != pb[i]) return (int)pa[i] - (int)pb[i];
  }
  return 0;
}

static void* msvcrt_memcpy(void *dst, const void *src, size_t n) {
  uint8_t *d = (uint8_t*)dst;
  const uint8_t *s = (const uint8_t*)src;
  for (size_t i = 0; i < n; i++) d[i] = s[i];
  return dst;
}

static void* msvcrt_memset(void *dst, int c, size_t n) {
  uint8_t *d = (uint8_t*)dst;
  for (size_t i = 0; i < n; i++) d[i] = (uint8_t)c;
  return dst;
}

static void* msvcrt_memmove(void *dst, const void *src, size_t n) {
  uint8_t *d = (uint8_t*)dst;
  const uint8_t *s = (const uint8_t*)src;
  if (d < s) {
    for (size_t i = 0; i < n; i++) d[i] = s[i];
  } else {
    for (size_t i = n; i > 0; i--) d[i-1] = s[i-1];
  }
  return dst;
}

// --- Character classification ---

static int msvcrt_isdigit(int c) { return (c >= '0' && c <= '9') ? 1 : 0; }
static int msvcrt_isalpha(int c) { return ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) ? 1 : 0; }
static int msvcrt_isspace(int c) { return (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v') ? 1 : 0; }
static int msvcrt_toupper(int c) { return (c >= 'a' && c <= 'z') ? c - 32 : c; }
static int msvcrt_tolower(int c) { return (c >= 'A' && c <= 'Z') ? c + 32 : c; }

static int msvcrt_atoi(const char *s) {
  return (int)msvcrt_strtol(s, NULL, 10);
}

// --- CRT internals ---

// Helper to call a void(void) Windows callback with proper shadow space
static void call_win64_void(void (*fn)(void)) {
  // Windows x64 ABI requires 32 bytes of shadow space above the return address.
  // Our SysV-compiled code doesn't provide this, so we manually allocate it.
  __asm__ volatile(
    "sub $0x20, %%rsp\n\t"
    "call *%0\n\t"
    "add $0x20, %%rsp"
    : : "r"(fn) : "rcx", "rdx", "r8", "r9", "r10", "r11", "memory"
  );
}

// _initterm: call array of function pointers
static void msvcrt_initterm(void (**start)(void), void (**end)(void)) {
  while (start < end) {
    if (*start != NULL) {
      call_win64_void(*start);
    }
    start++;
  }
}

// Fake FILE* handles for stdin/stdout/stderr
static int fake_stdin_handle = 0;
static int fake_stdout_handle = 1;
static int fake_stderr_handle = 2;

static void* msvcrt_iob_func(int index) {
  if (index == 0) return &fake_stdin_handle;
  if (index == 1) return &fake_stdout_handle;
  if (index == 2) return &fake_stderr_handle;
  return NULL;
}

// exit/abort
extern void kernel_Threading_terminateCurrentThread_V(void);

static void msvcrt_exit(int code) {
  kernel_Threading_terminateCurrentThread_V();
  while(1) {} // never reached
}

static void msvcrt_abort(void) {
  console_write_string("abort() called\n");
  kernel_Threading_terminateCurrentThread_V();
  while(1) {}
}

// --- New msvcrt functions for help.exe ---

static void msvcrt_setusermatherr(void *handler) {
  (void)handler; // no-op
}

static int64_t msvcrt_c_specific_handler(void) {
  return 0; // no-op
}

static void msvcrt_set_app_type(int type) {
  (void)type; // no-op
}

static void msvcrt_amsg_exit(int code) {
  kernel_Threading_terminateCurrentThread_V();
  while(1) {}
}

static int msvcrt_xcptfilter(int code, void *info) {
  (void)code; (void)info;
  return 0;
}

static void msvcrt_cexit(void) {
  // no-op
}

static void msvcrt_terminate_func(void) {
  msvcrt_exit(3);
}

static int msvcrt_wsystem(const uint16_t *cmd) {
  (void)cmd;
  return -1; // failure
}

// Data variables for _fmode and _commode
static int msvcrt_fmode_var = 0;
static int msvcrt_commode_var = 0;

// Static data for __wgetmainargs
static uint16_t wargv0[] = {'h','e','l','p',0};
static uint16_t *wargv_ptrs[2] = { wargv0, NULL };
static uint16_t *wenv_empty[1] = { NULL };

static int msvcrt_wgetmainargs(int *argc, uint16_t ***argv, uint16_t ***env, int expand, void *startup) {
  (void)expand; (void)startup;
  if (argc) *argc = 1;
  if (argv) *argv = wargv_ptrs;
  if (env) *env = wenv_empty;
  return 0;
}

// Wide-string case-insensitive compare
static int msvcrt_wcsnicmp(const uint16_t *s1, const uint16_t *s2, size_t n) {
  for (size_t i = 0; i < n; i++) {
    uint16_t c1 = s1[i];
    uint16_t c2 = s2[i];
    // Simple ASCII tolower
    if (c1 >= 'A' && c1 <= 'Z') c1 += 32;
    if (c2 >= 'A' && c2 <= 'Z') c2 += 32;
    if (c1 != c2) return (int)c1 - (int)c2;
    if (c1 == 0) return 0;
  }
  return 0;
}

// wcscat_s
static int msvcrt_wcscat_s(uint16_t *dst, size_t dstsize, const uint16_t *src) {
  if (!dst || !src || dstsize == 0) return 22; // EINVAL
  // Find end of dst
  size_t dlen = 0;
  while (dlen < dstsize && dst[dlen] != 0) dlen++;
  if (dlen >= dstsize) return 22;
  // Copy src
  size_t i = 0;
  while (src[i] && dlen + i + 1 < dstsize) {
    dst[dlen + i] = src[i];
    i++;
  }
  dst[dlen + i] = 0;
  return 0;
}

// wcscpy_s
static int msvcrt_wcscpy_s(uint16_t *dst, size_t dstsize, const uint16_t *src) {
  if (!dst || dstsize == 0) return 22;
  if (!src) { dst[0] = 0; return 22; }
  size_t i = 0;
  while (src[i] && i + 1 < dstsize) {
    dst[i] = src[i];
    i++;
  }
  dst[i] = 0;
  return 0;
}

// _ultow: unsigned long to wide string
static uint16_t* msvcrt_ultow(unsigned long val, uint16_t *buf, int radix) {
  if (!buf || radix < 2 || radix > 36) return NULL;
  uint16_t tmp[24];
  int len = 0;
  if (val == 0) {
    tmp[len++] = '0';
  } else {
    while (val > 0 && len < 23) {
      int digit = (int)(val % (unsigned long)radix);
      tmp[len++] = (uint16_t)(digit < 10 ? '0' + digit : 'a' + digit - 10);
      val /= (unsigned long)radix;
    }
  }
  // Reverse into buf
  for (int i = 0; i < len; i++) {
    buf[i] = tmp[len - 1 - i];
  }
  buf[len] = 0;
  return buf;
}

// setlocale: return pointer to static "C"
static const char msvcrt_locale_c[] = "C";
static char* msvcrt_setlocale(int category, const char *locale) {
  (void)category; (void)locale;
  return (char*)msvcrt_locale_c;
}

// =============================================================================
// MSVCRT Function Address Table
// =============================================================================

// Function IDs must match Loader.java MSVCRT_* constants
#define MSVCRT_PRINTF     1
#define MSVCRT_SPRINTF    2
#define MSVCRT_SNPRINTF   3
#define MSVCRT_PUTS       4
#define MSVCRT_PUTCHAR    5
#define MSVCRT_MALLOC     6
#define MSVCRT_FREE       7
#define MSVCRT_CALLOC     8
#define MSVCRT_STRLEN     9
#define MSVCRT_STRCPY     10
#define MSVCRT_STRNCPY    11
#define MSVCRT_STRCMP      12
#define MSVCRT_STRNCMP    13
#define MSVCRT_MEMCMP     14
#define MSVCRT_MEMCPY     15
#define MSVCRT_MEMSET     16
#define MSVCRT_EXIT       17
#define MSVCRT_ABORT      18
#define MSVCRT_INITTERM   19
#define MSVCRT_IOBFUNC    20
#define MSVCRT_FPRINTF    21
#define MSVCRT_ATOI       22
#define MSVCRT_FFLUSH     23
#define MSVCRT_REALLOC    24
#define MSVCRT_MEMMOVE    25
#define MSVCRT_STRCAT     26
#define MSVCRT_STRCHR     27
#define MSVCRT_STRRCHR    28
#define MSVCRT_STRTOL     29
#define MSVCRT_ISDIGIT    30
#define MSVCRT_ISALPHA    31
#define MSVCRT_ISSPACE    32
#define MSVCRT_TOUPPER    33
#define MSVCRT_TOLOWER_F  34
#define MSVCRT_SETUSERMATHERR 35
#define MSVCRT_C_SPECIFIC_HANDLER 36
#define MSVCRT_FMODE 37
#define MSVCRT_COMMODE 38
#define MSVCRT_TERMINATE 39
#define MSVCRT_SET_APP_TYPE 40
#define MSVCRT_WGETMAINARGS 41
#define MSVCRT_AMSG_EXIT 42
#define MSVCRT_XCPTFILTER 43
#define MSVCRT_WCSNICMP 44
#define MSVCRT_WSYSTEM 45
#define MSVCRT_WCSCAT_S 46
#define MSVCRT_WCSCPY_S 47
#define MSVCRT_ULTOW 48
#define MSVCRT_SETLOCALE 49
#define MSVCRT_CEXIT 50

int64_t kernel_Native_getMsvcrtFuncAddr_Int(int32_t funcId) {
  switch (funcId) {
    case MSVCRT_PRINTF:   return (int64_t)(uintptr_t)msvcrt_printf;
    case MSVCRT_SPRINTF:  return (int64_t)(uintptr_t)msvcrt_sprintf;
    case MSVCRT_SNPRINTF: return (int64_t)(uintptr_t)msvcrt_snprintf;
    case MSVCRT_PUTS:     return (int64_t)(uintptr_t)msvcrt_puts;
    case MSVCRT_PUTCHAR:  return (int64_t)(uintptr_t)msvcrt_putchar;
    case MSVCRT_MALLOC:   return (int64_t)(uintptr_t)msvcrt_malloc;
    case MSVCRT_FREE:     return (int64_t)(uintptr_t)msvcrt_free;
    case MSVCRT_CALLOC:   return (int64_t)(uintptr_t)msvcrt_calloc;
    case MSVCRT_STRLEN:   return (int64_t)(uintptr_t)msvcrt_strlen;
    case MSVCRT_STRCPY:   return (int64_t)(uintptr_t)msvcrt_strcpy;
    case MSVCRT_STRNCPY:  return (int64_t)(uintptr_t)msvcrt_strncpy;
    case MSVCRT_STRCMP:    return (int64_t)(uintptr_t)msvcrt_strcmp;
    case MSVCRT_STRNCMP:  return (int64_t)(uintptr_t)msvcrt_strncmp;
    case MSVCRT_MEMCMP:   return (int64_t)(uintptr_t)msvcrt_memcmp;
    case MSVCRT_MEMCPY:   return (int64_t)(uintptr_t)msvcrt_memcpy;
    case MSVCRT_MEMSET:   return (int64_t)(uintptr_t)msvcrt_memset;
    case MSVCRT_EXIT:     return (int64_t)(uintptr_t)msvcrt_exit;
    case MSVCRT_ABORT:    return (int64_t)(uintptr_t)msvcrt_abort;
    case MSVCRT_INITTERM: return (int64_t)(uintptr_t)msvcrt_initterm;
    case MSVCRT_IOBFUNC:  return (int64_t)(uintptr_t)msvcrt_iob_func;
    case MSVCRT_FPRINTF:  return (int64_t)(uintptr_t)msvcrt_fprintf;
    case MSVCRT_ATOI:     return (int64_t)(uintptr_t)msvcrt_atoi;
    case MSVCRT_FFLUSH:   return (int64_t)(uintptr_t)msvcrt_fflush;
    case MSVCRT_REALLOC:  return (int64_t)(uintptr_t)msvcrt_realloc;
    case MSVCRT_MEMMOVE:  return (int64_t)(uintptr_t)msvcrt_memmove;
    case MSVCRT_STRCAT:   return (int64_t)(uintptr_t)msvcrt_strcat;
    case MSVCRT_STRCHR:   return (int64_t)(uintptr_t)msvcrt_strchr;
    case MSVCRT_STRRCHR:  return (int64_t)(uintptr_t)msvcrt_strrchr;
    case MSVCRT_STRTOL:   return (int64_t)(uintptr_t)msvcrt_strtol;
    case MSVCRT_ISDIGIT:  return (int64_t)(uintptr_t)msvcrt_isdigit;
    case MSVCRT_ISALPHA:  return (int64_t)(uintptr_t)msvcrt_isalpha;
    case MSVCRT_ISSPACE:  return (int64_t)(uintptr_t)msvcrt_isspace;
    case MSVCRT_TOUPPER:  return (int64_t)(uintptr_t)msvcrt_toupper;
    case MSVCRT_TOLOWER_F: return (int64_t)(uintptr_t)msvcrt_tolower;
    case MSVCRT_SETUSERMATHERR: return (int64_t)(uintptr_t)msvcrt_setusermatherr;
    case MSVCRT_C_SPECIFIC_HANDLER: return (int64_t)(uintptr_t)msvcrt_c_specific_handler;
    case MSVCRT_FMODE: return (int64_t)(uintptr_t)&msvcrt_fmode_var;
    case MSVCRT_COMMODE: return (int64_t)(uintptr_t)&msvcrt_commode_var;
    case MSVCRT_TERMINATE: return (int64_t)(uintptr_t)msvcrt_terminate_func;
    case MSVCRT_SET_APP_TYPE: return (int64_t)(uintptr_t)msvcrt_set_app_type;
    case MSVCRT_WGETMAINARGS: return (int64_t)(uintptr_t)msvcrt_wgetmainargs;
    case MSVCRT_AMSG_EXIT: return (int64_t)(uintptr_t)msvcrt_amsg_exit;
    case MSVCRT_XCPTFILTER: return (int64_t)(uintptr_t)msvcrt_xcptfilter;
    case MSVCRT_WCSNICMP: return (int64_t)(uintptr_t)msvcrt_wcsnicmp;
    case MSVCRT_WSYSTEM: return (int64_t)(uintptr_t)msvcrt_wsystem;
    case MSVCRT_WCSCAT_S: return (int64_t)(uintptr_t)msvcrt_wcscat_s;
    case MSVCRT_WCSCPY_S: return (int64_t)(uintptr_t)msvcrt_wcscpy_s;
    case MSVCRT_ULTOW: return (int64_t)(uintptr_t)msvcrt_ultow;
    case MSVCRT_SETLOCALE: return (int64_t)(uintptr_t)msvcrt_setlocale;
    case MSVCRT_CEXIT: return (int64_t)(uintptr_t)msvcrt_cexit;
    default: return 0;
  }
}
