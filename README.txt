# JOS — A Java OS Kernel

A bare-metal x86_64 operating system kernel written in Java, compiled to native code via a custom Java bytecode-to-LLVM-IR translator. Runs directly on hardware or QEMU with no JVM.

## Features

- **Java kernel**: Core OS logic written in Java, translated to LLVM IR at build time
- **Custom translator**: `JavaToLLVM.java` converts Java bytecode directly to LLVM IR (no GraalVM)
- **Preemptive multithreading**: Round-robin scheduler with timer-driven context switching, up to 16 kernel threads
- **Memory management**: E820 detection, bitmap physical allocator, 4-level paging, heap allocator
- **VGA console**: 80x25 text mode with keyboard input and serial output
- **Disk & filesystem**: ATA PIO driver with embedded read-only filesystem
- **Windows PE loader**: Loads and runs Windows PE32+ (x86_64) executables with kernel32.dll (60 functions) and msvcrt.dll (50 functions) emulation — runs real Windows binaries like `help.exe`
- **Shell**: Interactive command line with `ls`, `run`, `ps`, `peek`, `poke`, `cat`, `memstat`, and more

## Build & Run

Requirements: `gcc`, `clang`, `javac`, `nasm`/`as`, `qemu-system-x86_64`, `python3`

```bash
make                # build kernel → build/BB.bin
make disk           # build + embed user programs into disk image
make qemu-disk      # boot in QEMU with disk image
make test           # run automated test suite
```

## Architecture

```
bootloader.asm          → Real mode boot, E820, paging, long mode entry
kernel/*.java           → Kernel modules (Core, Console, Memory, Threading, ...)
JavaToLLVM.java         → Bytecode-to-LLVM-IR translator
runtime.c               → Native glue (port I/O, memory access, msvcrt emulation)
interrupts.asm          → IDT, ISR stubs, context switch, syscall dispatch
user/                   → User programs (SBF and Windows PE format)
```

### Build Pipeline

```
javac kernel/*.java → JavaToLLVM → .ll (LLVM IR) → clang → .o → ld → BB.bin
```

### User Programs

| Program | Format | Description |
|---------|--------|-------------|
| hello.sbf | SBF | Hello world via kernel syscalls |
| counter.sbf | SBF | Multithreading counter demo |
| win_dual_hello.exe | PE | Dual-compatible Windows/JOS binary |
| win_memtest.exe | PE | HeapAlloc/HeapFree test |
| win_printf.exe | PE | msvcrt printf/malloc test |
| win_threads.exe | PE | CreateThread/WaitForSingleObject test |
| win_fileio.exe | PE | CreateFileA/ReadFile test |
| help.exe | PE | Real Windows system binary (from System32) |

## Testing

```bash
make test           # 4 core tests (boot, memory, command, pe)
make test-verbose   # verbose output
python3 test/test_pe_extended.py build/BB.bin   # 4 extended PE tests
```

## Key Constraints

- Java translator supports only static methods/fields, primitives, and basic control flow — no objects, arrays, or exceptions
- Kernel code requires `-mno-red-zone -mno-mmx -mno-sse -mno-sse2` compiler flags
- All kernel classes need `<clinit>` called before use (static initialization)
