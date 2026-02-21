/*
 * win_fileio.c - Test CreateFileA/ReadFile on JOS PE loader
 *
 * Build (Windows clang):
 *   clang.exe --target=x86_64-windows-gnu -nostdlib -o win_fileio.exe win_fileio.c -lkernel32
 */

typedef unsigned long DWORD;
typedef int BOOL;
typedef void* HANDLE;

#define STD_OUTPUT_HANDLE  ((DWORD)-11)
#define INVALID_HANDLE_VALUE ((HANDLE)(long long)-1)
#define GENERIC_READ       0x80000000
#define OPEN_EXISTING      3
#define FILE_ATTRIBUTE_NORMAL 0x80

__declspec(dllimport) HANDLE GetStdHandle(DWORD nStdHandle);
__declspec(dllimport) BOOL WriteFile(HANDLE hFile, const void* lpBuffer,
                                      DWORD nNumberOfBytesToWrite,
                                      DWORD* lpNumberOfBytesWritten,
                                      void* lpOverlapped);
__declspec(dllimport) BOOL ReadFile(HANDLE hFile, void* lpBuffer,
                                     DWORD nNumberOfBytesToRead,
                                     DWORD* lpNumberOfBytesRead,
                                     void* lpOverlapped);
__declspec(dllimport) HANDLE CreateFileA(const char* lpFileName,
                                          DWORD dwDesiredAccess,
                                          DWORD dwShareMode,
                                          void* lpSecurityAttributes,
                                          DWORD dwCreationDisposition,
                                          DWORD dwFlagsAndAttributes,
                                          HANDLE hTemplateFile);
__declspec(dllimport) BOOL CloseHandle(HANDLE hObject);
__declspec(dllimport) void ExitProcess(DWORD uExitCode);
__declspec(dllimport) DWORD GetFileSize(HANDLE hFile, DWORD* lpFileSizeHigh);

static void write_str(HANDLE h, const char *s) {
    DWORD len = 0;
    while (s[len]) len++;
    DWORD written;
    WriteFile(h, s, len, &written, (void*)0);
}

void mainCRTStartup(void) {
    HANDLE hOut = GetStdHandle(STD_OUTPUT_HANDLE);
    write_str(hOut, "File I/O test start\r\n");

    /* Try to open hello.sbf (which should be on the disk) */
    HANDLE hFile = CreateFileA("hello.sbf", GENERIC_READ, 0,
                                (void*)0, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, (HANDLE)0);
    if (hFile == INVALID_HANDLE_VALUE) {
        write_str(hOut, "FAIL: Could not open hello.sbf\r\n");
        ExitProcess(1);
    }

    write_str(hOut, "Opened hello.sbf\r\n");

    DWORD fileSize = GetFileSize(hFile, (DWORD*)0);
    write_str(hOut, "Got file size\r\n");

    /* Read first 32 bytes */
    char buf[33];
    DWORD bytesRead = 0;
    ReadFile(hFile, buf, 32, &bytesRead, (void*)0);
    buf[bytesRead] = 0;

    write_str(hOut, "Read bytes from file\r\n");

    CloseHandle(hFile);
    write_str(hOut, "FILEIO PASS\r\n");
    ExitProcess(0);
    while(1) {}
}
