# Repository Guidelines

## Project Structure & Module Organization
JOS is a bare-metal x86_64 kernel written in Java. The kernel is organized into modular Java classes in the `kernel/` directory:

- **`kernel/Core.java`** - Kernel entry point (`startKernel`) and main shell loop
- **`kernel/Native.java`** - Native method declarations (bridge to runtime.c)
- **`kernel/Console.java`** - VGA console output, serial output, formatting functions
- **`kernel/Memory.java`** - Physical memory manager (E820, bitmap), virtual memory/paging, heap allocator
- **`kernel/Interrupts.java`** - Interrupt handling (IDT, PIC, PIT, keyboard)
- **`kernel/Threading.java`** - Thread management and round-robin scheduler
- **`kernel/Disk.java`** - ATA PIO disk driver
- **`kernel/Filesystem.java`** - Simple Flat Read-Only Filesystem (SFROFS)
- **`kernel/Loader.java`** - SBF and PE executable loaders, Windows API emulation (kernel32 + msvcrt)
- **`kernel/Shell.java`** - Command line interface and command handling
- **`kernel/Syscalls.java`** - System call handling (Linux-compatible + Windows emulation)

`JavaToLLVM.java` is a custom bytecode-to-LLVM-IR translator that replaces GraalVM. `runtime.c` implements native glue (port I/O, memory read/write, string helpers, msvcrt function implementations). `bootloader.asm` handles real-mode boot, E820 memory detection, paging setup, and long-mode transition. `constants.inc` defines shared assembly constants. `linker.ld` controls the memory layout. Build artifacts go to `obj/` and `build/BB.bin`; generated LLVM IR lands in `generated-llvm/`.

### User Programs (`user/`)
- **SBF format**: `hello.c` (hello world), `counter.c` (threading test) — built with cross-compiler, converted via `mksbf.py`
- **Windows PE format**: `win_dual_hello.c` (kernel32 WriteFile), `win_memtest.c` (HeapAlloc/HeapFree), `win_printf.c` (msvcrt printf/malloc), `win_threads.c` (CreateThread), `win_fileio.c` (CreateFileA/ReadFile), `win_echo.c` (stdin/stdout)
- **Real Windows binary**: `help.exe` (from `C:\Windows\System32\help.exe`, 32KB PE32+)

### Test Files (`test/`)
- `run_tests.py` — Main test harness runner (supports `--test` filter)
- `test_boot.py` — Boot verification (checks for `> ` prompt)
- `test_memory.py` — Virtual memory test (runs `vmtest` command)
- `test_command.py` — Shell command readiness test
- `test_pe_loader.py` — Core PE loader test (win_dual_hello.exe)
- `test_pe_extended.py` — Extended PE tests (win_memtest, win_printf, win_threads, win_fileio)
- `expect.py` — QEMU expect-like test framework using monitor socket
- `run_help.py` — Manual test runner for help.exe
- `debug_pe.py` — PE loader debugging/inspection tool

## Translation Pipeline
The build compiles Java source to bytecode (`javac`), then `JavaToLLVM` translates `.class` files directly to LLVM IR (`.ll`), which `clang` compiles to object files. This avoids the complexity of GraalVM native-image. The translator supports only the bytecode subset used by kernel classes: static methods/fields, primitives (int, long, char, boolean), strings, and basic control flow. It maintains a symbolic operand stack and generates SSA-form LLVM IR with phi nodes at control flow merge points.

**Multi-class support**: The translator handles multiple Java class files. All kernel class `<clinit>` methods are called at startup before `initFilesystem()` to ensure static arrays are properly initialized.

**Symbol naming**: All kernel symbols use the format `kernel_ClassName_methodName` (e.g., `kernel_Console_writeString_String`).

## Key Architecture Details
- **Paging**: Bootloader identity-maps first 128MB using 2MB huge pages in the page directory. Page tables at `0x1000` (PML4), `0x2000` (PDPT), `0x3000` (PD with huge page entries).
- **Memory bitmap**: Starts at `0x100000` (1MB mark), tracks up to 4GB of physical pages.
- **Page allocator**: First-fit search through bitmap, pages start at index 1024 (4MB+).
- **Virtual memory**: `mapPage()` walks/allocates 4-level page tables to map arbitrary virtual-to-physical mappings.
- **Heap**: Bump allocator starting at 4MB virtual, allocates physical pages and maps them on demand. Used for thread stacks (16KB each) and program loading.
- **Interrupts**: IDT set up in `interrupts.asm`, dispatched to `kernel.Interrupts.handleInterrupt()` in Java.
- **Threading**: Preemptive kernel threads with shared address space. Max 16 threads, round-robin scheduling every 10 timer ticks (~100ms). Thread RSP table at `0x880000` (fixed address for assembly access). Context switch in `interrupts.asm`: Java scheduler (`kernel.Threading`) sets `ctxLoadRSP`/`ctxSaveRSPAddr` globals, assembly swaps RSP after `interrupt_dispatch` returns. New threads start via fake interrupt frame on heap-allocated 16KB stacks. Thread 0 = shell (boot stack at 0x200000 downward).
- **Thread termination**: When a thread exits via `sys_exit`, `terminateCurrentThread()` marks it as TERMINATED, frees the stack, and switches to another ready thread.
- **E820 memory map**: Entry count at `0x500`, buffer at `0x504`. These must stay below `0x7C00` (bootloader load address) to avoid overlapping kernel code.

## Windows PE Loader Architecture

### Overview
JOS can load and run Windows PE32+ (x86_64) executables, including real Windows system binaries. The PE loader auto-detects format via DOS magic (`MZ`) and PE signature, parses COFF/optional headers, loads sections, processes base relocations, and resolves imports.

### Import Resolution
- **kernel32.dll** (60 functions): Emulated in Java (`Loader.handleKernel32Call`). Each import gets an `int 0x80` stub that traps into the kernel. The interrupt handler reads the function ID from the stub and dispatches to Java emulation code.
- **msvcrt.dll** (50 functions): Implemented in C (`runtime.c`). Each import gets a direct-call stub that translates Win64 calling convention (RCX, RDX, R8, R9) to SysV (RDI, RSI, RDX, RCX) and calls the C implementation. Stubs save/restore RDI and RSI (callee-saved in Win64) around the call.
- **ntdll.dll**: Mapped to kernel32 emulation.
- **api-ms-win-crt-***: UCRT forwarders mapped to msvcrt.
- **Data imports** (`_fmode`, `_commode`): Return raw variable addresses instead of call stubs.

### Function Name Resolution
Import functions are identified by prefix character matching (not full string comparison) since the Java translator doesn't support general string operations. Characters at specific positions disambiguate similar names (e.g., `WriteFile` vs `WriteConsoleW` distinguished by character at position 5).

### Key Implementation Details
- **Relocation before imports**: Relocations must be processed BEFORE import table (standard Windows loader order). Reversing this corrupts IAT entries.
- **Calling convention stubs**: Direct call stubs use `push rdi; push rsi; sub rsp,8; [translate regs]; call rax; add rsp,8; pop rsi; pop rdi; ret`. Using `jmp` (tail call) instead of `call`+`ret` corrupts Win64 callee-saved registers.
- **TEB/PEB**: Fake Thread Environment Block at GS_BASE for Windows TLS access (`gs:[0x30]` self-pointer, `gs:[0x60]` PEB pointer).
- **Thread trampolines**: Heap-allocated machine code that sets up Win64 calling convention, calls the PE entry point, then calls ExitProcess on return.
- **Handle table**: Array-based with type discrimination (HANDLE_CONSOLE, HANDLE_FILE, HANDLE_THREAD).

### Supported kernel32.dll Functions (60)
GetStdHandle, WriteFile, WriteConsoleW, ExitProcess, TerminateProcess, GetLastError, SetLastError, GetProcessHeap, HeapAlloc, HeapFree, HeapSetInformation, LocalAlloc, LocalFree, VirtualAlloc, VirtualFree, ReadFile, CloseHandle, CreateFileA, CreateThread, Sleep, WaitForSingleObject, GetCommandLineA, GetEnvironmentStringsA, FreeEnvironmentStringsA, GetConsoleMode, SetConsoleMode, GetConsoleOutputCP, GetModuleHandleA, GetModuleHandleW, GetModuleFileNameA, GetCurrentProcessId, GetCurrentThreadId, GetCurrentProcess, IsDebuggerPresent, GetTickCount, QueryPerformanceCounter, QueryPerformanceFrequency, FlushFileBuffers, SetFilePointer, GetFileSize, GetFileType, SetHandleCount, GetStartupInfoA, GetSystemTimeAsFileTime, MultiByteToWideChar, WideCharToMultiByte, GetStringTypeW, FormatMessageW, SetThreadUILanguage, InitializeCriticalSection, DeleteCriticalSection, EnterCriticalSection, LeaveCriticalSection, TlsAlloc, TlsGetValue, TlsSetValue, UnhandledExceptionFilter, RtlVirtualUnwind, RtlLookupFunctionEntry, RtlCaptureContext

### Supported msvcrt.dll Functions (50)
printf, sprintf, snprintf, fprintf, puts, putchar, malloc, free, calloc, realloc, strlen, strcpy, strncpy, strcmp, strncmp, strcat, strchr, strrchr, strtol, memcmp, memcpy, memset, memmove, exit, abort, _cexit, atoi, fflush, isdigit, isalpha, isspace, toupper, tolower, setlocale, _initterm, __iob_func, __wgetmainargs, __setusermatherr, __C_specific_handler, __set_app_type, _amsg_exit, _XcptFilter, _fmode (data), _commode (data), ?terminate@@YAXXZ, _wcsnicmp, _wsystem, wcscat_s, wcscpy_s, _ultow

## Build, Test, and Development Commands
- `make` or `make BB.bin` — full build (assembler + translator + clang + link)
- `make clean && make` — clean rebuild
- `make disk` — build disk image with all user programs embedded at 1MB offset
- `make qemu-disk` — boot with disk image (needed for `run` and `ls` shell commands)
- `make test` — run 4 core tests (boot, memory, command, pe)
- `make test-boot` — run only boot test
- `make test-memory` — run only memory test
- `make test-command` — run only command test
- `make test-pe` — run only PE loader test
- `make test-verbose` — run tests with verbose output
- `python3 test/test_pe_extended.py build/BB.bin` — run 4 extended PE tests
- `./build_kernel_custom.sh` — just the Java->LLVM->object pipeline
- `javac JavaToLLVM.java && javac kernel/*.java && java JavaToLLVM kernel/*.class generated-llvm/Kernel.ll` — manual translator run

## Runtime Constraints & Known Limitations
- **No objects or arrays**: The translator handles only static methods/fields and primitives. No heap-allocated Java objects, no `new`, no arrays. Use `runtime.c` native methods for buffers.
- **No exception handling**: No try/catch/finally support.
- **Boolean parameters**: Booleans are `i1` in function signatures but `i32` on the operand stack; the translator handles the conversion automatically.
- **Long comparisons**: `lcmp` generates a `select` chain producing -1/0/1, then a branch. This works but is verbose.
- **Loop phi nodes**: Back-edge phi nodes (loops with values on the operand stack across iterations) are not supported. Loops must store values to locals before the back-edge. This is the default pattern javac generates.
- **No varargs**: Java translator cannot handle variadic functions. Printf/sprintf must be implemented in C (runtime.c).

## Design Philosophy
Always implement functionality in Java (in the `kernel/` package) whenever possible. Only fall back to `runtime.c` (C) or assembly when Java cannot express the operation — specifically for hardware I/O (`in`/`out` instructions), raw memory access, and CPU-level primitives. If a new feature can be built on top of existing native methods, write it in Java rather than adding new C code.

## Coding Style & Naming Conventions
Four-space indentation in Java, two spaces in C. Java types use UpperCamelCase with lowerCamelCase methods. Native methods declared in `kernel/Native.java` are implemented in `runtime.c` with mangled names (e.g., `kernel_Native_writeMemory_Long_Char`). All kernel module methods are prefixed with their module name (e.g., `kernel_Console_writeString`, `kernel_Memory_allocPage`). Assembly uses Intel syntax with inline comments.

## Testing & Validation Guidelines

### Automated Test Harness
Run `make test` to execute the 4 core tests, and `python3 test/test_pe_extended.py` for 4 extended PE tests:

**Core tests** (`make test`):
- **Boot test** (`test_boot.py`): Verifies kernel boots and shows `> ` prompt
- **Memory test** (`test_memory.py`): Runs `vmtest` command and checks for "PASS: Virtual memory works!"
- **Command test** (`test_command.py`): Verifies kernel reaches command-ready state
- **PE loader test** (`test_pe_loader.py`): Tests loading Windows PE executables (win_dual_hello.exe)

**Extended PE tests** (`test_pe_extended.py`):
- **win_memtest**: HeapAlloc/HeapFree with verification pattern
- **win_printf**: msvcrt printf with format specifiers
- **win_threads**: CreateThread and WaitForSingleObject
- **win_fileio**: CreateFileA, ReadFile, GetFileSize

The test harness uses `qemu-system-x86_64 -nographic` with QEMU monitor socket (`sendkey` commands) for reliable keyboard input simulation.

### Testing User Programs

1. Build and create disk: `make disk`
2. Run QEMU: `make qemu-disk`
3. In the kernel shell:
   ```
   > ls
   hello.sbf (4200 bytes)
   counter.sbf (4168 bytes)
   win_dual_hello.exe (2048 bytes)
   win_memtest.exe (2048 bytes)
   win_printf.exe (2048 bytes)
   win_threads.exe (2048 bytes)
   win_fileio.exe (2048 bytes)
   help.exe (32768 bytes)
   > run help.exe
   Spawned thread 1
   > Unable to get Message-Not Found message
   ```

The `run` command auto-detects file format (SBF or PE), loads the binary from the disk filesystem, spawns a new kernel thread, and returns immediately to the shell. The thread runs concurrently and is preempted by the timer. Use `ps` to list active threads.

### Manual Validation
Key things to verify after changes:
- `grep "phi" generated-llvm/Kernel.ll` — confirm phi nodes present at merge blocks
- `clang -c generated-llvm/Kernel.ll -o /dev/null` — verify LLVM IR is valid
- Boot in QEMU and check: memory init shows `freePages > 0`, VM test prints `PASS`, command prompt `>` appears, keyboard input works, spinner animates.

## Commit & Pull Request Guidelines
Concise, sentence-case subjects. Note the main module affected. Explain translator or bootloader changes in the body. Include QEMU boot evidence (console output) for changes affecting runtime behavior.
