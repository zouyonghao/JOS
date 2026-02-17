#!/usr/bin/env python3
"""
Expect-like functionality for interacting with QEMU serial output.
Provides pattern matching, timeouts, and input sending capabilities.
"""

import select
import sys
import time
import re


def clean_ansi(text):
    """Remove ANSI escape sequences from text."""
    # Remove ANSI escape sequences
    text = re.sub(r'\x1b\[[0-9;?]*[a-zA-Z]', '', text)
    # Remove other control characters except newline and tab
    text = re.sub(r'[\x00-\x08\x0b-\x0c\x0e-\x1f\x7f]', '', text)
    # Normalize carriage returns
    text = text.replace('\r\n', '\n').replace('\r', '\n')
    return text


class ExpectSession:
    """Handles communication with a process through stdin/stdout."""
    
    def __init__(self, process, timeout=30):
        self.process = process
        self.timeout = timeout
        self.buffer = ""  # Accumulated output
        self.debug = False
        
    def set_debug(self, enabled):
        self.debug = enabled
        
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
        """Send data to process stdin."""
        if self.debug:
            print(f"[DEBUG] Send: {repr(data)}", file=sys.stderr)
        if isinstance(data, str):
            data = data.encode('utf-8')
        self.process.stdin.write(data)
        self.process.stdin.flush()
        
    def sendline(self, line=""):
        """Send a line followed by newline."""
        self.send(line + "\n")
        
    def get_buffer(self, clean=True):
        """Get current buffer contents."""
        if clean:
            return clean_ansi(self.buffer)
        return self.buffer
        
    def clear_buffer(self):
        """Clear the buffer."""
        self.buffer = ""
        
    def drain(self, timeout=0.5):
        """Read all available output."""
        start = time.time()
        while time.time() - start < timeout:
            data = self._read_available(0.1)
            if data:
                self.buffer += data
        return clean_ansi(self.buffer)
        
    def close(self):
        """Terminate the process."""
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except:
                try:
                    self.process.kill()
                    self.process.wait(timeout=2)
                except:
                    pass  # Best effort cleanup
