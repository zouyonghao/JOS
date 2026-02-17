// kernel_api.h - User-mode API for kernel services
// This header provides the interface for user programs to call kernel functions

#ifndef KERNEL_API_H
#define KERNEL_API_H

// Print a null-terminated string to the console
// This calls the kernel's print function at a known address
void kernel_print(const char* str);

// Exit the user program with a status code
// This returns control to the kernel
void kernel_exit(int status);

#endif // KERNEL_API_H
