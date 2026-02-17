// hello.c - User-mode hello world program
// This is the first user program for JOS

#include "lib/kernel_api.h"

// Entry point for the user program
// The kernel will jump here after loading the program
void _start() {
    // Print a greeting message
    kernel_print("Hello from user program!\n");
    
    // Exit with status 0 (success)
    kernel_exit(0);
}
