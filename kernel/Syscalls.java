package kernel;

/**
 * System call handling module
 */
public class Syscalls {
    
    // Syscall numbers
    public static final int SYS_PRINT = 1;
    public static final int SYS_EXIT = 2;
    public static final int SYS_YIELD = 3;
    public static final int SYS_GETPID = 4;
    
    // kernel32.dll function IDs (must match Loader.java)
    public static final int KERNEL32_GET_STD_HANDLE = 1;
    public static final int KERNEL32_WRITE_FILE = 2;
    public static final int KERNEL32_EXIT_PROCESS = 3;
    
    // Syscall argument globals (accessed by assembly)
    private static long syscallNum = 0;
    private static long syscallArg1 = 0;
    private static long syscallArg2 = 0;
    private static long syscallArg3 = 0;
    private static long syscallRet = 0;
    
    // Windows syscall emulation globals
    private static long winSyscallNum = 0;
    private static long winSyscallArg1 = 0;
    private static long winSyscallArg2 = 0;
    private static long winSyscallArg3 = 0;
    private static long winSyscallArg4 = 0;
    private static long isWindowsSyscall = 0;
    private static long winSyscallRet = 0;
    
    // Called from interrupt handler when vector == 0x80
    public static void handleSyscall() {
        // Check if this is a kernel32.dll function call (RAX in range 1-16)
        if (syscallNum >= KERNEL32_GET_STD_HANDLE && syscallNum <= 16) {
            syscallRet = Loader.handleKernel32Call((int)syscallNum, syscallArg1, syscallArg2, syscallArg3, 0);
            return;
        }
        syscallRet = handleSyscallInternal(syscallNum, syscallArg1, syscallArg2, syscallArg3);
    }
    
    private static long handleSyscallInternal(long num, long arg1, long arg2, long arg3) {
        switch ((int)num) {
            case SYS_PRINT:
                return sysPrint(arg1, arg2);
            case SYS_EXIT:
                Threading.terminateCurrentThread();
                return 0;
            case SYS_YIELD:
                Threading.schedule();
                return 0;
            case SYS_GETPID:
                return Threading.getCurrentThreadId();
            default:
                return -1;
        }
    }
    
    private static long sysPrint(long ptr, long len) {
        int i = 0;
        while (i < len) {
            char c = Native.readMemoryByte(ptr + i);
            Console.writeChar(c);
            i = i + 1;
        }
        return 0;
    }
    
    // Windows syscall handler (for MSR_LSTAR)
    public static void handleWindowsSyscall() {
        winSyscallRet = Loader.handleWindowsSyscall(winSyscallNum, winSyscallArg1, winSyscallArg2, winSyscallArg3, winSyscallArg4);
    }
    
    // Getters for assembly
    public static long getSyscallRet() { return syscallRet; }
    public static long getWinSyscallRet() { return winSyscallRet; }
}
