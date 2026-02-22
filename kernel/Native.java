package kernel;

/**
 * Native method declarations - bridge to runtime.c and assembly
 */
public class Native {

    // Low-level hardware access - inline assembly
    @InlineAsm(value = "xorl ${0:k}, ${0:k}\n\tinb ${1:w}, ${0:b}", constraints = "=&{eax},{edx}")
    public static native char inb(int port);

    @InlineAsm(value = "xorl ${0:k}, ${0:k}\n\tinw ${1:w}, ${0:w}", constraints = "=&{eax},{edx}")
    public static native int inw(int port);

    @InlineAsm(value = "outb ${1:b}, ${0:w}", constraints = "{edx},{eax}")
    public static native void outb(int port, char data);

    @InlineAsm(value = "outw ${1:w}, ${0:w}", constraints = "{edx},{eax}")
    public static native void outw(int port, int data);

    @InlineAsm(value = "outl ${1:k}, ${0:w}", constraints = "{edx},{eax}")
    public static native void outl(int port, int data);

    // Memory access (64-bit for page tables and E820) - inline assembly
    @InlineAsm(value = "movq ($1), $0", constraints = "=r,r")
    public static native long readMemoryLong(long addr);

    @InlineAsm(value = "movq $1, ($0)", constraints = "r,r")
    public static native void writeMemoryLong(long addr, long data);

    // Byte-level memory store - inline assembly
    @InlineAsm(value = "movb ${1:b}, ($0)", constraints = "r,{eax}")
    public static native void writeMemoryByteRaw(long addr, int val);

    // writeMemory with VGA/serial side effects (moved from C)
    public static void writeMemory(long addr, char _byte) {
        if (addr >= 0xB8000L && addr <= 0xB8F9FL) {
            if ((addr - 0xB8000L) % 2 == 0) {
                writeSerial(_byte);
            }
        }
        if (addr == 0x3F8L) {
            writeSerial(_byte);
        }
        writeMemoryByteRaw(addr, (int)_byte);
    }

    public static char readMemoryByte(long addr) {
        return (char)(readMemoryLong(addr) & 0xFFL);
    }

    // Native memory operations (moved from C)
    public static void memcpy(long dst, long src, long len) {
        long i = 0;
        while (i < len) {
            writeMemoryByteRaw(dst + i, (int)readMemoryByte(src + i));
            i = i + 1;
        }
    }

    public static void memset(long dst, int val, long len) {
        long i = 0;
        while (i < len) {
            writeMemoryByteRaw(dst + i, val);
            i = i + 1;
        }
    }

    // Paging control - inline assembly
    @InlineAsm(value = "movq %cr3, $0", constraints = "=r")
    public static native long getCR3();

    @InlineAsm(value = "movq $0, %cr3", constraints = "r")
    public static native void setCR3(long val);

    @InlineAsm(value = "movq %cr2, $0", constraints = "=r")
    public static native long readCR2();

    @InlineAsm(value = "movq %cr0, $0\n\tbtsq $$31, $0\n\tmovq $0, %cr0", constraints = "=&r")
    public static native void enablePaging();

    // Interrupt controller - assembly externs via @CName
    @CName("idt_set_gate")
    public static native void idtSetGateAsm(int vector, long handler, char typeAttr);

    // Returns address of ISR handler for given vector (C helper for isr_stub_table array access)
    public static native long getIsrStubAddr(int idx);

    @CName("get_syscall_handler")
    public static native long getSyscallHandler();

    // setIDTGate implementation (moved from C)
    public static void setIDTGate(int vector, long handlerAddr, char typeAttr) {
        if (vector >= 0 && vector < 48) {
            long handler = getIsrStubAddr(vector);
            idtSetGateAsm(vector, handler, typeAttr);
        } else if (vector == 0x80) {
            long handler = getSyscallHandler();
            idtSetGateAsm(vector, handler, typeAttr);
        }
    }

    @CName("idt_load")
    public static native void loadIDT();

    @CName("pic_send_eoi")
    public static native void sendEOI(int irq);

    @CName("enable_interrupts")
    public static native void enableInterrupts();

    @CName("disable_interrupts")
    public static native void disableInterrupts();

    // Timer (moved from C - uses Java static field)
    private static long timerTicks = 0;

    public static long getTicks() {
        return timerTicks;
    }

    public static void incTicks() {
        timerTicks = timerTicks + 1;
    }

    // Serial output (moved from C - just calls outb)
    public static void writeSerial(char c) {
        outb(0x3F8, c);
    }

    // Program execution - inline assembly
    @InlineAsm(value = "callq *$0", constraints = "r,~{rax},~{rcx},~{rdx},~{rsi},~{rdi},~{r8},~{r9},~{r10},~{r11},~{memory}")
    public static native void callProgram(long entryPoint);

    // Windows syscall MSR initialization
    @CName("init_syscall_msr")
    public static native void initSyscallMSR();

    // msvcrt function address resolver
    public static native long getMsvcrtFuncAddr(int funcId);

    // GS segment base (for Windows TEB emulation) - inline assembly
    @InlineAsm(value = "movq $0, %rax\n\tmovq $0, %rdx\n\tshrq $$32, %rdx\n\tmovl $$0xC0000101, %ecx\n\twrmsr", constraints = "r,~{rax},~{rcx},~{rdx}")
    public static native void setGSBase(long addr);

    // I/O delay
    public static void ioWait() {
        outb(0x80, (char)0);
    }

    // Initialization marker
    public static void init() {
        // Called at kernel start to ensure this class is loaded
    }
}
