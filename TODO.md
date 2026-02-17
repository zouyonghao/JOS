# JOS TODO

## Translator (JavaToLLVM.java)
- [x] Array support (`newarray`, `iastore`, `iaload`, `arraylength`, `bastore`, `baload`, etc.)
- [x] Multi-class support (translate multiple .class files, resolve cross-class references)
- [x] `tableswitch` / `lookupswitch` opcodes (for switch statements)
- [x] `ineg` / `lneg` opcodes
- [x] Constant pool `Integer` entries used by `ldc` (currently only handles strings)

## Kernel (Kernel.java)
- [x] Proper heap allocator with free (replace bump allocator)
- [x] More shell commands (memstat, dump, peek, poke)
- [x] Serial port output (COM1) for better QEMU debugging
- [x] ATA PIO disk read support (port I/O based, implementable in Java)
- [x] Simple read-only filesystem (load data from disk)

## OS Features
- [x] Preemptive multithreading (kernel threads, round-robin scheduler, timer-driven context switch)
- [x] Thread management syscalls (yield, getpid, exit with cleanup)
- [x] Shell `ps` command (list active threads with TID and state)
- [x] Simple binary loader (load user programs from disk)
- [ ] User mode (ring 3) with syscall/sysret interface
- [ ] Process abstraction (per-process page tables, register save/restore)
- [ ] ELF loader (proper ELF format support)

## Infrastructure
- [x] Automated test harness (boot QEMU, capture serial output, check for PASS/FAIL)
