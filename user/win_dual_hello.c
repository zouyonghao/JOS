/*
 * win_dual_hello.c - Windows binary that runs on both Windows and JOS
 * 
 * This program uses Windows API calls (WriteFile, ExitProcess) from kernel32.dll.
 * On Windows: These are resolved by the Windows loader.
 * On JOS: These are emulated by the PE loader's import table handler.
 * 
 * Build (Windows clang):
 *   clang.exe --target=x86_64-windows-gnu -nostdlib -o win_dual_hello.exe win_dual_hello.c -lkernel32
 *
 * Or with lld-link:
 *   clang.exe -c win_dual_hello.c -o win_dual_hello.obj
 *   lld-link.exe -out:win_dual_hello.exe -entry:mainCRTStartup -subsystem:console win_dual_hello.obj kernel32.lib
 */

/* Windows API function declarations */
/* These will be resolved from kernel32.dll import table */

/* Standard Windows types */
typedef unsigned long DWORD;
typedef int BOOL;
typedef void* HANDLE;
typedef unsigned long ULONG_PTR;
typedef const char* LPCSTR;
typedef char* LPSTR;

#define INVALID_HANDLE_VALUE ((HANDLE)(long long)-1)
#define STD_OUTPUT_HANDLE ((DWORD)-11)

/* Windows API functions - imported from kernel32.dll */
__declspec(dllimport) HANDLE GetStdHandle(DWORD nStdHandle);
__declspec(dllimport) BOOL WriteFile(HANDLE hFile, const void* lpBuffer, 
                                      DWORD nNumberOfBytesToWrite, 
                                      DWORD* lpNumberOfBytesWritten, 
                                      void* lpOverlapped);
__declspec(dllimport) void ExitProcess(DWORD uExitCode);

/* Helper function to get string length */
static unsigned long my_strlen(const char* s) {
    unsigned long len = 0;
    while (s[len] != '\0') {
        len++;
    }
    return len;
}

/* Entry point - must never return */
void mainCRTStartup(void) {
    const char* message = "Hello from Windows binary!\r\n"
                          "This program runs on both Windows and JOS!\r\n";
    unsigned long len = my_strlen(message);
    DWORD bytesWritten;
    
    /* Get standard output handle */
    HANDLE hStdOut = GetStdHandle(STD_OUTPUT_HANDLE);
    
    /* Write message to console */
    if (hStdOut != INVALID_HANDLE_VALUE) {
        WriteFile(hStdOut, message, (DWORD)len, &bytesWritten, (void*)0);
    }
    
    /* Exit with success */
    ExitProcess(0);
    
    /* Never reached */
    while(1) {}
}
