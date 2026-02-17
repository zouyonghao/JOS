public class Kernel {

    public static native void writeMemory(long addr, char _byte);
    
    // Low-level hardware access
    public static native char inb(int port);
    public static native void outb(int port, char data);
    public static native void outw(int port, int data);
    public static native void outl(int port, int data);
    
    // Memory access (64-bit for page tables and E820)
    public static native long readMemoryLong(long addr);
    public static native void writeMemoryLong(long addr, long data);
    
    // Paging control
    public static native long getCR3();
    public static native void setCR3(long val);
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
    
    // ===================================================================
    // MEMORY MANAGEMENT (All in Java!)
    // ===================================================================
    
    // E820 memory map location (set up by bootloader)
    private static final long E820_COUNT_ADDR = 0xF000L;
    private static final long E820_BUFFER_ADDR = 0xF004L;
    private static final int E820_ENTRY_SIZE = 24;
    
    // Memory types from E820
    private static final int E820_TYPE_AVAILABLE = 1;
    private static final int E820_TYPE_RESERVED = 2;
    private static final int E820_TYPE_ACPI_RECLAIM = 3;
    private static final int E820_TYPE_ACPI_NVS = 4;
    private static final int E820_TYPE_BAD = 5;
    
    // Page constants
    private static final long PAGE_SIZE = 4096;
    private static final int PAGE_SHIFT = 12;
    private static final long PAGE_MASK = ~(PAGE_SIZE - 1);
    
    // Page table constants
    private static final long PT_PRESENT = 1L << 0;
    private static final long PT_WRITABLE = 1L << 1;
    private static final long PT_USER = 1L << 2;
    private static final long PT_LARGE = 1L << 7;
    private static final long PT_FRAME = 0x000FFFFFFFFFF000L;
    
    // Memory region tracking (simplified - supports up to 4GB with 128KB bitmap)
    private static final long MAX_PHYS_MEM = 0x100000000L;  // 4GB
    private static final int BITMAP_SIZE = (int)(MAX_PHYS_MEM / PAGE_SIZE / 8);  // 128KB
    private static final long BITMAP_START = 0x100000L;  // Place bitmap at 1MB mark
    private static long totalPages = 0;
    private static long freePages = 0;
    
    // Use bootloader's page tables at 0x1000 (don't create new ones)
    // Bootloader identity-maps first 128MB using 2MB huge pages in the PD
    private static final long BOOT_PML4_ADDR = 0x1000L;   // Bootloader's PML4
    private static final long BOOT_PDPT_ADDR = 0x2000L;   // Bootloader's PDPT
    private static final long BOOT_PD_ADDR = 0x3000L;     // Bootloader's PD (2MB huge pages)
    
    // Heap starts at 4MB
    private static final long HEAP_START = 0x400000L;
    private static final long HEAP_SIZE = 0x400000L;      // 4MB heap
    private static long heapCurrent = HEAP_START;
    private static long heapEnd = HEAP_START + HEAP_SIZE;
    
    // ===================================================================
    // PHYSICAL MEMORY MANAGER (Bitmap-based)
    // ===================================================================
    

    // Parse E820 memory map and initialize bitmap
    private static void initMemoryMap() {
        int entryCount = (int)readMemoryLong(E820_COUNT_ADDR) & 0xFFFF;
        
        writeString("E820 entries: ");
        writeNumber(entryCount);
        writeString("\n");
        
        // Clear bitmap (use int offset)
        int offset = 0;
        while (offset < BITMAP_SIZE) {
            writeMemoryLong(BITMAP_START + (long)offset, 0);
            offset = offset + 8;
        }
        
        // Mark kernel memory (0-4MB = pages 0-1023) as used
        markPagesUsed(0, 0x400);
        
        totalPages = 0;
        freePages = 0;
        
        // Parse E820 entries - use only low 32-bits, avoid long ops
        int i = 0;
        while (i < entryCount && i < 20) {  // Limit to 20 entries
            long entryAddr = E820_BUFFER_ADDR + (long)(i * E820_ENTRY_SIZE);
            
            // Read base address (only use low 32 bits for <4GB systems)
            long baseVal = readMemoryLong(entryAddr);
            int baseLow = (int)baseVal;
            
            // Read length (only use low 32 bits)
            long lengthVal = readMemoryLong(entryAddr + 8);
            int lengthLow = (int)lengthVal;
            
            int type = (int)(readMemoryLong(entryAddr + 16) & 0xFFFFFFFFL);
            
            // Debug output
            writeString("  Entry ");
            writeNumber(i);
            writeString(": base=0x");
            writeHex(baseLow);
            writeString(" len=0x");
            writeHex(lengthLow);
            writeString(" type=");
            writeNumber(type);
            writeString("\n");
            
            // Only handle entries with non-zero length in low 32-bits
            if (type == E820_TYPE_AVAILABLE && lengthLow != 0) {
                // Calculate page numbers (divide by 4096 = shift 12, done via int)
                int startPage = (baseLow + 4095) >> 12;  // Round up
                int endPage = (baseLow + lengthLow) >> 12;  // Round down
                
                // Clamp to valid range
                if (startPage < 0x400) startPage = 0x400;  // Skip first 4MB
                if (endPage > 0x40000) endPage = 0x40000;  // Max 256K pages = 1GB
                
                if (endPage > startPage) {
                    writeString("    Adding pages ");
                    writeNumber(startPage);
                    writeString(" to ");
                    writeNumber(endPage);
                    writeString("\n");
                    int page = startPage;
                    while (page < endPage) {
                        markPageFree((long)page);
                        totalPages = totalPages + 1;
                        freePages = freePages + 1;
                        page = page + 1;
                    }
                }
            }
            i = i + 1;
        }
    }
    
    // Mark a single page as used in bitmap
    private static void markPageUsed(long pageNum) {
        long byteOffset = BITMAP_START + (pageNum >> 6);
        int bitOffset = (int)(pageNum & 63);
        long val = readMemoryLong(byteOffset);
        val = val | (1L << bitOffset);
        writeMemoryLong(byteOffset, val);
    }
    
    // Mark a range of pages as used
    private static void markPagesUsed(long startPage, long count) {
        long i = 0;
        while (i < count) {
            markPageUsed(startPage + i);
            i = i + 1;
        }
    }
    
    // Mark a single page as free in bitmap
    private static void markPageFree(long pageNum) {
        long byteOffset = BITMAP_START + (pageNum >> 6);
        int bitOffset = (int)(pageNum & 63);
        long val = readMemoryLong(byteOffset);
        val = val & ~(1L << bitOffset);
        writeMemoryLong(byteOffset, val);
    }
    
    // Check if page is free
    private static boolean isPageFree(long pageNum) {
        long byteOffset = BITMAP_START + (pageNum >> 6);
        int bitOffset = (int)(pageNum & 63);
        long val = readMemoryLong(byteOffset);
        boolean free = (val & (1L << bitOffset)) == 0;
        return free;
    }
    
    // Debug version
    private static boolean isPageFreeDebug(long pageNum) {
        long byteOffset = BITMAP_START + (pageNum >> 6);
        int bitOffset = (int)(pageNum & 63);
        long val = readMemoryLong(byteOffset);
        long mask = 1L << bitOffset;
        long masked = val & mask;
        boolean free = masked == 0;
        writeString("  Page ");
        writeNumber((int)pageNum);
        writeString(" byteOff=");
        writeNumber((int)byteOffset);
        writeString(" bit=");
        writeNumber(bitOffset);
        writeString(" mask=");
        writeNumber((int)mask);
        writeString(" val=");
        writeNumber((int)val);
        writeString(" masked=");
        writeNumber((int)masked);
        writeString(free ? " FREE\n" : " USED\n");
        return free;
    }
    
    // Allocate a single physical page
    private static long allocPage() {
        writeString("  allocPage: freePages=");
        writeNumber(freePages);
        writeString(" totalPages=");
        writeNumber(totalPages);
        writeString("\n");
        
        if (freePages == 0) return 0;
        
        // Simple first-fit search (start after kernel memory)
        // Use int for iteration to avoid long comparisons
        int maxPage = (int)(totalPages + 0x400);
        if (maxPage > (int)(MAX_PHYS_MEM >> PAGE_SHIFT)) {
            maxPage = (int)(MAX_PHYS_MEM >> PAGE_SHIFT);
        }
        
        writeString("  Searching pages 0x400 to ");
        writeNumber(maxPage);
        writeString("\n");
        
        int page = 0x400;  // Start at page 1024 (4MB)
        int checked = 0;
        while (page < maxPage) {
            boolean free;
            if (checked < 3) {
                free = isPageFreeDebug((long)page);
                checked = checked + 1;
            } else {
                free = isPageFree((long)page);
            }
            if (free) {
                writeString("  Found free page: ");
                writeNumber(page);
                writeString("\n");
                markPageUsed((long)page);
                freePages = freePages - 1;
                return ((long)page) << PAGE_SHIFT;
            }
            page = page + 1;
        }
        writeString("  No free page found!\n");
        return 0;
    }
    
    // Free a physical page
    private static void freePage(long physAddr) {
        int pageNum = (int)(physAddr >> PAGE_SHIFT);
        // Don't free kernel memory (pages below 0x400)
        // Use subtraction to avoid long comparison
        int diff = pageNum - 0x400;
        if (diff >= 0) {
            markPageFree((long)pageNum);
            freePages = freePages + 1;
        }
    }
    
    // ===================================================================
    // VIRTUAL MEMORY / PAGING
    // ===================================================================
    
    // Initialize page tables for identity mapping first 4MB
    private static void initPaging() {
        // The bootloader already set up paging at 0x1000
        // We just verify it's working and don't change CR3
        // The bootloader mapped first 4MB identity
        
        // Verify we can read the page tables
        long pml4Entry = readMemoryLong(BOOT_PML4_ADDR);
        if ((pml4Entry & PT_PRESENT) == 0) {
            writeString("ERROR: PML4 not present\n");
        }
    }
    
    // Map a virtual address to a physical address
    private static boolean mapPage(long virtAddr, long physAddr, boolean writable) {
        long flags = PT_PRESENT;
        if (writable) flags = flags | PT_WRITABLE;
        
        // Calculate indices
        int pml4Index = (int)((virtAddr >> 39) & 0x1FF);
        int pdptIndex = (int)((virtAddr >> 30) & 0x1FF);
        int pdIndex = (int)((virtAddr >> 21) & 0x1FF);
        int ptIndex = (int)((virtAddr >> 12) & 0x1FF);
        
        // For now, only support first PML4 entry (maps 512GB)
        if (pml4Index != 0) return false;
        
        // Get PDPT from bootloader's PML4
        long pdptEntry = readMemoryLong(BOOT_PML4_ADDR);
        long pdptAddr = pdptEntry & PT_FRAME;
        
        // Get PD from PDPT
        long pdEntryAddr = pdptAddr + pdptIndex * 8;
        long pdEntry = readMemoryLong(pdEntryAddr);
        long pdAddr;
        if ((pdEntry & PT_PRESENT) == 0) {
            // Allocate new PD
            pdAddr = allocPage();
            if (pdAddr == 0) return false;
            writeMemoryLong(pdEntryAddr, pdAddr | PT_PRESENT | PT_WRITABLE);
            // Clear new PD
            long jd = 0;
            while (jd < 512) {
                writeMemoryLong(pdAddr + jd * 8, 0);
                jd = jd + 1;
            }
        } else {
            pdAddr = pdEntry & PT_FRAME;
        }
        
        // Get PT from PD
        long ptEntryAddr = pdAddr + pdIndex * 8;
        long ptEntry = readMemoryLong(ptEntryAddr);
        long ptAddr;
        if ((ptEntry & PT_PRESENT) == 0) {
            // Allocate new PT
            ptAddr = allocPage();
            if (ptAddr == 0) return false;
            writeMemoryLong(ptEntryAddr, ptAddr | PT_PRESENT | PT_WRITABLE);
            // Clear new PT
            long jt = 0;
            while (jt < 512) {
                writeMemoryLong(ptAddr + jt * 8, 0);
                jt = jt + 1;
            }
        } else {
            ptAddr = ptEntry & PT_FRAME;
        }
        
        // Set page table entry
        writeMemoryLong(ptAddr + ptIndex * 8, (physAddr & PT_FRAME) | flags);
        return true;
    }
    
    // ===================================================================
    // HEAP ALLOCATOR (Simple bump allocator with page allocation)
    // ===================================================================
    
    private static long heapAlloc(long size) {
        // Align to 8 bytes
        size = (size + 7) & ~7;
        
        long result = heapCurrent;
        long newCurrent = heapCurrent + size;
        
        // Check if we need more pages
        while (newCurrent > heapEnd) {
            long page = allocPage();
            if (page == 0) return 0;  // Out of memory
            
            if (!mapPage(heapEnd, page, true)) {
                freePage(page);
                return 0;
            }
            heapEnd = heapEnd + PAGE_SIZE;
        }
        
        heapCurrent = newCurrent;
        return result;
    }
    
    // ===================================================================
    // VIRTUAL MEMORY TEST
    // ===================================================================
    
    private static void testVirtualMemory() {
        writeString("VM Test: Allocating physical page...\n");
        
        // Allocate a physical page
        long physPage = allocPage();
        if (physPage == 0) {
            writeString("FAIL: Could not allocate page\n");
            return;
        }
        
        writeString("  Physical page: 0x");
        writeHex(physPage);
        writeString("\n");
        
        // Map it to virtual address 0x600000 (6MB - above kernel)
        long virtAddr = 0x600000L;
        writeString("  Mapping to virtual: 0x");
        writeHex(virtAddr);
        writeString("...\n");
        
        if (!mapPage(virtAddr, physPage, true)) {
            writeString("FAIL: mapPage failed\n");
            freePage(physPage);
            return;
        }
        
        writeString("  Map successful!\n");
        
        // Write test pattern via virtual address
        writeString("  Writing test pattern...\n");
        writeMemoryLong(virtAddr, 0x123456789ABCDEF0L);
        writeMemoryLong(virtAddr + 8, 0x0FEDCBA987654321L);
        
        // Read back via virtual address
        writeString("  Reading via virtual address...\n");
        long val1 = readMemoryLong(virtAddr);
        long val2 = readMemoryLong(virtAddr + 8);
        
        writeString("  Read back: 0x");
        writeHex(val1);
        writeString(", 0x");
        writeHex(val2);
        writeString("\n");
        
        // Verify
        if (val1 == 0x123456789ABCDEF0L && val2 == 0x0FEDCBA987654321L) {
            writeString("PASS: Virtual memory works!\n");
        } else {
            writeString("FAIL: Data mismatch\n");
        }
        
        // Cleanup
        writeMemoryLong(virtAddr, 0);
        writeMemoryLong(virtAddr + 8, 0);
    }
    
    // Write a 64-bit value as hex
    private static void writeHex(long val) {
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
    
    private static void printMemoryStats() {
        writeString("Memory Stats:\n");
        writeString("  Total pages: ");
        writeNumber(totalPages);
        writeString("\n  Free pages: ");
        writeNumber(freePages);
        writeString("\n  Used pages: ");
        writeNumber(totalPages - freePages);
        writeString("\n  Heap used: ");
        writeNumber((heapCurrent - HEAP_START) / 1024);
        writeString(" KB\n");
    }
    
    // Static buffer for number conversion (no heap allocation)
    private static char n1=0, n2=0, n3=0, n4=0, n5=0, n6=0, n7=0, n8=0, n9=0, n10=0;
    private static char n11=0, n12=0, n13=0, n14=0, n15=0, n16=0, n17=0, n18=0, n19=0, n20=0;
    
    private static void writeNumber(long num) {
        if (num == 0) {
            writeChar('0');
            return;
        }
        
        int i = 0;
        while (num > 0) {
            char digit = (char)('0' + (num % 10));
            if (i == 0) n1 = digit;
            else if (i == 1) n2 = digit;
            else if (i == 2) n3 = digit;
            else if (i == 3) n4 = digit;
            else if (i == 4) n5 = digit;
            else if (i == 5) n6 = digit;
            else if (i == 6) n7 = digit;
            else if (i == 7) n8 = digit;
            else if (i == 8) n9 = digit;
            else if (i == 9) n10 = digit;
            else if (i == 10) n11 = digit;
            else if (i == 11) n12 = digit;
            else if (i == 12) n13 = digit;
            else if (i == 13) n14 = digit;
            else if (i == 14) n15 = digit;
            else if (i == 15) n16 = digit;
            else if (i == 16) n17 = digit;
            else if (i == 17) n18 = digit;
            else if (i == 18) n19 = digit;
            else if (i == 19) n20 = digit;
            num = num / 10;
            i = i + 1;
        }
        
        while (i > 0) {
            i = i - 1;
            if (i == 0) writeChar(n1);
            else if (i == 1) writeChar(n2);
            else if (i == 2) writeChar(n3);
            else if (i == 3) writeChar(n4);
            else if (i == 4) writeChar(n5);
            else if (i == 5) writeChar(n6);
            else if (i == 6) writeChar(n7);
            else if (i == 7) writeChar(n8);
            else if (i == 8) writeChar(n9);
            else if (i == 9) writeChar(n10);
            else if (i == 10) writeChar(n11);
            else if (i == 11) writeChar(n12);
            else if (i == 12) writeChar(n13);
            else if (i == 13) writeChar(n14);
            else if (i == 14) writeChar(n15);
            else if (i == 15) writeChar(n16);
            else if (i == 16) writeChar(n17);
            else if (i == 17) writeChar(n18);
            else if (i == 18) writeChar(n19);
            else if (i == 19) writeChar(n20);
        }
    }

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
    
    // ATA PIO ports (Primary bus)
    private static final int ATA_DATA = 0x1F0;
    private static final int ATA_ERROR = 0x1F1;
    private static final int ATA_SECTOR_COUNT = 0x1F2;
    private static final int ATA_LBA_LOW = 0x1F3;
    private static final int ATA_LBA_MID = 0x1F4;
    private static final int ATA_LBA_HIGH = 0x1F5;
    private static final int ATA_DRIVE_SELECT = 0x1F6;
    private static final int ATA_STATUS = 0x1F7;
    private static final int ATA_COMMAND = 0x1F7;
    
    // ATA commands
    private static final char ATA_CMD_READ_SECTORS = 0x20;
    private static final char ATA_CMD_WRITE_SECTORS = 0x30;
    private static final char ATA_CMD_IDENTIFY = 0xEC;
    
    // ATA status bits
    private static final char ATA_SR_BSY = 0x80;   // Busy
    private static final char ATA_SR_DRDY = 0x40;  // Drive ready
    private static final char ATA_SR_DRQ = 0x08;   // Data request ready
    
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
    
    // Helper to check if buffer matches "mem" (3 chars)
    private static boolean isMem() {
        if (inputIndex != 3) return false;
        if (c1 != 'm') return false;
        if (c2 != 'e') return false;
        if (c3 != 'm') return false;
        return true;
    }
    
    // Helper to check if buffer matches "vmtest" (6 chars)
    private static boolean isVmtest() {
        if (inputIndex != 6) return false;
        if (c1 != 'v') return false;
        if (c2 != 'm') return false;
        if (c3 != 't') return false;
        if (c4 != 'e') return false;
        if (c5 != 's') return false;
        if (c6 != 't') return false;
        return true;
    }
    
    // Helper to check if buffer matches "memstat" (7 chars)
    private static boolean isMemstat() {
        if (inputIndex != 7) return false;
        if (c1 != 'm') return false;
        if (c2 != 'e') return false;
        if (c3 != 'm') return false;
        if (c4 != 's') return false;
        if (c5 != 't') return false;
        if (c6 != 'a') return false;
        if (c7 != 't') return false;
        return true;
    }
    
    // Helper to check if buffer matches "dump" (4 chars)
    private static boolean isDump() {
        if (inputIndex != 4) return false;
        if (c1 != 'd') return false;
        if (c2 != 'u') return false;
        if (c3 != 'm') return false;
        if (c4 != 'p') return false;
        return true;
    }
    
    private static boolean isSerial() {
        if (inputIndex != 6) return false;
        if (c1 != 's') return false;
        if (c2 != 'e') return false;
        if (c3 != 'r') return false;
        if (c4 != 'i') return false;
        if (c5 != 'a') return false;
        if (c6 != 'l') return false;
        return true;
    }
    
    private static boolean isDisktest() {
        if (inputIndex != 8) return false;
        if (c1 != 'd') return false;
        if (c2 != 'i') return false;
        if (c3 != 's') return false;
        if (c4 != 'k') return false;
        if (c5 != 't') return false;
        if (c6 != 'e') return false;
        if (c7 != 's') return false;
        if (c8 != 't') return false;
        return true;
    }
    
    // Helper to check if buffer starts with "peek "
    private static boolean isPeek() {
        if (inputIndex < 6) return false; // "peek " + at least 1 hex char
        if (c1 != 'p') return false;
        if (c2 != 'e') return false;
        if (c3 != 'e') return false;
        if (c4 != 'k') return false;
        if (c5 != ' ') return false;
        return true;
    }
    
    // Helper to check if buffer starts with "poke "
    private static boolean isPoke() {
        if (inputIndex < 8) return false; // "poke " + addr + space + value
        if (c1 != 'p') return false;
        if (c2 != 'o') return false;
        if (c3 != 'k') return false;
        if (c4 != 'e') return false;
        if (c5 != ' ') return false;
        return true;
    }
    
    // Parse hex character to value, returns -1 if invalid
    private static int hexCharToVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
    
    // Get character at position (1-indexed)
    private static char getInputChar(int pos) {
        if (pos == 1) return c1;
        if (pos == 2) return c2;
        if (pos == 3) return c3;
        if (pos == 4) return c4;
        if (pos == 5) return c5;
        if (pos == 6) return c6;
        if (pos == 7) return c7;
        if (pos == 8) return c8;
        if (pos == 9) return c9;
        if (pos == 10) return c10;
        if (pos == 11) return c11;
        if (pos == 12) return c12;
        return 0;
    }
    
    // Parse hex number starting at position, returns -1 if no valid hex
    // Stores next position in static variable parseNextPos
    private static int parseNextPos = 0;
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
        parseNextPos = pos;
        return val;
    }
    
    private static void writeSerialMessage(String msg) {
        if (msg == null) return;
        int i = 0;
        int len = msg.length();
        while (i < len) {
            writeSerial(msg.charAt(i));
            i = i + 1;
        }
    }
    
    // Native method for 32-bit port output (needed for ACPI shutdown)
    
    // ===================================================================
    // ATA PIO DISK I/O
    // ===================================================================
    
    // Read a 16-bit word from the ATA data port
    private static int ataReadWord() {
        // Read two bytes from data port and combine into a word
        char low = inb(ATA_DATA);
        char high = inb(ATA_DATA);
        return ((int)high << 8) | ((int)low & 0xFF);
    }
    
    // Wait until the drive is not busy
    private static void ataWaitNotBusy() {
        char status;
        do {
            status = inb(ATA_STATUS);
        } while ((status & ATA_SR_BSY) != 0);
    }
    
    // Wait until data is ready (DRQ set)
    private static void ataWaitDataReady() {
        char status;
        do {
            status = inb(ATA_STATUS);
        } while ((status & ATA_SR_DRQ) == 0 && (status & ATA_SR_BSY) == 0);
    }
    
    // Read a single sector (512 bytes) using LBA28 addressing
    // drive: 0 = master, 1 = slave
    private static void ataReadSector(int lba, int drive, long bufferAddr) {
        // Wait for drive to be ready
        ataWaitNotBusy();
        
        // Select drive and set upper LBA bits (bits 24-27)
        char driveSelect = (char)(0xE0 | (drive << 4) | ((lba >> 24) & 0x0F));
        outb(ATA_DRIVE_SELECT, driveSelect);
        
        // Small delay
        ioWait();
        
        // Set sector count (1 sector)
        outb(ATA_SECTOR_COUNT, (char)1);
        
        // Set LBA address (low, mid, high bytes)
        outb(ATA_LBA_LOW, (char)(lba & 0xFF));
        outb(ATA_LBA_MID, (char)((lba >> 8) & 0xFF));
        outb(ATA_LBA_HIGH, (char)((lba >> 16) & 0xFF));
        
        // Send read command
        outb(ATA_COMMAND, ATA_CMD_READ_SECTORS);
        
        // Wait for data to be ready
        ataWaitNotBusy();
        
        // Read 256 words (512 bytes) from data port
        int i = 0;
        long addr = bufferAddr;
        while (i < 256) {
            int word = ataReadWord();
            // Store low byte
            writeMemory(addr, (char)(word & 0xFF));
            // Store high byte
            writeMemory(addr + 1, (char)((word >> 8) & 0xFF));
            addr = addr + 2;
            i = i + 1;
        }
    }
    
    // Read multiple sectors from disk
    // Returns true on success, false on failure
    private static boolean readDisk(int lba, int count, long bufferAddr) {
        if (count <= 0) return false;
        if (lba < 0) return false;
        
        int sector = 0;
        long addr = bufferAddr;
        while (sector < count) {
            int currentLba = lba + sector;
            ataReadSector(currentLba, 0, addr);
            addr = addr + 512;
            sector = sector + 1;
        }
        return true;
    }
    
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
    
    // Write a byte as 2-digit hex (for memory dump)
    private static void writeHexByte(int val) {
        int nibbleHigh = (val >> 4) & 0xF;
        int nibbleLow = val & 0xF;
        char cHigh;
        char cLow;
        if (nibbleHigh < 10) {
            cHigh = (char)('0' + nibbleHigh);
        } else {
            cHigh = (char)('A' + nibbleHigh - 10);
        }
        if (nibbleLow < 10) {
            cLow = (char)('0' + nibbleLow);
        } else {
            cLow = (char)('A' + nibbleLow - 10);
        }
        writeChar(cHigh);
        writeChar(cLow);
    }
    
    // Write a 32-bit value as 8-digit hex (for addresses)
    private static void writeHexLong32(long val) {
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
    
    // Dump memory at given address for specified number of lines
    private static void dumpMemory(long addr, int lines) {
        int line = 0;
        while (line < lines) {
            long lineAddr = addr + (line * 16L);
            
            // Print address
            writeHexLong32(lineAddr);
            writeString(": ");
            
            // Read and print 16 bytes in hex
            int i = 0;
            while (i < 16) {
                long byteAddr = lineAddr + i;
                // Read as a char (byte) from memory
                char b = (char)(readMemoryLong(byteAddr) & 0xFFL);
                writeHexByte((int)b);
                writeChar(' ');
                i = i + 1;
            }
            
            // Print ASCII representation
            writeString(" |");
            i = 0;
            while (i < 16) {
                long byteAddr = lineAddr + i;
                char b = (char)(readMemoryLong(byteAddr) & 0xFFL);
                if (b >= 32 && b <= 126) {
                    writeChar(b);
                } else {
                    writeChar('.');
                }
                i = i + 1;
            }
            writeString("|\n");
            
            line = line + 1;
        }
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
            writeString("  mem     - Show memory statistics\n");
            writeString("  memstat - Show detailed memory stats\n");
            writeString("  dump    - Dump VGA memory (0xB8000)\n");
            writeString("  vmtest  - Test virtual memory mapping\n");
            writeString("  serial  - Send test message to COM1 serial port\n");
            writeString("  disktest- Read and display boot sector\n");
            writeString("  peek    - Read memory (e.g., peek B8000)\n");
            writeString("  poke    - Write memory (e.g., poke B8000 1234)\n");
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
        } else if (isMem()) {
            printMemoryStats();
        } else if (isMemstat()) {
            printMemoryStats();
        } else if (isDump()) {
            dumpMemory(0xB8000L, 5);
        } else if (isVmtest()) {
            testVirtualMemory();
        } else if (isSerial()) {
            writeString("Sending test message to COM1 serial port...\n");
            writeSerialMessage("Hello from JavaOS Kernel via COM1!\n");
            writeString("Message sent to COM1.\n");
        } else if (isDisktest()) {
            writeString("Reading boot sector (LBA 0)...\n");
            // Allocate a buffer on the heap for sector data (512 bytes)
            long buffer = heapAlloc(512);
            if (buffer == 0) {
                writeString("FAIL: Could not allocate buffer\n");
            } else {
                boolean success = readDisk(0, 1, buffer);
                if (success) {
                    writeString("Boot sector read successfully.\n");
                    writeString("First 64 bytes in hex:\n");
                    // Dump first 64 bytes (4 lines of 16 bytes)
                    dumpMemory(buffer, 4);
                } else {
                    writeString("FAIL: Could not read boot sector\n");
                }
                // Note: buffer is not freed (simplified heap)
            }
        } else if (isPeek()) {
            long addr = parseHex(6); // After "peek "
            if (addr < 0) {
                writeString("Usage: peek <hexaddr>\n");
            } else {
                long val = readMemoryLong(addr);
                writeString("Value at 0x");
                writeHex(addr);
                writeString(": 0x");
                writeHex(val);
                writeString("\n");
            }
        } else if (isPoke()) {
            long addr = parseHex(6); // After "poke "
            if (addr < 0) {
                writeString("Usage: poke <hexaddr> <hexvalue>\n");
            } else {
                int next = parseNextPos;
                // Skip space
                if (next <= inputIndex && getInputChar(next) == ' ') {
                    next = next + 1;
                    long val = parseHex(next);
                    if (val < 0) {
                        writeString("Usage: poke <hexaddr> <hexvalue>\n");
                    } else {
                        writeMemoryLong(addr, val);
                        writeString("Wrote 0x");
                        writeHex(val);
                        writeString(" to 0x");
                        writeHex(addr);
                        writeString("\n");
                    }
                } else {
                    writeString("Usage: poke <hexaddr> <hexvalue>\n");
                }
            }
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
        
        // Initialize memory management
        writeString("Initializing memory...\n");
        initMemoryMap();
        writeString("Memory map parsed.\n");
        initPaging();
        writeString("Paging enabled.\n");
        
        // Run VM test automatically
        writeString("Running VM test...\n");
        testVirtualMemory();
        writeString("\n");
        
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
