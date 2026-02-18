package kernel;

/**
 * Shell module - command line interface and command handling
 */
public class Shell {

    // Input state
    private static final int INPUT_MAX = 64;
    private static char[] inputBuffer = new char[INPUT_MAX];
    private static int inputIndex = 0;
    
    // Command history
    private static final int HISTORY_SIZE = 8;
    private static final int HISTORY_LEN = 64;
    private static char[] history0 = new char[HISTORY_LEN];
    private static char[] history1 = new char[HISTORY_LEN];
    private static char[] history2 = new char[HISTORY_LEN];
    private static char[] history3 = new char[HISTORY_LEN];
    private static char[] history4 = new char[HISTORY_LEN];
    private static char[] history5 = new char[HISTORY_LEN];
    private static char[] history6 = new char[HISTORY_LEN];
    private static char[] history7 = new char[HISTORY_LEN];
    private static int historyCount = 0;
    private static int historyNext = 0;
    
    // Memory watch
    private static final int WATCH_MAX = 8;
    private static long[] watchedAddresses = new long[WATCH_MAX];
    private static int watchCount = 0;

    public static void readCommand() {
        inputIndex = 0;
        while (true) {
            char c = Interrupts.ringBufferGet();
            if (c == 0) continue;
            
            if (c == '\n' || c == '\r') {
                Console.writeChar('\n');
                break;
            } else if (c == '\b') {
                backspaceBuffer();
            } else if (c >= 32 && c <= 126) {
                addToBuffer(c);
            }
        }
        saveToHistory();
    }
    
    private static void addToBuffer(char c) {
        if (inputIndex < INPUT_MAX - 1) {
            inputBuffer[inputIndex] = c;
            inputIndex = inputIndex + 1;
            Console.writeChar(c);
        }
    }
    
    private static void backspaceBuffer() {
        if (inputIndex > 0) {
            inputIndex = inputIndex - 1;
            Console.writeChar('\b');
            Console.writeChar(' ');
            Console.writeChar('\b');
        }
    }
    
    private static void saveToHistory() {
        if (inputIndex == 0) return;
        
        int slot = historyNext;
        char[] histBuf = getHistoryBuffer(slot);
        int i = 0;
        while (i < inputIndex && i < HISTORY_LEN - 1) {
            histBuf[i] = inputBuffer[i];
            i = i + 1;
        }
        histBuf[i] = 0;
        
        historyNext = historyNext + 1;
        if (historyNext >= HISTORY_SIZE) {
            historyNext = 0;
        }
        if (historyCount < HISTORY_SIZE) {
            historyCount = historyCount + 1;
        }
    }
    
    private static char[] getHistoryBuffer(int slot) {
        if (slot == 0) return history0;
        if (slot == 1) return history1;
        if (slot == 2) return history2;
        if (slot == 3) return history3;
        if (slot == 4) return history4;
        if (slot == 5) return history5;
        if (slot == 6) return history6;
        return history7;
    }
    
    private static boolean inputEquals(String str) {
        if (str == null) return false;
        int len = str.length();
        if (inputIndex != len) return false;
        int i = 0;
        while (i < len) {
            if (inputBuffer[i] != str.charAt(i)) return false;
            i = i + 1;
        }
        return true;
    }
    
    private static boolean inputStartsWith(String prefix) {
        if (prefix == null) return false;
        int len = prefix.length();
        if (inputIndex < len) return false;
        int i = 0;
        while (i < len) {
            if (inputBuffer[i] != prefix.charAt(i)) return false;
            i = i + 1;
        }
        return true;
    }
    
    private static char getInputChar(int pos) {
        if (pos >= 1 && pos <= inputIndex) {
            return inputBuffer[pos - 1];
        }
        return 0;
    }
    
    private static int hexCharToVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
    
    private static long parseHex(int startPos) {
        long val = 0;
        int pos = startPos;
        int digits = 0;
        while (pos <= inputIndex) {
            char c = getInputChar(pos);
            int v = hexCharToVal(c);
            if (v < 0) break;
            val = (val << 4) | v;
            pos = pos + 1;
            digits = digits + 1;
        }
        if (digits == 0) return -1;
        return val;
    }

    public static void executeCommand() {
        if (inputIndex == 0) return;
        
        if (inputEquals("help")) {
            showHelp();
        } else if (inputEquals("clear")) {
            Console.clearScreen();
        } else if (inputEquals("info")) {
            showInfo();
        } else if (inputEquals("mem")) {
            Memory.printMemoryStats();
        } else if (inputEquals("heapstat")) {
            Memory.printHeapStats();
        } else if (inputEquals("vmtest")) {
            testVirtualMemory();
        } else if (inputEquals("ls")) {
            Filesystem.listFiles();
        } else if (inputEquals("ps")) {
            Threading.listThreads();
        } else if (inputEquals("history")) {
            showHistory();
        } else if (inputEquals("watchlist")) {
            showWatchlist();
        } else if (inputEquals("shutdown")) {
            shutdown();
        } else if (inputEquals("reboot")) {
            // Reboot via triple fault
            Native.outb(0x64, (char)0xFE);
        } else if (inputStartsWith("cat ")) {
            doCatCommand();
        } else if (inputStartsWith("stat ")) {
            doStatCommand();
        } else if (inputStartsWith("run ")) {
            doRunCommand();
        } else if (inputStartsWith("peek ")) {
            doPeekCommand();
        } else if (inputStartsWith("poke ")) {
            doPokeCommand();
        } else if (inputStartsWith("watch ")) {
            doWatchCommand();
        } else if (inputStartsWith("unwatch ")) {
            doUnwatchCommand();
        } else {
            Console.writeString("Unknown command: ");
            int i = 0;
            while (i < inputIndex) {
                Console.writeChar(inputBuffer[i]);
                i = i + 1;
            }
            Console.writeString("\nType 'help' for available commands.\n");
        }
    }
    
    private static void showHelp() {
        Console.writeString("Available commands:\n");
        Console.writeString("  help      - Show this help\n");
        Console.writeString("  clear     - Clear the screen\n");
        Console.writeString("  info      - Show kernel info\n");
        Console.writeString("  mem       - Show memory statistics\n");
        Console.writeString("  heapstat  - Show heap statistics\n");
        Console.writeString("  vmtest    - Test virtual memory\n");
        Console.writeString("  ls        - List files\n");
        Console.writeString("  cat <fn>  - Show file contents\n");
        Console.writeString("  stat <fn> - Show file info\n");
        Console.writeString("  run <fn>  - Run a program\n");
        Console.writeString("  ps        - List threads\n");
        Console.writeString("  peek <addr> [lines] - Dump memory\n");
        Console.writeString("  poke <addr> <val>   - Write memory\n");
        Console.writeString("  watch <addr>        - Watch address\n");
        Console.writeString("  unwatch <addr>      - Unwatch address\n");
        Console.writeString("  watchlist           - Show watched addresses\n");
        Console.writeString("  history   - Show command history\n");
        Console.writeString("  shutdown  - Shutdown system\n");
        Console.writeString("  reboot    - Reboot system\n");
    }
    
    private static void showInfo() {
        Console.writeString("JOS Kernel\n");
        Console.writeString("  Version: 0.2.0 (Modular)\n");
        Console.writeString("  Thread: ");
        Console.writeNumber(Threading.getCurrentThreadId());
        Console.writeString("\n");
        Console.writeString("  Ticks: ");
        Console.writeNumber(Interrupts.getTickCount());
        Console.writeString("\n");
    }
    
    private static void showHistory() {
        Console.writeString("Command History (");
        Console.writeNumber(historyCount);
        Console.writeString(" commands):\n");
        
        if (historyCount == 0) {
            Console.writeString("  (empty)\n");
            return;
        }
        
        int startIdx = historyNext - historyCount;
        if (startIdx < 0) startIdx = startIdx + HISTORY_SIZE;
        
        int i = 0;
        while (i < historyCount) {
            int idx = startIdx + i;
            if (idx >= HISTORY_SIZE) idx = idx - HISTORY_SIZE;
            
            Console.writeString("  ");
            Console.writeNumber(i + 1);
            Console.writeString(": ");
            
            char[] histBuf = getHistoryBuffer(idx);
            int j = 0;
            while (j < HISTORY_LEN) {
                char c = histBuf[j];
                if (c == 0) break;
                Console.writeChar(c);
                j = j + 1;
            }
            Console.writeChar('\n');
            
            i = i + 1;
        }
    }
    
    private static void showWatchlist() {
        Console.writeString("Watched Addresses (");
        Console.writeNumber(watchCount);
        Console.writeString("/");
        Console.writeNumber(WATCH_MAX);
        Console.writeString("):\n");
        
        if (watchCount == 0) {
            Console.writeString("  (none)\n");
            return;
        }
        
        int i = 0;
        while (i < watchCount) {
            long addr = watchedAddresses[i];
            long val = Native.readMemoryLong(addr);
            Console.writeString("  [");
            Console.writeNumber(i + 1);
            Console.writeString("] ");
            Console.writeHex(addr);
            Console.writeString(" = ");
            Console.writeHex(val);
            Console.writeString("\n");
            i = i + 1;
        }
    }
    
    private static void doCatCommand() {
        int pos = 4;
        while (pos < inputIndex && inputBuffer[pos] == ' ') pos = pos + 1;
        if (pos >= inputIndex) {
            Console.writeString("Usage: cat <filename>\n");
            return;
        }
        
        int nameLen = inputIndex - pos;
        char[] nameBuf = new char[nameLen];
        int i = 0;
        while (i < nameLen) {
            nameBuf[i] = inputBuffer[pos + i];
            i = i + 1;
        }
        
        int fileIdx = Filesystem.findFileByName(nameBuf, nameLen);
        if (fileIdx < 0) {
            Console.writeString("File not found\n");
        } else {
            Filesystem.catFile(fileIdx);
        }
    }
    
    private static void doStatCommand() {
        int pos = 5;
        while (pos < inputIndex && inputBuffer[pos] == ' ') pos = pos + 1;
        if (pos >= inputIndex) {
            Console.writeString("Usage: stat <filename>\n");
            return;
        }
        
        int nameLen = inputIndex - pos;
        char[] nameBuf = new char[nameLen];
        int i = 0;
        while (i < nameLen) {
            nameBuf[i] = inputBuffer[pos + i];
            i = i + 1;
        }
        
        int fileIdx = Filesystem.findFileByName(nameBuf, nameLen);
        if (fileIdx < 0) {
            Console.writeString("File not found\n");
        } else {
            Filesystem.statFile(fileIdx);
        }
    }
    
    private static void doRunCommand() {
        int pos = 4;
        while (pos < inputIndex && inputBuffer[pos] == ' ') pos = pos + 1;
        if (pos >= inputIndex) {
            Console.writeString("Usage: run <filename>\n");
            return;
        }
        
        int nameLen = inputIndex - pos;
        char[] nameBuf = new char[nameLen];
        int i = 0;
        while (i < nameLen) {
            nameBuf[i] = inputBuffer[pos + i];
            i = i + 1;
        }
        
        long entryPoint = Loader.loadBinaryAuto(nameBuf, nameLen);
        if (entryPoint != 0) {
            Loader.runProgram(entryPoint);
        }
    }
    
    private static void doPeekCommand() {
        long addr = parseHex(6);
        if (addr < 0) {
            Console.writeString("Usage: peek <hex_addr> [lines]\n");
            return;
        }
        dumpMemory(addr, 8);
    }
    
    private static void doPokeCommand() {
        long addr = parseHex(6);
        // Skip to value
        int pos = 6;
        while (pos <= inputIndex && inputBuffer[pos - 1] != ' ') pos = pos + 1;
        long val = parseHex(pos);
        
        if (addr < 0 || val < 0) {
            Console.writeString("Usage: poke <hex_addr> <hex_val>\n");
            return;
        }
        
        Native.writeMemoryLong(addr, val);
        Console.writeString("Wrote ");
        Console.writeHex(val);
        Console.writeString(" to ");
        Console.writeHex(addr);
        Console.writeString("\n");
    }
    
    private static void doWatchCommand() {
        long addr = parseHex(7);
        if (addr < 0) {
            Console.writeString("Usage: watch <hex_addr>\n");
            return;
        }
        
        if (watchCount >= WATCH_MAX) {
            Console.writeString("Watch list full\n");
            return;
        }
        
        int i = 0;
        while (i < watchCount) {
            if (watchedAddresses[i] == addr) {
                Console.writeString("Already watching ");
                Console.writeHex(addr);
                Console.writeString("\n");
                return;
            }
            i = i + 1;
        }
        
        watchedAddresses[watchCount] = addr;
        watchCount = watchCount + 1;
        Console.writeString("Added watch for ");
        Console.writeHex(addr);
        Console.writeString("\n");
    }
    
    private static void doUnwatchCommand() {
        long addr = parseHex(9);
        if (addr < 0) {
            Console.writeString("Usage: unwatch <hex_addr>\n");
            return;
        }
        
        int i = 0;
        while (i < watchCount) {
            if (watchedAddresses[i] == addr) {
                int j = i;
                while (j < watchCount - 1) {
                    watchedAddresses[j] = watchedAddresses[j + 1];
                    j = j + 1;
                }
                watchCount = watchCount - 1;
                Console.writeString("Removed watch for ");
                Console.writeHex(addr);
                Console.writeString("\n");
                return;
            }
            i = i + 1;
        }
        Console.writeString("Address not in watch list\n");
    }
    
    private static void dumpMemory(long addr, int lines) {
        int line = 0;
        while (line < lines) {
            long lineAddr = addr + line * 16;
            Console.writeHex(lineAddr);
            Console.writeString(": ");
            
            int b = 0;
            while (b < 16) {
                long val = Native.readMemoryLong(lineAddr + b) & 0xFF;
                if (val < 16) Console.writeChar('0');
                Console.writeHex(val);
                Console.writeChar(' ');
                b = b + 1;
            }
            
            Console.writeString(" |");
            b = 0;
            while (b < 16) {
                char c = (char)(Native.readMemoryLong(lineAddr + b) & 0xFF);
                if (c >= 32 && c <= 126) {
                    Console.writeChar(c);
                } else {
                    Console.writeChar('.');
                }
                b = b + 1;
            }
            Console.writeString("|\n");
            
            line = line + 1;
        }
    }
    
    private static void testVirtualMemory() {
        Console.writeString("VM Test: Allocating physical page...\n");
        
        long physPage = Memory.allocPage();
        if (physPage == 0) {
            Console.writeString("FAIL: Could not allocate page\n");
            return;
        }
        
        Console.writeString("  Physical page: ");
        Console.writeHex(physPage);
        Console.writeString("\n");
        
        long virtAddr = 0x600000L;
        Console.writeString("  Mapping to virtual: ");
        Console.writeHex(virtAddr);
        Console.writeString("...\n");
        
        if (!Memory.mapPage(virtAddr, physPage, true)) {
            Console.writeString("FAIL: mapPage failed\n");
            Memory.freePage(physPage);
            return;
        }
        
        Console.writeString("  Map successful!\n");
        
        Native.writeMemoryLong(virtAddr, 0x123456789ABCDEF0L);
        Native.writeMemoryLong(virtAddr + 8, 0x0FEDCBA987654321L);
        
        long val1 = Native.readMemoryLong(virtAddr);
        long val2 = Native.readMemoryLong(virtAddr + 8);
        
        Console.writeString("  Read back: ");
        Console.writeHex(val1);
        Console.writeString(", ");
        Console.writeHex(val2);
        Console.writeString("\n");
        
        if (val1 == 0x123456789ABCDEF0L && val2 == 0x0FEDCBA987654321L) {
            Console.writeString("PASS: Virtual memory works!\n");
        } else {
            Console.writeString("FAIL: Data mismatch\n");
        }
        
        Native.writeMemoryLong(virtAddr, 0);
        Native.writeMemoryLong(virtAddr + 8, 0);
    }
    
    private static void shutdown() {
        Console.writeString("Shutting down...\n");
        // ACPI shutdown
        Native.outw(0x604, 0x2000);
        Native.outw(0xB004, 0x2000);
        // If ACPI fails, halt
        while (true) {
            Native.disableInterrupts();
            Native.enableInterrupts();
        }
    }
}
