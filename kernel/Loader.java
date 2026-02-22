package kernel;

/**
 * Program loader module - SBF and PE format loaders
 * Supports Windows PE executables with kernel32.dll and msvcrt.dll emulation.
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
    private static final int OPT_RELOC_TABLE_RVA = 152;

    // Import Directory Entry structure offsets
    private static final int IMPORT_DESCRIPTOR_SIZE = 20;
    private static final int IMPORT_ORIGINAL_FIRST_THUNK = 0;
    private static final int IMPORT_TIME_DATE_STAMP = 4;
    private static final int IMPORT_FORWARDER_CHAIN = 8;
    private static final int IMPORT_NAME_RVA = 12;
    private static final int IMPORT_FIRST_THUNK = 16;

    private static final int SECTION_HEADER_SIZE = 40;
    private static final int IMAGE_REL_BASED_DIR64 = 10;
    private static final int IMAGE_REL_BASED_HIGHLOW = 3;
    private static final int IMAGE_REL_BASED_ABSOLUTE = 0;

    // Windows syscall numbers
    private static final int WIN_NT_CLOSE = 0x0F;
    private static final int WIN_NT_WRITE_FILE = 0x08;
    private static final int WIN_NT_TERMINATE_PROCESS = 0x2C;

    // NT Status codes
    private static final long STATUS_SUCCESS = 0L;
    private static final long STATUS_INVALID_HANDLE = 0xC0000008L;

    // ===================================================================
    // HANDLE TABLE (type-discriminated)
    // ===================================================================
    private static final int MAX_WIN_HANDLES = 64;
    private static final int WIN_HANDLE_STDIN = 0;
    private static final int WIN_HANDLE_STDOUT = 1;
    private static final int WIN_HANDLE_STDERR = 2;

    // Handle types
    private static final int HANDLE_TYPE_FREE = 0;
    private static final int HANDLE_TYPE_CONSOLE = 1;
    private static final int HANDLE_TYPE_FILE = 2;
    private static final int HANDLE_TYPE_THREAD = 3;

    private static int[] winHandleType = new int[MAX_WIN_HANDLES];
    private static int[] winHandleFileIdx = new int[MAX_WIN_HANDLES];
    private static int[] winHandleFileOffset = new int[MAX_WIN_HANDLES];
    private static int[] winHandleFileSize = new int[MAX_WIN_HANDLES];
    private static long[] winHandleFileBuffer = new long[MAX_WIN_HANDLES];
    private static int[] winHandleThreadId = new int[MAX_WIN_HANDLES];

    // ===================================================================
    // PE STATE
    // ===================================================================
    private static long peLoadedBase = 0;
    private static long peLoadedEnd = 0;

    // Fake TEB/PEB for Windows PE programs (gs:[0x30] TEB access)
    private static long fakeTebAddr = 0;
    private static long fakePebAddr = 0;

    // Emulated function table
    private static final int MAX_EMULATED_FUNCS = 128;
    private static long emulatedFuncAddrs = 0;
    private static int nextEmulatedFuncSlot = 0;

    // DLL IDs
    private static final int DLL_UNKNOWN = 0;
    private static final int DLL_KERNEL32 = 1;
    private static final int DLL_MSVCRT = 2;
    private static final int DLL_NTDLL = 3;

    // kernel32.dll function IDs (1-49)
    private static final int FUNC_GET_STD_HANDLE = 1;
    private static final int FUNC_WRITE_FILE = 2;
    private static final int FUNC_EXIT_PROCESS = 3;
    private static final int FUNC_GET_LAST_ERROR = 4;
    private static final int FUNC_SET_LAST_ERROR = 5;
    private static final int FUNC_GET_PROCESS_HEAP = 6;
    private static final int FUNC_HEAP_ALLOC = 7;
    private static final int FUNC_HEAP_FREE = 8;
    private static final int FUNC_LOCAL_ALLOC = 9;
    private static final int FUNC_LOCAL_FREE = 10;
    private static final int FUNC_READ_FILE = 11;
    private static final int FUNC_CLOSE_HANDLE = 12;
    private static final int FUNC_GET_COMMAND_LINE_A = 13;
    private static final int FUNC_GET_ENVIRONMENT_STRINGS_A = 14;
    private static final int FUNC_FREE_ENVIRONMENT_STRINGS_A = 15;
    private static final int FUNC_GET_CONSOLE_MODE = 16;
    private static final int FUNC_CREATE_FILE_A = 17;
    private static final int FUNC_CREATE_THREAD = 18;
    private static final int FUNC_SLEEP = 19;
    private static final int FUNC_WAIT_FOR_SINGLE_OBJECT = 20;
    private static final int FUNC_GET_MODULE_HANDLE_A = 21;
    private static final int FUNC_GET_CURRENT_PROCESS_ID = 22;
    private static final int FUNC_GET_CURRENT_THREAD_ID = 23;
    private static final int FUNC_IS_DEBUGGER_PRESENT = 24;
    private static final int FUNC_GET_TICK_COUNT = 25;
    private static final int FUNC_VIRTUAL_ALLOC = 26;
    private static final int FUNC_VIRTUAL_FREE = 27;
    private static final int FUNC_QUERY_PERFORMANCE_COUNTER = 28;
    private static final int FUNC_QUERY_PERFORMANCE_FREQUENCY = 29;
    private static final int FUNC_FLUSH_FILE_BUFFERS = 30;
    private static final int FUNC_SET_FILE_POINTER = 31;
    private static final int FUNC_GET_FILE_SIZE = 32;
    private static final int FUNC_GET_FILE_TYPE = 33;
    private static final int FUNC_SET_HANDLE_COUNT = 34;
    private static final int FUNC_GET_STARTUP_INFO_A = 35;
    private static final int FUNC_GET_MODULE_FILE_NAME_A = 36;
    private static final int FUNC_MULTI_BYTE_TO_WIDE_CHAR = 37;
    private static final int FUNC_WIDE_CHAR_TO_MULTI_BYTE = 38;
    private static final int FUNC_GET_STRING_TYPE_W = 39;
    private static final int FUNC_SET_CONSOLE_MODE = 40;
    private static final int FUNC_INIT_CRIT_SECTION = 41;
    private static final int FUNC_DELETE_CRIT_SECTION = 42;
    private static final int FUNC_ENTER_CRIT_SECTION = 43;
    private static final int FUNC_LEAVE_CRIT_SECTION = 44;
    private static final int FUNC_TLS_ALLOC = 45;
    private static final int FUNC_TLS_GET_VALUE = 46;
    private static final int FUNC_TLS_SET_VALUE = 47;
    private static final int FUNC_GET_CURRENT_PROCESS = 48;
    private static final int FUNC_UNHANDLED_EXCEPTION_FILTER = 49;
    private static final int FUNC_WRITE_CONSOLE_W = 50;
    private static final int FUNC_GET_CONSOLE_OUTPUT_CP = 51;
    private static final int FUNC_FORMAT_MESSAGE_W = 52;
    private static final int FUNC_HEAP_SET_INFORMATION = 53;
    private static final int FUNC_SET_THREAD_UI_LANGUAGE = 54;
    private static final int FUNC_TERMINATE_PROCESS = 55;
    private static final int FUNC_GET_SYSTEM_TIME_AS_FILE_TIME = 56;
    private static final int FUNC_RTL_VIRTUAL_UNWIND = 57;
    private static final int FUNC_RTL_LOOKUP_FUNCTION_ENTRY = 58;
    private static final int FUNC_RTL_CAPTURE_CONTEXT = 59;
    private static final int FUNC_GET_MODULE_HANDLE_W = 60;
    private static final int FUNC_LOAD_LIBRARY_W = 61;
    private static final int FUNC_GET_PROC_ADDRESS = 62;
    private static final int FUNC_FREE_LIBRARY = 63;
    private static final int FUNC_GET_SYSTEM_DIRECTORY_W = 64;
    private static final int FUNC_GET_STARTUP_INFO_W = 65;
    private static final int FUNC_SET_UNHANDLED_EXC_FILTER = 66;

    // Per-thread last error
    private static long[] winLastError = new long[16];

    // Static command line and environment strings
    private static long cmdLineAddr = 0;
    private static long envStringsAddr = 0;
    private static long startupInfoAddr = 0;

    // Thread trampoline stubs
    private static long trampolineBase = 0;
    private static int nextTrampolineSlot = 0;

    public static void initWinHandles() {
        int i = 0;
        while (i < MAX_WIN_HANDLES) {
            winHandleType[i] = HANDLE_TYPE_FREE;
            winHandleFileIdx[i] = 0;
            winHandleFileOffset[i] = 0;
            winHandleFileSize[i] = 0;
            winHandleFileBuffer[i] = 0;
            winHandleThreadId[i] = 0;
            i = i + 1;
        }
        // Reserve stdin/stdout/stderr
        winHandleType[WIN_HANDLE_STDIN] = HANDLE_TYPE_CONSOLE;
        winHandleType[WIN_HANDLE_STDOUT] = HANDLE_TYPE_CONSOLE;
        winHandleType[WIN_HANDLE_STDERR] = HANDLE_TYPE_CONSOLE;

        // Clear last error
        i = 0;
        while (i < 16) {
            winLastError[i] = 0;
            i = i + 1;
        }

        nextEmulatedFuncSlot = 0;
    }

    // ===================================================================
    // RAW MEMORY HELPERS (absolute addresses, not buffer+offset)
    // ===================================================================

    private static long readUInt64LE_raw(long addr) {
        long b0 = (long)(Native.readMemoryLong(addr) & 0xFF);
        long b1 = (long)(Native.readMemoryLong(addr + 1) & 0xFF);
        long b2 = (long)(Native.readMemoryLong(addr + 2) & 0xFF);
        long b3 = (long)(Native.readMemoryLong(addr + 3) & 0xFF);
        long b4 = (long)(Native.readMemoryLong(addr + 4) & 0xFF);
        long b5 = (long)(Native.readMemoryLong(addr + 5) & 0xFF);
        long b6 = (long)(Native.readMemoryLong(addr + 6) & 0xFF);
        long b7 = (long)(Native.readMemoryLong(addr + 7) & 0xFF);
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) |
               (b4 << 32) | (b5 << 40) | (b6 << 48) | (b7 << 56);
    }

    private static void writeUInt64LE_raw(long addr, long val) {
        Native.writeMemory(addr + 0, (char)(val & 0xFF));
        Native.writeMemory(addr + 1, (char)((val >> 8) & 0xFF));
        Native.writeMemory(addr + 2, (char)((val >> 16) & 0xFF));
        Native.writeMemory(addr + 3, (char)((val >> 24) & 0xFF));
        Native.writeMemory(addr + 4, (char)((val >> 32) & 0xFF));
        Native.writeMemory(addr + 5, (char)((val >> 40) & 0xFF));
        Native.writeMemory(addr + 6, (char)((val >> 48) & 0xFF));
        Native.writeMemory(addr + 7, (char)((val >> 56) & 0xFF));
    }

    private static void writeUInt32LE_raw(long addr, int val) {
        Native.writeMemory(addr + 0, (char)(val & 0xFF));
        Native.writeMemory(addr + 1, (char)((val >> 8) & 0xFF));
        Native.writeMemory(addr + 2, (char)((val >> 16) & 0xFF));
        Native.writeMemory(addr + 3, (char)((val >> 24) & 0xFF));
    }

    private static int readUInt32LE_raw(long addr) {
        int b0 = (int)Native.readMemoryLong(addr) & 0xFF;
        int b1 = (int)Native.readMemoryLong(addr + 1) & 0xFF;
        int b2 = (int)Native.readMemoryLong(addr + 2) & 0xFF;
        int b3 = (int)Native.readMemoryLong(addr + 3) & 0xFF;
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    // ===================================================================
    // BINARY LOADING
    // ===================================================================

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

        long entryPoint;
        if (isPEFormat(buffer, fileSize)) {
            Console.writeString("Detected Windows PE executable\n");
            entryPoint = loadPE(buffer, fileSize);
        } else {
            Console.writeString("Loading as SBF format\n");
            entryPoint = loadSBF(buffer, fileSize);
        }

        // Free the file buffer (loadPE/loadSBF copies data to execMem)
        Memory.heapFree(buffer);

        return entryPoint;
    }

    private static boolean isPEFormat(long buffer, int fileSize) {
        if (fileSize < 64) return false;

        char m = (char)(Native.readMemoryLong(buffer) & 0xFF);
        char z = (char)(Native.readMemoryLong(buffer + 1) & 0xFF);
        int dosMagic = ((int)z << 8) | (int)m;

        if (dosMagic != DOS_MAGIC) return false;

        int peOffset = readUInt32LE(buffer, DOS_LFANEW_OFFSET);

        if (peOffset < 0 || peOffset + 4 > fileSize) return false;

        int peSig = readUInt32LE(buffer, peOffset);

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

        // Validate PE header values
        if (numSections < 0 || numSections > 96) {
            Console.writeString("ERROR: Invalid PE numSections: ");
            Console.writeNumber(numSections);
            Console.writeString("\n");
            return 0;
        }
        if (sizeOfImage <= 0 || sizeOfImage > 0x2000000) {
            Console.writeString("ERROR: Invalid PE sizeOfImage: ");
            Console.writeNumber(sizeOfImage);
            Console.writeString("\n");
            return 0;
        }
        if (sizeOfHeaders > sizeOfImage || sizeOfHeaders > fileSize) {
            Console.writeString("ERROR: Invalid PE sizeOfHeaders\n");
            return 0;
        }

        long execMem = Memory.heapAlloc(sizeOfImage);
        if (execMem == 0) {
            Console.writeString("ERROR: Could not allocate executable memory\n");
            return 0;
        }

        // Zero the entire image area
        Native.memset(execMem, 0, (long)sizeOfImage);

        peLoadedBase = execMem;
        peLoadedEnd = execMem + sizeOfImage;

        // Copy headers
        Native.memcpy(execMem, buffer, (long)sizeOfHeaders);

        // Load sections
        int sectionTableOffset = optHeaderOffset + optHeaderSize;
        int s = 0;
        while (s < numSections) {
            int secOffset = sectionTableOffset + s * SECTION_HEADER_SIZE;

            int virtSize = readUInt32LE(buffer, secOffset + 8);
            int virtAddr = readUInt32LE(buffer, secOffset + 12);
            int rawSize = readUInt32LE(buffer, secOffset + 16);
            int rawAddr = readUInt32LE(buffer, secOffset + 20);

            int copySize = virtSize;
            if (rawSize < copySize) copySize = rawSize;

            if (copySize > 0 && rawAddr > 0) {
                // Validate section bounds
                if (virtAddr + copySize > sizeOfImage || rawAddr + copySize > fileSize) {
                    Console.writeString("WARNING: Section bounds exceed image/file, skipping\n");
                } else {
                    Native.memcpy(execMem + virtAddr, buffer + rawAddr, (long)copySize);
                }
            }

            s = s + 1;
        }


        // Process relocations FIRST (fix absolute addresses for new load address)
        int relocDirRVA = readUInt32LE(buffer, optHeaderOffset + OPT_RELOC_TABLE_RVA);
        if (relocDirRVA != 0) {
            processRelocations(execMem, relocDirRVA, imageBase);
        }

        // Process import table AFTER relocations (writes stub addresses to IAT)
        int importTableRVA = readUInt32LE(buffer, optHeaderOffset + OPT_IMPORT_TABLE_RVA);
        if (importTableRVA != 0) {
            processImportTable(execMem, importTableRVA);
        }

        long entryPoint = execMem + entryPointRVA;

        // Set up fake TEB/PEB for Windows gs:[0x30] TEB access
        setupFakeTEB();

        // Create trampoline: write '!' to serial, call entry, write '@', then ExitProcess
        long trampoline = Memory.heapAlloc(128);
        int tp = 0;
        // mov dx, 0x3F8          66 BA F8 03
        Native.writeMemory(trampoline + tp, (char)0x66); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0xBA); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0xF8); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0x03); tp = tp + 1;
        // mov al, '!'            B0 21
        Native.writeMemory(trampoline + tp, (char)0xB0); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0x21); tp = tp + 1;
        // out dx, al             EE
        Native.writeMemory(trampoline + tp, (char)0xEE); tp = tp + 1;
        // and rsp, -16           48 83 E4 F0  (force 16-byte alignment)
        Native.writeMemory(trampoline + tp, (char)0x48); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0x83); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0xE4); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0xF0); tp = tp + 1;
        // sub rsp, 0x20         48 83 EC 20  (32-byte shadow space)
        Native.writeMemory(trampoline + tp, (char)0x48); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0x83); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0xEC); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0x20); tp = tp + 1;
        // mov rax, entryPoint; call rax
        Native.writeMemory(trampoline + tp, (char)0x48); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0xB8); tp = tp + 1;
        Native.writeMemoryLong(trampoline + tp, entryPoint);
        tp = tp + 8;
        Native.writeMemory(trampoline + tp, (char)0xFF); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0xD0); tp = tp + 1;  // call rax
        // After return, write '@' to serial
        // mov al, '@'            B0 40
        Native.writeMemory(trampoline + tp, (char)0xB0); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0x40); tp = tp + 1;
        // out dx, al             EE
        Native.writeMemory(trampoline + tp, (char)0xEE); tp = tp + 1;
        // ExitProcess via int 0x80: mov rax, 3 (FUNC_EXIT_PROCESS); int 0x80
        Native.writeMemory(trampoline + tp, (char)0x48); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0xB8); tp = tp + 1;
        Native.writeMemoryLong(trampoline + tp, 3L);  // FUNC_EXIT_PROCESS
        tp = tp + 8;
        Native.writeMemory(trampoline + tp, (char)0xCD); tp = tp + 1;
        Native.writeMemory(trampoline + tp, (char)0x80); tp = tp + 1;
        // Infinite hlt loop (fallback)
        Native.writeMemory(trampoline + tp, (char)0xF4); tp = tp + 1;  // hlt
        Native.writeMemory(trampoline + tp, (char)0xEB); tp = tp + 1;  // jmp -2
        Native.writeMemory(trampoline + tp, (char)0xFD); tp = tp + 1;

        // Pre-init security cookie to skip __security_init_cookie generation
        Native.writeMemoryLong(execMem + 0x4040, 0x12345678ABCDEF01L);
        Native.writeMemoryLong(execMem + 0x4080, ~0x12345678ABCDEF01L);
        Console.writeString("PE loaded successfully\n");

        return trampoline;  // Start at trampoline instead of entry point
    }

    private static void setupFakeTEB() {
        // Allocate TEB (256 bytes) and PEB (256 bytes)
        if (fakeTebAddr == 0) {
            fakeTebAddr = Memory.heapAlloc(256);
            fakePebAddr = Memory.heapAlloc(256);
            if (fakeTebAddr == 0 || fakePebAddr == 0) {
                Console.writeString("ERROR: Could not allocate TEB/PEB\n");
                return;
            }
            // Zero both structures
            Native.memset(fakeTebAddr, 0, 256);
            Native.memset(fakePebAddr, 0, 256);
        }

        // TEB fields:
        // [0x00] ExceptionList = -1 (no SEH chain)
        Native.writeMemoryLong(fakeTebAddr + 0x00, 0xFFFFFFFFFFFFFFFFL);
        // [0x08] StackBase (top of mapped memory - conservative)
        Native.writeMemoryLong(fakeTebAddr + 0x08, 0x08000000L);
        // [0x10] StackLimit (heap area - conservative)
        Native.writeMemoryLong(fakeTebAddr + 0x10, 0x00400000L);
        // [0x30] Self pointer (TEB address)
        Native.writeMemoryLong(fakeTebAddr + 0x30, fakeTebAddr);
        // [0x60] ProcessEnvironmentBlock (PEB pointer)
        Native.writeMemoryLong(fakeTebAddr + 0x60, fakePebAddr);

        // PEB fields:
        // [0x02] BeingDebugged = 0 (already zero)
        // [0x10] ImageBaseAddress = peLoadedBase
        Native.writeMemoryLong(fakePebAddr + 0x10, peLoadedBase);

        // Set GS_BASE to point to our fake TEB
        Native.setGSBase(fakeTebAddr);
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

        Native.memcpy(execMem, buffer + 16, execSize);

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

    // ===================================================================
    // WINDOWS SYSCALL EMULATION
    // ===================================================================

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
        if (fileHandle == WIN_HANDLE_STDOUT || fileHandle == WIN_HANDLE_STDERR) {
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
    // RELOCATION PROCESSING (Phase 1 - critical fix)
    // ===================================================================

    private static void processRelocations(long execMem, int relocDirRVA, long imageBase) {
        long delta = execMem - imageBase;
        if (delta == 0) {
            return; // No relocation needed
        }

        int blockOffset = relocDirRVA;
        int relocCount = 0;

        while (true) {
            int blockRVA = readUInt32LE(execMem, blockOffset);
            int blockSize = readUInt32LE(execMem, blockOffset + 4);

            if (blockRVA == 0 || blockSize == 0) {
                break;
            }

            int numEntries = (blockSize - 8) / 2;
            int entryIdx = 0;
            while (entryIdx < numEntries) {
                int entry = readUInt16LE(execMem, blockOffset + 8 + entryIdx * 2);
                int type = (entry >> 12) & 0xF;
                int offset = entry & 0xFFF;

                long targetAddr = execMem + blockRVA + offset;

                if (type == IMAGE_REL_BASED_DIR64) {
                    long oldVal = readUInt64LE_raw(targetAddr);
                    long newVal = oldVal + delta;
                    writeUInt64LE_raw(targetAddr, newVal);
                    relocCount = relocCount + 1;
                } else if (type == IMAGE_REL_BASED_HIGHLOW) {
                    int oldVal = readUInt32LE_raw(targetAddr);
                    int newVal = oldVal + (int)delta;
                    writeUInt32LE_raw(targetAddr, newVal);
                    relocCount = relocCount + 1;
                } else if (type == IMAGE_REL_BASED_ABSOLUTE) {
                    // Padding - ignore
                }

                entryIdx = entryIdx + 1;
            }

            blockOffset = blockOffset + blockSize;
        }
    }

    // ===================================================================
    // IMPORT TABLE PROCESSING
    // ===================================================================

    private static void processImportTable(long execMem, int importTableRVA) {
        int descOffset = importTableRVA;
        int dllCount = 0;

        while (true) {
            int originalFirstThunk = readUInt32LE(execMem, descOffset + IMPORT_ORIGINAL_FIRST_THUNK);
            int nameRVA = readUInt32LE(execMem, descOffset + IMPORT_NAME_RVA);
            int firstThunk = readUInt32LE(execMem, descOffset + IMPORT_FIRST_THUNK);

            if (nameRVA == 0 && firstThunk == 0) {
                break;
            }

            int dllId = identifyDll(execMem, nameRVA);

            int thunkOffset = firstThunk;
            int ordinalOrHintRVA = originalFirstThunk;
            if (ordinalOrHintRVA == 0) {
                ordinalOrHintRVA = firstThunk;
            }

            int funcIdx = 0;
            while (true) {
                long thunkData = readUInt64LE(execMem, ordinalOrHintRVA + funcIdx * 8);
                if (thunkData == 0) {
                    break;
                }

                if ((thunkData & 0x8000000000000000L) != 0) {
                    // Ordinal import - skip
                } else {
                    int hintNameRVA = (int)thunkData;
                    long funcAddr = resolveImportByHintName(execMem, hintNameRVA, dllId);
                    Native.writeMemoryLong(execMem + thunkOffset + funcIdx * 8, funcAddr);
                }

                funcIdx = funcIdx + 1;
            }

            descOffset = descOffset + IMPORT_DESCRIPTOR_SIZE;
            dllCount = dllCount + 1;
        }
    }

    // Identify DLL by name (case-insensitive)
    // Returns DLL_KERNEL32, DLL_MSVCRT, DLL_NTDLL, or DLL_UNKNOWN
    private static int identifyDll(long execMem, int nameRVA) {
        // Read up to 12 chars for comparison
        char c0 = toLower((char)(Native.readMemoryLong(execMem + nameRVA) & 0xFF));
        char c1 = toLower((char)(Native.readMemoryLong(execMem + nameRVA + 1) & 0xFF));
        char c2 = toLower((char)(Native.readMemoryLong(execMem + nameRVA + 2) & 0xFF));
        char c3 = toLower((char)(Native.readMemoryLong(execMem + nameRVA + 3) & 0xFF));
        char c4 = toLower((char)(Native.readMemoryLong(execMem + nameRVA + 4) & 0xFF));
        char c5 = toLower((char)(Native.readMemoryLong(execMem + nameRVA + 5) & 0xFF));
        char c6 = toLower((char)(Native.readMemoryLong(execMem + nameRVA + 6) & 0xFF));
        char c7 = toLower((char)(Native.readMemoryLong(execMem + nameRVA + 7) & 0xFF));

        // Check "kernel32"
        if (c0 == 'k' && c1 == 'e' && c2 == 'r' && c3 == 'n' &&
            c4 == 'e' && c5 == 'l' && c6 == '3' && c7 == '2') {
            return DLL_KERNEL32;
        }

        // Check "msvcrt" (msvcrt.dll or msvcrt*.dll variations)
        if (c0 == 'm' && c1 == 's' && c2 == 'v' && c3 == 'c' && c4 == 'r' && c5 == 't') {
            return DLL_MSVCRT;
        }

        // Check "ntdll"
        if (c0 == 'n' && c1 == 't' && c2 == 'd' && c3 == 'l' && c4 == 'l') {
            return DLL_NTDLL;
        }

        // Check "api-ms-win-crt" (UCRT forwarders - treat as msvcrt)
        if (c0 == 'a' && c1 == 'p' && c2 == 'i' && c3 == '-' &&
            c4 == 'm' && c5 == 's' && c6 == '-') {
            return DLL_MSVCRT;
        }

        Console.writeString("    DLL: unknown (");
        int k = 0;
        while (k < 12) {
            char ch = (char)(Native.readMemoryLong(execMem + nameRVA + k) & 0xFF);
            if (ch == 0) break;
            Console.writeChar(ch);
            k = k + 1;
        }
        Console.writeString(")\n");
        return DLL_UNKNOWN;
    }

    private static char toLower(char c) {
        if (c >= 'A' && c <= 'Z') {
            return (char)(c + 32);
        }
        return c;
    }

    // Case-insensitive character comparison
    private static int charEqualsIgnoreCase(char c, char expected) {
        if (c == expected) return 1;
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
    private static long resolveImportByHintName(long execMem, int hintNameRVA, int dllId) {
        int hint = readUInt16LE(execMem, hintNameRVA);
        int nameOffset = hintNameRVA + 2;

        if (dllId == DLL_KERNEL32) {
            return resolveKernel32Func(execMem, nameOffset);
        } else if (dllId == DLL_MSVCRT) {
            return resolveMsvcrtFunc(execMem, nameOffset);
        } else if (dllId == DLL_NTDLL) {
            return resolveKernel32Func(execMem, nameOffset); // many overlap
        }

        // Unknown DLL - create a no-op stub
        return createEmulatedFunc(FUNC_GET_LAST_ERROR); // safe no-op
    }

    // ===================================================================
    // FULL NAME COMPARISON HELPER
    // ===================================================================

    // Compare name at execMem+nameOffset with a static string
    // Returns 1 if match, 0 otherwise
    private static int nameMatches(long execMem, int nameOffset, char c0, char c1, char c2, char c3,
                                    char c4, char c5, char c6, char c7,
                                    char c8, char c9, char c10, char c11, int len) {
        if (len >= 1 && (char)(Native.readMemoryLong(execMem + nameOffset) & 0xFF) != c0) return 0;
        if (len >= 2 && (char)(Native.readMemoryLong(execMem + nameOffset + 1) & 0xFF) != c1) return 0;
        if (len >= 3 && (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF) != c2) return 0;
        if (len >= 4 && (char)(Native.readMemoryLong(execMem + nameOffset + 3) & 0xFF) != c3) return 0;
        if (len >= 5 && (char)(Native.readMemoryLong(execMem + nameOffset + 4) & 0xFF) != c4) return 0;
        if (len >= 6 && (char)(Native.readMemoryLong(execMem + nameOffset + 5) & 0xFF) != c5) return 0;
        if (len >= 7 && (char)(Native.readMemoryLong(execMem + nameOffset + 6) & 0xFF) != c6) return 0;
        if (len >= 8 && (char)(Native.readMemoryLong(execMem + nameOffset + 7) & 0xFF) != c7) return 0;
        if (len >= 9 && (char)(Native.readMemoryLong(execMem + nameOffset + 8) & 0xFF) != c8) return 0;
        if (len >= 10 && (char)(Native.readMemoryLong(execMem + nameOffset + 9) & 0xFF) != c9) return 0;
        if (len >= 11 && (char)(Native.readMemoryLong(execMem + nameOffset + 10) & 0xFF) != c10) return 0;
        if (len >= 12 && (char)(Native.readMemoryLong(execMem + nameOffset + 11) & 0xFF) != c11) return 0;
        // Check null terminator at position len
        if ((char)(Native.readMemoryLong(execMem + nameOffset + len) & 0xFF) != 0) return 0;
        return 1;
    }

    // Shorter helper for common name lengths
    private static int nameStartsWith(long execMem, int nameOffset, char c0, char c1, char c2, char c3) {
        if ((char)(Native.readMemoryLong(execMem + nameOffset) & 0xFF) != c0) return 0;
        if ((char)(Native.readMemoryLong(execMem + nameOffset + 1) & 0xFF) != c1) return 0;
        if ((char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF) != c2) return 0;
        if ((char)(Native.readMemoryLong(execMem + nameOffset + 3) & 0xFF) != c3) return 0;
        return 1;
    }

    // ===================================================================
    // KERNEL32 FUNCTION RESOLUTION (full name matching)
    // ===================================================================

    private static long resolveKernel32Func(long execMem, int nameOffset) {
        char c0 = (char)(Native.readMemoryLong(execMem + nameOffset) & 0xFF);
        char c1 = (char)(Native.readMemoryLong(execMem + nameOffset + 1) & 0xFF);
        char c2 = (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF);
        char c3 = (char)(Native.readMemoryLong(execMem + nameOffset + 3) & 0xFF);
        char c4 = (char)(Native.readMemoryLong(execMem + nameOffset + 4) & 0xFF);

        // Group by first letter for efficiency
        if (c0 == 'G') {
            // GetStdHandle
            if (c1 == 'e' && c2 == 't' && c3 == 'S' && c4 == 't') {
                char c5 = (char)(Native.readMemoryLong(execMem + nameOffset + 5) & 0xFF);
                if (c5 == 'd') return createEmulatedFunc(FUNC_GET_STD_HANDLE);
                if (c5 == 'r') {
                    // GetStringTypeW
                    return createEmulatedFunc(FUNC_GET_STRING_TYPE_W);
                }
                // GetStartupInfoA / GetStartupInfoW
                if (c5 == 'a') {
                    char c14 = (char)(Native.readMemoryLong(execMem + nameOffset + 14) & 0xFF);
                    if (c14 == 'W') return createEmulatedFunc(FUNC_GET_STARTUP_INFO_W);
                    return createEmulatedFunc(FUNC_GET_STARTUP_INFO_A);
                }
            }
            // GetLastError
            if (c1 == 'e' && c2 == 't' && c3 == 'L' && c4 == 'a') {
                return createEmulatedFunc(FUNC_GET_LAST_ERROR);
            }
            // GetProcAddress
            if (c1 == 'e' && c2 == 't' && c3 == 'P' && c4 == 'r') {
                char c5 = (char)(Native.readMemoryLong(execMem + nameOffset + 5) & 0xFF);
                if (c5 == 'o') return createEmulatedFunc(FUNC_GET_PROC_ADDRESS);
                // GetProcessHeap
                return createEmulatedFunc(FUNC_GET_PROCESS_HEAP);
            }
            // GetCommandLineA / GetConsoleMode / GetConsoleOutputCP
            if (c1 == 'e' && c2 == 't' && c3 == 'C' && c4 == 'o') {
                char c5 = (char)(Native.readMemoryLong(execMem + nameOffset + 5) & 0xFF);
                if (c5 == 'm') return createEmulatedFunc(FUNC_GET_COMMAND_LINE_A);
                if (c5 == 'n') {
                    // GetConsoleMode vs GetConsoleOutputCP
                    char c10 = (char)(Native.readMemoryLong(execMem + nameOffset + 10) & 0xFF);
                    if (c10 == 'M') return createEmulatedFunc(FUNC_GET_CONSOLE_MODE);
                    if (c10 == 'O') return createEmulatedFunc(FUNC_GET_CONSOLE_OUTPUT_CP);
                    return createEmulatedFunc(FUNC_GET_CONSOLE_MODE);
                }
            }
            // GetEnvironmentStringsA / GetEnvironmentStringsW
            if (c1 == 'e' && c2 == 't' && c3 == 'E' && c4 == 'n') {
                return createEmulatedFunc(FUNC_GET_ENVIRONMENT_STRINGS_A);
            }
            // GetModuleHandleA / GetModuleHandleW / GetModuleFileNameA
            if (c1 == 'e' && c2 == 't' && c3 == 'M' && c4 == 'o') {
                // G(0)e(1)t(2)M(3)o(4)d(5)u(6)l(7)e(8)H(9)/F(9)...
                char c9 = (char)(Native.readMemoryLong(execMem + nameOffset + 9) & 0xFF);
                if (c9 == 'H') {
                    // GetModuleHandle - check A vs W at position 15
                    char c15 = (char)(Native.readMemoryLong(execMem + nameOffset + 15) & 0xFF);
                    if (c15 == 'W') return createEmulatedFunc(FUNC_GET_MODULE_HANDLE_W);
                    return createEmulatedFunc(FUNC_GET_MODULE_HANDLE_A);
                }
                if (c9 == 'F') return createEmulatedFunc(FUNC_GET_MODULE_FILE_NAME_A);
                return createEmulatedFunc(FUNC_GET_MODULE_HANDLE_A);
            }
            // GetCurrentProcessId / GetCurrentThreadId / GetCurrentProcess
            if (c1 == 'e' && c2 == 't' && c3 == 'C' && c4 == 'u') {
                char c10 = (char)(Native.readMemoryLong(execMem + nameOffset + 10) & 0xFF);
                if (c10 == 'T') return createEmulatedFunc(FUNC_GET_CURRENT_THREAD_ID);
                if (c10 == 'P') {
                    // GetCurrentProcess vs GetCurrentProcessId - check c17
                    // G(0)e(1)t(2)C(3)u(4)r(5)r(6)e(7)n(8)t(9)P(10)r(11)o(12)c(13)e(14)s(15)s(16)I(17)/\0(17)
                    char c17 = (char)(Native.readMemoryLong(execMem + nameOffset + 17) & 0xFF);
                    if (c17 == 'I') return createEmulatedFunc(FUNC_GET_CURRENT_PROCESS_ID);
                    return createEmulatedFunc(FUNC_GET_CURRENT_PROCESS);
                }
                return createEmulatedFunc(FUNC_GET_CURRENT_PROCESS);
            }
            // GetTickCount
            if (c1 == 'e' && c2 == 't' && c3 == 'T' && c4 == 'i') {
                return createEmulatedFunc(FUNC_GET_TICK_COUNT);
            }
            // GetSystemTimeAsFileTime / GetSystemDirectoryW
            if (c1 == 'e' && c2 == 't' && c3 == 'S' && c4 == 'y') {
                char c5 = (char)(Native.readMemoryLong(execMem + nameOffset + 5) & 0xFF);
                if (c5 == 't') return createEmulatedFunc(FUNC_GET_SYSTEM_TIME_AS_FILE_TIME);
                if (c5 == 'D') return createEmulatedFunc(FUNC_GET_SYSTEM_DIRECTORY_W);
            }
            // GetFileSize / GetFileType
            if (c1 == 'e' && c2 == 't' && c3 == 'F' && c4 == 'i') {
                char c7 = (char)(Native.readMemoryLong(execMem + nameOffset + 7) & 0xFF);
                if (c7 == 'S') return createEmulatedFunc(FUNC_GET_FILE_SIZE);
                if (c7 == 'T') return createEmulatedFunc(FUNC_GET_FILE_TYPE);
            }
        }

        if (c0 == 'S') {
            // SetLastError
            if (c1 == 'e' && c2 == 't' && c3 == 'L' && c4 == 'a') {
                return createEmulatedFunc(FUNC_SET_LAST_ERROR);
            }
            // SetFilePointer
            if (c1 == 'e' && c2 == 't' && c3 == 'F' && c4 == 'i') {
                return createEmulatedFunc(FUNC_SET_FILE_POINTER);
            }
            // SetHandleCount
            if (c1 == 'e' && c2 == 't' && c3 == 'H' && c4 == 'a') {
                return createEmulatedFunc(FUNC_SET_HANDLE_COUNT);
            }
            // SetConsoleMode
            if (c1 == 'e' && c2 == 't' && c3 == 'C' && c4 == 'o') {
                return createEmulatedFunc(FUNC_SET_CONSOLE_MODE);
            }
            // Sleep
            if (c1 == 'l' && c2 == 'e' && c3 == 'e' && c4 == 'p') {
                return createEmulatedFunc(FUNC_SLEEP);
            }
            // SetUnhandledExceptionFilter
            if (c1 == 'e' && c2 == 't' && c3 == 'U' && c4 == 'n') {
                return createEmulatedFunc(FUNC_UNHANDLED_EXCEPTION_FILTER);
            }
            // SetThreadUILanguage
            if (c1 == 'e' && c2 == 't' && c3 == 'T' && c4 == 'h') {
                return createEmulatedFunc(FUNC_SET_THREAD_UI_LANGUAGE);
            }
        }

        if (c0 == 'W') {
            // WriteFile / WriteConsoleW
            if (c1 == 'r' && c2 == 'i' && c3 == 't' && c4 == 'e') {
                char c5 = (char)(Native.readMemoryLong(execMem + nameOffset + 5) & 0xFF);
                if (c5 == 'F') return createEmulatedFunc(FUNC_WRITE_FILE);
                if (c5 == 'C') return createEmulatedFunc(FUNC_WRITE_CONSOLE_W);
            }
            // WaitForSingleObject
            if (c1 == 'a' && c2 == 'i' && c3 == 't' && c4 == 'F') {
                return createEmulatedFunc(FUNC_WAIT_FOR_SINGLE_OBJECT);
            }
            // WideCharToMultiByte
            if (c1 == 'i' && c2 == 'd' && c3 == 'e' && c4 == 'C') {
                return createEmulatedFunc(FUNC_WIDE_CHAR_TO_MULTI_BYTE);
            }
        }

        if (c0 == 'E') {
            // ExitProcess
            if (c1 == 'x' && c2 == 'i' && c3 == 't' && c4 == 'P') {
                return createEmulatedFunc(FUNC_EXIT_PROCESS);
            }
            // EnterCriticalSection
            if (c1 == 'n' && c2 == 't' && c3 == 'e' && c4 == 'r') {
                return createEmulatedFunc(FUNC_ENTER_CRIT_SECTION);
            }
        }

        if (c0 == 'H') {
            // HeapAlloc
            if (c1 == 'e' && c2 == 'a' && c3 == 'p' && c4 == 'A') {
                return createEmulatedFunc(FUNC_HEAP_ALLOC);
            }
            // HeapFree
            if (c1 == 'e' && c2 == 'a' && c3 == 'p' && c4 == 'F') {
                return createEmulatedFunc(FUNC_HEAP_FREE);
            }
            // HeapSetInformation
            if (c1 == 'e' && c2 == 'a' && c3 == 'p' && c4 == 'S') {
                return createEmulatedFunc(FUNC_HEAP_SET_INFORMATION);
            }
        }

        if (c0 == 'L') {
            // LocalAlloc
            if (c1 == 'o' && c2 == 'c' && c3 == 'a' && c4 == 'l') {
                char c5 = (char)(Native.readMemoryLong(execMem + nameOffset + 5) & 0xFF);
                if (c5 == 'A') return createEmulatedFunc(FUNC_LOCAL_ALLOC);
                if (c5 == 'F') return createEmulatedFunc(FUNC_LOCAL_FREE);
            }
            // LeaveCriticalSection
            if (c1 == 'e' && c2 == 'a' && c3 == 'v' && c4 == 'e') {
                return createEmulatedFunc(FUNC_LEAVE_CRIT_SECTION);
            }
            // LoadLibraryW
            if (c1 == 'o' && c2 == 'a' && c3 == 'd' && c4 == 'L') {
                return createEmulatedFunc(FUNC_LOAD_LIBRARY_W);
            }
        }

        if (c0 == 'R') {
            // ReadFile
            if (c1 == 'e' && c2 == 'a' && c3 == 'd' && c4 == 'F') {
                return createEmulatedFunc(FUNC_READ_FILE);
            }
            // Rtl* functions (RtlVirtualUnwind, RtlLookupFunctionEntry, RtlCaptureContext)
            if (c1 == 't' && c2 == 'l') {
                char c3r = (char)(Native.readMemoryLong(execMem + nameOffset + 3) & 0xFF);
                if (c3r == 'V') return createEmulatedFunc(FUNC_RTL_VIRTUAL_UNWIND);
                if (c3r == 'L') return createEmulatedFunc(FUNC_RTL_LOOKUP_FUNCTION_ENTRY);
                if (c3r == 'C') return createEmulatedFunc(FUNC_RTL_CAPTURE_CONTEXT);
                return createEmulatedFunc(FUNC_RTL_VIRTUAL_UNWIND); // fallback
            }
        }

        if (c0 == 'C') {
            // CloseHandle
            if (c1 == 'l' && c2 == 'o' && c3 == 's' && c4 == 'e') {
                return createEmulatedFunc(FUNC_CLOSE_HANDLE);
            }
            // CreateFileA
            if (c1 == 'r' && c2 == 'e' && c3 == 'a' && c4 == 't') {
                char c6 = (char)(Native.readMemoryLong(execMem + nameOffset + 6) & 0xFF);
                if (c6 == 'F') return createEmulatedFunc(FUNC_CREATE_FILE_A);
                if (c6 == 'T') return createEmulatedFunc(FUNC_CREATE_THREAD);
            }
        }

        if (c0 == 'I') {
            // IsDebuggerPresent
            if (c1 == 's' && c2 == 'D' && c3 == 'e') {
                return createEmulatedFunc(FUNC_IS_DEBUGGER_PRESENT);
            }
            // InitializeCriticalSection / InitializeCriticalSectionAndSpinCount
            if (c1 == 'n' && c2 == 'i' && c3 == 't' && c4 == 'i') {
                return createEmulatedFunc(FUNC_INIT_CRIT_SECTION);
            }
        }

        if (c0 == 'V') {
            // VirtualAlloc
            if (c1 == 'i' && c2 == 'r' && c3 == 't' && c4 == 'u') {
                char c7 = (char)(Native.readMemoryLong(execMem + nameOffset + 7) & 0xFF);
                if (c7 == 'A') return createEmulatedFunc(FUNC_VIRTUAL_ALLOC);
                if (c7 == 'F') return createEmulatedFunc(FUNC_VIRTUAL_FREE);
            }
        }

        if (c0 == 'Q') {
            // QueryPerformanceCounter / QueryPerformanceFrequency
            if (c1 == 'u' && c2 == 'e' && c3 == 'r' && c4 == 'y') {
                // Disambiguate by c16: 'C'=Counter, 'F'=Frequency
                char c16 = (char)(Native.readMemoryLong(execMem + nameOffset + 16) & 0xFF);
                if (c16 == 'C') return createEmulatedFunc(FUNC_QUERY_PERFORMANCE_COUNTER);
                return createEmulatedFunc(FUNC_QUERY_PERFORMANCE_FREQUENCY);
            }
        }

        if (c0 == 'F') {
            // FlushFileBuffers
            if (c1 == 'l' && c2 == 'u' && c3 == 's' && c4 == 'h') {
                return createEmulatedFunc(FUNC_FLUSH_FILE_BUFFERS);
            }
            // FreeEnvironmentStringsA
            if (c1 == 'r' && c2 == 'e' && c3 == 'e' && c4 == 'E') {
                return createEmulatedFunc(FUNC_FREE_ENVIRONMENT_STRINGS_A);
            }
            // FreeLibrary
            if (c1 == 'r' && c2 == 'e' && c3 == 'e' && c4 == 'L') {
                return createEmulatedFunc(FUNC_FREE_LIBRARY);
            }
            // FormatMessageW
            if (c1 == 'o' && c2 == 'r' && c3 == 'm' && c4 == 'a') {
                return createEmulatedFunc(FUNC_FORMAT_MESSAGE_W);
            }
        }

        if (c0 == 'M') {
            // MultiByteToWideChar
            if (c1 == 'u' && c2 == 'l' && c3 == 't' && c4 == 'i') {
                return createEmulatedFunc(FUNC_MULTI_BYTE_TO_WIDE_CHAR);
            }
        }

        if (c0 == 'T') {
            // TlsAlloc
            if (c1 == 'l' && c2 == 's' && c3 == 'A') {
                return createEmulatedFunc(FUNC_TLS_ALLOC);
            }
            // TlsGetValue
            if (c1 == 'l' && c2 == 's' && c3 == 'G') {
                return createEmulatedFunc(FUNC_TLS_GET_VALUE);
            }
            // TlsSetValue
            if (c1 == 'l' && c2 == 's' && c3 == 'S') {
                return createEmulatedFunc(FUNC_TLS_SET_VALUE);
            }
            // TerminateProcess
            if (c1 == 'e' && c2 == 'r' && c3 == 'm') {
                return createEmulatedFunc(FUNC_TERMINATE_PROCESS);
            }
        }

        if (c0 == 'U') {
            // UnhandledExceptionFilter
            if (c1 == 'n' && c2 == 'h' && c3 == 'a' && c4 == 'n') {
                return createEmulatedFunc(FUNC_UNHANDLED_EXCEPTION_FILTER);
            }
        }

        if (c0 == 'D') {
            // DeleteCriticalSection
            if (c1 == 'e' && c2 == 'l' && c3 == 'e' && c4 == 't') {
                return createEmulatedFunc(FUNC_DELETE_CRIT_SECTION);
            }
        }

        // Unrecognized - print name and return safe stub
        Console.writeString("      STUB: ");
        int k = 0;
        while (k < 30) {
            char ch = (char)(Native.readMemoryLong(execMem + nameOffset + k) & 0xFF);
            if (ch == 0) break;
            Console.writeChar(ch);
            k = k + 1;
        }
        Console.writeString("\n");
        return createEmulatedFunc(FUNC_GET_LAST_ERROR); // safe no-op returns 0
    }

    // ===================================================================
    // MSVCRT FUNCTION RESOLUTION
    // ===================================================================

    private static long resolveMsvcrtFunc(long execMem, int nameOffset) {
        char c0 = (char)(Native.readMemoryLong(execMem + nameOffset) & 0xFF);
        char c1 = (char)(Native.readMemoryLong(execMem + nameOffset + 1) & 0xFF);
        char c2 = (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF);
        char c3 = (char)(Native.readMemoryLong(execMem + nameOffset + 3) & 0xFF);

        // Try to get a native C function address for this msvcrt function
        int funcId = identifyMsvcrtFunc(c0, c1, c2, c3, execMem, nameOffset);

        // Handle data imports first - return raw variable address, not a call stub
        if (funcId == MSVCRT_FMODE) {
            return Native.getMsvcrtFuncAddr(37);
        }
        if (funcId == MSVCRT_COMMODE) {
            return Native.getMsvcrtFuncAddr(38);
        }
        if (funcId == MSVCRT_WCMDLN) {
            return Native.getMsvcrtFuncAddr(52);
        }

        // Check if this function has a Java implementation
        int javaId = msvcrtToJavaId(funcId);
        if (javaId > 0) {
            return createEmulatedFunc(javaId);
        }

        // Fall back to C direct-call stub for remaining functions
        if (funcId > 0) {
            return createDirectCallStub(Native.getMsvcrtFuncAddr(funcId));
        }

        // Fallback: return safe no-op stub
        Console.writeString("      MSVCRT STUB: ");
        int k2 = 0;
        while (k2 < 30) {
            char ch = (char)(Native.readMemoryLong(execMem + nameOffset + k2) & 0xFF);
            if (ch == 0) break;
            Console.writeChar(ch);
            k2 = k2 + 1;
        }
        Console.writeString("\n");
        return createEmulatedFunc(FUNC_GET_LAST_ERROR);
    }

    // Map C-side MSVCRT_* IDs to Java dispatch IDs (100+)
    // Returns 0 for functions that must stay in C
    private static int msvcrtToJavaId(int cFuncId) {
        // Batch A: Trivial stubs
        if (cFuncId == MSVCRT_EXIT) return MSVCRT_J_EXIT;
        if (cFuncId == MSVCRT_ABORT) return MSVCRT_J_ABORT;
        if (cFuncId == MSVCRT_CEXIT) return MSVCRT_J_CEXIT;
        if (cFuncId == MSVCRT_AMSG_EXIT) return MSVCRT_J_AMSG_EXIT;
        if (cFuncId == MSVCRT_TERMINATE) return MSVCRT_J_TERMINATE;
        if (cFuncId == MSVCRT_FFLUSH) return MSVCRT_J_FFLUSH;
        if (cFuncId == MSVCRT_SETUSERMATHERR) return MSVCRT_J_SETUSERMATHERR;
        if (cFuncId == MSVCRT_C_SPECIFIC_HANDLER) return MSVCRT_J_C_SPECIFIC_HANDLER;
        if (cFuncId == MSVCRT_SET_APP_TYPE) return MSVCRT_J_SET_APP_TYPE;
        if (cFuncId == MSVCRT_XCPTFILTER) return MSVCRT_J_XCPTFILTER;
        if (cFuncId == MSVCRT_WSYSTEM) return MSVCRT_J_WSYSTEM;
        // Batch B: Character functions
        if (cFuncId == MSVCRT_ISDIGIT) return MSVCRT_J_ISDIGIT;
        if (cFuncId == MSVCRT_ISALPHA) return MSVCRT_J_ISALPHA;
        if (cFuncId == MSVCRT_ISSPACE) return MSVCRT_J_ISSPACE;
        if (cFuncId == MSVCRT_TOUPPER) return MSVCRT_J_TOUPPER;
        if (cFuncId == MSVCRT_TOLOWER_FUNC) return MSVCRT_J_TOLOWER;
        if (cFuncId == MSVCRT_PUTCHAR) return MSVCRT_J_PUTCHAR;
        if (cFuncId == MSVCRT_ATOI) return MSVCRT_J_ATOI;
        // Batch C: String functions
        if (cFuncId == MSVCRT_STRLEN) return MSVCRT_J_STRLEN;
        if (cFuncId == MSVCRT_STRCMP) return MSVCRT_J_STRCMP;
        if (cFuncId == MSVCRT_STRNCMP) return MSVCRT_J_STRNCMP;
        if (cFuncId == MSVCRT_STRCPY) return MSVCRT_J_STRCPY;
        if (cFuncId == MSVCRT_STRNCPY) return MSVCRT_J_STRNCPY;
        if (cFuncId == MSVCRT_STRCAT) return MSVCRT_J_STRCAT;
        if (cFuncId == MSVCRT_STRCHR) return MSVCRT_J_STRCHR;
        if (cFuncId == MSVCRT_STRRCHR) return MSVCRT_J_STRRCHR;
        if (cFuncId == MSVCRT_PUTS) return MSVCRT_J_PUTS;
        // Batch D: Memory functions
        if (cFuncId == MSVCRT_MEMCPY) return MSVCRT_J_MEMCPY;
        if (cFuncId == MSVCRT_MEMSET) return MSVCRT_J_MEMSET;
        if (cFuncId == MSVCRT_MEMCMP) return MSVCRT_J_MEMCMP;
        if (cFuncId == MSVCRT_MEMMOVE) return MSVCRT_J_MEMMOVE;
        // Batch E: Heap functions
        if (cFuncId == MSVCRT_MALLOC) return MSVCRT_J_MALLOC;
        if (cFuncId == MSVCRT_FREE) return MSVCRT_J_FREE;
        if (cFuncId == MSVCRT_CALLOC) return MSVCRT_J_CALLOC;
        if (cFuncId == MSVCRT_REALLOC) return MSVCRT_J_REALLOC;
        // Batch F: Wide-string functions
        if (cFuncId == MSVCRT_WCSNICMP) return MSVCRT_J_WCSNICMP;
        if (cFuncId == MSVCRT_WCSCAT_S) return MSVCRT_J_WCSCAT_S;
        if (cFuncId == MSVCRT_WCSCPY_S) return MSVCRT_J_WCSCPY_S;
        if (cFuncId == MSVCRT_ULTOW) return MSVCRT_J_ULTOW;
        // Batch G: Misc
        if (cFuncId == MSVCRT_SETLOCALE) return MSVCRT_J_SETLOCALE;
        if (cFuncId == MSVCRT_IOBFUNC) return MSVCRT_J_IOBFUNC;
        if (cFuncId == MSVCRT_STRTOL) return MSVCRT_J_STRTOL;
        return 0; // stays in C
    }

    // msvcrt function IDs for Native.getMsvcrtFuncAddr()
    private static final int MSVCRT_PRINTF = 1;
    private static final int MSVCRT_SPRINTF = 2;
    private static final int MSVCRT_SNPRINTF = 3;
    private static final int MSVCRT_PUTS = 4;
    private static final int MSVCRT_PUTCHAR = 5;
    private static final int MSVCRT_MALLOC = 6;
    private static final int MSVCRT_FREE = 7;
    private static final int MSVCRT_CALLOC = 8;
    private static final int MSVCRT_STRLEN = 9;
    private static final int MSVCRT_STRCPY = 10;
    private static final int MSVCRT_STRNCPY = 11;
    private static final int MSVCRT_STRCMP = 12;
    private static final int MSVCRT_STRNCMP = 13;
    private static final int MSVCRT_MEMCMP = 14;
    private static final int MSVCRT_MEMCPY = 15;
    private static final int MSVCRT_MEMSET = 16;
    private static final int MSVCRT_EXIT = 17;
    private static final int MSVCRT_ABORT = 18;
    private static final int MSVCRT_INITTERM = 19;
    private static final int MSVCRT_IOBFUNC = 20;
    private static final int MSVCRT_FPRINTF = 21;
    private static final int MSVCRT_ATOI = 22;
    private static final int MSVCRT_FFLUSH = 23;
    private static final int MSVCRT_REALLOC = 24;
    private static final int MSVCRT_MEMMOVE = 25;
    private static final int MSVCRT_STRCAT = 26;
    private static final int MSVCRT_STRCHR = 27;
    private static final int MSVCRT_STRRCHR = 28;
    private static final int MSVCRT_STRTOL = 29;
    private static final int MSVCRT_ISDIGIT = 30;
    private static final int MSVCRT_ISALPHA = 31;
    private static final int MSVCRT_ISSPACE = 32;
    private static final int MSVCRT_TOUPPER = 33;
    private static final int MSVCRT_TOLOWER_FUNC = 34;
    private static final int MSVCRT_SETUSERMATHERR = 35;
    private static final int MSVCRT_C_SPECIFIC_HANDLER = 36;
    private static final int MSVCRT_FMODE = 37;
    private static final int MSVCRT_COMMODE = 38;
    private static final int MSVCRT_TERMINATE = 39;
    private static final int MSVCRT_SET_APP_TYPE = 40;
    private static final int MSVCRT_WGETMAINARGS = 41;
    private static final int MSVCRT_AMSG_EXIT = 42;
    private static final int MSVCRT_XCPTFILTER = 43;
    private static final int MSVCRT_WCSNICMP = 44;
    private static final int MSVCRT_WSYSTEM = 45;
    private static final int MSVCRT_WCSCAT_S = 46;
    private static final int MSVCRT_WCSCPY_S = 47;
    private static final int MSVCRT_ULTOW = 48;
    private static final int MSVCRT_SETLOCALE = 49;
    private static final int MSVCRT_CEXIT = 50;
    private static final int MSVCRT_VSNWPRINTF = 51;
    private static final int MSVCRT_WCMDLN = 52;  // data

    // ===================================================================
    // MSVCRT JAVA DISPATCH IDs (100-199, used via int 0x80 stubs)
    // These functions are implemented in Java instead of C
    // ===================================================================
    private static final int MSVCRT_J_EXIT = 100;
    private static final int MSVCRT_J_ABORT = 101;
    private static final int MSVCRT_J_CEXIT = 102;
    private static final int MSVCRT_J_AMSG_EXIT = 103;
    private static final int MSVCRT_J_TERMINATE = 104;
    private static final int MSVCRT_J_FFLUSH = 105;
    private static final int MSVCRT_J_SETUSERMATHERR = 106;
    private static final int MSVCRT_J_C_SPECIFIC_HANDLER = 107;
    private static final int MSVCRT_J_SET_APP_TYPE = 108;
    private static final int MSVCRT_J_XCPTFILTER = 109;
    private static final int MSVCRT_J_WSYSTEM = 110;
    // Batch B: Character functions
    private static final int MSVCRT_J_ISDIGIT = 111;
    private static final int MSVCRT_J_ISALPHA = 112;
    private static final int MSVCRT_J_ISSPACE = 113;
    private static final int MSVCRT_J_TOUPPER = 114;
    private static final int MSVCRT_J_TOLOWER = 115;
    private static final int MSVCRT_J_PUTCHAR = 116;
    private static final int MSVCRT_J_ATOI = 117;
    // Batch C: String functions
    private static final int MSVCRT_J_STRLEN = 118;
    private static final int MSVCRT_J_STRCMP = 119;
    private static final int MSVCRT_J_STRNCMP = 120;
    private static final int MSVCRT_J_STRCPY = 121;
    private static final int MSVCRT_J_STRNCPY = 122;
    private static final int MSVCRT_J_STRCAT = 123;
    private static final int MSVCRT_J_STRCHR = 124;
    private static final int MSVCRT_J_STRRCHR = 125;
    private static final int MSVCRT_J_PUTS = 126;
    // Batch D: Memory functions
    private static final int MSVCRT_J_MEMCPY = 127;
    private static final int MSVCRT_J_MEMSET = 128;
    private static final int MSVCRT_J_MEMCMP = 129;
    private static final int MSVCRT_J_MEMMOVE = 130;
    // Batch E: Heap functions
    private static final int MSVCRT_J_MALLOC = 131;
    private static final int MSVCRT_J_FREE = 132;
    private static final int MSVCRT_J_CALLOC = 133;
    private static final int MSVCRT_J_REALLOC = 134;
    // Batch F: Wide-string functions
    private static final int MSVCRT_J_WCSNICMP = 135;
    private static final int MSVCRT_J_WCSCAT_S = 136;
    private static final int MSVCRT_J_WCSCPY_S = 137;
    private static final int MSVCRT_J_ULTOW = 138;
    // Batch G: Misc
    private static final int MSVCRT_J_SETLOCALE = 139;
    private static final int MSVCRT_J_IOBFUNC = 140;
    private static final int MSVCRT_J_STRTOL = 141;

    private static int identifyMsvcrtFunc(char c0, char c1, char c2, char c3, long execMem, int nameOffset) {
        if (c0 == 'p') {
            if (c1 == 'r' && c2 == 'i' && c3 == 'n') return MSVCRT_PRINTF;
            if (c1 == 'u' && c2 == 't' && c3 == 's') {
                char c4 = (char)(Native.readMemoryLong(execMem + nameOffset + 4) & 0xFF);
                if (c4 == 0) return MSVCRT_PUTS;
            }
            if (c1 == 'u' && c2 == 't' && c3 == 'c') return MSVCRT_PUTCHAR;
        }
        if (c0 == 's') {
            if (c1 == 'p' && c2 == 'r' && c3 == 'i') return MSVCRT_SPRINTF;
            if (c1 == 'n' && c2 == 'p' && c3 == 'r') return MSVCRT_SNPRINTF;
            if (c1 == 't' && c2 == 'r') {
                if (c3 == 'l') return MSVCRT_STRLEN;
                if (c3 == 'c') {
                    char c4 = (char)(Native.readMemoryLong(execMem + nameOffset + 4) & 0xFF);
                    if (c4 == 'p') return MSVCRT_STRCPY;
                    if (c4 == 'm') return MSVCRT_STRCMP;
                    if (c4 == 'a') return MSVCRT_STRCAT;
                    if (c4 == 'h') return MSVCRT_STRCHR;
                }
                if (c3 == 'n') {
                    char c4 = (char)(Native.readMemoryLong(execMem + nameOffset + 4) & 0xFF);
                    if (c4 == 'c') {
                        char c5 = (char)(Native.readMemoryLong(execMem + nameOffset + 5) & 0xFF);
                        if (c5 == 'p') return MSVCRT_STRNCPY;
                        if (c5 == 'm') return MSVCRT_STRNCMP;
                    }
                }
                if (c3 == 'r') return MSVCRT_STRRCHR;
                if (c3 == 't') return MSVCRT_STRTOL;
            }
        }
        if (c0 == 'm') {
            if (c1 == 'a' && c2 == 'l' && c3 == 'l') return MSVCRT_MALLOC;
            if (c1 == 'e' && c2 == 'm') {
                if (c3 == 'c') {
                    char c4 = (char)(Native.readMemoryLong(execMem + nameOffset + 4) & 0xFF);
                    if (c4 == 'p') return MSVCRT_MEMCPY;
                    if (c4 == 'm') return MSVCRT_MEMCMP;
                }
                if (c3 == 's') return MSVCRT_MEMSET;
                if (c3 == 'm') return MSVCRT_MEMMOVE;
            }
        }
        if (c0 == 'f') {
            if (c1 == 'r' && c2 == 'e' && c3 == 'e') return MSVCRT_FREE;
            if (c1 == 'p' && c2 == 'r' && c3 == 'i') return MSVCRT_FPRINTF;
            if (c1 == 'f' && c2 == 'l' && c3 == 'u') return MSVCRT_FFLUSH;
        }
        if (c0 == 'c') {
            if (c1 == 'a' && c2 == 'l' && c3 == 'l') return MSVCRT_CALLOC;
        }
        if (c0 == 'e') {
            if (c1 == 'x' && c2 == 'i' && c3 == 't') return MSVCRT_EXIT;
        }
        if (c0 == 'a') {
            if (c1 == 'b' && c2 == 'o' && c3 == 'r') return MSVCRT_ABORT;
            if (c1 == 't' && c2 == 'o' && c3 == 'i') return MSVCRT_ATOI;
        }
        if (c0 == 'r') {
            if (c1 == 'e' && c2 == 'a' && c3 == 'l') return MSVCRT_REALLOC;
        }
        if (c0 == 'i') {
            if (c1 == 's' && c2 == 'd') return MSVCRT_ISDIGIT;
            if (c1 == 's' && c2 == 'a') return MSVCRT_ISALPHA;
            if (c1 == 's' && c2 == 's') return MSVCRT_ISSPACE;
        }
        if (c0 == 't' && c1 == 'o') {
            if (c2 == 'u') return MSVCRT_TOUPPER;
            if (c2 == 'l') return MSVCRT_TOLOWER_FUNC;
        }
        // CRT internals
        if (c0 == '_') {
            char x1 = (char)(Native.readMemoryLong(execMem + nameOffset + 1) & 0xFF);
            if (x1 == '_') {
                char x2 = (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF);
                // __acrt_iob_func
                if (x2 == 'a') return MSVCRT_IOBFUNC;
                // __setusermatherr / __set_app_type
                if (x2 == 's' && (char)(Native.readMemoryLong(execMem + nameOffset + 3) & 0xFF) == 'e') {
                    // Both start with "__set" - disambiguate by c5: '_'=__set_app_type, 'u'=__setusermatherr
                    char x5 = (char)(Native.readMemoryLong(execMem + nameOffset + 5) & 0xFF);
                    if (x5 == '_') return MSVCRT_SET_APP_TYPE;       // __set_app_type
                    if (x5 == 'u') return MSVCRT_SETUSERMATHERR;     // __setusermatherr
                }
                // __C_specific_handler
                if (x2 == 'C') return MSVCRT_C_SPECIFIC_HANDLER;
                // __wgetmainargs
                if (x2 == 'w') return MSVCRT_WGETMAINARGS;
            }
            // _initterm / _initterm_e
            if (x1 == 'i' && (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF) == 'n') {
                return MSVCRT_INITTERM;
            }
            // _snprintf
            if (x1 == 's' && (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF) == 'n') {
                return MSVCRT_SNPRINTF;
            }
            // _exit
            if (x1 == 'e' && (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF) == 'x') {
                return MSVCRT_EXIT;
            }
            // _fmode (DATA)
            if (x1 == 'f' && (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF) == 'm') {
                return MSVCRT_FMODE;
            }
            // _commode (DATA) / _cexit
            if (x1 == 'c') {
                char x2 = (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF);
                if (x2 == 'o') return MSVCRT_COMMODE;
                if (x2 == 'e') return MSVCRT_CEXIT;
            }
            // _amsg_exit
            if (x1 == 'a' && (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF) == 'm') {
                return MSVCRT_AMSG_EXIT;
            }
            // _XcptFilter
            if (x1 == 'X') return MSVCRT_XCPTFILTER;
            // _wcsnicmp / _wsystem
            if (x1 == 'w') {
                char x2 = (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF);
                if (x2 == 'c') return MSVCRT_WCSNICMP;
                if (x2 == 's') return MSVCRT_WSYSTEM;
            }
            // _ultow
            if (x1 == 'u' && (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF) == 'l') {
                return MSVCRT_ULTOW;
            }
            // _vsnwprintf
            if (x1 == 'v' && (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF) == 's') {
                return MSVCRT_VSNWPRINTF;
            }
            // _wcmdln (DATA)
            if (x1 == 'w' && (char)(Native.readMemoryLong(execMem + nameOffset + 2) & 0xFF) == 'c') {
                char x3 = (char)(Native.readMemoryLong(execMem + nameOffset + 3) & 0xFF);
                if (x3 == 'm') return MSVCRT_WCMDLN;
            }
        }
        // ?terminate@@YAXXZ
        if (c0 == '?') {
            return MSVCRT_TERMINATE;
        }
        // wcscat_s / wcscpy_s
        if (c0 == 'w' && c1 == 'c' && c2 == 's') {
            char x3 = (char)(Native.readMemoryLong(execMem + nameOffset + 3) & 0xFF);
            if (x3 == 'c') {
                char x4 = (char)(Native.readMemoryLong(execMem + nameOffset + 4) & 0xFF);
                if (x4 == 'a') return MSVCRT_WCSCAT_S;
                if (x4 == 'p') return MSVCRT_WCSCPY_S;
            }
        }
        // setlocale
        if (c0 == 's' && c1 == 'e' && c2 == 't' && c3 == 'l') {
            return MSVCRT_SETLOCALE;
        }
        // strncmp (also check 4-char prefix)
        if (c0 == 's' && c1 == 't' && c2 == 'r' && c3 == 'n') {
            char c4 = (char)(Native.readMemoryLong(execMem + nameOffset + 4) & 0xFF);
            if (c4 == 'c') {
                char c5 = (char)(Native.readMemoryLong(execMem + nameOffset + 5) & 0xFF);
                if (c5 == 'm') return MSVCRT_STRNCMP;
                if (c5 == 'p') return MSVCRT_STRNCPY;
            }
        }
        // memcpy
        if (c0 == 'm' && c1 == 'e' && c2 == 'm' && c3 == 'c') {
            char c4 = (char)(Native.readMemoryLong(execMem + nameOffset + 4) & 0xFF);
            if (c4 == 'p') return MSVCRT_MEMCPY;
        }

        return 0; // unrecognized
    }

    // ===================================================================
    // STUB CREATION
    // ===================================================================

    // Create int 0x80 stub for kernel32 functions handled in Java
    private static long createEmulatedFunc(int funcId) {
        if (emulatedFuncAddrs == 0) {
            emulatedFuncAddrs = Memory.heapAlloc(64 * MAX_EMULATED_FUNCS);
        }

        long stubAddr = emulatedFuncAddrs + nextEmulatedFuncSlot * 64;
        nextEmulatedFuncSlot = nextEmulatedFuncSlot + 1;
        if (nextEmulatedFuncSlot >= MAX_EMULATED_FUNCS) {
            Console.writeString("WARNING: Emulated function stub overflow, wrapping!\n");
            nextEmulatedFuncSlot = 0; // wrap (should not happen)
        }

        // mov rax, imm64 (0x48 0xB8 + 8 bytes)
        Native.writeMemory(stubAddr + 0, (char)0x48);
        Native.writeMemory(stubAddr + 1, (char)0xB8);
        Native.writeMemoryLong(stubAddr + 2, (long)funcId);
        // int 0x80 (0xCD 0x80)
        Native.writeMemory(stubAddr + 10, (char)0xCD);
        Native.writeMemory(stubAddr + 11, (char)0x80);
        // ret (0xC3)
        Native.writeMemory(stubAddr + 12, (char)0xC3);

        return stubAddr;
    }

    // Create direct call stub for C-implemented functions (msvcrt)
    // PE programs use Windows x64 calling convention: RCX, RDX, R8, R9
    // Our C runtime uses System V (Linux) convention: RDI, RSI, RDX, RCX, R8, R9
    // CRITICAL: RDI and RSI are callee-saved in Windows x64 ABI, so we must
    // save them before overwriting for SysV translation, then restore after.
    // Uses call+ret (not jmp) so we can restore before returning to PE caller.
    private static long createDirectCallStub(long targetAddr) {
        if (emulatedFuncAddrs == 0) {
            emulatedFuncAddrs = Memory.heapAlloc(64 * MAX_EMULATED_FUNCS);
        }

        long stubAddr = emulatedFuncAddrs + nextEmulatedFuncSlot * 64;
        nextEmulatedFuncSlot = nextEmulatedFuncSlot + 1;
        if (nextEmulatedFuncSlot >= MAX_EMULATED_FUNCS) {
            Console.writeString("WARNING: Direct call stub overflow, wrapping!\n");
            nextEmulatedFuncSlot = 0;
        }

        int off = 0;

        // Save callee-saved registers (Win64: RDI, RSI are nonvolatile)
        // push rdi  (57)
        Native.writeMemory(stubAddr + off, (char)0x57); off = off + 1;
        // push rsi  (56)
        Native.writeMemory(stubAddr + off, (char)0x56); off = off + 1;
        // sub rsp, 8 — align stack to 16 bytes before call
        // At entry: RSP = 8 mod 16 (caller did call). After 2 pushes: 8 mod 16.
        // sub 8 → 0 mod 16. Then call pushes 8 → 8 mod 16 at callee entry. ✓
        Native.writeMemory(stubAddr + off, (char)0x48); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0x83); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0xEC); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0x08); off = off + 1;

        // Translate Win64 → SysV calling convention
        // mov r10, r9    (4D 89 CA)  — save Windows arg4
        Native.writeMemory(stubAddr + off, (char)0x4D); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0x89); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0xCA); off = off + 1;
        // mov r11, r8    (4D 89 C3)  — save Windows arg3
        Native.writeMemory(stubAddr + off, (char)0x4D); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0x89); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0xC3); off = off + 1;
        // mov rdi, rcx   (48 89 CF)  — arg1: RCX -> RDI
        Native.writeMemory(stubAddr + off, (char)0x48); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0x89); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0xCF); off = off + 1;
        // mov rsi, rdx   (48 89 D6)  — arg2: RDX -> RSI
        Native.writeMemory(stubAddr + off, (char)0x48); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0x89); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0xD6); off = off + 1;
        // mov rdx, r11   (4C 89 DA)  — arg3: R8 -> RDX
        Native.writeMemory(stubAddr + off, (char)0x4C); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0x89); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0xDA); off = off + 1;
        // mov rcx, r10   (4C 89 D1)  — arg4: R9 -> RCX
        Native.writeMemory(stubAddr + off, (char)0x4C); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0x89); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0xD1); off = off + 1;

        // mov rax, imm64 (48 B8 + 8 bytes)
        Native.writeMemory(stubAddr + off, (char)0x48); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0xB8); off = off + 1;
        Native.writeMemoryLong(stubAddr + off, targetAddr);
        off = off + 8;

        // call rax  (FF D0)
        Native.writeMemory(stubAddr + off, (char)0xFF); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0xD0); off = off + 1;

        // Restore callee-saved registers and return to PE caller
        // add rsp, 8  (48 83 C4 08)
        Native.writeMemory(stubAddr + off, (char)0x48); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0x83); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0xC4); off = off + 1;
        Native.writeMemory(stubAddr + off, (char)0x08); off = off + 1;
        // pop rsi  (5E)
        Native.writeMemory(stubAddr + off, (char)0x5E); off = off + 1;
        // pop rdi  (5F)
        Native.writeMemory(stubAddr + off, (char)0x5F); off = off + 1;
        // ret      (C3)
        Native.writeMemory(stubAddr + off, (char)0xC3); off = off + 1;

        return stubAddr;
    }

    // ===================================================================
    // MSVCRT JAVA FUNCTION DISPATCH (IDs 100-199)
    // ===================================================================

    // Static state for setlocale and iob_func
    private static long localeStringAddr = 0;
    private static long fakeIobAddr = 0;

    public static long handleMsvcrtCall(int funcId, long arg1, long arg2, long arg3, long arg4) {
        // Batch A: Trivial stubs
        if (funcId == MSVCRT_J_EXIT) {
            Threading.terminateCurrentThread();
            return 0;
        }
        if (funcId == MSVCRT_J_ABORT) {
            Console.writeString("abort() called\n");
            Threading.terminateCurrentThread();
            return 0;
        }
        if (funcId == MSVCRT_J_CEXIT) {
            return 0;
        }
        if (funcId == MSVCRT_J_AMSG_EXIT) {
            Threading.terminateCurrentThread();
            return 0;
        }
        if (funcId == MSVCRT_J_TERMINATE) {
            Threading.terminateCurrentThread();
            return 0;
        }
        if (funcId == MSVCRT_J_FFLUSH) {
            return 0;
        }
        if (funcId == MSVCRT_J_SETUSERMATHERR) {
            return 0;
        }
        if (funcId == MSVCRT_J_C_SPECIFIC_HANDLER) {
            return 0;
        }
        if (funcId == MSVCRT_J_SET_APP_TYPE) {
            return 0;
        }
        if (funcId == MSVCRT_J_XCPTFILTER) {
            return 0;
        }
        if (funcId == MSVCRT_J_WSYSTEM) {
            return -1;
        }

        // Batch B: Character functions
        if (funcId == MSVCRT_J_ISDIGIT) {
            return (arg1 >= 48 && arg1 <= 57) ? 1 : 0;
        }
        if (funcId == MSVCRT_J_ISALPHA) {
            if (arg1 >= 65 && arg1 <= 90) return 1;
            if (arg1 >= 97 && arg1 <= 122) return 1;
            return 0;
        }
        if (funcId == MSVCRT_J_ISSPACE) {
            if (arg1 == 32) return 1;
            if (arg1 == 9) return 1;
            if (arg1 == 10) return 1;
            if (arg1 == 13) return 1;
            if (arg1 == 12) return 1;
            if (arg1 == 11) return 1;
            return 0;
        }
        if (funcId == MSVCRT_J_TOUPPER) {
            if (arg1 >= 97 && arg1 <= 122) return arg1 - 32;
            return arg1;
        }
        if (funcId == MSVCRT_J_TOLOWER) {
            if (arg1 >= 65 && arg1 <= 90) return arg1 + 32;
            return arg1;
        }
        if (funcId == MSVCRT_J_PUTCHAR) {
            Console.writeChar((char)(arg1 & 0xFF));
            return arg1;
        }
        if (funcId == MSVCRT_J_ATOI) {
            return msvcrt_java_atoi(arg1);
        }

        // Batch C: String functions
        if (funcId == MSVCRT_J_STRLEN) {
            return msvcrt_java_strlen(arg1);
        }
        if (funcId == MSVCRT_J_STRCMP) {
            return msvcrt_java_strcmp(arg1, arg2);
        }
        if (funcId == MSVCRT_J_STRNCMP) {
            return msvcrt_java_strncmp(arg1, arg2, arg3);
        }
        if (funcId == MSVCRT_J_STRCPY) {
            return msvcrt_java_strcpy(arg1, arg2);
        }
        if (funcId == MSVCRT_J_STRNCPY) {
            return msvcrt_java_strncpy(arg1, arg2, arg3);
        }
        if (funcId == MSVCRT_J_STRCAT) {
            return msvcrt_java_strcat(arg1, arg2);
        }
        if (funcId == MSVCRT_J_STRCHR) {
            return msvcrt_java_strchr(arg1, arg2);
        }
        if (funcId == MSVCRT_J_STRRCHR) {
            return msvcrt_java_strrchr(arg1, arg2);
        }
        if (funcId == MSVCRT_J_PUTS) {
            return msvcrt_java_puts(arg1);
        }

        // Batch D: Memory functions
        if (funcId == MSVCRT_J_MEMCPY) {
            Native.memcpy(arg1, arg2, arg3);
            return arg1;
        }
        if (funcId == MSVCRT_J_MEMSET) {
            Native.memset(arg1, (int)(arg2 & 0xFF), arg3);
            return arg1;
        }
        if (funcId == MSVCRT_J_MEMCMP) {
            return msvcrt_java_memcmp(arg1, arg2, arg3);
        }
        if (funcId == MSVCRT_J_MEMMOVE) {
            return msvcrt_java_memmove(arg1, arg2, arg3);
        }

        // Batch E: Heap functions
        if (funcId == MSVCRT_J_MALLOC) {
            return Memory.heapAlloc(arg1);
        }
        if (funcId == MSVCRT_J_FREE) {
            if (arg1 != 0) {
                Memory.heapFree(arg1);
            }
            return 0;
        }
        if (funcId == MSVCRT_J_CALLOC) {
            long total = arg1 * arg2;
            long ptr = Memory.heapAlloc(total);
            if (ptr != 0) {
                Native.memset(ptr, 0, total);
            }
            return ptr;
        }
        if (funcId == MSVCRT_J_REALLOC) {
            return msvcrt_java_realloc(arg1, arg2);
        }

        // Batch F: Wide-string functions
        if (funcId == MSVCRT_J_WCSNICMP) {
            return msvcrt_java_wcsnicmp(arg1, arg2, arg3);
        }
        if (funcId == MSVCRT_J_WCSCAT_S) {
            return msvcrt_java_wcscat_s(arg1, arg2, arg3);
        }
        if (funcId == MSVCRT_J_WCSCPY_S) {
            return msvcrt_java_wcscpy_s(arg1, arg2, arg3);
        }
        if (funcId == MSVCRT_J_ULTOW) {
            return msvcrt_java_ultow(arg1, arg2, (int)arg3);
        }

        // Batch G: Misc
        if (funcId == MSVCRT_J_SETLOCALE) {
            return msvcrt_java_setlocale();
        }
        if (funcId == MSVCRT_J_IOBFUNC) {
            return msvcrt_java_iob_func((int)arg1);
        }
        if (funcId == MSVCRT_J_STRTOL) {
            return msvcrt_java_strtol(arg1, arg2, (int)arg3);
        }

        return 0;
    }

    // ===================================================================
    // MSVCRT JAVA IMPLEMENTATIONS
    // ===================================================================

    private static long msvcrt_java_strlen(long s) {
        long len = 0;
        while ((Native.readMemoryLong(s + len) & 0xFF) != 0) {
            len = len + 1;
        }
        return len;
    }

    private static long msvcrt_java_strcmp(long a, long b) {
        long i = 0;
        while (true) {
            int ca = (int)(Native.readMemoryLong(a + i) & 0xFF);
            int cb = (int)(Native.readMemoryLong(b + i) & 0xFF);
            if (ca != cb) return (long)(ca - cb);
            if (ca == 0) return 0;
            i = i + 1;
        }
    }

    private static long msvcrt_java_strncmp(long a, long b, long n) {
        long i = 0;
        while (i < n) {
            int ca = (int)(Native.readMemoryLong(a + i) & 0xFF);
            int cb = (int)(Native.readMemoryLong(b + i) & 0xFF);
            if (ca != cb) return (long)(ca - cb);
            if (ca == 0) return 0;
            i = i + 1;
        }
        return 0;
    }

    private static long msvcrt_java_strcpy(long dst, long src) {
        long i = 0;
        while (true) {
            int c = (int)(Native.readMemoryLong(src + i) & 0xFF);
            Native.writeMemory(dst + i, (char)c);
            if (c == 0) break;
            i = i + 1;
        }
        return dst;
    }

    private static long msvcrt_java_strncpy(long dst, long src, long n) {
        long i = 0;
        while (i < n) {
            int c = (int)(Native.readMemoryLong(src + i) & 0xFF);
            Native.writeMemory(dst + i, (char)c);
            if (c == 0) {
                i = i + 1;
                while (i < n) {
                    Native.writeMemory(dst + i, (char)0);
                    i = i + 1;
                }
                return dst;
            }
            i = i + 1;
        }
        return dst;
    }

    private static long msvcrt_java_strcat(long dst, long src) {
        long d = dst;
        while ((Native.readMemoryLong(d) & 0xFF) != 0) {
            d = d + 1;
        }
        long i = 0;
        while (true) {
            int c = (int)(Native.readMemoryLong(src + i) & 0xFF);
            Native.writeMemory(d + i, (char)c);
            if (c == 0) break;
            i = i + 1;
        }
        return dst;
    }

    private static long msvcrt_java_strchr(long s, long c) {
        int target = (int)(c & 0xFF);
        long i = 0;
        while (true) {
            int ch = (int)(Native.readMemoryLong(s + i) & 0xFF);
            if (ch == target) return s + i;
            if (ch == 0) return 0;
            i = i + 1;
        }
    }

    private static long msvcrt_java_strrchr(long s, long c) {
        int target = (int)(c & 0xFF);
        long last = 0;
        long i = 0;
        while (true) {
            int ch = (int)(Native.readMemoryLong(s + i) & 0xFF);
            if (ch == target) last = s + i;
            if (ch == 0) break;
            i = i + 1;
        }
        if (target == 0) return s + i;
        return last;
    }

    private static long msvcrt_java_puts(long s) {
        if (s == 0) return -1;
        long i = 0;
        while (true) {
            int c = (int)(Native.readMemoryLong(s + i) & 0xFF);
            if (c == 0) break;
            Console.writeChar((char)c);
            i = i + 1;
        }
        Console.writeChar('\n');
        return 0;
    }

    private static long msvcrt_java_memcmp(long a, long b, long n) {
        long i = 0;
        while (i < n) {
            int ba = (int)(Native.readMemoryLong(a + i) & 0xFF);
            int bb = (int)(Native.readMemoryLong(b + i) & 0xFF);
            if (ba != bb) return (long)(ba - bb);
            i = i + 1;
        }
        return 0;
    }

    private static long msvcrt_java_memmove(long dst, long src, long n) {
        if (dst < src) {
            Native.memcpy(dst, src, n);
        } else if (dst > src) {
            long i = n;
            while (i > 0) {
                i = i - 1;
                int b = (int)(Native.readMemoryLong(src + i) & 0xFF);
                Native.writeMemory(dst + i, (char)b);
            }
        }
        return dst;
    }

    private static long msvcrt_java_realloc(long ptr, long size) {
        if (ptr == 0) return Memory.heapAlloc(size);
        if (size == 0) {
            Memory.heapFree(ptr);
            return 0;
        }
        long np = Memory.heapAlloc(size);
        if (np != 0) {
            long oldSize = Memory.getAllocatedBlockSize(ptr);
            long copySize = oldSize;
            if (size < copySize) copySize = size;
            Native.memcpy(np, ptr, copySize);
            Memory.heapFree(ptr);
        }
        return np;
    }

    private static long msvcrt_java_atoi(long s) {
        return msvcrt_java_strtol(s, 0, 10);
    }

    private static long msvcrt_java_strtol(long s, long endptrAddr, int base) {
        long result = 0;
        int neg = 0;
        long pos = s;
        // Skip whitespace
        int ch = (int)(Native.readMemoryLong(pos) & 0xFF);
        while (ch == 32 || ch == 9) {
            pos = pos + 1;
            ch = (int)(Native.readMemoryLong(pos) & 0xFF);
        }
        // Sign
        if (ch == 45) { neg = 1; pos = pos + 1; ch = (int)(Native.readMemoryLong(pos) & 0xFF); }
        else if (ch == 43) { pos = pos + 1; ch = (int)(Native.readMemoryLong(pos) & 0xFF); }
        // Auto-detect base
        if (base == 0) {
            if (ch == 48) {
                int next = (int)(Native.readMemoryLong(pos + 1) & 0xFF);
                if (next == 120 || next == 88) { base = 16; pos = pos + 2; }
                else { base = 8; pos = pos + 1; }
            } else {
                base = 10;
            }
        }
        if (base == 16 && ch == 48) {
            int next = (int)(Native.readMemoryLong(pos + 1) & 0xFF);
            if (next == 120 || next == 88) { pos = pos + 2; }
        }
        // Parse digits
        ch = (int)(Native.readMemoryLong(pos) & 0xFF);
        while (ch != 0) {
            int digit = -1;
            if (ch >= 48 && ch <= 57) digit = ch - 48;
            else if (ch >= 97 && ch <= 102) digit = ch - 97 + 10;
            else if (ch >= 65 && ch <= 70) digit = ch - 65 + 10;
            if (digit < 0 || digit >= base) break;
            result = result * (long)base + (long)digit;
            pos = pos + 1;
            ch = (int)(Native.readMemoryLong(pos) & 0xFF);
        }
        // Write endptr
        if (endptrAddr != 0) {
            Native.writeMemoryLong(endptrAddr, pos);
        }
        if (neg != 0) return -result;
        return result;
    }

    // Wide-string functions
    private static int readWChar(long addr) {
        int lo = (int)(Native.readMemoryLong(addr) & 0xFF);
        int hi = (int)(Native.readMemoryLong(addr + 1) & 0xFF);
        return lo | (hi << 8);
    }

    private static void writeWChar(long addr, int val) {
        Native.writeMemory(addr, (char)(val & 0xFF));
        Native.writeMemory(addr + 1, (char)((val >> 8) & 0xFF));
    }

    private static long msvcrt_java_wcsnicmp(long s1, long s2, long n) {
        long i = 0;
        while (i < n) {
            int c1 = readWChar(s1 + i * 2);
            int c2 = readWChar(s2 + i * 2);
            if (c1 >= 65 && c1 <= 90) c1 = c1 + 32;
            if (c2 >= 65 && c2 <= 90) c2 = c2 + 32;
            if (c1 != c2) return (long)(c1 - c2);
            if (c1 == 0) return 0;
            i = i + 1;
        }
        return 0;
    }

    private static long msvcrt_java_wcscat_s(long dst, long dstsize, long src) {
        if (dst == 0 || src == 0 || dstsize == 0) return 22;
        long dlen = 0;
        while (dlen < dstsize && readWChar(dst + dlen * 2) != 0) {
            dlen = dlen + 1;
        }
        if (dlen >= dstsize) return 22;
        long i = 0;
        while (readWChar(src + i * 2) != 0 && dlen + i + 1 < dstsize) {
            writeWChar(dst + (dlen + i) * 2, readWChar(src + i * 2));
            i = i + 1;
        }
        writeWChar(dst + (dlen + i) * 2, 0);
        return 0;
    }

    private static long msvcrt_java_wcscpy_s(long dst, long dstsize, long src) {
        if (dst == 0 || dstsize == 0) return 22;
        if (src == 0) {
            writeWChar(dst, 0);
            return 22;
        }
        long i = 0;
        while (readWChar(src + i * 2) != 0 && i + 1 < dstsize) {
            writeWChar(dst + i * 2, readWChar(src + i * 2));
            i = i + 1;
        }
        writeWChar(dst + i * 2, 0);
        return 0;
    }

    private static long msvcrt_java_ultow(long val, long buf, int radix) {
        if (buf == 0 || radix < 2 || radix > 36) return 0;
        // Use a temporary array for digit reversal
        int len = 0;
        long tmp = val & 0xFFFFFFFFL;
        int[] digits = new int[24];
        if (tmp == 0) {
            digits[len] = 48;
            len = len + 1;
        } else {
            while (tmp > 0 && len < 23) {
                int digit = (int)(tmp % (long)radix);
                if (digit < 10) digits[len] = 48 + digit;
                else digits[len] = 97 + digit - 10;
                len = len + 1;
                tmp = tmp / (long)radix;
            }
        }
        // Reverse into buf
        int i = 0;
        while (i < len) {
            writeWChar(buf + (long)i * 2, digits[len - 1 - i]);
            i = i + 1;
        }
        writeWChar(buf + (long)len * 2, 0);
        return buf;
    }

    private static long msvcrt_java_setlocale() {
        if (localeStringAddr == 0) {
            localeStringAddr = Memory.heapAlloc(4);
            if (localeStringAddr != 0) {
                Native.writeMemory(localeStringAddr, 'C');
                Native.writeMemory(localeStringAddr + 1, (char)0);
            }
        }
        return localeStringAddr;
    }

    private static long msvcrt_java_iob_func(int index) {
        if (fakeIobAddr == 0) {
            // Allocate 3 fake FILE handles (8 bytes each)
            fakeIobAddr = Memory.heapAlloc(24);
            if (fakeIobAddr != 0) {
                Native.writeMemoryLong(fakeIobAddr, 0);
                Native.writeMemoryLong(fakeIobAddr + 8, 1);
                Native.writeMemoryLong(fakeIobAddr + 16, 2);
            }
        }
        if (index == 0) return fakeIobAddr;
        if (index == 1) return fakeIobAddr + 8;
        if (index == 2) return fakeIobAddr + 16;
        return 0;
    }

    // ===================================================================
    // KERNEL32 FUNCTION DISPATCH
    // ===================================================================

    public static long handleKernel32Call(int funcId, long arg1, long arg2, long arg3, long arg4) {
        long arg5 = Syscalls.getSyscallArg5();
        long arg6 = Syscalls.getSyscallArg6();
        long arg7 = Syscalls.getSyscallArg7();

        if (funcId == FUNC_GET_STD_HANDLE) {
            return kernel32_GetStdHandle((int)arg1);
        } else if (funcId == FUNC_WRITE_FILE) {
            return kernel32_WriteFile(arg1, arg2, arg3, arg4, arg5);
        } else if (funcId == FUNC_EXIT_PROCESS) {
            kernel32_ExitProcess((int)arg1);
            return 0;
        } else if (funcId == FUNC_GET_LAST_ERROR) {
            return kernel32_GetLastError();
        } else if (funcId == FUNC_SET_LAST_ERROR) {
            kernel32_SetLastError(arg1);
            return 0;
        } else if (funcId == FUNC_GET_PROCESS_HEAP) {
            return 0x10001L;
        } else if (funcId == FUNC_HEAP_ALLOC) {
            return kernel32_HeapAlloc(arg1, arg2, arg3);
        } else if (funcId == FUNC_HEAP_FREE) {
            return kernel32_HeapFree(arg1, arg2, arg3);
        } else if (funcId == FUNC_LOCAL_ALLOC) {
            return kernel32_LocalAlloc((int)arg1, arg2);
        } else if (funcId == FUNC_LOCAL_FREE) {
            return kernel32_LocalFree(arg1);
        } else if (funcId == FUNC_READ_FILE) {
            return kernel32_ReadFile(arg1, arg2, arg3, arg4, arg5);
        } else if (funcId == FUNC_CLOSE_HANDLE) {
            return kernel32_CloseHandle(arg1);
        } else if (funcId == FUNC_GET_COMMAND_LINE_A) {
            return kernel32_GetCommandLineA();
        } else if (funcId == FUNC_GET_ENVIRONMENT_STRINGS_A) {
            return kernel32_GetEnvironmentStringsA();
        } else if (funcId == FUNC_FREE_ENVIRONMENT_STRINGS_A) {
            return 1; // TRUE
        } else if (funcId == FUNC_GET_CONSOLE_MODE) {
            return kernel32_GetConsoleMode(arg1, arg2);
        } else if (funcId == FUNC_CREATE_FILE_A) {
            return kernel32_CreateFileA(arg1, arg2, arg3, arg4, arg5);
        } else if (funcId == FUNC_CREATE_THREAD) {
            return kernel32_CreateThread(arg1, arg2, arg3, arg4);
        } else if (funcId == FUNC_SLEEP) {
            kernel32_Sleep((int)arg1);
            return 0;
        } else if (funcId == FUNC_WAIT_FOR_SINGLE_OBJECT) {
            return kernel32_WaitForSingleObject(arg1, (int)arg2);
        } else if (funcId == FUNC_GET_MODULE_HANDLE_A) {
            return peLoadedBase; // Return PE base
        } else if (funcId == FUNC_GET_CURRENT_PROCESS_ID) {
            return (long)Threading.getCurrentThreadId();
        } else if (funcId == FUNC_GET_CURRENT_THREAD_ID) {
            return (long)Threading.getCurrentThreadId();
        } else if (funcId == FUNC_IS_DEBUGGER_PRESENT) {
            return 0;
        } else if (funcId == FUNC_GET_TICK_COUNT) {
            return (long)Interrupts.getTickCount() * 10;
        } else if (funcId == FUNC_VIRTUAL_ALLOC) {
            return kernel32_VirtualAlloc(arg1, arg2, arg3, arg4);
        } else if (funcId == FUNC_VIRTUAL_FREE) {
            return kernel32_VirtualFree(arg1, arg2, arg3);
        } else if (funcId == FUNC_QUERY_PERFORMANCE_COUNTER) {
            return kernel32_QueryPerformanceCounter(arg1);
        } else if (funcId == FUNC_QUERY_PERFORMANCE_FREQUENCY) {
            return kernel32_QueryPerformanceFrequency(arg1);
        } else if (funcId == FUNC_FLUSH_FILE_BUFFERS) {
            return 1; // TRUE
        } else if (funcId == FUNC_SET_FILE_POINTER) {
            return 0; // success
        } else if (funcId == FUNC_GET_FILE_SIZE) {
            return kernel32_GetFileSize(arg1);
        } else if (funcId == FUNC_GET_FILE_TYPE) {
            return 2; // FILE_TYPE_CHAR
        } else if (funcId == FUNC_SET_HANDLE_COUNT) {
            return (long)arg1;
        } else if (funcId == FUNC_GET_STARTUP_INFO_A) {
            kernel32_GetStartupInfoA(arg1);
            return 0;
        } else if (funcId == FUNC_GET_MODULE_FILE_NAME_A) {
            return kernel32_GetModuleFileNameA(arg1, arg2, arg3);
        } else if (funcId == FUNC_MULTI_BYTE_TO_WIDE_CHAR) {
            return kernel32_MultiByteToWideChar(arg1, arg2, arg3, arg4);
        } else if (funcId == FUNC_WIDE_CHAR_TO_MULTI_BYTE) {
            return 0;
        } else if (funcId == FUNC_GET_STRING_TYPE_W) {
            return 0;
        } else if (funcId == FUNC_SET_CONSOLE_MODE) {
            return 1; // TRUE
        } else if (funcId == FUNC_INIT_CRIT_SECTION) {
            return 0; // no-op
        } else if (funcId == FUNC_DELETE_CRIT_SECTION) {
            return 0;
        } else if (funcId == FUNC_ENTER_CRIT_SECTION) {
            return 0;
        } else if (funcId == FUNC_LEAVE_CRIT_SECTION) {
            return 0;
        } else if (funcId == FUNC_TLS_ALLOC) {
            return 0; // TLS index 0
        } else if (funcId == FUNC_TLS_GET_VALUE) {
            return 0;
        } else if (funcId == FUNC_TLS_SET_VALUE) {
            return 1; // TRUE
        } else if (funcId == FUNC_GET_CURRENT_PROCESS) {
            return 0xFFFFFFFFFFFFFFFFL; // pseudo handle
        } else if (funcId == FUNC_UNHANDLED_EXCEPTION_FILTER) {
            return 0; // EXCEPTION_CONTINUE_SEARCH
        } else if (funcId == FUNC_WRITE_CONSOLE_W) {
            return kernel32_WriteConsoleW(arg1, arg2, arg3, arg4);
        } else if (funcId == FUNC_GET_CONSOLE_OUTPUT_CP) {
            return 437; // CP 437
        } else if (funcId == FUNC_FORMAT_MESSAGE_W) {
            return kernel32_FormatMessageW(arg1, arg2, arg3, arg4, arg5, arg6, arg7);
        } else if (funcId == FUNC_HEAP_SET_INFORMATION) {
            return 1; // TRUE
        } else if (funcId == FUNC_SET_THREAD_UI_LANGUAGE) {
            return 0;
        } else if (funcId == FUNC_TERMINATE_PROCESS) {
            Threading.terminateCurrentThread();
            return 0;
        } else if (funcId == FUNC_GET_SYSTEM_TIME_AS_FILE_TIME) {
            // Write 8 zero bytes to the FILETIME pointer
            if (arg1 != 0) {
                writeUInt64LE_raw(arg1, 0);
            }
            return 0;
        } else if (funcId == FUNC_RTL_VIRTUAL_UNWIND) {
            return 0;
        } else if (funcId == FUNC_RTL_LOOKUP_FUNCTION_ENTRY) {
            return 0;
        } else if (funcId == FUNC_RTL_CAPTURE_CONTEXT) {
            // Zero out the CONTEXT structure (1232 bytes on x64)
            if (arg1 != 0) {
                Native.memset(arg1, 0, 1232);
            }
            return 0;
        } else if (funcId == FUNC_GET_MODULE_HANDLE_W) {
            return peLoadedBase;
        } else if (funcId == FUNC_LOAD_LIBRARY_W) {
            return kernel32_LoadLibraryW(arg1);
        } else if (funcId == FUNC_GET_PROC_ADDRESS) {
            return kernel32_GetProcAddress(arg1, arg2);
        } else if (funcId == FUNC_FREE_LIBRARY) {
            return 1; // TRUE
        } else if (funcId == FUNC_GET_SYSTEM_DIRECTORY_W) {
            return kernel32_GetSystemDirectoryW(arg1, (int)arg2);
        } else if (funcId == FUNC_GET_STARTUP_INFO_W) {
            kernel32_GetStartupInfoW(arg1);
            return 0;
        } else if (funcId == FUNC_SET_UNHANDLED_EXC_FILTER) {
            return 0; // NULL
        }
        return 0;
    }

    // ===================================================================
    // KERNEL32 IMPLEMENTATIONS
    // ===================================================================

    private static long kernel32_GetStdHandle(int nStdHandle) {
        // STD_INPUT_HANDLE = -10 = 0xFFFFFFF6
        // STD_OUTPUT_HANDLE = -11 = 0xFFFFFFF5
        // STD_ERROR_HANDLE = -12 = 0xFFFFFFF4
        if (nStdHandle == -11 || nStdHandle == 0xFFFFFFF5) {
            return WIN_HANDLE_STDOUT;
        }
        if (nStdHandle == -10 || nStdHandle == 0xFFFFFFF6) {
            return WIN_HANDLE_STDIN;
        }
        if (nStdHandle == -12 || nStdHandle == 0xFFFFFFF4) {
            return WIN_HANDLE_STDERR;
        }
        return 0xFFFFFFFFFFFFFFFFL; // INVALID_HANDLE_VALUE
    }

    private static long kernel32_WriteFile(long hFile, long lpBuffer, long nBytes, long lpWritten, long lpOverlapped) {
        if (hFile == WIN_HANDLE_STDOUT || hFile == WIN_HANDLE_STDERR) {
            int i = 0;
            while (i < nBytes) {
                char c = (char)(Native.readMemoryLong(lpBuffer + i) & 0xFF);
                Console.writeChar(c);
                i = i + 1;
            }
            if (lpWritten != 0) {
                writeUInt32LE_raw(lpWritten, (int)nBytes);
            }
            return 1; // TRUE
        }
        // File handle
        if (hFile >= 0 && hFile < MAX_WIN_HANDLES && winHandleType[(int)hFile] == HANDLE_TYPE_FILE) {
            // Writing to files not supported yet
            if (lpWritten != 0) {
                writeUInt32LE_raw(lpWritten, (int)nBytes);
            }
            return 1;
        }
        return 0; // FALSE
    }

    private static long kernel32_ReadFile(long hFile, long lpBuffer, long nBytes, long lpRead, long lpOverlapped) {
        if (hFile == WIN_HANDLE_STDIN) {
            // Line-buffered read from keyboard
            int bytesRead = 0;
            while (bytesRead < nBytes) {
                char c = 0;
                while (c == 0) {
                    c = Interrupts.ringBufferGet();
                    if (c == 0) {
                        Threading.schedule();
                    }
                }
                // Echo
                Console.writeChar(c);
                Native.writeMemory(lpBuffer + bytesRead, c);
                bytesRead = bytesRead + 1;
                if (c == '\n' || c == '\r') {
                    break; // Line-buffered
                }
            }
            if (lpRead != 0) {
                writeUInt32LE_raw(lpRead, bytesRead);
            }
            return 1; // TRUE
        }
        // File handle
        if (hFile >= 0 && hFile < MAX_WIN_HANDLES && winHandleType[(int)hFile] == HANDLE_TYPE_FILE) {
            int idx = (int)hFile;
            int offset = winHandleFileOffset[idx];
            int size = winHandleFileSize[idx];
            long buf = winHandleFileBuffer[idx];
            int toRead = (int)nBytes;
            if (offset + toRead > size) {
                toRead = size - offset;
            }
            if (toRead > 0) {
                Native.memcpy(lpBuffer, buf + offset, (long)toRead);
                winHandleFileOffset[idx] = offset + toRead;
            }
            if (lpRead != 0) {
                writeUInt32LE_raw(lpRead, toRead);
            }
            return 1; // TRUE
        }
        if (lpRead != 0) {
            writeUInt32LE_raw(lpRead, 0);
        }
        return 0; // FALSE
    }

    private static void kernel32_ExitProcess(int uExitCode) {
        Console.writeString("Process exiting with code ");
        Console.writeNumber(uExitCode);
        Console.writeString("\n");
        Threading.terminateCurrentThread();
    }

    private static long kernel32_GetLastError() {
        int tid = Threading.getCurrentThreadId();
        if (tid >= 0 && tid < 16) {
            return winLastError[tid];
        }
        return 0;
    }

    private static void kernel32_SetLastError(long error) {
        int tid = Threading.getCurrentThreadId();
        if (tid >= 0 && tid < 16) {
            winLastError[tid] = error;
        }
    }

    private static long kernel32_HeapAlloc(long hHeap, long dwFlags, long dwBytes) {
        long ptr = Memory.heapAlloc(dwBytes);
        if (ptr != 0 && (dwFlags & 0x08) != 0) {
            // HEAP_ZERO_MEMORY
            Native.memset(ptr, 0, dwBytes);
        }
        return ptr;
    }

    private static long kernel32_HeapFree(long hHeap, long dwFlags, long lpMem) {
        if (lpMem != 0) {
            Memory.heapFree(lpMem);
        }
        return 1; // TRUE
    }

    private static long kernel32_LocalAlloc(int uFlags, long uBytes) {
        long ptr = Memory.heapAlloc(uBytes);
        if (ptr != 0 && (uFlags & 0x40) != 0) {
            // LMEM_ZEROINIT
            Native.memset(ptr, 0, uBytes);
        }
        return ptr;
    }

    private static long kernel32_LocalFree(long hMem) {
        if (hMem != 0) {
            Memory.heapFree(hMem);
        }
        return 0; // success
    }

    private static long kernel32_CloseHandle(long handle) {
        if (handle >= 3 && handle < MAX_WIN_HANDLES) {
            int idx = (int)handle;
            if (winHandleType[idx] == HANDLE_TYPE_FILE) {
                if (winHandleFileBuffer[idx] != 0) {
                    Memory.heapFree(winHandleFileBuffer[idx]);
                    winHandleFileBuffer[idx] = 0;
                }
            }
            winHandleType[idx] = HANDLE_TYPE_FREE;
        }
        return 1; // TRUE
    }

    private static long kernel32_GetCommandLineA() {
        if (cmdLineAddr == 0) {
            cmdLineAddr = Memory.heapAlloc(16);
            // Write "prog\0"
            Native.writeMemory(cmdLineAddr, 'p');
            Native.writeMemory(cmdLineAddr + 1, 'r');
            Native.writeMemory(cmdLineAddr + 2, 'o');
            Native.writeMemory(cmdLineAddr + 3, 'g');
            Native.writeMemory(cmdLineAddr + 4, (char)0);
        }
        return cmdLineAddr;
    }

    private static long kernel32_GetEnvironmentStringsA() {
        if (envStringsAddr == 0) {
            envStringsAddr = Memory.heapAlloc(16);
            // Empty environment: double null terminator
            Native.writeMemory(envStringsAddr, (char)0);
            Native.writeMemory(envStringsAddr + 1, (char)0);
        }
        return envStringsAddr;
    }

    private static long kernel32_GetConsoleMode(long hConsole, long lpMode) {
        if (lpMode != 0) {
            writeUInt32LE_raw(lpMode, 0x7); // ENABLE_PROCESSED_INPUT | ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT
        }
        return 1; // TRUE
    }

    private static long kernel32_CreateFileA(long lpFileName, long dwDesiredAccess, long dwShareMode, long lpSecAttr, long dwCreationDisp) {
        // Read filename from memory
        char[] name = new char[48];
        int nameLen = 0;
        while (nameLen < 47) {
            char c = (char)(Native.readMemoryLong(lpFileName + nameLen) & 0xFF);
            if (c == 0) break;
            name[nameLen] = c;
            nameLen = nameLen + 1;
        }

        if (nameLen == 0) return 0xFFFFFFFFFFFFFFFFL;

        // Find file in filesystem
        int fileIdx = Filesystem.findFileByName(name, nameLen);
        if (fileIdx < 0) {
            kernel32_SetLastError(2); // ERROR_FILE_NOT_FOUND
            return 0xFFFFFFFFFFFFFFFFL;
        }

        // Allocate handle
        int handle = allocHandle();
        if (handle < 0) {
            kernel32_SetLastError(4); // ERROR_TOO_MANY_OPEN_FILES
            return 0xFFFFFFFFFFFFFFFFL;
        }

        // Load file content
        int fileSize = Filesystem.getFileSize(fileIdx);
        int startSector = Filesystem.getFileStartSector(fileIdx);
        long fileBuf = Memory.heapAlloc(fileSize);
        if (fileBuf == 0) {
            winHandleType[handle] = HANDLE_TYPE_FREE;
            return 0xFFFFFFFFFFFFFFFFL;
        }

        int sectors = (fileSize + 511) / 512;
        Disk.readDisk(2048 + startSector, sectors, fileBuf);

        winHandleType[handle] = HANDLE_TYPE_FILE;
        winHandleFileIdx[handle] = fileIdx;
        winHandleFileOffset[handle] = 0;
        winHandleFileSize[handle] = fileSize;
        winHandleFileBuffer[handle] = fileBuf;

        return (long)handle;
    }

    private static int allocHandle() {
        int i = 3; // Skip stdin/stdout/stderr
        while (i < MAX_WIN_HANDLES) {
            if (winHandleType[i] == HANDLE_TYPE_FREE) {
                return i;
            }
            i = i + 1;
        }
        return -1;
    }

    private static long kernel32_CreateThread(long lpThreadAttributes, long dwStackSize, long lpStartAddress, long lpParameter) {
        // Create trampoline: sets RCX=lpParameter, calls lpStartAddress, then ExitProcess
        if (trampolineBase == 0) {
            trampolineBase = Memory.heapAlloc(256);
            nextTrampolineSlot = 0;
        }

        long tramp = trampolineBase + nextTrampolineSlot * 64;
        nextTrampolineSlot = nextTrampolineSlot + 1;

        // sub rsp, 0x30 (shadow space 32 + 16 alignment)
        Native.writeMemory(tramp + 0, (char)0x48);
        Native.writeMemory(tramp + 1, (char)0x83);
        Native.writeMemory(tramp + 2, (char)0xEC);
        Native.writeMemory(tramp + 3, (char)0x30);
        // mov rcx, lpParameter (0x48 0xB9 + imm64)
        Native.writeMemory(tramp + 4, (char)0x48);
        Native.writeMemory(tramp + 5, (char)0xB9);
        Native.writeMemoryLong(tramp + 6, lpParameter);
        // mov rax, lpStartAddress (0x48 0xB8 + imm64)
        Native.writeMemory(tramp + 14, (char)0x48);
        Native.writeMemory(tramp + 15, (char)0xB8);
        Native.writeMemoryLong(tramp + 16, lpStartAddress);
        // call rax (0xFF 0xD0)
        Native.writeMemory(tramp + 24, (char)0xFF);
        Native.writeMemory(tramp + 25, (char)0xD0);
        // mov rax, FUNC_EXIT_PROCESS (for int 0x80)
        Native.writeMemory(tramp + 26, (char)0x48);
        Native.writeMemory(tramp + 27, (char)0xB8);
        Native.writeMemoryLong(tramp + 28, (long)FUNC_EXIT_PROCESS);
        // xor rcx, rcx (exit code 0)
        Native.writeMemory(tramp + 36, (char)0x48);
        Native.writeMemory(tramp + 37, (char)0x31);
        Native.writeMemory(tramp + 38, (char)0xC9);
        // int 0x80
        Native.writeMemory(tramp + 39, (char)0xCD);
        Native.writeMemory(tramp + 40, (char)0x80);

        int tid = Threading.spawnThread(tramp);
        if (tid < 0) return 0;

        // Allocate a thread handle
        int handle = allocHandle();
        if (handle >= 0) {
            winHandleType[handle] = HANDLE_TYPE_THREAD;
            winHandleThreadId[handle] = tid;
        }

        return (long)(handle >= 0 ? handle : 0);
    }

    private static void kernel32_Sleep(int dwMilliseconds) {
        long ticks = (long)dwMilliseconds / 10; // 100Hz timer, each tick ~10ms
        if (ticks < 1) ticks = 1;
        long start = Native.getTicks();
        Native.enableInterrupts();
        while (Native.getTicks() - start < ticks) {
            // Spin - timer interrupt handles scheduling
        }
        Native.disableInterrupts();
    }

    private static long kernel32_WaitForSingleObject(long hHandle, int dwMilliseconds) {
        if (hHandle >= 0 && hHandle < MAX_WIN_HANDLES) {
            int idx = (int)hHandle;
            if (winHandleType[idx] == HANDLE_TYPE_THREAD) {
                int tid = winHandleThreadId[idx];
                // Enable interrupts so the preemptive scheduler can run the target thread
                Native.enableInterrupts();
                while (!Threading.isThreadTerminated(tid)) {
                    // Spin - timer interrupt will schedule other threads
                }
                Native.disableInterrupts();
                return 0; // WAIT_OBJECT_0
            }
        }
        return 0;
    }

    private static long kernel32_VirtualAlloc(long lpAddress, long dwSize, long flAllocationType, long flProtect) {
        long ptr = Memory.heapAlloc(dwSize);
        if (ptr != 0) {
            Native.memset(ptr, 0, dwSize);
        }
        return ptr;
    }

    private static long kernel32_VirtualFree(long lpAddress, long dwSize, long dwFreeType) {
        if (lpAddress != 0) {
            Memory.heapFree(lpAddress);
        }
        return 1; // TRUE
    }

    private static long kernel32_QueryPerformanceCounter(long lpCounter) {
        if (lpCounter != 0) {
            writeUInt64LE_raw(lpCounter, Native.getTicks() * 10000);
        }
        return 1;
    }

    private static long kernel32_QueryPerformanceFrequency(long lpFrequency) {
        if (lpFrequency != 0) {
            writeUInt64LE_raw(lpFrequency, 1000000L);
        }
        return 1;
    }

    private static long kernel32_GetFileSize(long hFile) {
        if (hFile >= 0 && hFile < MAX_WIN_HANDLES && winHandleType[(int)hFile] == HANDLE_TYPE_FILE) {
            return (long)winHandleFileSize[(int)hFile];
        }
        return 0xFFFFFFFFL; // INVALID_FILE_SIZE
    }

    private static void kernel32_GetStartupInfoA(long lpStartupInfo) {
        if (lpStartupInfo != 0) {
            // STARTUPINFOA is 68 bytes on x64, just zero it
            Native.memset(lpStartupInfo, 0, 104);
            // cb = size = 104
            writeUInt32LE_raw(lpStartupInfo, 104);
        }
    }

    private static long kernel32_GetModuleFileNameA(long hModule, long lpFilename, long nSize) {
        if (lpFilename != 0 && nSize > 0) {
            // Return "prog.exe"
            char[] name = new char[9];
            name[0] = 'p'; name[1] = 'r'; name[2] = 'o'; name[3] = 'g';
            name[4] = '.'; name[5] = 'e'; name[6] = 'x'; name[7] = 'e'; name[8] = (char)0;
            int i = 0;
            while (i < 9 && i < nSize) {
                Native.writeMemory(lpFilename + i, name[i]);
                i = i + 1;
            }
            return 8;
        }
        return 0;
    }

    private static long kernel32_MultiByteToWideChar(long codePage, long dwFlags, long lpMultiByteStr, long cbMultiByte) {
        // Simple: return length or 0
        if (cbMultiByte == -1) {
            // Count chars including null
            int len = 0;
            while ((char)(Native.readMemoryLong(lpMultiByteStr + len) & 0xFF) != 0) {
                len = len + 1;
            }
            return (long)(len + 1);
        }
        return cbMultiByte;
    }

    private static long kernel32_WriteConsoleW(long hConsole, long lpBuffer, long nChars, long lpWritten) {
        // Write wide chars (2 bytes each) - output low byte to console
        int i = 0;
        while (i < nChars) {
            char c = (char)(Native.readMemoryLong(lpBuffer + i * 2) & 0xFF);
            Console.writeChar(c);
            i = i + 1;
        }
        if (lpWritten != 0) {
            writeUInt32LE_raw(lpWritten, (int)nChars);
        }
        return 1; // TRUE
    }

    private static long kernel32_LoadLibraryW(long lpFileName) {
        // Stub: return a fake module handle (non-zero = success)
        // In reality, we'd need to load the DLL, but for now just return success
        if (lpFileName == 0) return 0;
        return 0x10002L; // Fake module handle
    }

    private static long kernel32_GetProcAddress(long hModule, long lpProcName) {
        // Stub: return 0 (function not found)
        // In reality, we'd look up the function in the loaded DLL
        return 0;
    }

    private static long kernel32_GetSystemDirectoryW(long lpBuffer, int uSize) {
        // Return "C:\Windows\System32" in wide char
        // That's 20 chars + null = 21 wide chars = 42 bytes
        if (lpBuffer == 0 || uSize < 21) return 0;
        // Write "C:\Windows\System32" as wide chars
        char[] sysDir = new char[20];
        sysDir[0] = 'C'; sysDir[1] = ':'; sysDir[2] = '\\';
        sysDir[3] = 'W'; sysDir[4] = 'i'; sysDir[5] = 'n'; sysDir[6] = 'd'; sysDir[7] = 'o'; sysDir[8] = 'w'; sysDir[9] = 's';
        sysDir[10] = '\\'; sysDir[11] = 'S'; sysDir[12] = 'y'; sysDir[13] = 's'; sysDir[14] = 't'; sysDir[15] = 'e'; sysDir[16] = 'm'; sysDir[17] = '3'; sysDir[18] = '2';
        int i = 0;
        while (i < 19) {
            // Write wide char (2 bytes per char)
            Native.writeMemory(lpBuffer + i * 2, sysDir[i]);
            Native.writeMemory(lpBuffer + i * 2 + 1, (char)0);
            i = i + 1;
        }
        // Null terminator
        Native.writeMemory(lpBuffer + i * 2, (char)0);
        Native.writeMemory(lpBuffer + i * 2 + 1, (char)0);
        return (long)i; // Return length (excluding null)
    }

    private static void kernel32_GetStartupInfoW(long lpStartupInfo) {
        if (lpStartupInfo != 0) {
            // STARTUPINFOW is 104 bytes on x64, just zero it
            Native.memset(lpStartupInfo, 0, 104);
            // cb = size = 104
            writeUInt32LE_raw(lpStartupInfo, 104);
        }
    }

    private static long kernel32_FormatMessageW(long dwFlags, long lpSource, long dwMessageId, long dwLanguageId, long lpBuffer, long nSize, long lpArguments) {
        // FormatMessageW implementation
        // For now, just return a simple message if buffer is provided
        if (lpBuffer != 0 && nSize > 0) {
            // Write "Message not found" as wide string
            char[] msg = new char[18];
            msg[0] = 'M'; msg[1] = 'e'; msg[2] = 's'; msg[3] = 's'; msg[4] = 'a'; msg[5] = 'g'; msg[6] = 'e'; msg[7] = ' ';
            msg[8] = 'n'; msg[9] = 'o'; msg[10] = 't'; msg[11] = ' '; msg[12] = 'f'; msg[13] = 'o'; msg[14] = 'u'; msg[15] = 'n'; msg[16] = 'd'; msg[17] = 0;
            int i = 0;
            while (i < 18 && i < nSize) {
                Native.writeMemory(lpBuffer + i * 2, msg[i]);
                Native.writeMemory(lpBuffer + i * 2 + 1, (char)0);
                i = i + 1;
            }
            if (i < nSize) {
                Native.writeMemory(lpBuffer + i * 2, (char)0);
                Native.writeMemory(lpBuffer + i * 2 + 1, (char)0);
            }
            return (long)i;
        }
        return 0;
    }

    // ===================================================================
    // PE FAULT HANDLING (Phase 5)
    // ===================================================================

    public static boolean isInPeRange(long rip) {
        return rip >= peLoadedBase && rip < peLoadedEnd && peLoadedBase != 0;
    }

    public static long getPeLoadedBase() { return peLoadedBase; }
}
