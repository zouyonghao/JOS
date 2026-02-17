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
OBJLIST = ./obj/bootloader.o ./obj/Kernel.o ./obj/runtime.o ./obj/interrupts_asm.o
BUILDDIR = ./build

BB.bin : $(BUILDDIR) $(OBJLIST)
	$(CC) $(OBJLIST) -o $(BUILDDIR)/BB.bin $(CFLAGS) $(LDFLAGS) $(64BITFLAGS) $(DIRECTIVES) -T $(LINKER) $(OPTFLAGS)

qemu: BB.bin
	$(QEMUCMD) $(QEMUFLAGS)$(BUILDDIR)/BB.bin

qemu-noexit: BB.bin
	$(QEMUCMD) $(QEMUFLAGS_NOEXIT)$(BUILDDIR)/BB.bin

$(OBJDIR)/Kernel.o: Kernel.java JavaToLLVM.java
	./build_kernel_custom.sh

$(OBJDIR)/runtime.o: runtime.c $(OBJDIR)
	$(CC) -c runtime.c -o obj/runtime.o $(CFLAGS) $(OPTFLAGS) -fno-pic

$(OBJDIR)/bootloader.o : bootloader.asm $(OBJDIR)
	$(AS) bootloader.asm -o $(OBJDIR)/bootloader.o $(ASFLAGS)

$(OBJDIR)/interrupts_asm.o : interrupts.asm $(OBJDIR)
	$(AS) interrupts.asm -o $(OBJDIR)/interrupts_asm.o

$(BUILDDIR) :
	test ! -d $(BUILDDIR) && mkdir $(BUILDDIR)

$(OBJDIR) :
	test ! -d $(OBJDIR) && mkdir $(OBJDIR)

clean :
	rm -rf generated-llvm
	rm -rf $(OBJLIST)
	rm -f $(BUILDDIR)/BB.bin
	rm -f *.class
