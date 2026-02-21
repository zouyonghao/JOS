package kernel;

/**
 * Thread management module - kernel threads and scheduler
 */
public class Threading {

    private static final int MAX_THREADS = 16;
    private static final long THREAD_STACK_SIZE = 16384;  // 16KB per thread

    private static final int THREAD_FREE = 0;
    private static final int THREAD_READY = 1;
    private static final int THREAD_RUNNING = 2;
    private static final int THREAD_TERMINATED = 3;

    // Per-thread state
    private static int[] threadState = new int[MAX_THREADS];
    private static long[] threadStackBase = new long[MAX_THREADS];

    // Saved RSP table at fixed raw memory address
    private static final long THREAD_RSP_TABLE = 0x880000L;

    private static int currentThreadId = 0;
    private static int threadCount = 0;
    private static int scheduleCounter = 0;
    private static final int SCHEDULE_INTERVAL = 10;

    // Context switch interface
    private static long ctxSaveRSPAddr = 0;
    private static long ctxLoadRSP = 0;

    public static void initThreading() {
        int i = 0;
        while (i < MAX_THREADS) {
            threadState[i] = THREAD_FREE;
            threadStackBase[i] = 0;
            i = i + 1;
        }
        threadState[0] = THREAD_RUNNING;
        currentThreadId = 0;
        threadCount = 1;
    }
    
    public static int spawnThread(long entryPoint) {
        // Find free slot
        int tid = -1;
        int i = 1; // skip thread 0 (shell)
        while (i < MAX_THREADS) {
            if (threadState[i] == THREAD_FREE) {
                tid = i;
                break;
            }
            i = i + 1;
        }
        
        if (tid < 0) {
            Console.writeString("ERROR: No free thread slots\n");
            return -1;
        }
        
        // Allocate stack from heap
        long stackBase = Memory.heapAlloc(THREAD_STACK_SIZE);
        if (stackBase == 0) {
            Console.writeString("ERROR: Could not allocate thread stack\n");
            return -1;
        }
        threadStackBase[tid] = stackBase;
        long stackTop = stackBase + THREAD_STACK_SIZE;
        
        // Build fake interrupt frame on the new stack (grows downward)
        // When assembly does popq R15..RAX, addq $8 (skip vector), iretq,
        // execution begins at entryPoint with interrupts enabled
        long sp = stackTop;
        
        // iretq frame (popped last, so written first at high addresses)
        sp = sp - 8; Native.writeMemoryLong(sp, 0x10);         // SS (kernel data segment)
        sp = sp - 8; Native.writeMemoryLong(sp, stackTop);     // RSP (stack pointer after iretq)
        sp = sp - 8; Native.writeMemoryLong(sp, 0x202);        // RFLAGS (bit 1 reserved=1, IF=1)
        sp = sp - 8; Native.writeMemoryLong(sp, 0x08);         // CS (kernel code segment)
        sp = sp - 8; Native.writeMemoryLong(sp, entryPoint);   // RIP
        
        // Vector number (dummy, skipped by addq $8, %rsp)
        sp = sp - 8; Native.writeMemoryLong(sp, 0);
        
        // 15 GPRs in push order: RAX, RCX, RDX, RBX, RBP, RSI, RDI, R8-R15
        // (popped in reverse: R15 first, RAX last)
        i = 0;
        while (i < 15) {
            sp = sp - 8; Native.writeMemoryLong(sp, 0);
            i = i + 1;
        }
        
        // Save initial RSP to thread RSP table
        Native.writeMemoryLong(THREAD_RSP_TABLE + tid * 8, sp);

        threadState[tid] = THREAD_READY;
        threadCount = threadCount + 1;
        return tid;
    }
    
    public static void schedule() {
        if (threadCount <= 1) {
            return;
        }
        
        // Increment counter
        scheduleCounter = scheduleCounter + 1;
        
        // Only schedule every N ticks
        if (scheduleCounter < SCHEDULE_INTERVAL) {
            return;
        }
        scheduleCounter = 0;
        
        // Find next runnable thread
        int next = currentThreadId;
        while (true) {
            next = next + 1;
            if (next >= MAX_THREADS) {
                next = 0;
            }
            if (next == currentThreadId) {
                return; // No other runnable thread
            }
            if (threadState[next] == THREAD_READY) {
                break;
            }
        }
        
        // Load next RSP
        ctxLoadRSP = Native.readMemoryLong(THREAD_RSP_TABLE + next * 8);
        
        // Save current RSP
        ctxSaveRSPAddr = THREAD_RSP_TABLE + currentThreadId * 8;
        
        // Update states
        threadState[currentThreadId] = THREAD_READY;
        threadState[next] = THREAD_RUNNING;
        currentThreadId = next;
    }
    
    public static void terminateCurrentThread() {
        threadState[currentThreadId] = THREAD_TERMINATED;
        threadCount = threadCount - 1;
        
        // Free stack memory (thread 0 uses boot stack, not heap)
        if (currentThreadId != 0 && threadStackBase[currentThreadId] != 0) {
            Memory.heapFree(threadStackBase[currentThreadId]);
            threadStackBase[currentThreadId] = 0;
        }
        
        // Find next ready thread
        int next = -1;
        int i = 0;
        while (i < MAX_THREADS) {
            if (threadState[i] == THREAD_READY) {
                next = i;
                break;
            }
            i = i + 1;
        }
        
        if (next >= 0) {
            // Don't save dying thread's RSP
            ctxSaveRSPAddr = 0;
            ctxLoadRSP = Native.readMemoryLong(THREAD_RSP_TABLE + next * 8);
            threadState[next] = THREAD_RUNNING;
            threadState[currentThreadId] = THREAD_FREE;
            currentThreadId = next;
        } else {
            // No runnable threads - should not happen if shell (thread 0) is alive
            Console.writeString("No runnable threads!\n");
            while (true) {
                // Halt
            }
        }
    }
    
    public static void listThreads() {
        Console.writeString("TID  STATE\n");
        int i = 0;
        while (i < MAX_THREADS) {
            if (threadState[i] != THREAD_FREE) {
                Console.writeString(" ");
                Console.writeNumber(i);
                Console.writeString("    ");
                if (threadState[i] == THREAD_RUNNING) {
                    Console.writeString("RUNNING\n");
                } else if (threadState[i] == THREAD_READY) {
                    Console.writeString("READY\n");
                } else if (threadState[i] == THREAD_TERMINATED) {
                    Console.writeString("TERMINATED\n");
                } else {
                    Console.writeString("UNKNOWN\n");
                }
            }
            i = i + 1;
        }
    }
    
    // Called from assembly interrupt handler
    public static long getCtxSaveRSPAddr() { return ctxSaveRSPAddr; }
    public static long getCtxLoadRSP() { return ctxLoadRSP; }
    public static void clearCtxLoadRSP() { ctxLoadRSP = 0; }
    public static int getCurrentThreadId() { return currentThreadId; }

    // Phase 4: Thread state queries for WaitForSingleObject
    public static boolean isThreadTerminated(int tid) {
        if (tid < 0 || tid >= MAX_THREADS) return true;
        return threadState[tid] == THREAD_TERMINATED || threadState[tid] == THREAD_FREE;
    }

    public static int getThreadState(int tid) {
        if (tid < 0 || tid >= MAX_THREADS) return THREAD_FREE;
        return threadState[tid];
    }
}
