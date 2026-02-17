// hello.c - User-mode hello world program
// This is the first user program for JOS

#include "lib/kernel_api.h"

// Entry point for the user program
void _start() {
    // Print a message using syscall
    const char* msg = "Hello from user program!\n";
    
    // Calculate length
    int len = 0;
    while (msg[len] != '\0') {
        len = len + 1;
    }
    
    kernel_print(msg, len);
    
    // Exit cleanly
    kernel_exit(0);
}
