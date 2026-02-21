/*
 * win_echo.c - Test stdin read on JOS PE loader
 *
 * Build (Windows clang):
 *   clang.exe --target=x86_64-windows-gnu -nostdlib -o win_echo.exe win_echo.c -lkernel32
 */

typedef unsigned long DWORD;
typedef int BOOL;
typedef void* HANDLE;

#define STD_INPUT_HANDLE  ((DWORD)-10)
#define STD_OUTPUT_HANDLE ((DWORD)-11)

__declspec(dllimport) HANDLE GetStdHandle(DWORD nStdHandle);
__declspec(dllimport) BOOL WriteFile(HANDLE hFile, const void* lpBuffer,
                                      DWORD nNumberOfBytesToWrite,
                                      DWORD* lpNumberOfBytesWritten,
                                      void* lpOverlapped);
__declspec(dllimport) BOOL ReadFile(HANDLE hFile, void* lpBuffer,
                                     DWORD nNumberOfBytesToRead,
                                     DWORD* lpNumberOfBytesRead,
                                     void* lpOverlapped);
__declspec(dllimport) void ExitProcess(DWORD uExitCode);

static void write_str(HANDLE h, const char *s) {
    DWORD len = 0;
    while (s[len]) len++;
    DWORD written;
    WriteFile(h, s, len, &written, (void*)0);
}

void mainCRTStartup(void) {
    HANDLE hIn = GetStdHandle(STD_INPUT_HANDLE);
    HANDLE hOut = GetStdHandle(STD_OUTPUT_HANDLE);

    write_str(hOut, "Echo test - type a line:\r\n");

    char buf[128];
    DWORD bytesRead = 0;
    ReadFile(hIn, buf, 127, &bytesRead, (void*)0);

    write_str(hOut, "You typed: ");
    DWORD written;
    WriteFile(hOut, buf, bytesRead, &written, (void*)0);
    write_str(hOut, "\r\nECHO PASS\r\n");

    ExitProcess(0);
    while(1) {}
}
