public class Kernel {

    public static native void writeMemory(long addr, char _byte);

    private static final int SCREEN_WIDTH = 80;
    private static final int SCREEN_HEIGHT = 25;
    private static final char DEFAULT_ATTRIBUTE = 7;

    private static int cursorX = 0;
    private static int cursorY = 0;

    // Test global arrays
    private static int[] testIntArray = new int[10];
    private static String[] testStringArray = new String[5];
    private static char[] testCharArray = new char[20];

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
        for (int i = 0; i < str.length(); i++) {
            writeChar(str.charAt(i));
        }
    }

    private static void writeString(String str, int x, int y) {
        if (str == null) {
            return;
        }
        for (int i = 0; i < str.length(); i++) {
            writeCharAt(str.charAt(i), x + i, y);
        }
    }

    public static void startKernel(long dummy) {
        // Clear screen first
        clearScreen();

        // Write banner
        writeString("=== Java OS Kernel ===\n");
        writeString("GraalVM Native Image + LLVM Backend\n");
        writeString("String constants: WORKING!\n");
        writeString("\n");

        // Test various string operations
        writeString("Test 1: Simple strings\n");
        writeString("Test 2: Numbers: 12345\n");
        writeString("Test 3: Special chars: !@#$%\n");
        writeString("\n");

        writeString("Kernel initialized successfully.\n");
        writeString("System ready.\n");
        writeString("\n");

        // Test 4: Global arrays
        writeString("Test 4: Global arrays\n");

        // Test int array
        testIntArray[0] = 42;
        testIntArray[1] = 100;
        testIntArray[2] = testIntArray[0] + testIntArray[1];
        writeString("Int array: assigned values\n");

        // Test String array
        testStringArray[0] = "Hello";
        testStringArray[1] = "World";
        writeString("String array[0]: ");
        writeString(testStringArray[0]);
        writeString("\n");
        writeString("String array[1]: ");
        writeString(testStringArray[1]);
        writeString("\n");

        // Test char array
        testCharArray[0] = 'A';
        testCharArray[1] = 'B';
        testCharArray[2] = 'C';
        writeString("Char array: assigned ABC\n");

        writeString("\n");
        writeString("All tests completed!\n");

        // Infinite loop to keep kernel running
        while (true) {
            // Do nothing, just keep the processor busy
        }
    }

    public static void main(String[] args) {
        startKernel(0L);
    }
}
