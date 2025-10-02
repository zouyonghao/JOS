public class Kernel {

    public static native void writeMemory(long addr, char _byte);

    private static final int SCREEN_WIDTH = 80;
    private static final int SCREEN_HEIGHT = 25;
    private static final char DEFAULT_ATTRIBUTE = 7;

    private static int cursorX = 0;
    private static int cursorY = 0;

    // Test global arrays
    // private static int[] testIntArray = new int[10];
    // private static String[] testStringArray = new String[5];
    // private static char[] testCharArray = new char[20];

    private static int index(int x, int y) {
        return y * SCREEN_WIDTH + x;
    }

    // Low-level helper that writes a character to VGA text memory
    public static void writeCharAt(char c, int x, int y) {
        long addr = 0xB8000L + (y * SCREEN_WIDTH + x) * 2L;
        writeMemory(addr, c);
        writeMemory(addr + 1, DEFAULT_ATTRIBUTE);
    }

    private static void clearScreen() {
        for (int y = 0; y < SCREEN_HEIGHT; y++) {
            for (int x = 0; x < SCREEN_WIDTH; x++) {
                writeCharAt(' ', x, y);
            }
        }
        cursorX = 0;
        cursorY = 0;
    }

    private static void newLine() {
        cursorX = 0;
        cursorY++;
        if (cursorY >= SCREEN_HEIGHT) {
            cursorY = 0; // Simple wrap-around instead of scrolling
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
            cursorX++;
            if (cursorX >= SCREEN_WIDTH) {
                newLine();
            }
        }
    }

    private static void writeString(String str) {
        if (str == null) {
            return;
        }
        int len = str.length();  // Now works correctly with fixed layout
        for (int i = 0; i < len; i++) {
            writeChar(str.charAt(i));
        }
    }

    private static void writeString(String str, int x, int y) {
        if (str == null) {
            return;
        }
        int len = str.length();  // Use direct .length access
        for (int i = 0; i < len; i++) {
            writeCharAt(str.charAt(i), x + i, y);  // charAt() works!
        }
    }

    public static void startKernel(long dummy) {
        // Write a test marker to top-left of screen to verify kernel is running
        writeMemory(0xB8000L, 'X');
        writeMemory(0xB8001L, (char)0x0F); // Bright white on black

        // Clear screen first
        clearScreen();
        writeChar((char) (cursorX + '0'));

        writeString("Hello\n");
        writeChar((char) (cursorX + '0'));
        writeChar((char) (cursorY + '0'));
        // // Manually reset cursor to a known state
        // cursorX = 0;
        // cursorY = 1;
        writeString("World\n");

        // Infinite loop to keep kernel running
        while (true) {
            // Do nothing, just keep the processor busy
        }
    }

    public static void main(String[] args) {
        startKernel(0L);
    }
}
