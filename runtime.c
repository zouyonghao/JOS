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

// Standard C library stubs required by -Os optimizer (emits memcpy/memset/memmove calls)
void *memcpy(void *dst, const void *src, size_t n) {
  uint8_t *d = (uint8_t *)dst;
  const uint8_t *s = (const uint8_t *)src;
  for (size_t i = 0; i < n; i++) d[i] = s[i];
  return dst;
}

void *memset(void *dst, int c, size_t n) {
  uint8_t *d = (uint8_t *)dst;
  for (size_t i = 0; i < n; i++) d[i] = (uint8_t)c;
  return dst;
}

void *memmove(void *dst, const void *src, size_t n) {
  uint8_t *d = (uint8_t *)dst;
  const uint8_t *s = (const uint8_t *)src;
  if (d < s) {
    for (size_t i = 0; i < n; i++) d[i] = s[i];
  } else {
    for (size_t i = n; i > 0; i--) d[i-1] = s[i-1];
  }
  return dst;
}

// =============================================================================
// String operations (plain null-terminated C strings)
// Called by translator-generated invokevirtual on String objects
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
// ISR stub table accessor (array access that can't be expressed in Java)
// =============================================================================

extern void* isr_stub_table[];

int64_t kernel_Native_getIsrStubAddr_Int(int32_t idx) {
  return (int64_t)(uintptr_t)isr_stub_table[idx];
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
// Only functions that MUST stay in C are here (varargs, asm callbacks, data exports)

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

// --- CRT internals (must stay in C) ---

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

// _initterm: call array of function pointers (requires inline asm for Win64 callbacks)
static void msvcrt_initterm(void (**start)(void), void (**end)(void)) {
  while (start < end) {
    if (*start != NULL) {
      call_win64_void(*start);
    }
    start++;
  }
}

// Data variables for _fmode and _commode (addresses exported to PE)
static int msvcrt_fmode_var = 0;
static int msvcrt_commode_var = 0;

// Static data for __wgetmainargs (triple pointer output, static C data)
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

// _wcmdln data - pointer to wide command line
static wchar_t* msvcrt_wcmdln_var = NULL;

// _vsnwprintf implementation (requires C varargs)
static int msvcrt_vsnwprintf(wchar_t* buffer, size_t count, const wchar_t* format, va_list argptr) {
  // Simplified: just return 0 for now (no output)
  if (buffer && count > 0) buffer[0] = L'\0';
  return 0;
}

// =============================================================================
// MSVCRT Function Address Table
// Only functions that stay in C need entries here.
// Functions moved to Java are dispatched via int 0x80 stubs.
// =============================================================================

#define MSVCRT_PRINTF     1
#define MSVCRT_SPRINTF    2
#define MSVCRT_SNPRINTF   3
#define MSVCRT_INITTERM   19
#define MSVCRT_FPRINTF    21
#define MSVCRT_FMODE      37
#define MSVCRT_COMMODE    38
#define MSVCRT_WGETMAINARGS 41
#define MSVCRT_VSNWPRINTF 51
#define MSVCRT_WCMDLN     52

int64_t kernel_Native_getMsvcrtFuncAddr_Int(int32_t funcId) {
  switch (funcId) {
    case MSVCRT_PRINTF:   return (int64_t)(uintptr_t)msvcrt_printf;
    case MSVCRT_SPRINTF:  return (int64_t)(uintptr_t)msvcrt_sprintf;
    case MSVCRT_SNPRINTF: return (int64_t)(uintptr_t)msvcrt_snprintf;
    case MSVCRT_INITTERM: return (int64_t)(uintptr_t)msvcrt_initterm;
    case MSVCRT_FPRINTF:  return (int64_t)(uintptr_t)msvcrt_fprintf;
    case MSVCRT_FMODE:    return (int64_t)(uintptr_t)&msvcrt_fmode_var;
    case MSVCRT_COMMODE:  return (int64_t)(uintptr_t)&msvcrt_commode_var;
    case MSVCRT_WGETMAINARGS: return (int64_t)(uintptr_t)msvcrt_wgetmainargs;
    case MSVCRT_VSNWPRINTF: return (int64_t)(uintptr_t)msvcrt_vsnwprintf;
    case MSVCRT_WCMDLN:   return (int64_t)(uintptr_t)&msvcrt_wcmdln_var;
    default: return 0;
  }
}
