CC = gcc
CFLAGS = -Wall -Wextra -Wpedantic -std=gnu99 -ffreestanding
OPTFLAGS = -O0
64BITFLAGS = -mno-red-zone -mno-mmx -mno-sse -mno-sse2

AS = as
ASFLAGS = -msyntax=intel -mnaked-reg

LINKER = linker.ld
LDFLAGS= -lgcc -nostdlib

# QEMU with isa-debug-exit device for reliable shutdown support
QEMUCMD = qemu-system-x86_64
QEMUFLAGS = -nographic -display curses -monitor none -device isa-debug-exit,iobase=0xf4,iosize=0x04 -no-reboot -D qemu_debug.log -drive format=raw,file=

# QEMU without debug-exit (ACPI shutdown may not work without proper hardware init)
QEMUFLAGS_NOEXIT = -nographic -display curses -monitor none -d guest_errors -d int -no-reboot -D qemu_debug.log -drive format=raw,file=

OBJDIR = ./obj
OBJLIST = ./obj/bootloader.o ./obj/Kernel.o ./obj/runtime.o ./obj/interrupts_asm.o ./obj/win_syscall_handler.o
BUILDDIR = ./build

BB.bin : $(BUILDDIR) $(OBJLIST)
	$(CC) $(OBJLIST) -o $(BUILDDIR)/BB.bin $(CFLAGS) $(LDFLAGS) $(64BITFLAGS) $(DIRECTIVES) -T $(LINKER) $(OPTFLAGS)

qemu: BB.bin
	$(QEMUCMD) $(QEMUFLAGS)$(BUILDDIR)/BB.bin

qemu-noexit: BB.bin
	$(QEMUCMD) $(QEMUFLAGS_NOEXIT)$(BUILDDIR)/BB.bin

$(OBJDIR)/Kernel.o: kernel/*.java JavaToLLVM.java
	./build_kernel_custom.sh

$(OBJDIR)/runtime.o: runtime.c $(OBJDIR)
	$(CC) -c runtime.c -o obj/runtime.o $(CFLAGS) $(OPTFLAGS) -fno-pic

$(OBJDIR)/bootloader.o : bootloader.asm $(OBJDIR)
	$(AS) bootloader.asm -o $(OBJDIR)/bootloader.o $(ASFLAGS)

$(OBJDIR)/interrupts_asm.o : interrupts.asm $(OBJDIR)
	$(AS) interrupts.asm -o $(OBJDIR)/interrupts_asm.o

$(OBJDIR)/win_syscall_handler.o : win_syscall_handler.asm $(OBJDIR)
	$(AS) win_syscall_handler.asm -o $(OBJDIR)/win_syscall_handler.o

$(BUILDDIR) :
	test ! -d $(BUILDDIR) && mkdir $(BUILDDIR)

$(OBJDIR) :
	test ! -d $(OBJDIR) && mkdir $(OBJDIR)

clean :
	rm -rf generated-llvm
	rm -rf $(OBJLIST)
	rm -f $(BUILDDIR)/BB.bin
	rm -f *.class
	rm -f kernel/*.class

# Run automated test harness
test: disk
	python3 test/run_tests.py --kernel $(BUILDDIR)/BB.bin

# Run tests with verbose output
test-verbose: disk
	python3 test/run_tests.py --kernel $(BUILDDIR)/BB.bin -v

# Run specific tests
test-boot: disk
	python3 test/run_tests.py --kernel $(BUILDDIR)/BB.bin --test boot

test-memory: disk
	python3 test/run_tests.py --kernel $(BUILDDIR)/BB.bin --test memory

test-command: disk
	python3 test/run_tests.py --kernel $(BUILDDIR)/BB.bin --test command

test-pe: disk
	python3 test/run_tests.py --kernel $(BUILDDIR)/BB.bin --test pe

# Create disk image with user programs
disk: BB.bin
	python3 makedisk.py $(BUILDDIR)/disk.img user/hello.sbf user/counter.sbf user/win_dual_hello.exe
	@echo "Created $(BUILDDIR)/disk.img"
	# Embed filesystem into kernel disk image at 1MB offset
	python3 embed_fs.py $(BUILDDIR)/BB.bin $(BUILDDIR)/disk.img
	@echo "Embedded filesystem into $(BUILDDIR)/BB.bin"

# Run QEMU with disk image (for testing user programs)
# Filesystem is embedded in the kernel disk image at 1MB offset (sector 2048)
qemu-disk: disk
	$(QEMUCMD) $(QEMUFLAGS)$(BUILDDIR)/BB.bin

# Read filesystem from same drive (drive 0) at offset 2048 (1MB)
# This requires kernel changes to read from sector 2048+

# Run QEMU with serial output (for CI/testing)
qemu-serial: BB.bin
	$(QEMUCMD) -nographic -serial stdio -device isa-debug-exit,iobase=0xf4,iosize=0x04 -no-reboot -drive format=raw,file=$(BUILDDIR)/BB.bin
