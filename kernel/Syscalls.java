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

    // Syscall argument globals (accessed by assembly)
    private static long syscallNum = 0;
    private static long syscallArg1 = 0;
    private static long syscallArg2 = 0;
    private static long syscallArg3 = 0;
    private static long syscallArg4 = 0;
    private static long syscallArg5 = 0;
    private static long syscallArg6 = 0;
    private static long syscallArg7 = 0;
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
        // Check if this is a kernel32.dll function call (RAX in range 1-99)
        if (syscallNum >= 1 && syscallNum <= 99) {
            syscallRet = Loader.handleKernel32Call((int)syscallNum, syscallArg1, syscallArg2, syscallArg3, syscallArg4);
            return;
        }
        // Check if this is a msvcrt function call handled in Java (RAX in range 100-199)
        if (syscallNum >= 100 && syscallNum <= 199) {
            syscallRet = Loader.handleMsvcrtCall((int)syscallNum, syscallArg1, syscallArg2, syscallArg3, syscallArg4);
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

    // Getters for assembly and Loader
    public static long getSyscallRet() { return syscallRet; }
    public static long getWinSyscallRet() { return winSyscallRet; }
    public static long getSyscallArg4() { return syscallArg4; }
    public static long getSyscallArg5() { return syscallArg5; }
    public static long getSyscallArg6() { return syscallArg6; }
    public static long getSyscallArg7() { return syscallArg7; }
}
