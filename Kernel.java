public class Kernel {

    public static native void writeMemory(long addr, char _byte);
    
    // Low-level hardware access
    public static native char inb(int port);
    public static native void outb(int port, char data);
    
    // Interrupt controller natives
    public static native void setIDTGate(int vector, long handlerAddr, char typeAttr);
    public static native void loadIDT();
    public static native void sendEOI(int irq);
    public static native void enableInterrupts();
    public static native void disableInterrupts();
    
    // Timer
    public static native long getTicks();
    public static native void incTicks();

    private static final int SCREEN_WIDTH = 80;
    private static final int SCREEN_HEIGHT = 25;
    private static final char DEFAULT_ATTRIBUTE = 7;

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

    private static int cursorX = 0;
    private static int cursorY = 0;
    
    // Timer state
    private static volatile int tickCount = 0;
    private static int lastDisplayedTick = -1;
    
    // Keyboard state
    private static volatile char lastKey = 0;

    private static void writeCharAt(char c, int x, int y) {
        long addr = 0xB8000L + (y * SCREEN_WIDTH + x) * 2L;
        writeMemory(addr, c);
        writeMemory(addr + 1, DEFAULT_ATTRIBUTE);
    }

    private static void clearScreen() {
        int y = 0;
        while (y < SCREEN_HEIGHT) {
            int x = 0;
            while (x < SCREEN_WIDTH) {
                writeCharAt(' ', x, y);
                x = x + 1;
            }
            y = y + 1;
        }
        cursorX = 0;
        cursorY = 0;
    }

    private static void newLine() {
        cursorX = 0;
        cursorY = cursorY + 1;
        if (cursorY >= SCREEN_HEIGHT) {
            cursorY = 0;
        }
    }

    private static void writeChar(char c) {
        if (c == '\n') {
            newLine();
            return;
        }
        if (c == '\r') {
            cursorX = 0;
            return;
        }
        if (c >= 32 && c <= 126) {
            writeCharAt(c, cursorX, cursorY);
            cursorX = cursorX + 1;
            if (cursorX >= SCREEN_WIDTH) {
                newLine();
            }
        }
    }

    private static void writeString(String str) {
        if (str == null) return;
        int i = 0;
        int len = str.length();
        while (i < len) {
            writeChar(str.charAt(i));
            i = i + 1;
        }
    }

    // Initialize IDT - set up all 48 gates
    private static void initIDT() {
        int vector = 0;
        while (vector < 48) {
            setIDTGate(vector, 0, KERNEL_INT_GATE);
            vector = vector + 1;
        }
        loadIDT();
    }

    // Remap PIC to avoid conflict with CPU exceptions
    private static void remapPIC() {
        char mask1 = inb(PIC1_DATA);
        char mask2 = inb(PIC2_DATA);
        
        outb(PIC1_COMMAND, (char) (ICW1_INIT | ICW1_ICW4));
        ioWait();
        outb(PIC2_COMMAND, (char) (ICW1_INIT | ICW1_ICW4));
        ioWait();
        
        outb(PIC1_DATA, (char) 32);
        ioWait();
        outb(PIC2_DATA, (char) 40);
        ioWait();
        
        outb(PIC1_DATA, (char) 4);
        ioWait();
        outb(PIC2_DATA, (char) 2);
        ioWait();
        
        outb(PIC1_DATA, ICW4_8086);
        ioWait();
        outb(PIC2_DATA, ICW4_8086);
        ioWait();
        
        outb(PIC1_DATA, mask1);
        outb(PIC2_DATA, mask2);
    }

    private static void ioWait() {
        outb(0x80, (char) 0);
    }

    private static void enableIRQ(int irq) {
        int port;
        if (irq < 8) {
            port = PIC1_DATA;
        } else {
            port = PIC2_DATA;
            irq = irq - 8;
        }
        char value = (char) (inb(port) & ~(1 << irq));
        outb(port, value);
    }

    private static void initPIT() {
        outb(PIT_COMMAND, (char) 0x34);
        outb(PIT_CHANNEL0, (char) 0x9C);
        outb(PIT_CHANNEL0, (char) 0x2E);
    }

    // Convert scancode to ASCII - now in Java!
    private static char scancodeToAscii(char scancode) {
        if (scancode == 0x1E) return 'a';
        if (scancode == 0x30) return 'b';
        if (scancode == 0x2E) return 'c';
        if (scancode == 0x20) return 'd';
        if (scancode == 0x12) return 'e';
        if (scancode == 0x21) return 'f';
        if (scancode == 0x22) return 'g';
        if (scancode == 0x23) return 'h';
        if (scancode == 0x17) return 'i';
        if (scancode == 0x24) return 'j';
        if (scancode == 0x25) return 'k';
        if (scancode == 0x26) return 'l';
        if (scancode == 0x32) return 'm';
        if (scancode == 0x31) return 'n';
        if (scancode == 0x18) return 'o';
        if (scancode == 0x19) return 'p';
        if (scancode == 0x10) return 'q';
        if (scancode == 0x13) return 'r';
        if (scancode == 0x1F) return 's';
        if (scancode == 0x14) return 't';
        if (scancode == 0x16) return 'u';
        if (scancode == 0x2F) return 'v';
        if (scancode == 0x11) return 'w';
        if (scancode == 0x2D) return 'x';
        if (scancode == 0x15) return 'y';
        if (scancode == 0x2C) return 'z';
        if (scancode == 0x02) return '1';
        if (scancode == 0x03) return '2';
        if (scancode == 0x04) return '3';
        if (scancode == 0x05) return '4';
        if (scancode == 0x06) return '5';
        if (scancode == 0x07) return '6';
        if (scancode == 0x08) return '7';
        if (scancode == 0x09) return '8';
        if (scancode == 0x0A) return '9';
        if (scancode == 0x0B) return '0';
        if (scancode == 0x39) return ' ';
        if (scancode == 0x1C) return '\n';
        return 0;
    }

    // Called from interrupt context in C for ALL interrupts
    // Java handles ALL dispatch logic!
    public static void handleInterrupt(int vector) {
        if (vector == 32) {
            // Timer interrupt (IRQ0 -> vector 32)
            tickCount = tickCount + 1;
            incTicks();
            sendEOI(0);
        } else if (vector == 33) {
            // Keyboard interrupt (IRQ1 -> vector 33)
            char scancode = inb(KEYBOARD_DATA);
            if (scancode < 128) {
                char ascii = scancodeToAscii(scancode);
                if (ascii != 0) {
                    lastKey = ascii;
                }
            }
            sendEOI(1);
        } else if (vector >= 32 && vector <= 47) {
            // Other hardware IRQs
            sendEOI(vector - 32);
        } else if (vector < 32) {
            // CPU exception - display error and halt
            disableInterrupts();
            writeCharAt('E', 0, 0);
            char hexDigit = (vector < 10) ? (char) ('0' + vector) : (char) ('A' + vector - 10);
            writeCharAt(hexDigit, 1, 0);
            while (true) { }
        }
    }

    private static void initInterrupts() {
        initIDT();
        remapPIC();
        initPIT();
        enableIRQ(0);
        enableIRQ(1);
        enableInterrupts();
    }

    public static void startKernel(long dummy) {
        clearScreen();
        
        writeString("JavaOS Kernel v0.5\n");
        writeString("==================\n\n");
        
        writeString("Initializing from Java...\n");
        initInterrupts();
        
        writeString("Interrupts enabled!\n");
        writeString("Timer: 100Hz\n");
        writeString("Keyboard: enabled\n");
        writeString("Dispatch: Java\n\n");
        writeString("Type something:\n\n");
        
        int lastTick = 0;
        while (true) {
            int currentTick = tickCount;
            
            if (currentTick != lastTick) {
                lastTick = currentTick;
                
                int idx = currentTick & 3;
                char spinChar;
                if (idx == 0) spinChar = '|';
                else if (idx == 1) spinChar = '/';
                else if (idx == 2) spinChar = '-';
                else spinChar = '\\';
                
                long addr = 0xB8000L + (0 * SCREEN_WIDTH + 79) * 2L;
                writeMemory(addr, spinChar);
                writeMemory(addr + 1, (char) 0x0E);
            }
            
            if (lastKey != 0) {
                writeChar(lastKey);
                lastKey = 0;
            }
        }
    }

    public static void main(String[] args) {
        startKernel(0L);
    }
}
