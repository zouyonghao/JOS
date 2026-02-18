package kernel;

/**
 * Native method declarations - bridge to runtime.c and assembly
 */
public class Native {

    // Low-level hardware access
    public static native char inb(int port);
    public static native int inw(int port);
    public static native void outb(int port, char data);
    public static native void outw(int port, int data);
    public static native void outl(int port, int data);
    
    // Memory access (64-bit for page tables and E820)
    public static native long readMemoryLong(long addr);
    public static native void writeMemoryLong(long addr, long data);
    public static native void writeMemory(long addr, char _byte);
    
    public static char readMemoryByte(long addr) {
        return (char)(readMemoryLong(addr) & 0xFFL);
    }
    
    // Native memory operations
    public static native void memcpy(long dst, long src, long len);
    public static native void memset(long dst, int val, long len);
    
    // Paging control
    public static native long getCR3();
    public static native void setCR3(long val);
    public static native long readCR2();
    public static native void enablePaging();
    
    // Interrupt controller natives
    public static native void setIDTGate(int vector, long handlerAddr, char typeAttr);
    public static native void loadIDT();
    public static native void sendEOI(int irq);
    public static native void enableInterrupts();
    public static native void disableInterrupts();
    
    // Timer
    public static native long getTicks();
    public static native void incTicks();
    
    // Serial output
    public static native void writeSerial(char c);
    
    // Program execution
    public static native void callProgram(long entryPoint);
    
    // I/O delay
    public static void ioWait() {
        outb(0x80, (char)0);
    }
    
    // Initialization marker
    public static void init() {
        // Called at kernel start to ensure this class is loaded
    }
}
