# Repository Guidelines

## Project Structure & Module Organization
JOS is a bare-metal x86_64 kernel written in Java. `Kernel.java` holds all kernel logic (VGA console, interrupt handling, memory management, virtual memory, heap allocator). `JavaToLLVM.java` is a custom bytecode-to-LLVM-IR translator that replaces GraalVM. `runtime.c` implements native glue (port I/O, memory read/write, string helpers). `bootloader.asm` handles real-mode boot, E820 memory detection, paging setup, and long-mode transition. `constants.inc` defines shared assembly constants. `linker.ld` controls the memory layout. Build artifacts go to `obj/` and `build/BB.bin`; generated LLVM IR lands in `generated-llvm/`.

## Translation Pipeline
The build compiles Java source to bytecode (`javac`), then `JavaToLLVM` translates `.class` files directly to LLVM IR (`.ll`), which `clang` compiles to object files. This avoids the complexity of GraalVM native-image. The translator supports only the bytecode subset used by `Kernel.java`: static methods/fields, primitives (int, long, char, boolean), strings, and basic control flow. It maintains a symbolic operand stack and generates SSA-form LLVM IR with phi nodes at control flow merge points.

## Key Architecture Details
- **Paging**: Bootloader identity-maps first 128MB using 2MB huge pages in the page directory. Page tables at `0x1000` (PML4), `0x2000` (PDPT), `0x3000` (PD with huge page entries).
- **Memory bitmap**: Starts at `0x100000` (1MB mark), tracks up to 4GB of physical pages.
- **Page allocator**: First-fit search through bitmap, pages start at index 1024 (4MB+).
- **Virtual memory**: `mapPage()` walks/allocates 4-level page tables to map arbitrary virtual-to-physical mappings.
- **Heap**: Bump allocator starting at 4MB virtual, allocates physical pages and maps them on demand. Used for thread stacks (16KB each) and program loading.
- **Interrupts**: IDT set up in `interrupts.asm`, dispatched to `Kernel.handleInterrupt()` in Java.
- **Threading**: Preemptive kernel threads with shared address space. Max 16 threads, round-robin scheduling every 10 timer ticks (~100ms). Thread RSP table at `0x880000` (fixed address for assembly access). Context switch in `interrupts.asm`: Java scheduler sets `ctxLoadRSP`/`ctxSaveRSPAddr` globals, assembly swaps RSP after `interrupt_dispatch` returns. New threads start via fake interrupt frame on heap-allocated 16KB stacks. Thread 0 = shell (boot stack at 0x200000 downward).
- **E820 memory map**: Entry count at `0x500`, buffer at `0x504`. These must stay below `0x7C00` (bootloader load address) to avoid overlapping kernel code.

## Build, Test, and Development Commands
- `make` or `make BB.bin` — full build (assembler + translator + clang + link)
- `make clean && make` — clean rebuild
- `make qemu` — boot in QEMU with `isa-debug-exit` device (uses curses display)
- `make disk` — build disk image with user programs (hello.sbf, counter.sbf) embedded at 1MB offset
- `make qemu-disk` — boot with disk image (needed for `run` and `ls` shell commands)
- `./build_kernel_custom.sh` — just the Java->LLVM->object pipeline
- `javac JavaToLLVM.java && javac Kernel.java && java JavaToLLVM Kernel.class generated-llvm/Kernel.ll` — manual translator run
- `timeout 10 make qemu` — automated boot test

## Runtime Constraints & Known Limitations
- **No objects or arrays**: The translator handles only static methods/fields and primitives. No heap-allocated Java objects, no `new`, no arrays. Use `runtime.c` native methods for buffers.
- **No exception handling**: No try/catch/finally support.
- **Boolean parameters**: Booleans are `i1` in function signatures but `i32` on the operand stack; the translator handles the conversion automatically.
- **Long comparisons**: `lcmp` generates a `select` chain producing -1/0/1, then a branch. This works but is verbose.
- **Loop phi nodes**: Back-edge phi nodes (loops with values on the operand stack across iterations) are not supported. Loops must store values to locals before the back-edge. This is the default pattern javac generates.

## Design Philosophy
Always implement functionality in Java (`Kernel.java`) whenever possible. Only fall back to `runtime.c` (C) or assembly when Java cannot express the operation — specifically for hardware I/O (`in`/`out` instructions), raw memory access, and CPU-level primitives. If a new feature can be built on top of existing native methods, write it in Java rather than adding new C code.

## Coding Style & Naming Conventions
Four-space indentation in Java, two spaces in C. Java types use UpperCamelCase with lowerCamelCase methods. Native methods declared in `Kernel.java` are implemented in `runtime.c` with mangled names (e.g., `Kernel_writeMemory_Long_Char`). Assembly uses Intel syntax with inline comments.

## Testing & Validation Guidelines

### Automated Test Harness
Run `make test` to execute the automated test suite in `test/`:
- **Boot test** (`test/test_boot.py`): Verifies kernel boots and shows `> ` prompt
- **Memory test** (`test/test_memory.py`): Runs `vmtest` command and checks for "PASS: Virtual memory works!"
- **Command test** (`test/test_command.py`): Verifies kernel reaches command-ready state

Test commands:
- `make test` — Run all tests with build

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
   > run hello.sbf
   Spawned thread 1
   > Hello from user program!
   > run counter.sbf
   Spawned thread 1
   > Thread 1: count 0
   Thread 1: count 1
   ...
   > ps
   TID  STATE
   0    RUNNING
   1    READY
   ```

The `run` command loads the SBF binary from the disk filesystem, spawns a new kernel thread, and returns immediately to the shell. The thread runs concurrently and is preempted by the timer. Use `ps` to list active threads.
- `make test-verbose` — Run tests with verbose output
- `make test-boot` — Run only boot test
- `make test-memory` — Run only memory test
- `make test-command` — Run only command test
- `python3 test/run_tests.py --help` — See test runner options

The test harness uses `qemu-system-x86_64 -nographic` to capture serial output via COM1 and verifies expected patterns appear.

### Manual Validation
Key things to verify after changes:
- `grep "phi" generated-llvm/Kernel.ll` — confirm phi nodes present at merge blocks
- `clang -c generated-llvm/Kernel.ll -o /dev/null` — verify LLVM IR is valid
- Boot in QEMU and check: memory init shows `freePages > 0`, VM test prints `PASS`, command prompt `>` appears, keyboard input works, spinner animates.

## Commit & Pull Request Guidelines
Concise, sentence-case subjects. Note the main module affected. Explain translator or bootloader changes in the body. Include QEMU boot evidence (console output) for changes affecting runtime behavior.
