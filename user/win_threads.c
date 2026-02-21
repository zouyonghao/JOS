/*
 * win_threads.c - Test CreateThread/WaitForSingleObject on JOS PE loader
 *
 * Build (Windows clang):
 *   clang.exe --target=x86_64-windows-gnu -nostdlib -o win_threads.exe win_threads.c -lkernel32
 */

typedef unsigned long DWORD;
typedef int BOOL;
typedef void* HANDLE;
typedef void* LPVOID;
typedef unsigned long ULONG_PTR;

#define STD_OUTPUT_HANDLE ((DWORD)-11)
#define INFINITE 0xFFFFFFFF

__declspec(dllimport) HANDLE GetStdHandle(DWORD nStdHandle);
__declspec(dllimport) BOOL WriteFile(HANDLE hFile, const void* lpBuffer,
                                      DWORD nNumberOfBytesToWrite,
                                      DWORD* lpNumberOfBytesWritten,
                                      void* lpOverlapped);
__declspec(dllimport) void ExitProcess(DWORD uExitCode);
__declspec(dllimport) HANDLE CreateThread(void* lpSecAttr, ULONG_PTR dwStackSize,
                                            DWORD (*lpStartAddress)(LPVOID),
                                            LPVOID lpParameter, DWORD dwCreationFlags,
                                            DWORD* lpThreadId);
__declspec(dllimport) DWORD WaitForSingleObject(HANDLE hHandle, DWORD dwMilliseconds);
__declspec(dllimport) void Sleep(DWORD dwMilliseconds);

static HANDLE g_hOut;

static void write_str(const char *s) {
    DWORD len = 0;
    while (s[len]) len++;
    DWORD written;
    WriteFile(g_hOut, s, len, &written, (void*)0);
}

static DWORD thread_func(LPVOID param) {
    write_str("Hello from thread!\r\n");
    return 0;
}

void mainCRTStartup(void) {
    g_hOut = GetStdHandle(STD_OUTPUT_HANDLE);
    write_str("CreateThread test start\r\n");

    HANDLE hThread = CreateThread((void*)0, 0, thread_func, (LPVOID)0, 0, (DWORD*)0);
    if (hThread == (HANDLE)0) {
        write_str("FAIL: CreateThread returned NULL\r\n");
        ExitProcess(1);
    }

    write_str("Waiting for thread...\r\n");
    WaitForSingleObject(hThread, INFINITE);
    write_str("THREAD PASS\r\n");
    ExitProcess(0);
    while(1) {}
}
