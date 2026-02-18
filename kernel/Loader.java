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
    
    // PE32+ Data Directory offsets (from optional header start)
    private static final int OPT_EXPORT_TABLE_RVA = 112;
    private static final int OPT_IMPORT_TABLE_RVA = 120;
    private static final int OPT_IMPORT_TABLE_SIZE = 124;
    
    // Import Directory Entry structure offsets
    private static final int IMPORT_DESCRIPTOR_SIZE = 20;
    private static final int IMPORT_ORIGINAL_FIRST_THUNK = 0;   // RVA
    private static final int IMPORT_TIME_DATE_STAMP = 4;
    private static final int IMPORT_FORWARDER_CHAIN = 8;
    private static final int IMPORT_NAME_RVA = 12;              // RVA to DLL name
    private static final int IMPORT_FIRST_THUNK = 16;           // RVA to IAT
    
    // Import Lookup Table (Thunk) entry format
    // For PE32+: bit 63 = ordinal flag, bits 0-15 = ordinal, bits 0-30 = hint/name table entry
    
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
    
    // Emulated kernel32.dll function table
    // Each entry is a code stub address that calls into our handler
    private static final int MAX_EMULATED_FUNCS = 16;
    private static long emulatedFuncAddrs = 0;  // Allocated memory for stubs
    
    // kernel32.dll function IDs
    private static final int FUNC_GET_STD_HANDLE = 1;
    private static final int FUNC_WRITE_FILE = 2;
    private static final int FUNC_EXIT_PROCESS = 3;

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
            
            // Section header layout:
            // 0-7: Name (8 bytes)
            // 8: VirtualSize (4 bytes)
            // 12: VirtualAddress (4 bytes) 
            // 16: SizeOfRawData (4 bytes)
            // 20: PointerToRawData (4 bytes)
            int virtSize = readUInt32LE(buffer, secOffset + 8);
            int virtAddr = readUInt32LE(buffer, secOffset + 12);
            int rawSize = readUInt32LE(buffer, secOffset + 16);
            int rawAddr = readUInt32LE(buffer, secOffset + 20);
            
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
        
        // Process import table
        int importTableRVA = readUInt32LE(buffer, optHeaderOffset + OPT_IMPORT_TABLE_RVA);
        if (importTableRVA != 0) {
            processImportTable(execMem, importTableRVA);
        }
        
        // Process relocations (base relocation table)
        // This is needed for ASLR-aware binaries
        int relocDirRVA = readUInt32LE(buffer, optHeaderOffset + 144);  // Base Relocation Table RVA
        if (relocDirRVA != 0) {
            processRelocations(execMem, relocDirRVA, imageBase);
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
    
    // ===================================================================
    // IMPORT TABLE PROCESSING
    // ===================================================================
    
    private static void processImportTable(long execMem, int importTableRVA) {
        int descOffset = importTableRVA;
        int dllCount = 0;
        
        // Iterate through import descriptors
        while (true) {
            int originalFirstThunk = readUInt32LE(execMem, descOffset + IMPORT_ORIGINAL_FIRST_THUNK);
            int nameRVA = readUInt32LE(execMem, descOffset + IMPORT_NAME_RVA);
            int firstThunk = readUInt32LE(execMem, descOffset + IMPORT_FIRST_THUNK);
            
            // Null descriptor marks end
            if (nameRVA == 0 && firstThunk == 0) {
                break;
            }
            
            // Read DLL name and check if it's kernel32.dll
            int isKernel32 = checkDllName(execMem, nameRVA);
            
            // Process thunks for this DLL
            int thunkOffset = firstThunk;
            int ordinalOrHintRVA = originalFirstThunk;
            if (ordinalOrHintRVA == 0) {
                ordinalOrHintRVA = firstThunk;  // Use FirstThunk if OFT is 0
            }
            
            int funcIdx = 0;
            while (true) {
                long thunkData = readUInt64LE(execMem, ordinalOrHintRVA + funcIdx * 8);
                if (thunkData == 0) {
                    break;  // End of thunks
                }
                
                // Check if import by ordinal (bit 63 set)
                if ((thunkData & 0x8000000000000000L) != 0) {
                    int ordinal = (int)(thunkData & 0xFFFF);
                    Console.writeString("      Ordinal: ");
                    Console.writeNumber(ordinal);
                    Console.writeString("\n");
                } else {
                    // Import by name: thunkData is RVA to hint/name table entry
                    int hintNameRVA = (int)thunkData;
                    
                    // Resolve function
                    long funcAddr = resolveImportByHintName(execMem, hintNameRVA, isKernel32);
                    
                    // Write to IAT
                    Native.writeMemoryLong(execMem + thunkOffset + funcIdx * 8, funcAddr);
                }
                
                funcIdx = funcIdx + 1;
            }
            
            descOffset = descOffset + IMPORT_DESCRIPTOR_SIZE;
            dllCount = dllCount + 1;
        }
    }
    
    // Check if DLL name is kernel32.dll (case-insensitive)
    // Returns 1 if kernel32.dll, 0 otherwise
    private static int checkDllName(long execMem, int nameRVA) {
        // Check for "KERNEL32.DLL" or "kernel32.dll"
        // K=0x4B, E=0x45, R=0x52, N=0x4E, E=0x45, L=0x4C, 3=0x33, 2=0x32
        char c0 = (char)(Native.readMemoryLong(execMem + nameRVA) & 0xFF);
        char c1 = (char)(Native.readMemoryLong(execMem + nameRVA + 1) & 0xFF);
        char c2 = (char)(Native.readMemoryLong(execMem + nameRVA + 2) & 0xFF);
        char c3 = (char)(Native.readMemoryLong(execMem + nameRVA + 3) & 0xFF);
        char c4 = (char)(Native.readMemoryLong(execMem + nameRVA + 4) & 0xFF);
        char c5 = (char)(Native.readMemoryLong(execMem + nameRVA + 5) & 0xFF);
        char c6 = (char)(Native.readMemoryLong(execMem + nameRVA + 6) & 0xFF);
        char c7 = (char)(Native.readMemoryLong(execMem + nameRVA + 7) & 0xFF);
        
        // Check "kernel32" (case-insensitive)
        if (charEqualsIgnoreCase(c0, 'K') == 0) return 0;
        if (charEqualsIgnoreCase(c1, 'E') == 0) return 0;
        if (charEqualsIgnoreCase(c2, 'R') == 0) return 0;
        if (charEqualsIgnoreCase(c3, 'N') == 0) return 0;
        if (charEqualsIgnoreCase(c4, 'E') == 0) return 0;
        if (charEqualsIgnoreCase(c5, 'L') == 0) return 0;
        if (charEqualsIgnoreCase(c6, '3') == 0) return 0;
        if (charEqualsIgnoreCase(c7, '2') == 0) return 0;
        
        return 1;
    }
    
    // Case-insensitive character comparison
    // Returns 1 if equal, 0 otherwise
    private static int charEqualsIgnoreCase(char c, char expected) {
        if (c == expected) return 1;
        // Convert to lowercase
        char lower = c;
        if (c >= 'A' && c <= 'Z') {
            lower = (char)(c + 32);
        }
        char expectedLower = expected;
        if (expected >= 'A' && expected <= 'Z') {
            expectedLower = (char)(expected + 32);
        }
        if (lower == expectedLower) return 1;
        return 0;
    }
    
    // Resolve import by reading function name from hint/name table
    private static long resolveImportByHintName(long execMem, int hintNameRVA, int isKernel32) {
        // hint/name table: 2-byte hint, followed by null-terminated function name
        int hint = readUInt16LE(execMem, hintNameRVA);
        int nameOffset = hintNameRVA + 2;
        
        // Read function name and resolve
        if (isKernel32 != 0) {
            return resolveKernel32Func(execMem, nameOffset);
        }
        
        // Unknown DLL - return dummy
        Console.writeString("      Unknown DLL function (hint ");
        Console.writeNumber(hint);
        Console.writeString(")\n");
        return 0xDEADBEEF;
    }
    
    // Resolve kernel32.dll function by name
    private static long resolveKernel32Func(long execMem, int nameOffset) {
        // Read function name character by character and match
        char c0 = (char)(Native.readMemoryLong(execMem + nameOffset) & 0xFF);
        char c1 = (char)(Native.readMemoryLong(execMem + nameOffset + 1) & 0xFF);
        char c2 = (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF);
        char c3 = (char)(Native.readMemoryLong(execMem + nameOffset + 3) & 0xFF);
        
        // Check for "GetStdHandle" (starts with "GetS")
        if (c0 == 'G' && c1 == 'e' && c2 == 't' && c3 == 'S') {
            return createEmulatedFunc(FUNC_GET_STD_HANDLE);
        }
        
        // Check for "WriteFile" (starts with "Writ")
        if (c0 == 'W' && c1 == 'r' && c2 == 'i' && c3 == 't') {
            return createEmulatedFunc(FUNC_WRITE_FILE);
        }
        
        // Check for "ExitProcess" (starts with "Exit")
        if (c0 == 'E' && c1 == 'x' && c2 == 'i' && c3 == 't') {
            return createEmulatedFunc(FUNC_EXIT_PROCESS);
        }
        
        return 0xDEADBEEF;
    }
    
    // Process base relocations
    private static void processRelocations(long execMem, int relocDirRVA, long imageBase) {
        // For now, we load at the preferred base, so no relocations needed
        // If we needed to relocate, we'd process the relocation blocks here
    }
    
    // Create or get emulated function stub
    private static long createEmulatedFunc(int funcId) {
        // Allocate memory for stubs if not done
        if (emulatedFuncAddrs == 0) {
            emulatedFuncAddrs = Memory.heapAlloc(64 * MAX_EMULATED_FUNCS);
        }
        
        // Each stub is 64 bytes max
        long stubAddr = emulatedFuncAddrs + (funcId - 1) * 64;
        
        // Create a simple stub that calls our handler via int 0x80
        // The stub will:
        //   mov rax, funcId
        //   int 0x80
        //   ret
        // For now, we use a special syscall number to dispatch to kernel32 handlers
        
        // mov rax, imm64 (0x48 0xB8)
        Native.writeMemory(stubAddr + 0, (char)0x48);
        Native.writeMemory(stubAddr + 1, (char)0xB8);
        // Function ID in RAX
        Native.writeMemoryLong(stubAddr + 2, (long)funcId);
        // int 0x80 (0xCD 0x80)
        Native.writeMemory(stubAddr + 10, (char)0xCD);
        Native.writeMemory(stubAddr + 11, (char)0x80);
        // ret (0xC3)
        Native.writeMemory(stubAddr + 12, (char)0xC3);
        
        return stubAddr;
    }
    
    // Handle kernel32.dll function calls from user code
    public static long handleKernel32Call(int funcId, long arg1, long arg2, long arg3, long arg4) {
        if (funcId == FUNC_GET_STD_HANDLE) {
            return kernel32_GetStdHandle((int)arg1);
        } else if (funcId == FUNC_WRITE_FILE) {
            return kernel32_WriteFile(arg1, arg2, arg3, arg4, 0);
        } else if (funcId == FUNC_EXIT_PROCESS) {
            kernel32_ExitProcess((int)arg1);
            return 0;  // Never reached
        }
        return 0;
    }
    
    // kernel32.dll: GetStdHandle
    private static long kernel32_GetStdHandle(int nStdHandle) {
        // nStdHandle: -11 = STD_OUTPUT_HANDLE, -12 = STD_INPUT_HANDLE, -13 = STD_ERROR_HANDLE
        if (nStdHandle == -11 || nStdHandle == 0xFFFFFFF5) {
            return WIN_HANDLE_STDOUT;  // Return our stdout handle
        }
        return 0xFFFFFFFFFFFFFFFFL;  // INVALID_HANDLE_VALUE
    }
    
    // kernel32.dll: WriteFile
    private static long kernel32_WriteFile(long hFile, long lpBuffer, long nNumberOfBytesToWrite, long lpNumberOfBytesWritten, long lpOverlapped) {
        if (hFile == WIN_HANDLE_STDOUT) {
            int i = 0;
            while (i < nNumberOfBytesToWrite) {
                char c = (char)(Native.readMemoryLong(lpBuffer + i) & 0xFF);
                Console.writeChar(c);
                i = i + 1;
            }
            if (lpNumberOfBytesWritten != 0) {
                Native.writeMemoryLong(lpNumberOfBytesWritten, nNumberOfBytesToWrite);
            }
            return 1;  // TRUE
        }
        return 0;  // FALSE
    }
    
    // kernel32.dll: ExitProcess
    private static void kernel32_ExitProcess(int uExitCode) {
        Console.writeString("Process exiting with code ");
        Console.writeNumber(uExitCode);
        Console.writeString("\n");
        Threading.terminateCurrentThread();
    }
    
    public static long getPeLoadedBase() { return peLoadedBase; }
}
