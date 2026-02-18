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
- **`kernel/Loader.java`** - SBF and PE executable loaders, Windows syscall emulation
- **`kernel/Shell.java`** - Command line interface and command handling
- **`kernel/Syscalls.java`** - System call handling (Linux-compatible + Windows emulation)

`JavaToLLVM.java` is a custom bytecode-to-LLVM-IR translator that replaces GraalVM. `runtime.c` implements native glue (port I/O, memory read/write, string helpers). `bootloader.asm` handles real-mode boot, E820 memory detection, paging setup, and long-mode transition. `constants.inc` defines shared assembly constants. `linker.ld` controls the memory layout. Build artifacts go to `obj/` and `build/BB.bin`; generated LLVM IR lands in `generated-llvm/`.

Test files are in `test/`:
- `test_boot.py` - Boot verification test
- `test_memory.py` - Virtual memory test (runs `vmtest` command)
- `test_command.py` - Command prompt readiness test
- `test_pe_loader.py` - Windows PE loader test
- `expect.py` - Expect-like test framework using QEMU monitor socket
- `run_tests.py` - Main test runner

## Translation Pipeline
The build compiles Java source to bytecode (`javac`), then `JavaToLLVM` translates `.class` files directly to LLVM IR (`.ll`), which `clang` compiles to object files. This avoids the complexity of GraalVM native-image. The translator supports only the bytecode subset used by kernel classes: static methods/fields, primitives (int, long, char, boolean), strings, and basic control flow. It maintains a symbolic operand stack and generates SSA-form LLVM IR with phi nodes at control flow merge points.

**Multi-class support**: The translator now handles multiple Java class files. All kernel class `<clinit>` methods are called at startup before `initFilesystem()` to ensure static arrays are properly initialized.

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

## Build, Test, and Development Commands
- `make` or `make BB.bin` — full build (assembler + translator + clang + link)
- `make clean && make` — clean rebuild
- `make disk` — build disk image with user programs (hello.sbf, counter.sbf, win_dual_hello.exe) embedded at 1MB offset
- `make qemu-disk` — boot with disk image (needed for `run` and `ls` shell commands)
- `make test` — run automated test suite (boot, memory, command, pe tests)
- `make test-boot` — run only boot test
- `make test-memory` — run only memory test
- `make test-command` — run only command test
- `make test-pe` — run only PE loader test
- `make test-verbose` — run tests with verbose output
- `./build_kernel_custom.sh` — just the Java->LLVM->object pipeline
- `javac JavaToLLVM.java && javac kernel/*.java && java JavaToLLVM kernel/*.class generated-llvm/Kernel.ll` — manual translator run

## Runtime Constraints & Known Limitations
- **No objects or arrays**: The translator handles only static methods/fields and primitives. No heap-allocated Java objects, no `new`, no arrays. Use `runtime.c` native methods for buffers.
- **No exception handling**: No try/catch/finally support.
- **Boolean parameters**: Booleans are `i1` in function signatures but `i32` on the operand stack; the translator handles the conversion automatically.
- **Long comparisons**: `lcmp` generates a `select` chain producing -1/0/1, then a branch. This works but is verbose.
- **Loop phi nodes**: Back-edge phi nodes (loops with values on the operand stack across iterations) are not supported. Loops must store values to locals before the back-edge. This is the default pattern javac generates.

## Design Philosophy
Always implement functionality in Java (in the `kernel/` package) whenever possible. Only fall back to `runtime.c` (C) or assembly when Java cannot express the operation — specifically for hardware I/O (`in`/`out` instructions), raw memory access, and CPU-level primitives. If a new feature can be built on top of existing native methods, write it in Java rather than adding new C code.

## Coding Style & Naming Conventions
Four-space indentation in Java, two spaces in C. Java types use UpperCamelCase with lowerCamelCase methods. Native methods declared in `kernel/Native.java` are implemented in `runtime.c` with mangled names (e.g., `kernel_Native_writeMemory_Long_Char`). All kernel module methods are prefixed with their module name (e.g., `kernel_Console_writeString`, `kernel_Memory_allocPage`). Assembly uses Intel syntax with inline comments.

## Testing & Validation Guidelines

### Automated Test Harness
Run `make test` to execute the automated test suite in `test/`:
- **Boot test** (`test/test_boot.py`): Verifies kernel boots and shows `> ` prompt
- **Memory test** (`test/test_memory.py`): Runs `vmtest` command and checks for "PASS: Virtual memory works!"
- **Command test** (`test/test_command.py`): Verifies kernel reaches command-ready state
- **PE loader test** (`test/test_pe_loader.py`): Tests loading Windows PE executables

Test commands:
- `make test` — Run all tests
- `make test-boot` — Run only boot test
- `make test-memory` — Run only memory test
- `make test-command` — Run only command test
- `make test-pe` — Run only PE loader test
- `make test-verbose` — Run tests with verbose output
- `python3 test/run_tests.py --help` — See test runner options

The test harness uses `qemu-system-x86_64 -nographic` with QEMU monitor socket (`sendkey` commands) for reliable keyboard input simulation.

### Testing User Programs

1. **Build the user program:**
   ```bash
   cd user && make
   ```

2. **Create disk image with the program:**
   ```bash
   make disk
   ```

3. **Run QEMU with the disk:**
   ```bash
   make qemu-disk
   ```

4. **In the kernel shell, run the program:**
   ```
   > ls
   hello.sbf (4200 bytes)
   counter.sbf (4168 bytes)
   win_dual_hello.exe (2048 bytes)
   > run hello.sbf
   Spawned thread 1
   > Hello from user program!
   > run win_dual_hello.exe
   Spawned thread 1
   > Hello from Windows binary!
   > ps
   TID  STATE
   0    RUNNING
   1    READY
   ```

The `run` command auto-detects file format (SBF or PE), loads the binary from the disk filesystem, spawns a new kernel thread, and returns immediately to the shell. The thread runs concurrently and is preempted by the timer. Use `ps` to list active threads.

### Manual Validation
Key things to verify after changes:
- `grep "phi" generated-llvm/Kernel.ll` — confirm phi nodes present at merge blocks
- `clang -c generated-llvm/Kernel.ll -o /dev/null` — verify LLVM IR is valid
- Boot in QEMU and check: memory init shows `freePages > 0`, VM test prints `PASS`, command prompt `>` appears, keyboard input works, spinner animates.

## Windows PE Support
JOS can load Windows PE executables that use kernel32.dll imports:
- PE format auto-detection in `run` command (checks DOS magic `MZ` and PE signature)
- PE loader parses headers, loads sections, and processes import tables
- kernel32.dll function emulation (GetStdHandle, WriteFile, ExitProcess)
- Test program: `win_dual_hello.exe` (runs on both Windows and JOS)

### kernel32.dll Import Table Support

The PE loader:
1. Parses the import table from the PE headers
2. Resolves kernel32.dll function imports (GetStdHandle, WriteFile, ExitProcess)
3. Creates emulation stubs that call into JOS kernel handlers
4. Patches the Import Address Table (IAT) with stub addresses

**Supported kernel32.dll Functions:**
| Function | Description |
|----------|-------------|
| GetStdHandle | Returns handle for STD_OUTPUT_HANDLE (-11) |
| WriteFile | Writes to console (handle 1) |
| ExitProcess | Terminates the current thread |

### Creating Windows PE for JOS

**Dual-Compatible Binary (recommended):**
```c
// win_dual_hello.c - Runs on both Windows and JOS
#include <windows.h>

void mainCRTStartup(void) {
    HANDLE hStdOut = GetStdHandle(STD_OUTPUT_HANDLE);
    const char* msg = "Hello from Windows binary!\r\n";
    DWORD written;
    WriteFile(hStdOut, msg, strlen(msg), &written, NULL);
    ExitProcess(0);
}
```

Build with Windows clang:
```bash
cd user
make win_dual_hello.exe
```

**Note**: The `mainCRTStartup` entry point must never return - use ExitProcess and mark with `__attribute__((noreturn))`.

### Testing PE Loader
```bash
# Via test suite
make test-pe

# Or manual test
make qemu-disk
# In JOS shell: run win_dual_hello.exe
```

The binary will also run on actual Windows:
```cmd
C:\> win_dual_hello.exe
Hello from Windows binary!
```

## Commit & Pull Request Guidelines
Concise, sentence-case subjects. Note the main module affected. Explain translator or bootloader changes in the body. Include QEMU boot evidence (console output) for changes affecting runtime behavior.
