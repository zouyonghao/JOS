# Makefile for JOS user programs
# Builds user programs and converts them to SBF format

# Toolchain
CC = gcc
LD = ld
ASM = as
PYTHON = python3

# Flags
# -m64: 64-bit code
# -ffreestanding: no standard library
# -nostdlib: don't link with standard library
# -nostdinc: don't search standard include paths
# -fno-builtin: don't recognize built-in functions
# -fno-stack-protector: disable stack protector
# -mno-red-zone: disable red zone (System V AMD64 ABI)
# -Wall: enable all warnings
CFLAGS = -m64 -ffreestanding -nostdlib -nostdinc \
         -fno-builtin -fno-stack-protector -mno-red-zone \
         -Wall -Wextra -I.

# Linker flags
# -nostdlib: don't use standard system libraries
# -Ttext 0x400000: set code start address (user space)
LDFLAGS = -nostdlib -Ttext 0x400000

# Source files
USER_C_SRCS = hello.c
USER_ASM_SRCS = lib/kernel_api.asm

# Object files
USER_C_OBJS = $(USER_C_SRCS:.c=.o)
USER_ASM_OBJS = $(USER_ASM_SRCS:.asm=.o)
USER_OBJS = $(USER_C_OBJS) $(USER_ASM_OBJS)

# Programs to build
PROGRAMS = hello.sbf

.PHONY: all clean inspect help

all: $(PROGRAMS)

# Build SBF from ELF
hello.sbf: hello.elf
	$(PYTHON) mksbf.py $< $@

# Link ELF binary
hello.elf: $(USER_OBJS)
	$(LD) $(LDFLAGS) -o $@ $^

# Compile C source files
%.o: %.c lib/kernel_api.h
	$(CC) $(CFLAGS) -c -o $@ $<

# Assemble ASM files (using GNU as)
lib/kernel_api.o: lib/kernel_api.asm
	$(ASM) -o $@ $<

clean:
	rm -f $(USER_OBJS) hello.elf $(PROGRAMS)

# Debug target to inspect ELF
inspect: hello.elf
	objdump -d $<
	readelf -S $<

# Help target
help:
	@echo "User Program Makefile"
	@echo ""
	@echo "Targets:"
	@echo "  all     - Build all user programs (default)"
	@echo "  clean   - Remove build artifacts"
	@echo "  inspect - Disassemble hello.elf for debugging"
	@echo "  help    - Show this help message"
