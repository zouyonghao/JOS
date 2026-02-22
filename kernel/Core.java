package kernel;

/**
 * Core kernel module - entry point and main loop
 */
public class Core {

    // ===================================================================
    // MAIN ENTRY POINT
    // ===================================================================

    public static void startKernel(long dummy) {
        // Initialize native bindings
        Native.init();
        
        // Clear screen
        Console.clearScreen();
        
        // Print banner
        Console.writeString("JOS Kernel Starting...\n");
        Console.writeString("============================\n\n");
        
        // Initialize memory management
        Memory.initMemoryMap();
        if (Memory.getTotalPages() == 0) {
            Console.writeString("FATAL: No usable memory pages found!\n");
            halt();
        }
        Memory.initPaging();

        // Initialize heap
        Memory.initHeap();

        // Initialize threading
        Threading.initThreading();

        // Initialize filesystem
        Filesystem.initFilesystem();
        if (!Filesystem.isInitialized()) {
            Console.writeString("WARNING: Filesystem not initialized\n");
        }
        
        // Initialize PE loader
        Loader.initWinHandles();
        
        // Initialize interrupts (PIC, IDT, PIT)
        Interrupts.initInterrupts();
        
        // Initialize syscall MSRs for Windows-compatible binaries
        Native.initSyscallMSR();
        
        Console.writeString("\nKernel initialization complete.\n");
        Console.writeString("Type 'help' for available commands.\n\n");
        
        // Main shell loop
        while (true) {
            Console.writeString("> ");
            Shell.readCommand();
            Shell.executeCommand();
        }
    }

    public static void main(String[] args) {
        // Dummy main for compilation
    }

    public static void halt() {
        Native.disableInterrupts();
        while (true) {
            // Infinite loop — CPU halted
        }
    }
}
