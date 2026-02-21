package kernel;

/**
 * Console output module - VGA and serial output
 */
public class Console {

    private static final int SCREEN_WIDTH = 80;
    private static final int SCREEN_HEIGHT = 25;
    private static final char DEFAULT_ATTRIBUTE = 7;
    
    private static int cursorX = 0;
    private static int cursorY = 0;
    
    // Static buffer for number conversion (no heap allocation)
    private static char[] numBuffer = new char[20];

    public static void writeCharAt(char c, int x, int y) {
        long addr = 0xB8000L + (y * SCREEN_WIDTH + x) * 2L;
        Native.writeMemory(addr, c);
        Native.writeMemory(addr + 1, DEFAULT_ATTRIBUTE);
    }

    public static void clearScreen() {
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

    public static void newLine() {
        cursorX = 0;
        cursorY = cursorY + 1;
        if (cursorY >= SCREEN_HEIGHT) {
            scrollUp();
            cursorY = SCREEN_HEIGHT - 1;
        }
    }

    private static void scrollUp() {
        // Copy rows 1..24 to 0..23 using memcpy (fast bulk copy)
        long vgaBase = 0xB8000L;
        long rowBytes = (long)(SCREEN_WIDTH * 2);
        Native.memcpy(vgaBase, vgaBase + rowBytes, rowBytes * (SCREEN_HEIGHT - 1));
        // Clear the last row
        int x = 0;
        while (x < SCREEN_WIDTH) {
            writeCharAt(' ', x, SCREEN_HEIGHT - 1);
            x = x + 1;
        }
    }

    public static void writeChar(char c) {
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

    public static void writeString(String str) {
        if (str == null) return;
        int i = 0;
        int len = str.length();
        while (i < len) {
            writeChar(str.charAt(i));
            i = i + 1;
        }
    }
    
    public static void writeStringAt(String str, int x, int y) {
        if (str == null) return;
        int savedX = cursorX;
        int savedY = cursorY;
        cursorX = x;
        cursorY = y;
        writeString(str);
        cursorX = savedX;
        cursorY = savedY;
    }
    
    public static void writeNumber(long num) {
        if (num == 0) {
            writeChar('0');
            return;
        }
        
        int i = 0;
        while (num > 0) {
            char digit = (char)('0' + (num % 10));
            numBuffer[i] = digit;
            num = num / 10;
            i = i + 1;
        }
        
        while (i > 0) {
            i = i - 1;
            writeChar(numBuffer[i]);
        }
    }
    
    // Write a 64-bit value as hex
    public static void writeHex(long val) {
        writeString("0x");
        int i = 60;
        while (i >= 0) {
            int nibble = (int)((val >> i) & 0xF);
            char c;
            if (nibble < 10) {
                c = (char)('0' + nibble);
            } else {
                c = (char)('A' + nibble - 10);
            }
            writeChar(c);
            i = i - 4;
        }
    }
    
    public static void writeHexByte(int val) {
        int high = (val >> 4) & 0xF;
        int low = val & 0xF;
        char ch = (char)(high < 10 ? '0' + high : 'A' + high - 10);
        char cl = (char)(low < 10 ? '0' + low : 'A' + low - 10);
        writeChar(ch);
        writeChar(cl);
    }
    
    public static void writeHexLong32(long val) {
        // Print low 32 bits as hex
        int i = 28;
        while (i >= 0) {
            int nibble = (int)((val >> i) & 0xF);
            char c;
            if (nibble < 10) {
                c = (char)('0' + nibble);
            } else {
                c = (char)('A' + nibble - 10);
            }
            writeChar(c);
            i = i - 4;
        }
    }
    
    public static void writeSerialMessage(String msg) {
        if (msg == null) return;
        int i = 0;
        int len = msg.length();
        while (i < len) {
            Native.writeSerial(msg.charAt(i));
            i = i + 1;
        }
    }
    
    // Getters/setters for cursor
    public static int getCursorX() { return cursorX; }
    public static int getCursorY() { return cursorY; }
    public static void setCursorX(int x) { cursorX = x; }
    public static void setCursorY(int y) { cursorY = y; }
}
