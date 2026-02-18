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
        Memory.initPaging();
        
        // Initialize heap
        Memory.initHeap();
        
        // Initialize threading
        Threading.initThreading();
        
        // Initialize filesystem
        Filesystem.initFilesystem();
        
        // Initialize PE loader
        Loader.initWinHandles();
        
        // Initialize interrupts (PIC, IDT, PIT)
        Interrupts.initInterrupts();
        
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
}
