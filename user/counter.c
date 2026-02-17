// counter.c - Test program for preemptive multithreading
// Run two instances to see interleaved output

#include "lib/kernel_api.h"

void _start() {
    int pid = kernel_getpid();

    char msg[] = "Thread X: count Y\n";
    msg[7] = '0' + pid;

    int i = 0;
    while (i < 5) {
        msg[16] = '0' + i;
        kernel_print(msg, 18);
        // Busy-wait to span multiple scheduling intervals
        volatile int delay = 0;
        while (delay < 50000000) {
            delay = delay + 1;
        }
        i = i + 1;
    }

    kernel_exit(0);
}
