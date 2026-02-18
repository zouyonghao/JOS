# Makefile for JOS user programs
# Builds user programs in SBF and Windows PE formats

# Toolchain
CC = gcc
LD = ld
ASM = as
PYTHON = python3

# Windows toolchain (via WSL)
WIN_CLANG = "/mnt/c/Program Files/LLVM/bin/clang.exe"
WIN_LLD_LINK = "/mnt/c/Program Files/LLVM/bin/lld-link.exe"
WIN_CLANG_WORKS := $(shell test -f $(WIN_CLANG) && echo yes || echo no)

# ============================================
# Flags for JOS SBF programs (Linux target)
# ============================================
CFLAGS = -m64 -ffreestanding -nostdlib -nostdinc \
         -fno-builtin -fno-stack-protector -mno-red-zone \
         -Wall -Wextra -I.

LDFLAGS = -nostdlib -Ttext 0x400000

# ============================================
# Flags for Windows PE programs
# ============================================
WIN_CFLAGS = --target=x86_64-windows-gnu -m64 -ffreestanding -nostdlib \
             -fno-builtin -fno-stack-protector -mno-red-zone \
             -Wall -Wextra -I.

# ============================================
# Source files
# ============================================
USER_C_SRCS = hello.c counter.c
USER_ASM_SRCS = lib/kernel_api.asm

# Object files
USER_C_OBJS = $(USER_C_SRCS:.c=.o)
USER_ASM_OBJS = $(USER_ASM_SRCS:.asm=.o)
USER_OBJS = $(USER_C_OBJS) $(USER_ASM_OBJS)

# Programs to build
PROGRAMS = hello.sbf counter.sbf
WIN_PROGRAMS = win_dual_hello.exe

# ============================================
# Phony targets
# ============================================
.PHONY: all all-win clean inspect help check-win-clang

# Default: build SBF programs
all: $(PROGRAMS)

# Build everything including Windows PE
all-all: $(PROGRAMS) $(WIN_PROGRAMS)

# Check Windows clang availability
check-win-clang:
ifeq ($(WIN_CLANG_WORKS),yes)
	@echo "Windows clang found: $(WIN_CLANG)"
else
	@echo "Windows clang NOT found at $(WIN_CLANG)"
	@echo "Windows PE builds will not work"
endif

# ============================================
# SBF (JOS native) build rules
# ============================================

# Build SBF from ELF
hello.sbf: hello.elf
	$(PYTHON) mksbf.py $< $@

counter.sbf: counter.elf
	$(PYTHON) mksbf.py $< $@

# Link ELF binary
hello.elf: hello.o lib/kernel_api.o
	$(LD) $(LDFLAGS) -o $@ $^

counter.elf: counter.o lib/kernel_api.o
	$(LD) $(LDFLAGS) -o $@ $^

# Compile C source files for Linux/ELF
%.o: %.c lib/kernel_api.h
	$(CC) $(CFLAGS) -c -o $@ $<

# Assemble ASM files for Linux/ELF
lib/kernel_api.o: lib/kernel_api.asm
	$(ASM) -o $@ $<

# ============================================
# Windows PE build rules (requires Windows clang via WSL)
# ============================================

# Windows PE: win_dual_hello.exe (uses kernel32.dll imports - runs on both Windows and JOS)
win_dual_hello.exe: win_dual_hello.obj
ifeq ($(WIN_CLANG_WORKS),yes)
	$(WIN_LLD_LINK) -out:$@ -entry:mainCRTStartup -subsystem:console $< -libpath:"C:\\Windows\\System32" kernel32.lib
else
	@echo "ERROR: Windows clang not found at $(WIN_CLANG)"
	@echo "Cannot build Windows PE binaries without Windows clang"
	@false
endif

win_dual_hello.obj: win_dual_hello.c
ifeq ($(WIN_CLANG_WORKS),yes)
	$(WIN_CLANG) $(WIN_CFLAGS) -c -o $@ $<
else
	@echo "ERROR: Windows clang not found at $(WIN_CLANG)"
	@false
endif

# ============================================
# Utility targets
# ============================================
clean:
	rm -f *.o lib/*.o *.elf $(PROGRAMS)
	rm -f *.obj $(WIN_PROGRAMS)

# Debug target to inspect ELF
inspect: hello.elf
	objdump -d $<
	readelf -S $<

# Inspect Windows PE
inspect-win: win_dual_hello.exe
	objdump -d $< | head -50
	objdump -x $< | head -30

# Test Windows PE builds
test-win: win_dual_hello.exe
	@echo "Windows PE binary built successfully:"
	@ls -la $<

# Help target
help:
	@echo "User Program Makefile"
	@echo ""
	@echo "Targets:"
	@echo "  all              - Build SBF programs (default)"
	@echo "  all-all          - Build SBF and Windows PE programs"
	@echo "  win_dual_hello.exe - Windows PE with kernel32.dll imports"
	@echo "  clean            - Remove build artifacts"
	@echo "  inspect          - Disassemble hello.elf"
	@echo "  inspect-win      - Inspect Windows PE binary"
	@echo "  test-win         - Build and verify Windows PE binary"
	@echo "  help             - Show this help message"
