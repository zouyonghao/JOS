
public class Kernel {

    public static native void writeMemory(long addr, char _byte);

    // Simple function to write a character at a specific screen position
    public static void writeCharAt(char c, int x, int y) {
        // VGA text mode: 80x25 characters, each character takes 2 bytes
        // Address = 0xB8000 + (y * 80 + x) * 2
        long addr = 0xB8000L + (y * 80 + x) * 2;
        writeMemory(addr, c); // Character
        writeMemory(addr + 1, (char) 7); // White on black attribute
    }

    public static void writeString(String str, int x, int y) {
        for (int i = 0; i < str.length(); i++) {
            writeCharAt(str.charAt(i), x + i, y);
        }
    }

    public static void startKernel(long dummy) {
        // Test our string-like output functionality

        // Line 1: "Java OS"
        writeCharAt('J', 0, 0);
        writeCharAt('a', 1, 0);
        writeCharAt('v', 2, 0);
        writeCharAt('a', 3, 0);
        writeCharAt(' ', 4, 0);
        writeCharAt('O', 5, 0);
        writeCharAt('S', 6, 0);
        writeCharAt(' ', 7, 0);

        // Line 2: "Testing"
        writeString("Testing", 0, 2);
    }

    public static void main(String[] args) {
        startKernel(0L);
    }
}
