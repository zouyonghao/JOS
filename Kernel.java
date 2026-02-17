public class Kernel {

    public static native void writeMemory(long addr, char _byte);
    
    // Low-level hardware access
    public static native char inb(int port);
    public static native int inw(int port);
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
    
    // Program execution
    public static native void callProgram(long entryPoint);
    
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
    private static final long HEAP_HEADER_SIZE = 8;       // Size of block header (8 bytes)
    private static final long HEAP_MIN_BLOCK_SIZE = 16;   // Minimum allocatable block size
    private static final long HEAP_ALLOCATED_FLAG = 0x8000000000000000L;  // High bit marks allocated
    
    // Heap statistics
    private static long heapCurrent = HEAP_START;
    private static long heapEnd = HEAP_START + HEAP_SIZE;
    private static long freeListHead = 0;                 // Head of free list (0 = empty)
    private static long heapTotalAllocated = 0;           // Total bytes allocated
    private static long heapTotalFree = 0;                // Total bytes free
    private static int heapFreeBlocks = 0;                // Number of free blocks
    
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
    // HEAP ALLOCATOR (Free list allocator with coalescing)
    // ===================================================================
    
    // Get block size from header (masked to remove allocated flag)
    private static long getBlockSize(long header) {
        return header & ~HEAP_ALLOCATED_FLAG;
    }
    
    // Check if block is allocated
    private static boolean isBlockAllocated(long header) {
        return (header & HEAP_ALLOCATED_FLAG) != 0;
    }
    
    // Read block header at given address
    private static long readBlockHeader(long addr) {
        return readMemoryLong(addr);
    }
    
    // Write block header at given address
    private static void writeBlockHeader(long addr, long size, boolean allocated) {
        long header = size;
        if (allocated) {
            header = header | HEAP_ALLOCATED_FLAG;
        }
        writeMemoryLong(addr, header);
    }
    
    // Get next free block from free list (stored in data area of free block)
    private static long getNextFree(long blockAddr) {
        return readMemoryLong(blockAddr + HEAP_HEADER_SIZE);
    }
    
    // Set next free block in free list
    private static void setNextFree(long blockAddr, long nextAddr) {
        writeMemoryLong(blockAddr + HEAP_HEADER_SIZE, nextAddr);
    }
    
    // Extend heap by allocating new pages
    private static boolean extendHeap(long minSize) {
        // Calculate how many pages we need
        long needed = minSize + HEAP_HEADER_SIZE;
        long pagesNeeded = (needed + PAGE_SIZE - 1) / PAGE_SIZE;
        
        int i = 0;
        while (i < pagesNeeded) {
            long page = allocPage();
            if (page == 0) return false;  // Out of memory
            
            if (!mapPage(heapEnd, page, true)) {
                freePage(page);
                return false;
            }
            heapEnd = heapEnd + PAGE_SIZE;
            i = i + 1;
        }
        return true;
    }
    
    // Split a free block if it's much larger than needed
    // Returns address of the allocated block
    private static long splitBlock(long blockAddr, long blockSize, long neededSize) {
        // Minimum size for a new free block (header + next pointer + some data)
        long minSplitSize = HEAP_HEADER_SIZE + 8 + HEAP_MIN_BLOCK_SIZE;
        
        if (blockSize >= neededSize + minSplitSize) {
            // Split the block
            long remaining = blockSize - neededSize - HEAP_HEADER_SIZE;
            long newBlockAddr = blockAddr + HEAP_HEADER_SIZE + neededSize;
            
            // Write header for new free block
            writeBlockHeader(newBlockAddr, remaining, false);
            
            // Add new block to free list
            setNextFree(newBlockAddr, freeListHead);
            freeListHead = newBlockAddr;
            heapFreeBlocks = heapFreeBlocks + 1;
            heapTotalFree = heapTotalFree + remaining;
            
            // Update original block size
            blockSize = neededSize;
        }
        
        // Mark block as allocated
        writeBlockHeader(blockAddr, blockSize, true);
        heapTotalAllocated = heapTotalAllocated + blockSize;
        heapTotalFree = heapTotalFree - blockSize;
        
        return blockAddr + HEAP_HEADER_SIZE;
    }
    
    // Coalesce adjacent free blocks
    private static void coalesce() {
        if (freeListHead == 0) return;
        
        // Simple coalescing: scan through free list and merge adjacent blocks
        // This is O(n^2) but simple and correct for a small kernel
        boolean changed = true;
        while (changed) {
            changed = false;
            long current = freeListHead;
            long prev = 0;
            
            while (current != 0) {
                long currentSize = getBlockSize(readBlockHeader(current));
                long currentEnd = current + HEAP_HEADER_SIZE + currentSize;
                long next = getNextFree(current);
                
                // Check if next block in memory is free
                long scan = freeListHead;
                long scanPrev = 0;
                boolean found = false;
                
                while (scan != 0) {
                    if (scan == currentEnd) {
                        // Found adjacent block - merge
                        long scanSize = getBlockSize(readBlockHeader(scan));
                        long newSize = currentSize + HEAP_HEADER_SIZE + scanSize;
                        
                        // Remove scan from free list
                        if (scanPrev == 0) {
                            freeListHead = getNextFree(scan);
                        } else {
                            setNextFree(scanPrev, getNextFree(scan));
                        }
                        
                        // Update current block size
                        writeBlockHeader(current, newSize, false);
                        heapTotalFree = heapTotalFree + HEAP_HEADER_SIZE;
                        heapFreeBlocks = heapFreeBlocks - 1;
                        
                        changed = true;
                        found = true;
                        break;
                    }
                    scanPrev = scan;
                    scan = getNextFree(scan);
                }
                
                if (found) {
                    // Restart scan since we modified the list
                    break;
                }
                
                prev = current;
                current = next;
            }
        }
    }
    
    // Allocate memory from heap using first-fit
    public static long heapAlloc(long size) {
        // Align to 8 bytes minimum
        if (size < HEAP_MIN_BLOCK_SIZE) {
            size = HEAP_MIN_BLOCK_SIZE;
        }
        size = (size + 7) & ~7;
        
        // Search free list for suitable block (first-fit)
        long prev = 0;
        long current = freeListHead;
        long neededSize = size;
        
        while (current != 0) {
            long header = readBlockHeader(current);
            long blockSize = getBlockSize(header);
            
            if (blockSize >= neededSize) {
                // Found suitable block - remove from free list
                long next = getNextFree(current);
                if (prev == 0) {
                    freeListHead = next;
                } else {
                    setNextFree(prev, next);
                }
                heapFreeBlocks = heapFreeBlocks - 1;
                heapTotalFree = heapTotalFree - blockSize;
                
                // Split if block is much larger than needed
                return splitBlock(current, blockSize, neededSize);
            }
            
            prev = current;
            current = getNextFree(current);
        }
        
        // No suitable block found - extend heap
        long resultAddr = heapCurrent;
        long totalNeeded = neededSize + HEAP_HEADER_SIZE;
        
        // Ensure we have enough mapped pages
        while (resultAddr + totalNeeded > heapEnd) {
            if (!extendHeap(totalNeeded)) {
                return 0;  // Out of memory
            }
        }
        
        // Initialize block header
        writeBlockHeader(resultAddr, neededSize, true);
        heapCurrent = resultAddr + HEAP_HEADER_SIZE + neededSize;
        heapTotalAllocated = heapTotalAllocated + neededSize;
        
        return resultAddr + HEAP_HEADER_SIZE;
    }
    
    // Free memory back to heap
    public static void heapFree(long ptr) {
        if (ptr == 0) return;
        
        // Get block address (ptr points after header)
        long blockAddr = ptr - HEAP_HEADER_SIZE;
        long header = readBlockHeader(blockAddr);
        long blockSize = getBlockSize(header);
        
        // Already free?
        if (!isBlockAllocated(header)) {
            return;
        }
        
        // Mark as free
        writeBlockHeader(blockAddr, blockSize, false);
        heapTotalAllocated = heapTotalAllocated - blockSize;
        heapTotalFree = heapTotalFree + blockSize;
        
        // Add to free list
        setNextFree(blockAddr, freeListHead);
        freeListHead = blockAddr;
        heapFreeBlocks = heapFreeBlocks + 1;
        
        // Try to coalesce with adjacent blocks
        coalesce();
    }
    
    // Get heap statistics
    private static void printHeapStats() {
        writeString("Heap Statistics:\n");
        writeString("  Start: 0x");
        writeHex(HEAP_START);
        writeString("\n");
        writeString("  Current: 0x");
        writeHex(heapCurrent);
        writeString("\n");
        writeString("  End: 0x");
        writeHex(heapEnd);
        writeString("\n");
        writeString("  Total size: ");
        writeNumber((int)(heapEnd - HEAP_START));
        writeString(" bytes\n");
        writeString("  Allocated: ");
        writeNumber((int)heapTotalAllocated);
        writeString(" bytes\n");
        writeString("  Free: ");
        writeNumber((int)heapTotalFree);
        writeString(" bytes\n");
        writeString("  Free blocks: ");
        writeNumber(heapFreeBlocks);
        writeString("\n");
        writeString("  Unallocated: ");
        writeNumber((int)(heapEnd - heapCurrent));
        writeString(" bytes\n");
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
    private static char[] numBuffer = new char[20];
    
    private static void writeNumber(long num) {
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
    
    // Keyboard state - ring buffer for better handling
    private static final int RING_SIZE = 256;
    private static char[] ringBuffer = new char[RING_SIZE];
    private static int ringHead = 0;
    private static int ringTail = 0;
    
    // Shell input state (using array for larger buffer)
    private static final int INPUT_MAX = 64;
    private static char[] inputBuffer = new char[INPUT_MAX];
    private static int inputIndex = 0;
    
    // ===================================================================
    // COMMAND HISTORY (circular buffer using 1D arrays)
    // ===================================================================
    private static final int HISTORY_SIZE = 8;
    private static final int HISTORY_LEN = 64;
    // Use flat arrays instead of 2D for translator compatibility
    private static char[] history0 = new char[HISTORY_LEN];
    private static char[] history1 = new char[HISTORY_LEN];
    private static char[] history2 = new char[HISTORY_LEN];
    private static char[] history3 = new char[HISTORY_LEN];
    private static char[] history4 = new char[HISTORY_LEN];
    private static char[] history5 = new char[HISTORY_LEN];
    private static char[] history6 = new char[HISTORY_LEN];
    private static char[] history7 = new char[HISTORY_LEN];
    private static int historyCount = 0;
    private static int historyNext = 0;  // Next slot to write
    
    // Get history buffer for slot (0-7)
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
    
    // ===================================================================
    // MEMORY MONITOR (watch addresses)
    // ===================================================================
    private static final int WATCH_MAX = 8;
    private static long[] watchedAddresses = new long[WATCH_MAX];
    private static int watchCount = 0;

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
    
    // ===================================================================
    // ARRAY-BASED STRING/INPUT UTILITIES
    // ===================================================================
    
    // Compare input buffer with string (length-first match)
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
    
    // Check if input starts with prefix
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
    
    // Helper checkers using array-based comparison
    private static boolean isHelp() { return inputEquals("help"); }
    private static boolean isClear() { return inputEquals("clear"); }
    private static boolean isInfo() { return inputEquals("info"); }
    private static boolean isReboot() { return inputEquals("reboot"); }
    private static boolean isTime() { return inputEquals("time"); }
    private static boolean isShutdown() { return inputEquals("shutdown"); }
    private static boolean isMem() { return inputEquals("mem"); }
    private static boolean isVmtest() { return inputEquals("vmtest"); }
    private static boolean isMemstat() { return inputEquals("memstat"); }
    private static boolean isHeapstat() { return inputEquals("heapstat"); }
    private static boolean isDump() { return inputEquals("dump"); }
    private static boolean isSerial() { return inputEquals("serial"); }
    private static boolean isDisktest() { return inputEquals("disktest"); }
    private static boolean isHistory() { return inputEquals("history"); }
    private static boolean isWatchlist() { return inputEquals("watchlist"); }
    private static boolean isPeek() { return inputStartsWith("peek "); }
    private static boolean isPoke() { return inputStartsWith("poke "); }
    private static boolean isWatch() { return inputStartsWith("watch "); }
    private static boolean isUnwatch() { return inputStartsWith("unwatch "); }
    private static boolean isLs() { return inputEquals("ls"); }
    private static boolean isCat() { return inputStartsWith("cat "); }
    private static boolean isStat() { return inputStartsWith("stat "); }
    private static boolean isRun() { return inputStartsWith("run "); }
    
    // Get character at position (1-indexed for compatibility with old code)
    private static char getInputChar(int pos) {
        if (pos >= 1 && pos <= inputIndex) {
            return inputBuffer[pos - 1];
        }
        return 0;
    }
    
    // Parse hex character to value, returns -1 if invalid
    private static int hexCharToVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
    
    // Parse hex number starting at position, returns -1 if no valid hex
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
    
    // ===================================================================
    // COMMAND HISTORY FUNCTIONS
    // ===================================================================
    
    // Save current command to history (circular buffer)
    private static void saveToHistory() {
        if (inputIndex == 0) return;
        
        // Copy command to history slot
        int slot = historyNext;
        char[] histBuf = getHistoryBuffer(slot);
        int i = 0;
        while (i < inputIndex && i < HISTORY_LEN - 1) {
            histBuf[i] = inputBuffer[i];
            i = i + 1;
        }
        histBuf[i] = 0;  // Null terminate
        
        historyNext = historyNext + 1;
        if (historyNext >= HISTORY_SIZE) {
            historyNext = 0;
        }
        if (historyCount < HISTORY_SIZE) {
            historyCount = historyCount + 1;
        }
    }
    
    // Display command history
    private static void showHistory() {
        writeString("Command History (");
        writeNumber(historyCount);
        writeString(" commands):\n");
        
        if (historyCount == 0) {
            writeString("  (empty)\n");
            return;
        }
        
        // Calculate starting index (oldest command)
        int startIdx = historyNext - historyCount;
        if (startIdx < 0) startIdx = startIdx + HISTORY_SIZE;
        
        int i = 0;
        while (i < historyCount) {
            int idx = startIdx + i;
            if (idx >= HISTORY_SIZE) idx = idx - HISTORY_SIZE;
            
            writeString("  ");
            writeNumber(i + 1);
            writeString(": ");
            
            // Print command from history array
            char[] histBuf = getHistoryBuffer(idx);
            int j = 0;
            while (j < HISTORY_LEN) {
                char c = histBuf[j];
                if (c == 0) break;
                writeChar(c);
                j = j + 1;
            }
            writeChar('\n');
            
            i = i + 1;
        }
    }
    
    // ===================================================================
    // MEMORY WATCH FUNCTIONS
    // ===================================================================
    
    // Add address to watch list
    private static void addWatch(long addr) {
        if (watchCount >= WATCH_MAX) {
            writeString("Watch list full (max ");
            writeNumber(WATCH_MAX);
            writeString(")\n");
            return;
        }
        // Check if already watching
        int i = 0;
        while (i < watchCount) {
            if (watchedAddresses[i] == addr) {
                writeString("Address 0x");
                writeHex(addr);
                writeString(" is already being watched\n");
                return;
            }
            i = i + 1;
        }
        watchedAddresses[watchCount] = addr;
        watchCount = watchCount + 1;
        writeString("Added watch for 0x");
        writeHex(addr);
        writeString("\n");
    }
    
    // Remove address from watch list
    private static void removeWatch(long addr) {
        int i = 0;
        while (i < watchCount) {
            if (watchedAddresses[i] == addr) {
                // Shift remaining addresses down
                int j = i;
                while (j < watchCount - 1) {
                    watchedAddresses[j] = watchedAddresses[j + 1];
                    j = j + 1;
                }
                watchCount = watchCount - 1;
                writeString("Removed watch for 0x");
                writeHex(addr);
                writeString("\n");
                return;
            }
            i = i + 1;
        }
        writeString("Address 0x");
        writeHex(addr);
        writeString(" is not in watch list\n");
    }
    
    // Display watch list with current values
    private static void showWatchlist() {
        writeString("Watched Addresses (");
        writeNumber(watchCount);
        writeString("/");
        writeNumber(WATCH_MAX);
        writeString("):\n");
        
        if (watchCount == 0) {
            writeString("  (none)\n");
            return;
        }
        
        int i = 0;
        while (i < watchCount) {
            long addr = watchedAddresses[i];
            long val = readMemoryLong(addr);
            writeString("  [");
            writeNumber(i + 1);
            writeString("] 0x");
            writeHex(addr);
            writeString(" = 0x");
            writeHex(val);
            writeString("\n");
            i = i + 1;
        }
    }
    
    // ===================================================================
    // RING BUFFER for keyboard input
    // ===================================================================
    
    // Add character to ring buffer (called from interrupt handler)
    private static void ringBufferPut(char c) {
        int nextHead = ringHead + 1;
        if (nextHead >= RING_SIZE) nextHead = 0;
        if (nextHead != ringTail) {  // Only add if not full
            ringBuffer[ringHead] = c;
            ringHead = nextHead;
        }
    }
    
    // Get character from ring buffer (returns 0 if empty)
    private static char ringBufferGet() {
        if (ringHead == ringTail) return 0;  // Empty
        char c = ringBuffer[ringTail];
        ringTail = ringTail + 1;
        if (ringTail >= RING_SIZE) ringTail = 0;
        return c;
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
    
    // ===================================================================
    // SIMPLE FLAT READ-ONLY FILESYSTEM (SFROFS)
    // ===================================================================
    
    // SFROFS constants
    private static final int SFROFS_SECTOR_SIZE = 512;
    private static final int SFROFS_SUPERBLOCK_SECTOR = 2049;  // 1MB offset for filesystem
    private static final int SFROFS_MAGIC_0 = 'S';  // S
    private static final int SFROFS_MAGIC_1 = 'F';  // F
    private static final int SFROFS_MAGIC_2 = 'R';  // R
    private static final int SFROFS_MAGIC_3 = 'O';  // O
    private static final int SFROFS_VERSION = 1;
    
    // File entry size: 48 (name) + 4 (start) + 4 (size) + 8 (reserved) = 64 bytes
    private static final int SFROFS_ENTRY_SIZE = 64;
    private static final int SFROFS_NAME_MAX = 48;
    private static final int SFROFS_MAX_FILES = 32;  // Maximum files we support
    
    // Filesystem state - using static arrays only (no 'new' operator)
    private static int fsNumFiles = 0;
    private static int fsFilesStartSector = 0;
    private static int fsInitialized = 0;
    
    // File metadata stored in parallel arrays
    // Filenames: flat array of 32 x 48 chars
    private static char[] fsFileNames = new char[SFROFS_MAX_FILES * SFROFS_NAME_MAX];
    private static int[] fsFileNameLengths = new int[SFROFS_MAX_FILES];
    private static int[] fsFileStartSectors = new int[SFROFS_MAX_FILES];
    private static int[] fsFileSizes = new int[SFROFS_MAX_FILES];
    
    // Read a byte from memory address
    private static char readMemoryByte(long addr) {
        return (char)(readMemoryLong(addr) & 0xFFL);
    }
    
    // Read uint32 from buffer at offset
    private static int readUInt32(long bufferAddr, int offset) {
        long addr = bufferAddr + offset;
        char b0 = readMemoryByte(addr);
        char b1 = readMemoryByte(addr + 1);
        char b2 = readMemoryByte(addr + 2);
        char b3 = readMemoryByte(addr + 3);
        return ((int)b0 & 0xFF) | 
               (((int)b1 & 0xFF) << 8) | 
               (((int)b2 & 0xFF) << 16) | 
               (((int)b3 & 0xFF) << 24);
    }
    
    // Read uint16 from buffer at offset
    private static int readUInt16(long bufferAddr, int offset) {
        long addr = bufferAddr + offset;
        char b0 = readMemoryByte(addr);
        char b1 = readMemoryByte(addr + 1);
        return ((int)b0 & 0xFF) | (((int)b1 & 0xFF) << 8);
    }
    
    // Get pointer to filename at given index
    private static int fsNameIdx(int fileIdx) {
        return fileIdx * SFROFS_NAME_MAX;
    }
    
    // Read null-terminated string from buffer at offset into filesystem name storage
    private static int readFilename(long bufferAddr, int offset, int fileIdx) {
        int nameBase = fsNameIdx(fileIdx);
        int len = 0;
        int i = 0;
        while (i < SFROFS_NAME_MAX) {
            char c = readMemoryByte(bufferAddr + offset + i);
            if (c == 0) break;
            fsFileNames[nameBase + i] = c;
            len = len + 1;
            i = i + 1;
        }
        return len;
    }
    
    // Compare filename in input buffer with stored filename
    private static boolean filenameEqualsInput(int fileIdx, int startPos, int len) {
        if (len != fsFileNameLengths[fileIdx]) return false;
        int nameBase = fsNameIdx(fileIdx);
        int i = 0;
        while (i < len) {
            if (inputBuffer[startPos + i] != fsFileNames[nameBase + i]) return false;
            i = i + 1;
        }
        return true;
    }
    
    // Find a file by comparing input buffer directly
    private static int findFileByInput(int startPos, int len) {
        if (fsInitialized == 0) return -1;
        int i = 0;
        while (i < fsNumFiles) {
            if (filenameEqualsInput(i, startPos, len)) {
                return i;
            }
            i = i + 1;
        }
        return -1;
    }
    
    // Handle the cat command - extracted to avoid translator issues
    private static void doCatCommand() {
        int pos = 4;
        while (pos < inputIndex && inputBuffer[pos] == ' ') pos = pos + 1;
        if (pos >= inputIndex) {
            writeString("Usage: cat <filename>\n");
            return;
        }
        int nameStart = pos;
        int nameLen = inputIndex - nameStart;
        int fileIdx = findFileByInput(nameStart, nameLen);
        if (fileIdx < 0) {
            writeString("File not found: ");
            int p = nameStart;
            while (p < inputIndex) {
                writeChar(inputBuffer[p]);
                p = p + 1;
            }
            writeString("\n");
        } else {
            catFileByIdx(fileIdx);
        }
    }
    
    // Handle the stat command - extracted to avoid translator issues
    private static void doStatCommand() {
        int pos = 5;
        while (pos < inputIndex && inputBuffer[pos] == ' ') pos = pos + 1;
        if (pos >= inputIndex) {
            writeString("Usage: stat <filename>\n");
            return;
        }
        int nameStart = pos;
        int nameLen = inputIndex - nameStart;
        int fileIdx = findFileByInput(nameStart, nameLen);
        if (fileIdx < 0) {
            writeString("File not found: ");
            int p = nameStart;
            while (p < inputIndex) {
                writeChar(inputBuffer[p]);
                p = p + 1;
            }
            writeString("\n");
        } else {
            statFileByIdx(fileIdx);
        }
    }
    
    // Find file by name using char array (avoids String allocation)
    private static int findFileByName(char[] name, int nameLen) {
        if (fsInitialized == 0 || name == null) return -1;
        if (nameLen <= 0 || nameLen > SFROFS_NAME_MAX) return -1;
        int i = 0;
        while (i < fsNumFiles) {
            if (fsFileNameLengths[i] == nameLen) {
                int nameBase = fsNameIdx(i);
                int j = 0;
                boolean match = true;
                while (j < nameLen) {
                    if (fsFileNames[nameBase + j] != name[j]) {
                        match = false;
                        break;
                    }
                    j = j + 1;
                }
                if (match) return i;
            }
            i = i + 1;
        }
        return -1;
    }
    
    // ===================================================================
    // SIMPLE BINARY FORMAT (SBF) LOADER
    // ===================================================================
    
    // SBF header constants
    private static final int SBF_MAGIC_0 = 'S';
    private static final int SBF_MAGIC_1 = 'B';
    private static final int SBF_MAGIC_2 = 'F';
    private static final int SBF_MAGIC_3 = 0;
    
    // Static filename buffer for loading (avoids allocation)
    private static char[] loadFilenameBuffer = new char[SFROFS_NAME_MAX];
    
    // Load a simple binary program from the filesystem
    // filename: char array containing the filename
    // nameLen: length of the filename
    // Returns the entry point address, or 0 on failure
    private static long loadBinary(char[] filename, int nameLen) {
        if (fsInitialized == 0) {
            writeString("ERROR: Filesystem not initialized\n");
            return 0;
        }
        
        // Find file in filesystem
        int fileIdx = findFileByName(filename, nameLen);
        boolean found = fileIdx >= 0;
        if (!found) {
            writeString("ERROR: File not found: ");
            int k = 0;
            while (k < nameLen) {
                writeChar(filename[k]);
                k = k + 1;
            }
            writeString("\n");
            return 0;
        }
        
        int fileSize = fsFileSizes[fileIdx];
        int startSector = fsFileStartSectors[fileIdx];
        
        if (fileSize < 16) {
            writeString("ERROR: File too small to be a valid SBF\n");
            return 0;
        }
        
        // Allocate buffer for file
        long buffer = heapAlloc(fileSize);
        if (buffer == 0) {
            writeString("ERROR: Could not allocate buffer for binary\n");
            return 0;
        }
        
        // Read file from disk
        // File sectors are relative to filesystem start (sector 2048)
        int sectors = (fileSize + SFROFS_SECTOR_SIZE - 1) / SFROFS_SECTOR_SIZE;
        int actualSector = 2048 + startSector;  // Add filesystem offset
        writeString("  Reading from sector ");
        writeNumber(actualSector);
        writeString("\n");
        boolean success = readDisk(actualSector, sectors, buffer);
        if (!success) {
            writeString("ERROR: Could not read binary file from disk\n");
            heapFree(buffer);
            return 0;
        }
        
        // Parse SBF header
        writeString("  SBF bytes: ");
        int m = 0;
        while (m < 4) {
            writeHexByte((int)readMemoryByte(buffer + m));
            writeString(" ");
            m = m + 1;
        }
        writeString("(expected: 53 42 46 00)\n");
        
        char magic0 = readMemoryByte(buffer);
        char magic1 = readMemoryByte(buffer + 1);
        char magic2 = readMemoryByte(buffer + 2);
        char magic3 = readMemoryByte(buffer + 3);
        
        if (magic0 != SBF_MAGIC_0 || magic1 != SBF_MAGIC_1 ||
            magic2 != SBF_MAGIC_2 || magic3 != SBF_MAGIC_3) {
            writeString("ERROR: Invalid SBF magic number\n");
            heapFree(buffer);
            return 0;
        }
        writeString("  SBF magic OK\n");
        
        int entryOffset = readUInt32(buffer, 4);
        int codeSize = readUInt32(buffer, 8);
        int dataSize = readUInt32(buffer, 12);
        
        writeString("SBF header:\n");
        writeString("  Entry offset: ");
        writeNumber(entryOffset);
        writeString("\n  Code size: ");
        writeNumber(codeSize);
        writeString(" bytes\n  Data size: ");
        writeNumber(dataSize);
        writeString(" bytes\n");
        
        // Verify sizes make sense
        if (16 + codeSize + dataSize > fileSize) {
            writeString("ERROR: SBF header claims larger size than file\n");
            heapFree(buffer);
            return 0;
        }
        
        // Allocate executable memory for code+data
        // For now we use heap memory (which is mapped executable)
        long execSize = codeSize + dataSize;
        long execMem = heapAlloc(execSize);
        if (execMem == 0) {
            writeString("ERROR: Could not allocate executable memory\n");
            heapFree(buffer);
            return 0;
        }
        
        // Copy code and data to executable memory
        // Code starts at offset 16 in the file
        long srcAddr = buffer + 16;
        long dstAddr = execMem;
        int copyPos = 0;
        while (copyPos < execSize) {
            // Read byte from source and write to destination
            writeMemory(dstAddr + copyPos, readMemoryByte(srcAddr + copyPos));
            copyPos = copyPos + 1;
        }
        
        // Free the file buffer (we have the code/data in execMem)
        heapFree(buffer);
        
        // Calculate entry point
        long entryPoint = execMem + entryOffset;
        
        writeString("Binary loaded at 0x");
        writeHex(execMem);
        writeString(", entry point at 0x");
        writeHex(entryPoint);
        writeString("\n");
        
        return entryPoint;
    }
    
    // Run a loaded program (synchronous - just call it)
    private static void runProgram(long entryPoint) {
        if (entryPoint == 0) {
            writeString("ERROR: Invalid entry point\n");
            return;
        }
        writeString("Jumping to program...\n");
        callProgram(entryPoint);
        writeString("\nProgram returned.\n");
    }
    
    // Initialize the filesystem
    private static void initFilesystem() {
        writeString("Init FS\n");
        disableInterrupts();
        
        long buffer = heapAlloc(512);
        if (buffer == 0) {
            writeString("No buf\n");
            enableInterrupts();
            return;
        }
        
        // Read sector 2049
        writeString("Read 2049\n");
        ataReadSectorBytes(2049, 0, buffer);
        
        // Check bytes
        writeString("Got: ");
        writeHexByte((int)readMemoryByte(buffer));
        writeString(" ");
        writeHexByte((int)readMemoryByte(buffer+1));
        writeString(" ");
        writeHexByte((int)readMemoryByte(buffer+2));
        writeString(" ");
        writeHexByte((int)readMemoryByte(buffer+3));
        writeString("\n");
        
        boolean success = true;
        if (!success) {
            writeString("ERROR: Could not read superblock\n");
            heapFree(buffer);
            enableInterrupts();
            return;
        }
        
        // Debug: print bytes as hex
        writeString("  Hex: ");
        writeHexByte((int)readMemoryByte(buffer));
        writeString(" ");
        writeHexByte((int)readMemoryByte(buffer + 1));
        writeString(" ");
        writeHexByte((int)readMemoryByte(buffer + 2));
        writeString(" ");
        writeHexByte((int)readMemoryByte(buffer + 3));
        writeString("\n");
        
        // Verify magic number
        char magic0 = readMemoryByte(buffer);
        char magic1 = readMemoryByte(buffer + 1);
        char magic2 = readMemoryByte(buffer + 2);
        char magic3 = readMemoryByte(buffer + 3);
        
        if (magic0 != SFROFS_MAGIC_0 || magic1 != SFROFS_MAGIC_1 ||
            magic2 != SFROFS_MAGIC_2 || magic3 != SFROFS_MAGIC_3) {
            writeString("WARNING: No SFROFS filesystem found (invalid magic)\n");
            heapFree(buffer);
            enableInterrupts();
            return;
        }
        
        // Read version
        int version = (int)readMemoryByte(buffer + 4);
        if (version != SFROFS_VERSION) {
            writeString("ERROR: Unsupported SFROFS version\n");
            heapFree(buffer);
            enableInterrupts();
            return;
        }
        
        // Read number of files (at offset 8: after magic[4] + version[1] + padding[3])
        fsNumFiles = readUInt16(buffer, 8);
        if (fsNumFiles > SFROFS_MAX_FILES) {
            writeString("WARNING: Too many files, limiting to ");
            writeNumber(SFROFS_MAX_FILES);
            writeString("\n");
            fsNumFiles = SFROFS_MAX_FILES;
        }
        
        // Read files start sector (at offset 10: after num_files[2])
        fsFilesStartSector = readUInt32(buffer, 10);
        
        writeString("  SFROFS v");
        writeNumber(version);
        writeString(" detected, ");
        writeNumber(fsNumFiles);
        writeString(" files\n");
        
        heapFree(buffer);
        
        if (fsNumFiles == 0) {
            fsInitialized = 1;
            return;
        }
        
        // Calculate file table sectors (sector 2 to N)
        int tableBytes = fsNumFiles * SFROFS_ENTRY_SIZE;
        int tableSectors = (tableBytes + SFROFS_SECTOR_SIZE - 1) / SFROFS_SECTOR_SIZE;
        
        // Allocate buffer for file table
        long tableBuffer = heapAlloc(tableSectors * SFROFS_SECTOR_SIZE);
        if (tableBuffer == 0) {
            writeString("ERROR: Could not allocate buffer for file table\n");
            return;
        }
        
        // Read file table from sector 2 (data disk drive 1)
        success = readDisk(SFROFS_SUPERBLOCK_SECTOR + 1, tableSectors, tableBuffer);
        if (!success) {
            writeString("ERROR: Could not read file table\n");
            heapFree(tableBuffer);
            return;
        }
        
        // Parse file entries
        writeString("  Reading file table...\n");
        int i = 0;
        while (i < fsNumFiles) {
            long entryAddr = tableBuffer + (i * SFROFS_ENTRY_SIZE);
            
            // Debug: print first 8 bytes of entry
            writeString("  Entry ");
            writeNumber(i);
            writeString(" bytes: ");
            int k = 0;
            while (k < 8) {
                writeHexByte((int)readMemoryByte(entryAddr + k));
                writeString(" ");
                k = k + 1;
            }
            writeString("\n");
            
            // Read filename
            fsFileNameLengths[i] = readFilename(entryAddr, 0, i);
            fsFileStartSectors[i] = readUInt32(entryAddr, 48);
            fsFileSizes[i] = readUInt32(entryAddr, 52);
            
            writeString("    Name len: ");
            writeNumber(fsFileNameLengths[i]);
            writeString(" size: ");
            writeNumber(fsFileSizes[i]);
            writeString("\n");
            
            i = i + 1;
        }
        
        heapFree(tableBuffer);
        fsInitialized = 1;
        writeString("  Filesystem initialized\n");
        
        // Re-enable interrupts
        enableInterrupts();
    }
    
    // Display file contents by index
    private static void catFileByIdx(int idx) {
        if (idx < 0 || idx >= fsNumFiles) return;
        int size = fsFileSizes[idx];
        int startSector = fsFileStartSectors[idx];
        
        if (size == 0) {
            writeString("(empty file)\n");
            return;
        }
        
        // Allocate buffer for file
        long buffer = heapAlloc(size);
        if (buffer == 0) {
            writeString("ERROR: Could not allocate buffer\n");
            return;
        }
        
        // Read file data
        int sectors = (size + SFROFS_SECTOR_SIZE - 1) / SFROFS_SECTOR_SIZE;
        int actualSector = 2048 + startSector;
        boolean success = readDisk(actualSector, sectors, buffer);
        if (!success) {
            writeString("ERROR: Could not read file\n");
            heapFree(buffer);
            return;
        }
        
        // Display contents
        int i = 0;
        while (i < size) {
            char c = readMemoryByte(buffer + i);
            writeChar(c);
            i = i + 1;
        }
        writeChar('\n');
        
        heapFree(buffer);
    }
    
    // Show file info by index
    private static void statFileByIdx(int idx) {
        if (idx < 0 || idx >= fsNumFiles) return;
        writeString("File: ");
        // Print filename char by char
        int nameBase = fsNameIdx(idx);
        int i = 0;
        while (i < fsFileNameLengths[idx]) {
            writeChar(fsFileNames[nameBase + i]);
            i = i + 1;
        }
        writeString("\n");
        writeString("  Size: ");
        writeNumber(fsFileSizes[idx]);
        writeString(" bytes\n");
        writeString("  Sectors: ");
        writeNumber((fsFileSizes[idx] + SFROFS_SECTOR_SIZE - 1) / SFROFS_SECTOR_SIZE);
        writeString(" (start: ");
        writeNumber(fsFileStartSectors[idx]);
        writeString(")\n");
    }
    
    // List all files
    private static void listFiles() {
        if (fsInitialized == 0) {
            writeString("Filesystem not initialized\n");
            return;
        }
        if (fsNumFiles == 0) {
            writeString("No files on disk\n");
            return;
        }
        writeString("Files (");
        writeNumber(fsNumFiles);
        writeString(" total):\n");
        writeString("  NAME                              SIZE\n");
        writeString("  --------------------------------  --------\n");
        int i = 0;
        while (i < fsNumFiles) {
            writeString("  ");
            // Print filename
            int nameBase = fsNameIdx(i);
            int j = 0;
            while (j < fsFileNameLengths[i]) {
                writeChar(fsFileNames[nameBase + j]);
                j = j + 1;
            }
            // Pad to 32 chars
            int pad = 32 - fsFileNameLengths[i];
            if (pad < 0) pad = 0;
            j = 0;
            while (j < pad) {
                writeChar(' ');
                j = j + 1;
            }
            writeNumber(fsFileSizes[i]);
            writeString(" bytes\n");
            i = i + 1;
        }
    }
    
    // ===================================================================
    // ATA PIO DISK I/O
    // ===================================================================
    
    // Read a 16-bit word from the ATA data port
    private static int ataReadWord() {
        // Read 16-bit word from ATA data port
        // Using inw is more reliable than two inb calls
        return inw(ATA_DATA);
    }
    
    // Alternative: read byte by byte
    private static void ataReadSectorBytes(int lba, int drive, long bufferAddr) {
        writeString("ATA: read sector ");
        writeNumber(lba);
        writeString("\n");
        
        ataWaitNotBusy();
        char driveSelect = (char)(0xE0 | (drive << 4) | ((lba >> 24) & 0x0F));
        outb(ATA_DRIVE_SELECT, driveSelect);
        ioWait();
        outb(ATA_SECTOR_COUNT, (char)1);
        outb(ATA_LBA_LOW, (char)(lba & 0xFF));
        outb(ATA_LBA_MID, (char)((lba >> 8) & 0xFF));
        outb(ATA_LBA_HIGH, (char)((lba >> 16) & 0x0F));
        outb(ATA_COMMAND, ATA_CMD_READ_SECTORS);
        ataWaitNotBusy();
        ataWaitDataReady();
        
        // Read 256 words (512 bytes) using 16-bit reads
        // ATA data port is 16-bit, so we need to read words and split
        writeString("Data: ");
        int i = 0;
        long addr = bufferAddr;
        while (i < 256) {
            // Read 16-bit word from ATA data port
            int word = inw(ATA_DATA);
            // Split into two bytes (little endian: low byte first)
            char low = (char)(word & 0xFF);
            char high = (char)((word >> 8) & 0xFF);
            writeMemory(addr, low);
            writeMemory(addr + 1, high);
            
            // Print first 4 bytes
            if (i == 0) {
                writeHexByte((int)low);
                writeString(" ");
                writeHexByte((int)high);
                writeString(" ");
            } else if (i == 1) {
                writeHexByte((int)low);
                writeString(" ");
                writeHexByte((int)high);
                writeString("\n");
            }
            
            addr = addr + 2;
            i = i + 1;
        }
    }
    
    // Wait until the drive is not busy
    private static void ataWaitNotBusy() {
        char status;
        do {
            status = inb(ATA_STATUS);
        } while ((status & ATA_SR_BSY) != 0);
    }
    
    // Wait until data is ready (DRQ set) or error
    private static void ataWaitDataReady() {
        char status;
        do {
            status = inb(ATA_STATUS);
        } while ((status & ATA_SR_BSY) != 0);  // Wait while busy
        // After BSY clears, DRQ should be set if data is ready
    }
    
    // Read a single sector (512 bytes) using LBA28 addressing
    private static void ataReadSector(int lba, int drive, long bufferAddr) {
        ataWaitNotBusy();
        
        char driveSelect = (char)(0xE0 | (drive << 4) | ((lba >> 24) & 0x0F));
        outb(ATA_DRIVE_SELECT, driveSelect);
        ioWait();
        outb(ATA_SECTOR_COUNT, (char)1);
        outb(ATA_LBA_LOW, (char)(lba & 0xFF));
        outb(ATA_LBA_MID, (char)((lba >> 8) & 0xFF));
        outb(ATA_LBA_HIGH, (char)((lba >> 16) & 0xFF));
        outb(ATA_COMMAND, ATA_CMD_READ_SECTORS);
        
        // Wait for command to complete and data to be ready
        ataWaitNotBusy();
        ataWaitDataReady();
        
        int i = 0;
        long addr = bufferAddr;
        while (i < 256) {
            int word = ataReadWord();
            writeMemory(addr, (char)(word & 0xFF));
            writeMemory(addr + 1, (char)((word >> 8) & 0xFF));
            addr = addr + 2;
            i = i + 1;
        }
    }
    
    // Read multiple sectors from disk
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
    
    // Read from data disk (drive 1) for filesystem
    private static boolean readDataDisk(int lba, int count, long bufferAddr) {
        if (count <= 0) return false;
        if (lba < 0) return false;
        
        int sector = 0;
        long addr = bufferAddr;
        while (sector < count) {
            int currentLba = lba + sector;
            ataReadSector(currentLba, 1, addr);  // Drive 1 = data disk
            addr = addr + 512;
            sector = sector + 1;
        }
        return true;
    }
    
    private static void shutdown() {
        outl(0x604, 0x2000);
        outw(0xB004, 0x2000);
        outb(0xF4, (char) 0x00);
    }
    
    private static void resetBuffer() {
        int i = 0;
        while (i < INPUT_MAX) {
            inputBuffer[i] = 0;
            i = i + 1;
        }
        inputIndex = 0;
    }
    
    private static void addToBuffer(char c) {
        if (inputIndex < INPUT_MAX) {
            inputBuffer[inputIndex] = c;
            inputIndex = inputIndex + 1;
        }
    }
    
    private static void backspaceBuffer() {
        if (inputIndex > 0) {
            inputIndex = inputIndex - 1;
            inputBuffer[inputIndex] = 0;
        }
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
        
        // Save command to history before executing
        saveToHistory();
        
        if (inputIndex == 0) {
            // Empty command, just show prompt
        } else if (isHelp()) {
            writeString("Available commands:\n");
            writeString("  help      - Show this help message\n");
            writeString("  clear     - Clear the screen\n");
            writeString("  info      - Show system information\n");
            writeString("  reboot    - Restart the system\n");
            writeString("  time      - Show timer tick count\n");
            writeString("  mem       - Show memory statistics\n");
            writeString("  memstat   - Show detailed memory stats\n");
            writeString("  heapstat  - Show heap statistics\n");
            writeString("  dump      - Dump VGA memory (0xB8000)\n");
            writeString("  vmtest    - Test virtual memory mapping\n");
            writeString("  serial    - Send test message to COM1\n");
            writeString("  disktest  - Read and display boot sector\n");
            writeString("  peek      - Read memory (e.g., peek B8000)\n");
            writeString("  poke      - Write memory (e.g., poke B8000 1234)\n");
            writeString("  history   - Show command history\n");
            writeString("  watch     - Watch memory address (e.g., watch B8000)\n");
            writeString("  unwatch   - Stop watching address\n");
            writeString("  watchlist - Show watched addresses and values\n");
            writeString("  ls        - List files on disk\n");
            writeString("  cat       - Display file contents (e.g., cat readme.txt)\n");
            writeString("  stat      - Show file info (e.g., stat readme.txt)\n");
            writeString("  run       - Load and run a binary (e.g., run program.bin)\n");
            writeString("  shutdown  - Power off\n");
        } else if (isClear()) {
            clearScreen();
        } else if (isInfo()) {
            writeString("JavaOS Kernel v0.6\n");
            writeString("Architecture: x86-64\n");
            writeString("Language: Java + C + ASM\n");
            writeString("Features: Arrays, Ring Buffer, History, Watch\n");
            writeString("Timer: 100Hz\n");
        } else if (isReboot()) {
            writeString("Rebooting...\n");
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
        } else if (isHeapstat()) {
            printHeapStats();
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
            long buffer = heapAlloc(512);
            if (buffer == 0) {
                writeString("FAIL: Could not allocate buffer\n");
            } else {
                boolean success = readDisk(0, 1, buffer);
                if (success) {
                    writeString("Boot sector read successfully.\n");
                    writeString("First 64 bytes in hex:\n");
                    dumpMemory(buffer, 4);
                } else {
                    writeString("FAIL: Could not read boot sector\n");
                }
                heapFree(buffer);
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
        } else if (isHistory()) {
            showHistory();
        } else if (isWatch()) {
            long addr = parseHex(6); // After "watch "
            if (addr < 0) {
                writeString("Usage: watch <hexaddr>\n");
            } else {
                addWatch(addr);
            }
        } else if (isUnwatch()) {
            long addr = parseHex(8); // After "unwatch "
            if (addr < 0) {
                writeString("Usage: unwatch <hexaddr>\n");
            } else {
                removeWatch(addr);
            }
        } else if (isWatchlist()) {
            showWatchlist();
        } else if (isLs()) {
            listFiles();
        } else if (isCat()) {
            doCatCommand();
        } else if (isStat()) {
            doStatCommand();
        } else if (isRun()) {
            {
                // Extract filename after "run "
                int pos = 4;
                while (pos < inputIndex && inputBuffer[pos] == ' ') pos = pos + 1;
                if (pos >= inputIndex) {
                    writeString("Usage: run <filename>\n");
                } else {
                    // Copy filename to static buffer (no heap allocation)
                    int nameLen = inputIndex - pos;
                    if (nameLen > SFROFS_NAME_MAX) nameLen = SFROFS_NAME_MAX;
                    int i = 0;
                    while (i < nameLen) {
                        loadFilenameBuffer[i] = inputBuffer[pos + i];
                        i = i + 1;
                    }
                    
                    // Load and run the binary
                    long entryPoint = loadBinary(loadFilenameBuffer, nameLen);
                    if (entryPoint != 0) {
                        runProgram(entryPoint);
                    }
                }
            }
        } else {
            writeString("Unknown command. Type 'help' for available commands.\n");
        }
        
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

    // Convert scancode to ASCII
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
        if (scancode == 0x34) return '.';  // Period
        return 0;
    }

    // Called from interrupt context in C for ALL interrupts
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
                    ringBufferPut(ascii);
                }
            }
            sendEOI(1);
        } else if (vector >= 32 && vector <= 47) {
            sendEOI(vector - 32);
        } else if (vector < 32) {
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
        
        writeString("JavaOS Kernel v0.6\n");
        writeString("==================\n\n");
        
        writeString("Initializing from Java...\n");
        initInterrupts();
        
        writeString("Interrupts enabled!\n");
        writeString("Timer: 100Hz\n");
        writeString("Keyboard: enabled (ring buffer)\n");
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
        
        // Initialize filesystem
        initFilesystem();
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
            
            // Process keys from ring buffer
            char key = ringBufferGet();
            while (key != 0) {
                if (key == '\n') {
                    executeCommand();
                } else if (key == '\b') {
                    if (inputIndex > 0) {
                        backspaceBuffer();
                        cursorX = cursorX - 1;
                        if (cursorX < 0) {
                            cursorX = SCREEN_WIDTH - 1;
                            cursorY = cursorY - 1;
                        }
                        writeCharAt(' ', cursorX, cursorY);
                    }
                } else if (inputIndex < INPUT_MAX && key >= 32 && key <= 126) {
                    addToBuffer(key);
                    writeChar(key);
                }
                key = ringBufferGet();
            }
        }
    }

    public static void main(String[] args) {
        startKernel(0L);
    }
}
