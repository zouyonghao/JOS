package kernel;

/**
 * Interrupt handling module - IDT, PIC, PIT, keyboard
 */
public class Interrupts {

    // Hardware ports
    private static final int PIC1_COMMAND = 0x20;
    private static final int PIC1_DATA = 0x21;
    private static final int PIC2_COMMAND = 0xA0;
    private static final int PIC2_DATA = 0xA1;
    private static final int PIT_COMMAND = 0x43;
    private static final int PIT_CHANNEL0 = 0x40;
    private static final int KEYBOARD_DATA = 0x60;
    
    // Constants
    private static final char ICW1_ICW4 = 0x01;
    private static final char ICW1_INIT = 0x10;
    private static final char ICW4_8086 = 0x01;
    private static final char IDT_PRESENT = (char) 0x80;
    private static final char IDT_INT_GATE = 0x0E;
    private static final char KERNEL_INT_GATE = (char) (IDT_PRESENT | IDT_INT_GATE);

    // Crash debug info (set by assembly on CPU exceptions)
    private static long crashRIP = 0;
    private static long crashErrorCode = 0;
    private static long crashStack120 = 0;
    private static long crashStack128 = 0;
    private static long crashStack136 = 0;
    private static long crashStack144 = 0;
    private static long crashStack152 = 0;

    // Timer state
    private static volatile int tickCount = 0;
    private static int lastDisplayedTick = -1;
    
    // Keyboard state
    private static final int RING_SIZE = 256;
    private static char[] ringBuffer = new char[RING_SIZE];
    private static int ringHead = 0;
    private static int ringTail = 0;
    private static int leftShiftPressed = 0;
    private static int rightShiftPressed = 0;
    
    // Shift scancodes
    private static final int LEFT_SHIFT_MAKE = 0x2A;
    private static final int LEFT_SHIFT_BREAK = 0xAA;
    private static final int RIGHT_SHIFT_MAKE = 0x36;
    private static final int RIGHT_SHIFT_BREAK = 0xB6;

    public static void initInterrupts() {
        initIDT();
        remapPIC();
        initPIT();
        Native.enableInterrupts();
    }

    // Called from assembly ISR stubs - must have exact name "interrupt_dispatch"
    @CName("interrupt_dispatch")
    public static void dispatch(long vector) {
        handleInterrupt((int)vector);
    }

    private static void initIDT() {
        int vector = 0;
        while (vector < 256) {
            Native.setIDTGate(vector, getInterruptHandler(vector), KERNEL_INT_GATE);
            vector = vector + 1;
        }
        Native.loadIDT();
    }
    
    // Get handler address for each vector - these are defined in interrupts.asm
    private static long getInterruptHandler(int vector) {
        // All vectors jump to interrupt_common_handler in assembly
        // which then calls handleInterrupt
        return 0x10000L + vector * 16; // Placeholder - actual addresses set in assembly
    }
    
    private static void remapPIC() {
        // ICW1
        Native.outb(PIC1_COMMAND, (char)(ICW1_INIT | ICW1_ICW4));
        Native.ioWait();
        Native.outb(PIC2_COMMAND, (char)(ICW1_INIT | ICW1_ICW4));
        Native.ioWait();
        
        // ICW2 - vector offsets
        Native.outb(PIC1_DATA, (char)0x20);
        Native.ioWait();
        Native.outb(PIC2_DATA, (char)0x28);
        Native.ioWait();
        
        // ICW3 - cascade
        Native.outb(PIC1_DATA, (char)0x04);
        Native.ioWait();
        Native.outb(PIC2_DATA, (char)0x02);
        Native.ioWait();
        
        // ICW4
        Native.outb(PIC1_DATA, (char)ICW4_8086);
        Native.ioWait();
        Native.outb(PIC2_DATA, (char)ICW4_8086);
        Native.ioWait();
        
        // Enable IRQs
        enableIRQ(0);  // Timer
        enableIRQ(1);  // Keyboard
    }
    
    private static void enableIRQ(int irq) {
        int port = PIC1_DATA;
        int irqMask = irq;
        if (irq >= 8) {
            port = PIC2_DATA;
            irqMask = irq - 8;
        }
        char mask = Native.inb(port);
        mask = (char)(mask & ~(1 << irqMask));
        Native.outb(port, mask);
    }
    
    private static void initPIT() {
        // Set PIT to 100Hz (divisor = 1193180 / 100 = 11931)
        int divisor = 11931;
        Native.outb(PIT_COMMAND, (char)0x36);
        Native.outb(PIT_CHANNEL0, (char)(divisor & 0xFF));
        Native.outb(PIT_CHANNEL0, (char)((divisor >> 8) & 0xFF));
    }
    
    private static void ioWait() {
        // Port 0x80 is used for POST codes, safe for I/O delay
        Native.outb(0x80, (char)0);
    }
    
    // Called from assembly interrupt dispatcher
    public static void handleInterrupt(int vector) {
        if (vector == 0x20) {
            // Timer interrupt
            tickCount = tickCount + 1;
            Native.incTicks();

            // Show spinner on screen
            if (tickCount % 10 == 0) {
                int idx = (tickCount / 10) & 3;
                char c;
                if (idx == 0) c = '|';
                else if (idx == 1) c = '/';
                else if (idx == 2) c = '-';
                else c = '\\';
                Console.writeCharAt(c, 79, 0);
            }

            // Check for context switch
            Threading.schedule();

            Native.sendEOI(0);
        } else if (vector == 0x21) {
            // Keyboard interrupt
            char scancode = Native.inb(KEYBOARD_DATA);
            handleScancode(scancode);
            Native.sendEOI(1);
        } else if (vector >= 0x20 && vector < 0x30) {
            // Other PIC interrupts
            Native.sendEOI(vector - 0x20);
        } else if (vector == 0x80) {
            // System call
            Syscalls.handleSyscall();
        } else if (vector >= 0 && vector < 32) {
            // CPU exception - check if inside PE loaded range
            handleCpuException(vector);
        }
    }

    // Phase 5: Graceful PE fault handling
    private static void handleCpuException(int vector) {
        if (Loader.getPeLoadedBase() != 0) {
            int tid = Threading.getCurrentThreadId();
            if (tid != 0) {
                Console.writeString("\nPE FAULT: vec ");
                Console.writeNumber(vector);
                Console.writeString(" tid=");
                Console.writeNumber(tid);
                Console.writeString("\n  RIP=");
                Console.writeHex(crashRIP);
                Console.writeString("\n  errCode=");
                Console.writeHex(crashErrorCode);
                Console.writeString("\n  stk120=");
                Console.writeHex(crashStack120);
                Console.writeString("\n  stk128=");
                Console.writeHex(crashStack128);
                Console.writeString("\n  stk136=");
                Console.writeHex(crashStack136);
                Console.writeString("\n  stk144=");
                Console.writeHex(crashStack144);
                Console.writeString("\n  stk152=");
                Console.writeHex(crashStack152);
                Console.writeString("\n");
                Threading.terminateCurrentThread();
                return;
            }
        }
        Console.writeString("\n*** KERNEL PANIC ***\n");
        Console.writeString("CPU EXCEPTION: vector ");
        Console.writeNumber(vector);
        Console.writeString("\n  RIP=");
        Console.writeHex(crashRIP);
        Console.writeString("\n  errCode=");
        Console.writeHex(crashErrorCode);
        Console.writeString("\n");
        Core.halt();
    }
    
    private static void handleScancode(char scancode) {
        int code = scancode & 0xFF;
        
        // Handle shift keys
        if (code == LEFT_SHIFT_MAKE) {
            leftShiftPressed = 1;
            return;
        }
        if (code == LEFT_SHIFT_BREAK) {
            leftShiftPressed = 0;
            return;
        }
        if (code == RIGHT_SHIFT_MAKE) {
            rightShiftPressed = 1;
            return;
        }
        if (code == RIGHT_SHIFT_BREAK) {
            rightShiftPressed = 0;
            return;
        }
        
        // Ignore key releases (high bit set)
        if ((code & 0x80) != 0) return;
        
        // Convert to ASCII
        char ascii = scancodeToAscii(scancode);
        if (ascii != 0) {
            ringBufferPut(ascii);
        }
    }
    
    private static char scancodeToAscii(char scancode) {
        int code = scancode & 0x7F;
        int shifted = leftShiftPressed | rightShiftPressed;
        
        // Use simple if-else instead of 2D array to avoid complex bytecode
        if (code >= 0x60) return 0;
        
        if (shifted == 0) {
            // Unshifted mapping
            if (code == 2) return '1';
            if (code == 3) return '2';
            if (code == 4) return '3';
            if (code == 5) return '4';
            if (code == 6) return '5';
            if (code == 7) return '6';
            if (code == 8) return '7';
            if (code == 9) return '8';
            if (code == 10) return '9';
            if (code == 11) return '0';
            if (code == 12) return '-';
            if (code == 13) return '=';
            if (code == 14) return '\b';
            if (code == 15) return '\t';
            if (code == 16) return 'q';
            if (code == 17) return 'w';
            if (code == 18) return 'e';
            if (code == 19) return 'r';
            if (code == 20) return 't';
            if (code == 21) return 'y';
            if (code == 22) return 'u';
            if (code == 23) return 'i';
            if (code == 24) return 'o';
            if (code == 25) return 'p';
            if (code == 26) return '[';
            if (code == 27) return ']';
            if (code == 28) return '\n';
            if (code == 30) return 'a';
            if (code == 31) return 's';
            if (code == 32) return 'd';
            if (code == 33) return 'f';
            if (code == 34) return 'g';
            if (code == 35) return 'h';
            if (code == 36) return 'j';
            if (code == 37) return 'k';
            if (code == 38) return 'l';
            if (code == 39) return ';';
            if (code == 40) return '\'';
            if (code == 41) return '`';
            if (code == 43) return '\\';
            if (code == 44) return 'z';
            if (code == 45) return 'x';
            if (code == 46) return 'c';
            if (code == 47) return 'v';
            if (code == 48) return 'b';
            if (code == 49) return 'n';
            if (code == 50) return 'm';
            if (code == 51) return ',';
            if (code == 52) return '.';
            if (code == 53) return '/';
            if (code == 57) return ' ';
        } else {
            // Shifted mapping
            if (code == 2) return '!';
            if (code == 3) return '@';
            if (code == 4) return '#';
            if (code == 5) return '$';
            if (code == 6) return '%';
            if (code == 7) return '^';
            if (code == 8) return '&';
            if (code == 9) return '*';
            if (code == 10) return '(';
            if (code == 11) return ')';
            if (code == 12) return '_';
            if (code == 13) return '+';
            if (code == 14) return '\b';
            if (code == 15) return '\t';
            if (code == 16) return 'Q';
            if (code == 17) return 'W';
            if (code == 18) return 'E';
            if (code == 19) return 'R';
            if (code == 20) return 'T';
            if (code == 21) return 'Y';
            if (code == 22) return 'U';
            if (code == 23) return 'I';
            if (code == 24) return 'O';
            if (code == 25) return 'P';
            if (code == 26) return '{';
            if (code == 27) return '}';
            if (code == 28) return '\n';
            if (code == 30) return 'A';
            if (code == 31) return 'S';
            if (code == 32) return 'D';
            if (code == 33) return 'F';
            if (code == 34) return 'G';
            if (code == 35) return 'H';
            if (code == 36) return 'J';
            if (code == 37) return 'K';
            if (code == 38) return 'L';
            if (code == 39) return ':';
            if (code == 40) return '"';
            if (code == 41) return '~';
            if (code == 43) return '|';
            if (code == 44) return 'Z';
            if (code == 45) return 'X';
            if (code == 46) return 'C';
            if (code == 47) return 'V';
            if (code == 48) return 'B';
            if (code == 49) return 'N';
            if (code == 50) return 'M';
            if (code == 51) return '<';
            if (code == 52) return '>';
            if (code == 53) return '?';
            if (code == 57) return ' ';
        }
        return 0;
    }
    
    // Ring buffer for keyboard input
    public static void ringBufferPut(char c) {
        int nextHead = ringHead + 1;
        if (nextHead >= RING_SIZE) nextHead = 0;
        if (nextHead != ringTail) {
            ringBuffer[ringHead] = c;
            ringHead = nextHead;
        }
    }
    
    public static char ringBufferGet() {
        if (ringHead == ringTail) return 0;
        char c = ringBuffer[ringTail];
        ringTail = ringTail + 1;
        if (ringTail >= RING_SIZE) ringTail = 0;
        return c;
    }
    
    // Getters
    public static int getTickCount() { return tickCount; }
}
