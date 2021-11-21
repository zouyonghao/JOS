CC = gcc
CFLAGS = -Wall -Wextra -Wpedantic -std=gnu99 -ffreestanding
OPTFLAGS = -O2
64BITFLAGS = -mno-red-zone -mno-mmx -mno-sse -mno-sse2

AS = as
ASFLAGS = -msyntax=intel -mnaked-reg

LINKER = linker.ld
LDFLAGS= -lgcc -nostdlib
# NOTE: in some installs you need to specify the path of the library files
#LDFLAGS += -L/lib/gcc/x86_64-elf/11.2.0/libgcc.a 

# ALT-2, then type quit
QEMUCMD = qemu-system-x86_64
QEMUFLAGS = -curses -drive format=raw,file=

OBJDIR = ./obj
OBJLIST = ./obj/vga.o ./obj/bootloader.o ./obj/HelloWorld.o ./obj/runtime.o
BUILDDIR = ./build

BB.bin : $(BUILDDIR) $(OBJLIST)
				$(CC) $(OBJLIST) -o $(BUILDDIR)/BB.bin $(CFLAGS) $(LDFLAGS) $(64BITFLAGS) $(DIRECTIVES) -T $(LINKER) $(OPTFLAGS)

qemu: BB.bin
			  $(QEMUCMD) $(QEMUFLAGS)$(BUILDDIR)/BB.bin

$(OBJDIR)/HelloWorld.o:
				gcj -s HelloWorld.java -o $(OBJDIR)/HelloWorld.s
				gcj -c HelloWorld.java -o $(OBJDIR)/HelloWorld.o
				gcc -c runtime.c -o $(OBJDIR)/runtime.o

$(OBJDIR)/vga.o : vga.c $(OBJDIR)
				$(CC) -c vga.c -o $(OBJDIR)/vga.o $(CFLAGS) $(OPTFLAGS) $(DIRECTIVES)

$(OBJDIR)/bootloader.o : bootloader.asm $(OBJDIR)
				$(AS) bootloader.asm -o $(OBJDIR)/bootloader.o $(ASFLAGS)

$(BUILDDIR) : 
				test ! -d $(BUILDDIR) && mkdir $(BUILDDIR) 

$(OBJDIR) :
				test ! -d $(OBJDIR) && mkdir $(OBJDIR) 

clean :
				rm $(OBJLIST)
				rm $(BUILDDIR)/BB.bin