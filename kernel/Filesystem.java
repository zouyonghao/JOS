package kernel;

/**
 * Simple Flat Read-Only Filesystem (SFROFS) module
 */
public class Filesystem {

    // SFROFS constants
    private static final int SFROFS_SECTOR_SIZE = 512;
    private static final int SFROFS_SUPERBLOCK_SECTOR = 2049;  // 1MB offset for filesystem
    private static final int SFROFS_MAGIC_0 = 'S';
    private static final int SFROFS_MAGIC_1 = 'F';
    private static final int SFROFS_MAGIC_2 = 'R';
    private static final int SFROFS_MAGIC_3 = 'O';
    private static final int SFROFS_VERSION = 1;
    
    private static final int SFROFS_ENTRY_SIZE = 64;
    private static final int SFROFS_NAME_MAX = 48;
    private static final int SFROFS_MAX_FILES = 32;
    
    // Filesystem state
    private static int fsNumFiles = 0;
    private static int fsFilesStartSector = 0;
    private static int fsInitialized = 0;
    
    // File metadata stored in parallel arrays
    private static char[] fsFileNames = new char[SFROFS_MAX_FILES * SFROFS_NAME_MAX];
    private static int[] fsFileNameLengths = new int[SFROFS_MAX_FILES];
    private static int[] fsFileStartSectors = new int[SFROFS_MAX_FILES];
    private static int[] fsFileSizes = new int[SFROFS_MAX_FILES];

    // Read a byte from memory address
    private static char readMemoryByte(long addr) {
        return (char)(Native.readMemoryLong(addr) & 0xFFL);
    }

    // Read uint16 from buffer at offset
    private static int readUInt16(long bufferAddr, int offset) {
        long addr = bufferAddr + offset;
        char b0 = readMemoryByte(addr);
        char b1 = readMemoryByte(addr + 1);
        return ((int)b0 & 0xFF) | (((int)b1 & 0xFF) << 8);
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
    
    // Get pointer to filename at given index
    private static int fsNameIdx(int fileIdx) {
        return fileIdx * SFROFS_NAME_MAX;
    }
    
    // Read null-terminated string from buffer at offset into filesystem name storage
    private static int readFilename(long bufferAddr, int entryOffset, int fileIdx) {
        int nameBase = fsNameIdx(fileIdx);
        int len = 0;
        int i = 0;
        while (i < SFROFS_NAME_MAX) {
            char c = readMemoryByte(bufferAddr + entryOffset + i);
            if (c == 0) break;
            fsFileNames[nameBase + i] = c;
            len = len + 1;
            i = i + 1;
        }
        return len;
    }

    public static void initFilesystem() {
        Console.writeString("Init FS\n");
        
        long buffer = Memory.heapAlloc(512);
        if (buffer == 0) {
            Console.writeString("No buf\n");
            return;
        }
        
        // Read sector 2049
        Console.writeString("Read 2049\n");
        Disk.ataReadSector(2049, 0, buffer);
        
        // Check bytes
        Console.writeString("Got: ");
        Console.writeHexByte((int)readMemoryByte(buffer));
        Console.writeString(" ");
        Console.writeHexByte((int)readMemoryByte(buffer+1));
        Console.writeString(" ");
        Console.writeHexByte((int)readMemoryByte(buffer+2));
        Console.writeString(" ");
        Console.writeHexByte((int)readMemoryByte(buffer+3));
        Console.writeString("\n");
        
        // Verify magic number
        char magic0 = readMemoryByte(buffer);
        char magic1 = readMemoryByte(buffer + 1);
        char magic2 = readMemoryByte(buffer + 2);
        char magic3 = readMemoryByte(buffer + 3);
        
        if (magic0 != SFROFS_MAGIC_0 || magic1 != SFROFS_MAGIC_1 ||
            magic2 != SFROFS_MAGIC_2 || magic3 != SFROFS_MAGIC_3) {
            Console.writeString("WARNING: No SFROFS filesystem found (invalid magic)\n");
            Memory.heapFree(buffer);
            return;
        }
        
        // Read version
        int version = (int)readMemoryByte(buffer + 4);
        if (version != SFROFS_VERSION) {
            Console.writeString("ERROR: Unsupported SFROFS version\n");
            Memory.heapFree(buffer);
            return;
        }
        
        // Read number of files (at offset 8: after magic[4] + version[1] + padding[3])
        fsNumFiles = readUInt16(buffer, 8);
        if (fsNumFiles > SFROFS_MAX_FILES) {
            Console.writeString("WARNING: Too many files, limiting to ");
            Console.writeNumber(SFROFS_MAX_FILES);
            Console.writeString("\n");
            fsNumFiles = SFROFS_MAX_FILES;
        }
        
        // Read files start sector (at offset 10: after num_files[2])
        fsFilesStartSector = readUInt32(buffer, 10);
        
        Console.writeString("  SFROFS v");
        Console.writeNumber(version);
        Console.writeString(" detected, ");
        Console.writeNumber(fsNumFiles);
        Console.writeString(" files\n");
        
        Memory.heapFree(buffer);
        
        if (fsNumFiles == 0) {
            fsInitialized = 1;
            return;
        }
        
        // Calculate file table sectors (sector 2 to N)
        int tableBytes = fsNumFiles * SFROFS_ENTRY_SIZE;
        int tableSectors = (tableBytes + SFROFS_SECTOR_SIZE - 1) / SFROFS_SECTOR_SIZE;
        
        // Allocate buffer for file table
        long tableBuffer = Memory.heapAlloc(tableSectors * SFROFS_SECTOR_SIZE);
        if (tableBuffer == 0) {
            Console.writeString("ERROR: Could not allocate buffer for file table\n");
            return;
        }
        
        // Read file table from sector 2 (data disk drive 1)
        boolean success = Disk.readDisk(SFROFS_SUPERBLOCK_SECTOR + 1, tableSectors, tableBuffer);
        if (!success) {
            Console.writeString("ERROR: Could not read file table\n");
            Memory.heapFree(tableBuffer);
            return;
        }
        
        // Parse file entries
        int i = 0;
        while (i < fsNumFiles) {
            int entryOffset = i * SFROFS_ENTRY_SIZE;
            
            // Read filename
            int nameLen = readFilename(tableBuffer, entryOffset, i);
            fsFileNameLengths[i] = nameLen;
            
            // Read start sector (at offset 48)
            fsFileStartSectors[i] = readUInt32(tableBuffer, entryOffset + 48);
            
            // Read file size (at offset 52)
            fsFileSizes[i] = readUInt32(tableBuffer, entryOffset + 52);
            
            i = i + 1;
        }
        
        Memory.heapFree(tableBuffer);
        fsInitialized = 1;
        Console.writeString("Filesystem initialized\n");
    }
    
    public static int findFileByName(char[] name, int nameLen) {
        if (fsInitialized == 0 || name == null) return -1;
        if (nameLen <= 0 || nameLen > SFROFS_NAME_MAX) return -1;
        
        int i = 0;
        while (i < fsNumFiles) {
            if (fsFileNameLengths[i] == nameLen) {
                int nameBase = i * SFROFS_NAME_MAX;
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
    
    public static void listFiles() {
        if (fsInitialized == 0) {
            Console.writeString("Filesystem not initialized\n");
            return;
        }
        
        Console.writeString("Files (");
        Console.writeNumber(fsNumFiles);
        Console.writeString("):\n");
        
        int i = 0;
        while (i < fsNumFiles) {
            int nameBase = i * SFROFS_NAME_MAX;
            int j = 0;
            while (j < fsFileNameLengths[i]) {
                Console.writeChar(fsFileNames[nameBase + j]);
                j = j + 1;
            }
            Console.writeString(" (");
            Console.writeNumber(fsFileSizes[i]);
            Console.writeString(" bytes)\n");
            i = i + 1;
        }
    }
    
    public static void catFile(int fileIdx) {
        if (fsInitialized == 0) {
            Console.writeString("Filesystem not initialized\n");
            return;
        }
        if (fileIdx < 0 || fileIdx >= fsNumFiles) {
            Console.writeString("Invalid file index\n");
            return;
        }
        
        int fileSize = fsFileSizes[fileIdx];
        int startSector = fsFileStartSectors[fileIdx];
        
        long buffer = Memory.heapAlloc(fileSize);
        if (buffer == 0) {
            Console.writeString("Could not allocate buffer\n");
            return;
        }
        
        int sectors = (fileSize + 511) / 512;
        int actualSector = 2048 + startSector;
        Disk.readDisk(actualSector, sectors, buffer);
        
        int i = 0;
        while (i < fileSize) {
            char c = (char)(Native.readMemoryLong(buffer + i) & 0xFF);
            Console.writeChar(c);
            i = i + 1;
        }
        Console.writeString("\n");
        
        Memory.heapFree(buffer);
    }
    
    public static void statFile(int fileIdx) {
        if (fsInitialized == 0) {
            Console.writeString("Filesystem not initialized\n");
            return;
        }
        if (fileIdx < 0 || fileIdx >= fsNumFiles) {
            Console.writeString("Invalid file index\n");
            return;
        }
        
        Console.writeString("File: ");
        int nameBase = fileIdx * SFROFS_NAME_MAX;
        int j = 0;
        while (j < fsFileNameLengths[fileIdx]) {
            Console.writeChar(fsFileNames[nameBase + j]);
            j = j + 1;
        }
        Console.writeString("\n");
        Console.writeString("  Size: ");
        Console.writeNumber(fsFileSizes[fileIdx]);
        Console.writeString(" bytes\n");
        Console.writeString("  Start sector: ");
        Console.writeNumber(fsFileStartSectors[fileIdx]);
        Console.writeString("\n");
    }
    
    // Getters
    public static boolean isInitialized() { return fsInitialized != 0; }
    public static int getNumFiles() { return fsNumFiles; }
    public static int getFileSize(int idx) { return fsFileSizes[idx]; }
    public static int getFileStartSector(int idx) { return fsFileStartSectors[idx]; }
}
