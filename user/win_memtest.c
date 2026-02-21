/*
 * win_memtest.c - Test HeapAlloc/HeapFree on JOS PE loader
 *
 * Build (Windows clang):
 *   clang.exe --target=x86_64-windows-gnu -nostdlib -o win_memtest.exe win_memtest.c -lkernel32
 */

typedef unsigned long DWORD;
typedef int BOOL;
typedef void* HANDLE;
typedef void* LPVOID;

#define STD_OUTPUT_HANDLE ((DWORD)-11)
#define HEAP_ZERO_MEMORY  0x00000008

__declspec(dllimport) HANDLE GetStdHandle(DWORD nStdHandle);
__declspec(dllimport) BOOL WriteFile(HANDLE hFile, const void* lpBuffer,
                                      DWORD nNumberOfBytesToWrite,
                                      DWORD* lpNumberOfBytesWritten,
                                      void* lpOverlapped);
__declspec(dllimport) void ExitProcess(DWORD uExitCode);
__declspec(dllimport) HANDLE GetProcessHeap(void);
__declspec(dllimport) LPVOID HeapAlloc(HANDLE hHeap, DWORD dwFlags, DWORD dwBytes);
__declspec(dllimport) BOOL HeapFree(HANDLE hHeap, DWORD dwFlags, LPVOID lpMem);
__declspec(dllimport) DWORD GetLastError(void);

static void write_str(HANDLE h, const char *s) {
    DWORD len = 0;
    while (s[len]) len++;
    DWORD written;
    WriteFile(h, s, len, &written, (void*)0);
}

void mainCRTStartup(void) {
    HANDLE hOut = GetStdHandle(STD_OUTPUT_HANDLE);
    HANDLE hHeap = GetProcessHeap();

    write_str(hOut, "HeapAlloc test start\r\n");

    /* Allocate 256 bytes with zero-init */
    char *buf = (char*)HeapAlloc(hHeap, HEAP_ZERO_MEMORY, 256);
    if (buf == (char*)0) {
        write_str(hOut, "FAIL: HeapAlloc returned NULL\r\n");
        ExitProcess(1);
    }

    write_str(hOut, "HeapAlloc OK\r\n");

    /* Write test pattern */
    buf[0] = 'J';
    buf[1] = 'O';
    buf[2] = 'S';
    buf[3] = '!';
    buf[4] = '\r';
    buf[5] = '\n';
    buf[6] = 0;

    /* Read back via WriteFile */
    DWORD written;
    WriteFile(hOut, buf, 6, &written, (void*)0);

    /* Free */
    HeapFree(hHeap, 0, buf);
    write_str(hOut, "HeapFree OK\r\n");

    write_str(hOut, "MEMTEST PASS\r\n");
    ExitProcess(0);
    while(1) {}
}
