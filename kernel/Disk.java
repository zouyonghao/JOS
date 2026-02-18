package kernel;

/**
 * Disk I/O module - ATA PIO driver
 */
public class Disk {

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
    private static final char ATA_SR_BSY = 0x80;
    private static final char ATA_SR_DRDY = 0x40;
    private static final char ATA_SR_DRQ = 0x08;

    private static void ataWaitNotBusy() {
        char status;
        do {
            status = Native.inb(ATA_STATUS);
        } while ((status & ATA_SR_BSY) != 0);
    }
    
    private static void ataWaitDataReady() {
        char status;
        do {
            status = Native.inb(ATA_STATUS);
        } while ((status & ATA_SR_DRQ) == 0 && (status & ATA_SR_BSY) != 0);
    }
    
    public static void ataReadSector(int lba, int drive, long bufferAddr) {
        char driveSelect = (char)(0xE0 | ((drive & 1) << 4) | ((lba >> 24) & 0x0F));
        
        ataWaitNotBusy();
        
        Native.outb(ATA_DRIVE_SELECT, driveSelect);
        Native.ioWait();
        Native.outb(ATA_SECTOR_COUNT, (char)1);
        Native.outb(ATA_LBA_LOW, (char)(lba & 0xFF));
        Native.outb(ATA_LBA_MID, (char)((lba >> 8) & 0xFF));
        Native.outb(ATA_LBA_HIGH, (char)((lba >> 16) & 0x0F));
        Native.outb(ATA_COMMAND, ATA_CMD_READ_SECTORS);
        
        ataWaitNotBusy();
        ataWaitDataReady();
        
        // Read 256 words (512 bytes) using 16-bit reads
        int i = 0;
        long addr = bufferAddr;
        while (i < 256) {
            // Read 16-bit word from ATA data port
            int word = Native.inw(ATA_DATA);
            // Split into two bytes (little endian: low byte first)
            char low = (char)(word & 0xFF);
            char high = (char)((word >> 8) & 0xFF);
            Native.writeMemory(addr, low);
            Native.writeMemory(addr + 1, high);
            addr = addr + 2;
            i = i + 1;
        }
    }
    
    public static boolean readDisk(int lba, int count, long bufferAddr) {
        int i = 0;
        while (i < count) {
            ataReadSector(lba + i, 0, bufferAddr + i * 512);
            i = i + 1;
        }
        return true;
    }
}
