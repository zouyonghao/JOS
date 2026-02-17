// kernel_api.h - User-mode API for kernel services
// This header provides the interface for user programs to call kernel functions

#ifndef KERNEL_API_H
#define KERNEL_API_H

// Syscall numbers
#define SYS_PRINT  1
#define SYS_EXIT   2
#define SYS_YIELD  3
#define SYS_GETPID 4

// Print a string to the console
// str: pointer to string
// len: length of string
void kernel_print(const char* str, int len);

// Exit the user program with a status code (terminates thread)
void kernel_exit(int status);

// Voluntarily yield CPU to another thread
void kernel_yield(void);

// Get current thread ID
int kernel_getpid(void);

#endif // KERNEL_API_H
