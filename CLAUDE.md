# CLAUDE.md

## Project Overview
JOS is a bare-metal x86_64 OS kernel written in Java, compiled via a custom Java bytecode-to-LLVM-IR translator (`JavaToLLVM.java`). It runs directly on hardware (or QEMU) with no JVM.

## Build & Run
```bash
make                # full build → build/BB.bin
make disk           # build + create disk image with user programs
make qemu-disk      # boot with disk (needed for ls/run commands)
make test           # run automated test suite (boot, memory, command, pe)
make test-pe        # run PE loader test only
cd user && make     # build user programs separately
```

## Key Files
- `kernel/` — modular kernel classes (Core, Console, Memory, Threading, Interrupts, Disk, Filesystem, Loader, Shell, Syscalls, Native)
- `JavaToLLVM.java` — bytecode-to-LLVM-IR translator (supports multiple .class files)
- `runtime.c` — native glue (port I/O, memory access, string helpers)
- `interrupts.asm` — IDT, ISR stubs, context switch assembly
- `bootloader.asm` — real-mode boot, E820, paging, long-mode entry
- `constants.inc` — shared assembly constants (segment selectors, memory addresses)
- `build_kernel_custom.sh` — Java→LLVM→object compilation pipeline
- `test/` — automated test suite
- `user/` — user programs (hello.c, counter.c, win_hello.exe) with kernel API library

## Architecture
- **Threading**: Preemptive kernel threads, round-robin scheduler (10 ticks / ~100ms). Context switch via RSP swap in `interrupts.asm`. Thread RSP table at `0x880000`. Max 16 threads. Thread 0 = shell. Thread cleanup on exit frees stack memory.
- **Memory**: E820 at `0x500`, bitmap at `0x100000`, heap at 4MB+, identity-mapped first 128MB with 2MB huge pages.
- **Syscalls**: `int $0x80` with vector 128. SYS_PRINT=1, SYS_EXIT=2, SYS_YIELD=3, SYS_GETPID=4.
- **User programs**: SBF format and Windows PE format, loaded from embedded filesystem at 1MB disk offset.
- **PE Loader**: Auto-detects PE format, parses DOS/PE headers, loads sections, emulates Windows syscalls (NtWriteFile, NtClose, NtTerminateProcess).

## Critical Constraints
- E820 buffer addresses (`0x500`/`0x504`) must stay below `0x7C00` — kernel code loads at `0x7E00+`
- clang flags must include `-mno-red-zone -mno-mmx -mno-sse -mno-sse2` for kernel code
- Java translator supports only static methods/fields, primitives, and basic control flow — no objects, arrays (use runtime.c), or exceptions
- All kernel classes need `<clinit>` called before use (static initialization)
- Implement features in Java whenever possible; only use C/asm for hardware I/O and CPU primitives

## Shell Commands
`help`, `memstat`, `vmtest`, `ls`, `run <file>`, `ps`, `peek <addr>`, `poke <addr> <val>`, `cat <file>`, `stat <file>`, `history`, `shutdown`, `reboot`

## Testing
```bash
make test           # run all tests
make test-boot      # boot test only
make test-memory    # memory/vmtest only
make test-command   # command test only
make test-pe        # PE loader test only
make test-verbose   # verbose output
```
