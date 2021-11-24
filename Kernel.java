public class Kernel {
	public static native void writeMemory(long addr, char _byte);

	public static void main(String[] args) {
		long terminalBuffer = 0xB8000L;
		int index = 0;
		writeMemory(terminalBuffer + (index+=2), 'H');
		writeMemory(terminalBuffer + (index+=2), 'e');
		writeMemory(terminalBuffer + (index+=2), 'l');
		writeMemory(terminalBuffer + (index+=2), 'l');
		writeMemory(terminalBuffer + (index+=2), 'o');
	}
}
