public class Kernel {

    public static native void writeMemory(long addr, char _byte);

    private static final int SCREEN_WIDTH = 80;
    // private static final int SCREEN_HEIGHT = 25;
    private static final char DEFAULT_ATTRIBUTE = 7;

    // private static int cursorX = 0;
    // private static int cursorY = 0;
    // private static final char[] screenBuffer = new char[SCREEN_WIDTH * SCREEN_HEIGHT];

    // private static int index(int x, int y) {
    //     return y * SCREEN_WIDTH + x;
    // }

    // Low-level helper that writes a character to VGA text memory
    public static void writeCharAt(char c, int x, int y) {
        long addr = 0xB8000L + (y * SCREEN_WIDTH + x) * 2L;
        writeMemory(addr, c);
        writeMemory(addr + 1, DEFAULT_ATTRIBUTE);
    }

    // private static void putChar(char c, int x, int y) {
    //     screenBuffer[index(x, y)] = c;
    //     writeCharAt(c, x, y);
    // }

    // private static void refreshScreen() {
    //     for (int y = 0; y < SCREEN_HEIGHT; y++) {
    //         for (int x = 0; x < SCREEN_WIDTH; x++) {
    //             writeCharAt(screenBuffer[index(x, y)], x, y);
    //         }
    //     }
    // }

    // private static void clearScreen() {
    //     for (int i = 0; i < screenBuffer.length; i++) {
    //         screenBuffer[i] = ' ';
    //     }

    //     writeCharAt(screenBuffer[0], 0, 0);
    //     // refreshScreen();
    //     // cursorX = 0;
    //     // cursorY = 0;
    // }

    // private static void scrollUp() {
    //     int rowSize = SCREEN_WIDTH;
    //     int limit = (SCREEN_HEIGHT - 1) * rowSize;
    //     for (int i = 0; i < limit; i++) {
    //         screenBuffer[i] = screenBuffer[i + rowSize];
    //     }
    //     for (int i = limit; i < screenBuffer.length; i++) {
    //         screenBuffer[i] = ' ';
    //     }
    //     refreshScreen();
    // }

    // private static void newLine() {
    //     cursorX = 0;
    //     cursorY++;
    //     if (cursorY >= SCREEN_HEIGHT) {
    //         scrollUp();
    //         cursorY = SCREEN_HEIGHT - 1;
    //     }
    // }

    // private static void writeChar(char c) {
    //     if (c == '\n') {
    //         newLine();
    //         return;
    //     }
    //     if (c == '\r') {
    //         cursorX = 0;
    //         return;
    //     }
    //     if (c >= 32 && c <= 126) {
    //         putChar(c, cursorX, cursorY);
    //         cursorX++;
    //         if (cursorX >= SCREEN_WIDTH) {
    //             newLine();
    //         }
    //     }
    // }

    private static void writeString(String str, int x, int y) {
        if (str == null) {
            return;
        }
        for (int i = 0; i < str.length(); i++) {
            writeCharAt(str.charAt(i), x + i, y);
        }
    }

    public static void startKernel(long dummy) {
        writeCharAt('J', 0, 0);
        writeCharAt('a', 1, 0);
        writeCharAt('v', 2, 0);
        writeCharAt('a', 3, 0);
        writeCharAt(' ', 4, 0);
        writeCharAt('O', 5, 0);
        writeCharAt('S', 6, 0);

        writeString("Hello, Worldaaa!", 0, 1);

        // initConsole();
        // demoOutput();
    }

    public static void main(String[] args) {
        startKernel(0L);
    }
}
