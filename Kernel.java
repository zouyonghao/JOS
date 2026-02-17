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
    
    // Shell input state
    private static char c1 = 0, c2 = 0, c3 = 0, c4 = 0, c5 = 0, c6 = 0, c7 = 0, c8 = 0;
    private static char c9 = 0, c10 = 0, c11 = 0, c12 = 0;
    private static int inputIndex = 0;
    private static final int INPUT_MAX = 12;

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
    
    private static void writeStringAt(String str, int x, int y) {
        if (str == null) return;
        int savedX = cursorX;
        int savedY = cursorY;
        cursorX = x;
        cursorY = y;
        writeString(str);
        cursorX = savedX;
        cursorY = savedY;
    }
    
    // Helper to check if buffer matches "help" (4 chars)
    private static boolean isHelp() {
        if (inputIndex != 4) return false;
        if (c1 != 'h') return false;
        if (c2 != 'e') return false;
        if (c3 != 'l') return false;
        if (c4 != 'p') return false;
        return true;
    }
    
    // Helper to check if buffer matches "clear" (5 chars)
    private static boolean isClear() {
        if (inputIndex != 5) return false;
        if (c1 != 'c') return false;
        if (c2 != 'l') return false;
        if (c3 != 'e') return false;
        if (c4 != 'a') return false;
        if (c5 != 'r') return false;
        return true;
    }
    
    // Helper to check if buffer matches "info" (4 chars)
    private static boolean isInfo() {
        if (inputIndex != 4) return false;
        if (c1 != 'i') return false;
        if (c2 != 'n') return false;
        if (c3 != 'f') return false;
        if (c4 != 'o') return false;
        return true;
    }
    
    // Helper to check if buffer matches "reboot" (6 chars)
    private static boolean isReboot() {
        if (inputIndex != 6) return false;
        if (c1 != 'r') return false;
        if (c2 != 'e') return false;
        if (c3 != 'b') return false;
        if (c4 != 'o') return false;
        if (c5 != 'o') return false;
        if (c6 != 't') return false;
        return true;
    }
    
    // Helper to check if buffer matches "time" (4 chars)
    private static boolean isTime() {
        if (inputIndex != 4) return false;
        if (c1 != 't') return false;
        if (c2 != 'i') return false;
        if (c3 != 'm') return false;
        if (c4 != 'e') return false;
        return true;
    }
    
    // Helper to check if buffer matches "shutdown" (8 chars)
    private static boolean isShutdown() {
        if (inputIndex != 8) return false;
        if (c1 != 's') return false;
        if (c2 != 'h') return false;
        if (c3 != 'u') return false;
        if (c4 != 't') return false;
        if (c5 != 'd') return false;
        if (c6 != 'o') return false;
        if (c7 != 'w') return false;
        if (c8 != 'n') return false;
        return true;
    }
    
    // Native method for 32-bit port output (needed for ACPI shutdown)
    public static native void outl(int port, int data);
    public static native void outw(int port, int data);
    
    private static void shutdown() {
        // Method 1: ACPI shutdown for QEMU (port 0x604, value 0x2000)
        outl(0x604, 0x2000);
        // Method 2: Alternative Bochs/QEMU port
        outw(0xB004, 0x2000);
        // Method 3: isa-debug-exit device (works with -device isa-debug-exit,iobase=0xf4,iosize=0x04)
        outb(0xF4, (char) 0x00);
    }
    
    private static void resetBuffer() {
        c1 = 0; c2 = 0; c3 = 0; c4 = 0; c5 = 0; c6 = 0;
        c7 = 0; c8 = 0; c9 = 0; c10 = 0; c11 = 0; c12 = 0;
        inputIndex = 0;
    }
    
    private static void addToBuffer(char c) {
        if (inputIndex == 0) c1 = c;
        else if (inputIndex == 1) c2 = c;
        else if (inputIndex == 2) c3 = c;
        else if (inputIndex == 3) c4 = c;
        else if (inputIndex == 4) c5 = c;
        else if (inputIndex == 5) c6 = c;
        else if (inputIndex == 6) c7 = c;
        else if (inputIndex == 7) c8 = c;
        else if (inputIndex == 8) c9 = c;
        else if (inputIndex == 9) c10 = c;
        else if (inputIndex == 10) c11 = c;
        else if (inputIndex == 11) c12 = c;
        inputIndex = inputIndex + 1;
    }
    
    private static void backspaceBuffer() {
        if (inputIndex == 0) return;
        inputIndex = inputIndex - 1;
        if (inputIndex == 0) c1 = 0;
        else if (inputIndex == 1) c2 = 0;
        else if (inputIndex == 2) c3 = 0;
        else if (inputIndex == 3) c4 = 0;
        else if (inputIndex == 4) c5 = 0;
        else if (inputIndex == 5) c6 = 0;
        else if (inputIndex == 6) c7 = 0;
        else if (inputIndex == 7) c8 = 0;
        else if (inputIndex == 8) c9 = 0;
        else if (inputIndex == 9) c10 = 0;
        else if (inputIndex == 10) c11 = 0;
        else if (inputIndex == 11) c12 = 0;
    }
    
    private static void executeCommand() {
        writeChar('\n');
        
        if (inputIndex == 0) {
            // Empty command, just show prompt
        } else if (isHelp()) {
            writeString("Available commands:\n");
            writeString("  help    - Show this help message\n");
            writeString("  clear   - Clear the screen\n");
            writeString("  info    - Show system information\n");
            writeString("  reboot  - Restart the system\n");
            writeString("  time    - Show timer tick count\n");
            writeString("  shutdown- Power off (requires isa-debug-exit)\n");
        } else if (isClear()) {
            clearScreen();
        } else if (isInfo()) {
            writeString("JavaOS Kernel v0.5\n");
            writeString("Architecture: x86-64\n");
            writeString("Language: Java + C + ASM\n");
            writeString("Timer: 100Hz\n");
        } else if (isReboot()) {
            writeString("Rebooting...\n");
            // Triple fault via invalid IDT
            loadIDT();
        } else if (isTime()) {
            writeString("Timer running. Check the spinner at top-right!\n");
        } else if (isShutdown()) {
            writeString("Shutting down...\n");
            shutdown();
            writeString("Shutdown failed.\n");
        } else {
            writeString("Unknown command. Type 'help' for available commands.\n");
        }
        
        // Reset buffer
        resetBuffer();
        writeString("> ");
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
        if (scancode == 0x1C) return '\n';  // Enter
        if (scancode == 0x0E) return '\b';  // Backspace
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
        writeString("Type 'help' for available commands.\n\n");
        writeString("> ");
        
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
                char key = lastKey;
                lastKey = 0;
                
                if (key == '\n') {
                    // Execute command
                    executeCommand();
                } else if (key == '\b') {
                    // Backspace - delete last character
                    if (inputIndex > 0) {
                        backspaceBuffer();
                        // Erase character from screen
                        cursorX = cursorX - 1;
                        if (cursorX < 0) {
                            cursorX = SCREEN_WIDTH - 1;
                            cursorY = cursorY - 1;
                        }
                        writeCharAt(' ', cursorX, cursorY);
                    }
                } else if (inputIndex < INPUT_MAX && key >= 32 && key <= 126) {
                    // Regular printable character - add to buffer and echo
                    addToBuffer(key);
                    writeChar(key);
                }
            }
        }
    }

    public static void main(String[] args) {
        startKernel(0L);
    }
}
