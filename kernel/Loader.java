package kernel;

/**
 * Program loader module - SBF and PE format loaders
 */
public class Loader {

    // ===================================================================
    // SBF FORMAT CONSTANTS
    // ===================================================================
    
    private static final int SBF_MAGIC_0 = 'S';
    private static final int SBF_MAGIC_1 = 'B';
    private static final int SBF_MAGIC_2 = 'F';
    private static final int SBF_MAGIC_3 = 0;
    
    // ===================================================================
    // WINDOWS PE FORMAT CONSTANTS
    // ===================================================================
    
    private static final int DOS_MAGIC = 0x5A4D;
    private static final int DOS_LFANEW_OFFSET = 0x3C;
    private static final int PE_MAGIC = 0x00004550;
    private static final int PE32PLUS_MAGIC = 0x20B;
    private static final int IMAGE_FILE_MACHINE_AMD64 = 0x8664;
    
    private static final int PE_MACHINE_OFFSET = 0;
    private static final int PE_NUMSECTIONS_OFFSET = 2;
    private static final int PE_OPTHDR_SIZE_OFFSET = 16;
    
    private static final int OPT_MAGIC_OFFSET = 0;
    private static final int OPT_ENTRYPOINT_OFFSET = 16;
    private static final int OPT_IMAGEBASE_OFFSET = 24;
    private static final int OPT_SIZEOFIMAGE_OFFSET = 56;
    private static final int OPT_SIZEOFHEADERS_OFFSET = 60;
    
    private static final int SECTION_HEADER_SIZE = 40;
    private static final int IMAGE_REL_BASED_DIR64 = 10;
    private static final int IMAGE_REL_BASED_HIGHLOW = 3;
    
    // Windows syscall numbers
    private static final int WIN_NT_CLOSE = 0x0F;
    private static final int WIN_NT_WRITE_FILE = 0x08;
    private static final int WIN_NT_TERMINATE_PROCESS = 0x2C;
    
    // NT Status codes
    private static final long STATUS_SUCCESS = 0L;
    private static final long STATUS_INVALID_HANDLE = 0xC0000008L;
    
    // Handle table
    private static final int MAX_WIN_HANDLES = 64;
    private static final int WIN_HANDLE_STDOUT = 1;
    private static long[] winHandleTable = new long[MAX_WIN_HANDLES];
    private static boolean[] winHandleUsed = new boolean[MAX_WIN_HANDLES];
    
    // Loaded PE base address
    private static long peLoadedBase = 0;

    public static void initWinHandles() {
        int i = 0;
        while (i < MAX_WIN_HANDLES) {
            winHandleUsed[i] = false;
            winHandleTable[i] = 0;
            i = i + 1;
        }
        winHandleUsed[0] = true;
        winHandleUsed[1] = true;
        winHandleUsed[2] = true;
    }
    
    public static long loadBinaryAuto(char[] filename, int nameLen) {
        if (!Filesystem.isInitialized()) {
            Console.writeString("ERROR: Filesystem not initialized\n");
            return 0;
        }
        
        int fileIdx = Filesystem.findFileByName(filename, nameLen);
        if (fileIdx < 0) {
            Console.writeString("ERROR: File not found\n");
            return 0;
        }
        
        int fileSize = Filesystem.getFileSize(fileIdx);
        int startSector = Filesystem.getFileStartSector(fileIdx);
        
        Console.writeString("Loading file, size=");
        Console.writeNumber(fileSize);
        Console.writeString(" bytes\n");
        
        long buffer = Memory.heapAlloc(fileSize);
        if (buffer == 0) {
            Console.writeString("ERROR: Could not allocate buffer\n");
            return 0;
        }
        
        int sectors = (fileSize + 511) / 512;
        int actualSector = 2048 + startSector;
        Disk.readDisk(actualSector, sectors, buffer);
        
        // Debug: print first 4 bytes
        Console.writeString("First bytes: ");
        Console.writeHexByte((int)Native.readMemoryLong(buffer) & 0xFF);
        Console.writeString(" ");
        Console.writeHexByte((int)Native.readMemoryLong(buffer + 1) & 0xFF);
        Console.writeString(" ");
        Console.writeHexByte((int)Native.readMemoryLong(buffer + 2) & 0xFF);
        Console.writeString(" ");
        Console.writeHexByte((int)Native.readMemoryLong(buffer + 3) & 0xFF);
        Console.writeString("\n");
        
        long entryPoint;
        if (isPEFormat(buffer, fileSize)) {
            Console.writeString("Detected Windows PE executable\n");
            entryPoint = loadPE(buffer, fileSize);
        } else {
            Console.writeString("Loading as SBF format\n");
            entryPoint = loadSBF(buffer, fileSize);
        }
        
        // Don't free buffer - it's used by the loaded program
        return entryPoint;
    }
    
    private static boolean isPEFormat(long buffer, int fileSize) {
        if (fileSize < 64) return false;
        
        // Check DOS magic "MZ" at offset 0
        char m = (char)(Native.readMemoryLong(buffer) & 0xFF);
        char z = (char)(Native.readMemoryLong(buffer + 1) & 0xFF);
        int dosMagic = ((int)z << 8) | (int)m;
        
        Console.writeString("DOS magic: 0x");
        Console.writeHex(dosMagic);
        Console.writeString(" (expected 0x5A4D)\n");
        
        if (dosMagic != DOS_MAGIC) return false;
        
        int peOffset = readUInt32LE(buffer, DOS_LFANEW_OFFSET);
        Console.writeString("PE offset: ");
        Console.writeNumber(peOffset);
        Console.writeString("\n");
        
        if (peOffset < 0 || peOffset + 4 > fileSize) return false;
        
        int peSig = readUInt32LE(buffer, peOffset);
        Console.writeString("PE signature: 0x");
        Console.writeHex(peSig);
        Console.writeString(" (expected 0x4550)\n");
        
        if (peSig != PE_MAGIC) return false;
        
        return true;
    }
    
    private static int readUInt32LE(long buffer, int offset) {
        int b0 = (int)Native.readMemoryLong(buffer + offset) & 0xFF;
        int b1 = (int)Native.readMemoryLong(buffer + offset + 1) & 0xFF;
        int b2 = (int)Native.readMemoryLong(buffer + offset + 2) & 0xFF;
        int b3 = (int)Native.readMemoryLong(buffer + offset + 3) & 0xFF;
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }
    
    private static int readUInt16LE(long buffer, int offset) {
        int b0 = (int)Native.readMemoryLong(buffer + offset) & 0xFF;
        int b1 = (int)Native.readMemoryLong(buffer + offset + 1) & 0xFF;
        return b0 | (b1 << 8);
    }
    
    private static long readUInt64LE(long buffer, int offset) {
        long low = (long)readUInt32LE(buffer, offset) & 0xFFFFFFFFL;
        long high = (long)readUInt32LE(buffer, offset + 4) & 0xFFFFFFFFL;
        return low | (high << 32);
    }
    
    private static long loadPE(long buffer, int fileSize) {
        Console.writeString("Loading Windows PE executable...\n");
        
        int peOffset = readUInt32LE(buffer, DOS_LFANEW_OFFSET);
        int coffOffset = peOffset + 4;
        
        int machine = readUInt16LE(buffer, coffOffset + PE_MACHINE_OFFSET);
        if (machine != IMAGE_FILE_MACHINE_AMD64) {
            Console.writeString("ERROR: PE is not x64 executable\n");
            return 0;
        }
        
        int numSections = readUInt16LE(buffer, coffOffset + PE_NUMSECTIONS_OFFSET);
        int optHeaderSize = readUInt16LE(buffer, coffOffset + PE_OPTHDR_SIZE_OFFSET);
        
        Console.writeString("  Sections: ");
        Console.writeNumber(numSections);
        Console.writeString("\n");
        
        int optHeaderOffset = coffOffset + 20;
        
        int optMagic = readUInt16LE(buffer, optHeaderOffset + OPT_MAGIC_OFFSET);
        if (optMagic != PE32PLUS_MAGIC) {
            Console.writeString("ERROR: Not PE32+ format\n");
            return 0;
        }
        
        int entryPointRVA = readUInt32LE(buffer, optHeaderOffset + OPT_ENTRYPOINT_OFFSET);
        long imageBase = readUInt64LE(buffer, optHeaderOffset + OPT_IMAGEBASE_OFFSET);
        int sizeOfImage = readUInt32LE(buffer, optHeaderOffset + OPT_SIZEOFIMAGE_OFFSET);
        int sizeOfHeaders = readUInt32LE(buffer, optHeaderOffset + OPT_SIZEOFHEADERS_OFFSET);
        
        Console.writeString("  ImageBase: ");
        Console.writeHex(imageBase);
        Console.writeString("\n  EntryPoint RVA: ");
        Console.writeHex((long)entryPointRVA);
        Console.writeString("\n");
        
        long execMem = Memory.heapAlloc(sizeOfImage);
        if (execMem == 0) {
            Console.writeString("ERROR: Could not allocate executable memory\n");
            return 0;
        }
        
        peLoadedBase = execMem;
        
        // Copy headers
        int h = 0;
        while (h < sizeOfHeaders) {
            Native.writeMemory(execMem + h, 
                (char)(Native.readMemoryLong(buffer + h) & 0xFF));
            h = h + 1;
        }
        
        // Load sections
        int sectionTableOffset = optHeaderOffset + optHeaderSize;
        int s = 0;
        while (s < numSections) {
            int secOffset = sectionTableOffset + s * SECTION_HEADER_SIZE;
            
            int virtAddr = readUInt32LE(buffer, secOffset + 12);
            int virtSize = readUInt32LE(buffer, secOffset + 16);
            int rawAddr = readUInt32LE(buffer, secOffset + 20);
            int rawSize = readUInt32LE(buffer, secOffset + 24);
            
            int copySize = virtSize;
            if (rawSize < copySize) copySize = rawSize;
            
            int b = 0;
            while (b < copySize) {
                Native.writeMemory(execMem + virtAddr + b,
                    (char)(Native.readMemoryLong(buffer + rawAddr + b) & 0xFF));
                b = b + 1;
            }
            
            s = s + 1;
        }
        
        long entryPoint = execMem + entryPointRVA;
        
        Console.writeString("  Loaded at: ");
        Console.writeHex(execMem);
        Console.writeString("\n  Entry point: ");
        Console.writeHex(entryPoint);
        Console.writeString("\nPE loaded successfully\n");
        
        return entryPoint;
    }
    
    private static long loadSBF(long buffer, int fileSize) {
        char magic0 = (char)(Native.readMemoryLong(buffer) & 0xFF);
        char magic1 = (char)(Native.readMemoryLong(buffer + 1) & 0xFF);
        char magic2 = (char)(Native.readMemoryLong(buffer + 2) & 0xFF);
        char magic3 = (char)(Native.readMemoryLong(buffer + 3) & 0xFF);
        
        if (magic0 != SBF_MAGIC_0 || magic1 != SBF_MAGIC_1 ||
            magic2 != SBF_MAGIC_2 || magic3 != SBF_MAGIC_3) {
            Console.writeString("ERROR: Invalid SBF magic number\n");
            return 0;
        }
        
        int entryOffset = readUInt32LE(buffer, 4);
        int codeSize = readUInt32LE(buffer, 8);
        int dataSize = readUInt32LE(buffer, 12);
        
        if (16 + codeSize + dataSize > fileSize) {
            Console.writeString("ERROR: SBF header claims larger size than file\n");
            return 0;
        }
        
        long execSize = codeSize + dataSize;
        long execMem = Memory.heapAlloc(execSize);
        if (execMem == 0) {
            Console.writeString("ERROR: Could not allocate executable memory\n");
            return 0;
        }
        
        long srcAddr = buffer + 16;
        long dstAddr = execMem;
        int copyPos = 0;
        while (copyPos < execSize) {
            Native.writeMemory(dstAddr + copyPos,
                (char)(Native.readMemoryLong(srcAddr + copyPos) & 0xFF));
            copyPos = copyPos + 1;
        }
        
        long entryPoint = execMem + entryOffset;
        
        Console.writeString("Binary loaded at ");
        Console.writeHex(execMem);
        Console.writeString(", entry at ");
        Console.writeHex(entryPoint);
        Console.writeString("\n");
        
        return entryPoint;
    }
    
    public static void runProgram(long entryPoint) {
        if (entryPoint == 0) {
            Console.writeString("ERROR: Invalid entry point\n");
            return;
        }
        int tid = Threading.spawnThread(entryPoint);
        if (tid < 0) {
            Console.writeString("Failed to spawn thread\n");
        } else {
            Console.writeString("Spawned thread ");
            Console.writeNumber(tid);
            Console.writeString("\n");
        }
    }
    
    // Windows syscall emulation
    public static long handleWindowsSyscall(long num, long arg1, long arg2, long arg3, long arg4) {
        if (num == WIN_NT_WRITE_FILE) {
            return winNtWriteFile(arg1, arg2, arg3, arg4);
        } else if (num == WIN_NT_CLOSE) {
            return winNtClose(arg1);
        } else if (num == WIN_NT_TERMINATE_PROCESS) {
            return winNtTerminateProcess(arg1, arg2);
        }
        return STATUS_SUCCESS;
    }
    
    private static long winNtWriteFile(long fileHandle, long buffer, long length, long writtenPtr) {
        if (fileHandle == WIN_HANDLE_STDOUT) {
            int i = 0;
            while (i < length) {
                char c = (char)(Native.readMemoryLong(buffer + i) & 0xFF);
                Console.writeChar(c);
                i = i + 1;
            }
            if (writtenPtr != 0) {
                Native.writeMemoryLong(writtenPtr, length);
            }
            return STATUS_SUCCESS;
        }
        return STATUS_INVALID_HANDLE;
    }
    
    private static long winNtClose(long handle) {
        return STATUS_SUCCESS;
    }
    
    private static long winNtTerminateProcess(long processHandle, long exitStatus) {
        Threading.terminateCurrentThread();
        return STATUS_SUCCESS;
    }
    
    public static long getPeLoadedBase() { return peLoadedBase; }
}
