package kernel;

/**
 * Memory management module - physical page allocator, virtual memory, heap
 */
public class Memory {

    // ===================================================================
    // PHYSICAL MEMORY MANAGER (Bitmap-based)
    // ===================================================================
    
    // E820 memory map location (set up by bootloader)
    private static final long E820_COUNT_ADDR = 0x500L;
    private static final long E820_BUFFER_ADDR = 0x504L;
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
    
    // Use bootloader's page tables at 0x1000
    private static final long BOOT_PML4_ADDR = 0x1000L;
    private static final long BOOT_PDPT_ADDR = 0x2000L;
    private static final long BOOT_PD_ADDR = 0x3000L;
    
    // ===================================================================
    // HEAP ALLOCATOR
    // ===================================================================
    
    private static final long HEAP_START = 0x400000L;
    private static final long HEAP_SIZE = 0x400000L;      // 4MB heap
    private static final long HEAP_HEADER_SIZE = 8;       // Size of block header (8 bytes)
    private static final long HEAP_MIN_BLOCK_SIZE = 16;   // Minimum allocatable block size
    private static final long HEAP_ALLOCATED_FLAG = 0x8000000000000000L;
    
    private static long heapCurrent = HEAP_START;
    private static long heapEnd = HEAP_START + HEAP_SIZE;
    private static long freeListHead = 0;
    private static long heapTotalAllocated = 0;
    private static long heapTotalFree = 0;
    private static int heapFreeBlocks = 0;

    // Parse E820 memory map and initialize bitmap
    public static void initMemoryMap() {
        int entryCount = (int)Native.readMemoryLong(E820_COUNT_ADDR) & 0xFFFF;
        
        Console.writeString("E820 entries: ");
        Console.writeNumber(entryCount);
        Console.writeString("\n");
        
        // Clear bitmap
        int offset = 0;
        while (offset < BITMAP_SIZE) {
            Native.writeMemoryLong(BITMAP_START + (long)offset, 0);
            offset = offset + 8;
        }
        
        // Mark kernel memory (0-4MB = pages 0-1023) as used
        markPagesUsed(0, 0x400);
        
        totalPages = 0;
        freePages = 0;
        
        // Parse E820 entries
        int i = 0;
        while (i < entryCount && i < 20) {
            long entryAddr = E820_BUFFER_ADDR + (long)(i * E820_ENTRY_SIZE);
            
            long baseVal = Native.readMemoryLong(entryAddr);
            int baseLow = (int)baseVal;
            
            long lengthVal = Native.readMemoryLong(entryAddr + 8);
            int lengthLow = (int)lengthVal;
            
            int type = (int)(Native.readMemoryLong(entryAddr + 16) & 0xFFFFFFFFL);
            
            Console.writeString("  Entry ");
            Console.writeNumber(i);
            Console.writeString(": base=0x");
            Console.writeHex(baseLow);
            Console.writeString(" len=0x");
            Console.writeHex(lengthLow);
            Console.writeString(" type=");
            Console.writeNumber(type);
            Console.writeString("\n");
            
            if (type == E820_TYPE_AVAILABLE && lengthLow != 0) {
                int startPage = (baseLow + 4095) >> 12;
                int endPage = (baseLow + lengthLow) >> 12;
                
                if (startPage < 0x400) startPage = 0x400;
                if (endPage > 0x40000) endPage = 0x40000;
                
                if (endPage > startPage) {
                    Console.writeString("    Adding pages ");
                    Console.writeNumber(startPage);
                    Console.writeString(" to ");
                    Console.writeNumber(endPage);
                    Console.writeString("\n");
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
    
    private static void markPageUsed(long pageNum) {
        long byteOffset = BITMAP_START + (pageNum >> 6);
        int bitOffset = (int)(pageNum & 63);
        long val = Native.readMemoryLong(byteOffset);
        val = val | (1L << bitOffset);
        Native.writeMemoryLong(byteOffset, val);
    }
    
    public static void markPagesUsed(long startPage, long count) {
        long i = 0;
        while (i < count) {
            markPageUsed(startPage + i);
            i = i + 1;
        }
    }
    
    private static void markPageFree(long pageNum) {
        long byteOffset = BITMAP_START + (pageNum >> 6);
        int bitOffset = (int)(pageNum & 63);
        long val = Native.readMemoryLong(byteOffset);
        val = val & ~(1L << bitOffset);
        Native.writeMemoryLong(byteOffset, val);
    }
    
    private static boolean isPageFree(long pageNum) {
        long byteOffset = BITMAP_START + (pageNum >> 6);
        int bitOffset = (int)(pageNum & 63);
        long val = Native.readMemoryLong(byteOffset);
        boolean free = (val & (1L << bitOffset)) == 0;
        return free;
    }
    
    // Allocate a single physical page
    public static long allocPage() {
        if (freePages == 0) return 0;
        
        int maxPage = (int)(totalPages + 0x400);
        if (maxPage > (int)(MAX_PHYS_MEM >> PAGE_SHIFT)) {
            maxPage = (int)(MAX_PHYS_MEM >> PAGE_SHIFT);
        }
        
        int page = 0x400;
        while (page < maxPage) {
            if (isPageFree((long)page)) {
                markPageUsed((long)page);
                freePages = freePages - 1;
                return ((long)page) << PAGE_SHIFT;
            }
            page = page + 1;
        }
        return 0;
    }
    
    // Free a physical page
    public static void freePage(long physAddr) {
        int pageNum = (int)(physAddr >> PAGE_SHIFT);
        int diff = pageNum - 0x400;
        if (diff >= 0) {
            markPageFree((long)pageNum);
            freePages = freePages + 1;
        }
    }
    
    // Initialize page tables
    public static void initPaging() {
        long pml4Entry = Native.readMemoryLong(BOOT_PML4_ADDR);
        if ((pml4Entry & PT_PRESENT) == 0) {
            Console.writeString("ERROR: PML4 not present\n");
        }
    }
    
    // Map a virtual address to a physical address
    public static boolean mapPage(long virtAddr, long physAddr, boolean writable) {
        long flags = PT_PRESENT;
        if (writable) flags = flags | PT_WRITABLE;
        
        int pml4Index = (int)((virtAddr >> 39) & 0x1FF);
        int pdptIndex = (int)((virtAddr >> 30) & 0x1FF);
        int pdIndex = (int)((virtAddr >> 21) & 0x1FF);
        int ptIndex = (int)((virtAddr >> 12) & 0x1FF);
        
        if (pml4Index != 0) return false;
        
        long pdptEntry = Native.readMemoryLong(BOOT_PML4_ADDR);
        long pdptAddr = pdptEntry & PT_FRAME;
        
        long pdEntryAddr = pdptAddr + pdptIndex * 8;
        long pdEntry = Native.readMemoryLong(pdEntryAddr);
        long pdAddr;
        if ((pdEntry & PT_PRESENT) == 0) {
            pdAddr = allocPage();
            if (pdAddr == 0) return false;
            Native.writeMemoryLong(pdEntryAddr, pdAddr | PT_PRESENT | PT_WRITABLE);
            long jd = 0;
            while (jd < 512) {
                Native.writeMemoryLong(pdAddr + jd * 8, 0);
                jd = jd + 1;
            }
        } else {
            pdAddr = pdEntry & PT_FRAME;
        }
        
        long ptEntryAddr = pdAddr + pdIndex * 8;
        long ptEntry = Native.readMemoryLong(ptEntryAddr);
        long ptAddr;
        if ((ptEntry & PT_PRESENT) == 0) {
            ptAddr = allocPage();
            if (ptAddr == 0) return false;
            Native.writeMemoryLong(ptEntryAddr, ptAddr | PT_PRESENT | PT_WRITABLE);
            long jt = 0;
            while (jt < 512) {
                Native.writeMemoryLong(ptAddr + jt * 8, 0);
                jt = jt + 1;
            }
        } else {
            ptAddr = ptEntry & PT_FRAME;
        }
        
        Native.writeMemoryLong(ptAddr + ptIndex * 8, (physAddr & PT_FRAME) | flags);
        return true;
    }
    
    // ===================================================================
    // HEAP ALLOCATOR METHODS
    // ===================================================================
    
    public static void initHeap() {
        // Heap is initialized with default values
        // No special initialization needed
    }
    
    private static long getBlockSize(long header) {
        return header & ~HEAP_ALLOCATED_FLAG;
    }
    
    private static boolean isBlockAllocated(long header) {
        return (header & HEAP_ALLOCATED_FLAG) != 0;
    }
    
    private static long readBlockHeader(long addr) {
        return Native.readMemoryLong(addr);
    }
    
    private static void writeBlockHeader(long addr, long size, boolean allocated) {
        long header = size;
        if (allocated) {
            header = header | HEAP_ALLOCATED_FLAG;
        }
        Native.writeMemoryLong(addr, header);
    }
    
    private static long getNextFree(long blockAddr) {
        return Native.readMemoryLong(blockAddr + HEAP_HEADER_SIZE);
    }
    
    private static void setNextFree(long blockAddr, long nextAddr) {
        Native.writeMemoryLong(blockAddr + HEAP_HEADER_SIZE, nextAddr);
    }
    
    private static boolean extendHeap(long minSize) {
        long needed = minSize + HEAP_HEADER_SIZE;
        long pagesNeeded = (needed + PAGE_SIZE - 1) / PAGE_SIZE;
        
        int i = 0;
        while (i < pagesNeeded) {
            long page = allocPage();
            if (page == 0) return false;
            
            if (!mapPage(heapEnd, page, true)) {
                freePage(page);
                return false;
            }
            heapEnd = heapEnd + PAGE_SIZE;
            i = i + 1;
        }
        return true;
    }
    
    private static long splitBlock(long blockAddr, long blockSize, long neededSize) {
        long minSplitSize = HEAP_HEADER_SIZE + 8 + HEAP_MIN_BLOCK_SIZE;
        
        if (blockSize >= neededSize + minSplitSize) {
            long remaining = blockSize - neededSize - HEAP_HEADER_SIZE;
            long newBlockAddr = blockAddr + HEAP_HEADER_SIZE + neededSize;
            
            writeBlockHeader(newBlockAddr, remaining, false);
            setNextFree(newBlockAddr, freeListHead);
            freeListHead = newBlockAddr;
            heapFreeBlocks = heapFreeBlocks + 1;
            heapTotalFree = heapTotalFree + remaining;
            
            blockSize = neededSize;
        }
        
        writeBlockHeader(blockAddr, blockSize, true);
        heapTotalAllocated = heapTotalAllocated + blockSize;
        heapTotalFree = heapTotalFree - blockSize;
        
        return blockAddr + HEAP_HEADER_SIZE;
    }
    
    private static void coalesce() {
        if (freeListHead == 0) return;
        
        boolean changed = true;
        while (changed) {
            changed = false;
            long current = freeListHead;
            long prev = 0;
            
            while (current != 0) {
                long currentSize = getBlockSize(readBlockHeader(current));
                long currentEnd = current + HEAP_HEADER_SIZE + currentSize;
                long next = getNextFree(current);
                
                long scan = freeListHead;
                long scanPrev = 0;
                boolean found = false;
                
                while (scan != 0) {
                    if (scan == currentEnd) {
                        long scanSize = getBlockSize(readBlockHeader(scan));
                        long newSize = currentSize + HEAP_HEADER_SIZE + scanSize;
                        
                        if (scanPrev == 0) {
                            freeListHead = getNextFree(scan);
                        } else {
                            setNextFree(scanPrev, getNextFree(scan));
                        }
                        
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
                
                if (found) break;
                
                prev = current;
                current = next;
            }
        }
    }
    
    // Allocate memory from heap
    public static long heapAlloc(long size) {
        if (size < HEAP_MIN_BLOCK_SIZE) {
            size = HEAP_MIN_BLOCK_SIZE;
        }
        size = (size + 7) & ~7;
        
        long prev = 0;
        long current = freeListHead;
        long neededSize = size;
        
        while (current != 0) {
            long header = readBlockHeader(current);
            long blockSize = getBlockSize(header);
            
            if (blockSize >= neededSize) {
                long next = getNextFree(current);
                if (prev == 0) {
                    freeListHead = next;
                } else {
                    setNextFree(prev, next);
                }
                heapFreeBlocks = heapFreeBlocks - 1;
                heapTotalFree = heapTotalFree - blockSize;
                
                return splitBlock(current, blockSize, neededSize);
            }
            
            prev = current;
            current = getNextFree(current);
        }
        
        long resultAddr = heapCurrent;
        long totalNeeded = neededSize + HEAP_HEADER_SIZE;
        
        while (resultAddr + totalNeeded > heapEnd) {
            if (!extendHeap(totalNeeded)) {
                return 0;
            }
        }
        
        writeBlockHeader(resultAddr, neededSize, true);
        heapCurrent = resultAddr + HEAP_HEADER_SIZE + neededSize;
        heapTotalAllocated = heapTotalAllocated + neededSize;
        
        return resultAddr + HEAP_HEADER_SIZE;
    }
    
    // Free memory back to heap
    public static void heapFree(long ptr) {
        if (ptr == 0) return;
        
        long blockAddr = ptr - HEAP_HEADER_SIZE;
        long header = readBlockHeader(blockAddr);
        long blockSize = getBlockSize(header);
        
        if (!isBlockAllocated(header)) return;
        
        writeBlockHeader(blockAddr, blockSize, false);
        heapTotalAllocated = heapTotalAllocated - blockSize;
        heapTotalFree = heapTotalFree + blockSize;
        
        setNextFree(blockAddr, freeListHead);
        freeListHead = blockAddr;
        heapFreeBlocks = heapFreeBlocks + 1;
        
        coalesce();
    }
    
    public static void printHeapStats() {
        Console.writeString("Heap Statistics:\n");
        Console.writeString("  Start: 0x");
        Console.writeHex(HEAP_START);
        Console.writeString("\n");
        Console.writeString("  Current: 0x");
        Console.writeHex(heapCurrent);
        Console.writeString("\n");
        Console.writeString("  End: 0x");
        Console.writeHex(heapEnd);
        Console.writeString("\n");
        Console.writeString("  Total size: ");
        Console.writeNumber((int)(heapEnd - HEAP_START));
        Console.writeString(" bytes\n");
        Console.writeString("  Allocated: ");
        Console.writeNumber((int)heapTotalAllocated);
        Console.writeString(" bytes\n");
        Console.writeString("  Free: ");
        Console.writeNumber((int)heapTotalFree);
        Console.writeString(" bytes\n");
        Console.writeString("  Free blocks: ");
        Console.writeNumber(heapFreeBlocks);
        Console.writeString("\n");
    }
    
    public static void printMemoryStats() {
        Console.writeString("Memory Stats:\n");
        Console.writeString("  Total pages: ");
        Console.writeNumber(totalPages);
        Console.writeString("\n  Free pages: ");
        Console.writeNumber(freePages);
        Console.writeString("\n  Used pages: ");
        Console.writeNumber(totalPages - freePages);
        Console.writeString("\n  Heap used: ");
        Console.writeNumber((heapCurrent - HEAP_START) / 1024);
        Console.writeString(" KB\n");
    }
    
    // Getters
    public static long getFreePages() { return freePages; }
    public static long getTotalPages() { return totalPages; }
    public static long getPageSize() { return PAGE_SIZE; }
}
