#!/usr/bin/env python3
"""
Expect-like functionality for interacting with QEMU serial output.
Provides pattern matching, timeouts, and input sending capabilities.
Uses QEMU monitor socket for reliable keyboard input.
"""

import select
import sys
import time
import re
import socket
import os

# Key mapping for QEMU sendkey command
KEY_MAP = {
    'a': 'a', 'b': 'b', 'c': 'c', 'd': 'd', 'e': 'e',
    'f': 'f', 'g': 'g', 'h': 'h', 'i': 'i', 'j': 'j',
    'k': 'k', 'l': 'l', 'm': 'm', 'n': 'n', 'o': 'o',
    'p': 'p', 'q': 'q', 'r': 'r', 's': 's', 't': 't',
    'u': 'u', 'v': 'v', 'w': 'w', 'x': 'x', 'y': 'y', 'z': 'z',
    '0': '0', '1': '1', '2': '2', '3': '3', '4': '4',
    '5': '5', '6': '6', '7': '7', '8': '8', '9': '9',
    ' ': 'spc', '.': 'dot', '\n': 'ret', '-': 'minus',
    '_': 'shift-minus', '=': 'equal', '+': 'shift-equal',
    '[': 'bracket_left', ']': 'bracket_right',
    '{': 'shift-bracket_left', '}': 'shift-bracket_right',
    '\\': 'backslash', '|': 'shift-backslash',
    ';': 'semicolon', ':': 'shift-semicolon',
    "'": 'apostrophe', '"': 'shift-apostrophe',
    ',': 'comma', '<': 'shift-comma',
    '/': 'slash', '?': 'shift-slash',
    '`': 'grave_accent', '~': 'shift-grave_accent',
}


def clean_ansi(text):
    """Remove ANSI escape sequences from text."""
    # Remove ANSI escape sequences
    text = re.sub(r'\x1b\[[0-9;?]*[a-zA-Z]', '', text)
    # Remove other control characters except newline and tab
    text = re.sub(r'[\x00-\x08\x0b-\x0c\x0e-\x1f\x7f]', '', text)
    # Normalize carriage returns
    text = text.replace('\r\n', '\n').replace('\r', '\n')
    # Remove spinner chars
    text = re.sub(r'[|/\\-]', '', text)
    return text


class ExpectSession:
    """Handles communication with a QEMU process through monitor socket and stdout."""
    
    def __init__(self, process, timeout=30, monitor_path="/tmp/qemu-test-monitor"):
        self.process = process
        self.timeout = timeout
        self.monitor_path = monitor_path
        self.buffer = ""  # Accumulated output
        self.debug = False
        self.sock = None
        
    def set_debug(self, enabled):
        self.debug = enabled
        
    def connect_monitor(self):
        """Connect to QEMU monitor socket."""
        # Wait for socket to be created
        for _ in range(50):  # Wait up to 5 seconds
            if os.path.exists(self.monitor_path):
                break
            time.sleep(0.1)
        
        if not os.path.exists(self.monitor_path):
            raise RuntimeError(f"Monitor socket not found: {self.monitor_path}")
        
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect(self.monitor_path)
        self.sock.settimeout(5)
        
        # Drain initial response
        try:
            self.sock.recv(1024)
        except:
            pass
        
    def _send_key(self, ch):
        """Send a single keystroke via QEMU monitor."""
        key = KEY_MAP.get(ch)
        if key:
            self.sock.sendall(f"sendkey {key}\n".encode())
            time.sleep(0.03)
            # Drain response
            try:
                self.sock.setblocking(False)
                self.sock.recv(4096)
            except:
                pass
            self.sock.setblocking(True)
            time.sleep(0.02)
        elif self.debug:
            print(f"[DEBUG] No key mapping for '{ch}'", file=sys.stderr)
            
    def _read_available(self, timeout=0.1):
        """Read any available data from stdout with timeout."""
        if self.process.poll() is not None:
            return ""
            
        if hasattr(select, 'select'):
            ready, _, _ = select.select([self.process.stdout], [], [], timeout)
            if ready:
                try:
                    data = self.process.stdout.read(4096)
                    if data:
                        return data.decode('utf-8', errors='replace')
                except:
                    pass
        return ""
        
    def _check_patterns(self, patterns, clean):
        """Check if any pattern matches in current buffer.
        
        Returns (index, match_text) or (None, None) if no match.
        """
        search_buf = clean_ansi(self.buffer) if clean else self.buffer
        for i, pattern in enumerate(patterns):
            match = pattern.search(search_buf)
            if match:
                return (i, match.group(0))
        return (None, None)
        
    def expect(self, patterns, timeout=None, clean=True):
        """
        Wait for one of the patterns to appear in output.
        
        Args:
            patterns: String or list of strings to match
            timeout: Maximum time to wait
            clean: If True, match against cleaned buffer (no ANSI codes)
            
        Returns:
            Tuple of (matched_index, matched_string)
            
        Raises:
            TimeoutError: If timeout reached
            RuntimeError: If process exits unexpectedly
        """
        if timeout is None:
            timeout = self.timeout
            
        if not isinstance(patterns, list):
            patterns = [patterns]
            
        # Compile patterns
        compiled = [re.compile(re.escape(p)) for p in patterns]
        
        start_time = time.time()
        
        # First, check if pattern is already in buffer
        idx, matched = self._check_patterns(compiled, clean)
        if idx is not None:
            if self.debug:
                print(f"[DEBUG] Found in existing buffer: {repr(matched)}", file=sys.stderr)
            return (idx, matched)
                
        while time.time() - start_time < timeout:
            # Check if process exited
            if self.process.poll() is not None:
                idx, matched = self._check_patterns(compiled, clean)
                if idx is not None:
                    return (idx, matched)
                raise RuntimeError(f"Process exited (code: {self.process.returncode})")
                
            # Read new data
            new_data = self._read_available(0.1)
            if new_data:
                self.buffer += new_data
                
                if self.debug:
                    cleaned = clean_ansi(new_data)
                    if cleaned.strip():
                        print(f"[DEBUG] Received: {repr(cleaned[:100])}", file=sys.stderr)
                    
                # Check patterns after adding new data
                idx, matched = self._check_patterns(compiled, clean)
                if idx is not None:
                    if self.debug:
                        print(f"[DEBUG] Matched: {repr(matched)}", file=sys.stderr)
                    return (idx, matched)
                        
        raise TimeoutError(f"Timeout waiting for: {patterns}")
        
    def expect_string(self, string, timeout=None):
        """Wait for a specific string."""
        idx, result = self.expect(string, timeout)
        return result
        
    def send(self, data):
        """Send data as keystrokes via QEMU monitor."""
        if self.sock is None:
            raise RuntimeError("Monitor socket not connected. Call connect_monitor() first.")
            
        if self.debug:
            print(f"[DEBUG] Send: {repr(data)}", file=sys.stderr)
            
        for ch in data:
            self._send_key(ch)
        
    def sendline(self, line=""):
        """Send a line followed by newline (Enter key)."""
        self.send(line)
        self._send_key('\n')
        
    def get_buffer(self, clean=True):
        """Get current buffer contents."""
        if clean:
            return clean_ansi(self.buffer)
        return self.buffer
        
    def clear_buffer(self):
        """Clear the internal buffer."""
        self.buffer = ""
        
    def drain(self, timeout=0.5):
        """Read any remaining data without blocking."""
        result = ""
        start = time.time()
        while time.time() - start < timeout:
            data = self._read_available(0.1)
            if data:
                self.buffer += data
                result += data
            else:
                break
        return result
        
    def close(self):
        """Close the session and cleanup."""
        if self.sock:
            try:
                self.sock.close()
            except:
                pass
            self.sock = None
        if self.process:
            try:
                self.process.terminate()
                self.process.wait(timeout=5)
            except:
                try:
                    self.process.kill()
                except:
                    pass
