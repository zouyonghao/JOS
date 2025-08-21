This project aims to build a OS with Java (mostly) and a runtime written in C and ASM.

## Simplified Compilation Process

This repository now contains a complete, self-contained copy of Graal VM with the necessary patches applied. The compilation process has been simplified:

### Prerequisites
- Java JDK 21 (with javac)
- GCC compiler
- GNU make
- qemu (for testing)

To install Java JDK on Ubuntu/Debian:
```bash
sudo apt install openjdk-21-jdk
```

### Compilation
The Graal source code is now embedded in this repository at `./graal/` with:
- Git metadata removed for a clean integration
- Stack overflow check patch already applied
- Build scripts updated to use the local copy

To compile:
```bash
make BB.bin
```

To run with qemu:
```bash
make qemu
```

### What's Changed
- **Graal Source**: Complete Graal VM source copied to `./graal/`
- **Patches Applied**: The `do_not_insert_stackoverflow_check.patch` is pre-applied
- **Build Scripts**: Updated `build_kernel.o.sh` and `use_graalvm.sh` to use local Graal
- **Self-Contained**: No need to install Graal separately in `~/graal`

The project now maintains its own copy of Graal VM, making it completely self-contained and independent of external Graal installations.
