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
- [ ] User mode (ring 3) with syscall/sysret interface
- [ ] Process abstraction (per-process page tables, register save/restore)
- [ ] Cooperative or preemptive scheduling (timer-driven context switch)
- [ ] ELF loader (load user programs from disk into user-mode pages)

## Infrastructure
- [x] Automated test harness (boot QEMU, capture serial output, check for PASS/FAIL)
- [x] CI build verification
