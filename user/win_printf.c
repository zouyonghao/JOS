/*
 * win_printf.c - Test msvcrt.dll printf/malloc/strlen on JOS PE loader
 *
 * Build (Windows clang):
 *   clang.exe --target=x86_64-windows-gnu -nostdlib -o win_printf.exe win_printf.c -lmsvcrt -lkernel32
 *
 * This uses C runtime functions resolved from msvcrt.dll.
 */

/* Declare msvcrt functions directly (no headers needed) */
typedef unsigned long long size_t;

__declspec(dllimport) int printf(const char *fmt, ...);
__declspec(dllimport) void *malloc(size_t size);
__declspec(dllimport) void free(void *ptr);
__declspec(dllimport) size_t strlen(const char *s);
__declspec(dllimport) void exit(int code);

void mainCRTStartup(void) {
    printf("printf test start\n");

    /* Test %s */
    printf("Hello from %s!\n", "JOS PE printf");

    /* Test %d */
    printf("Number: %d\n", 42);

    /* Test %x */
    printf("Hex: 0x%x\n", 0xDEAD);

    /* Test malloc + strlen */
    char *buf = (char*)malloc(64);
    if (buf == (char*)0) {
        printf("FAIL: malloc returned NULL\n");
        exit(1);
    }

    /* Manual strcpy */
    const char *src = "JOS malloc works!";
    int i = 0;
    while (src[i]) {
        buf[i] = src[i];
        i++;
    }
    buf[i] = 0;

    int len = (int)strlen(buf);
    printf("String: %s (len=%d)\n", buf, len);

    free(buf);
    printf("PRINTF PASS\n");
    exit(0);
    while(1) {}
}
