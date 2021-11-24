public class Kernel {
	public static native void writeMemory(long addr, char _byte);

	public static void main(String[] args) {
		long terminalBuffer = 0xB8000L;
		int index = 0;
		writeMemory(terminalBuffer + (index++), 'H');
		writeMemory(terminalBuffer + (index++), 'e');
		writeMemory(terminalBuffer + (index++), 'l');
		writeMemory(terminalBuffer + (index++), 'l');
		writeMemory(terminalBuffer + (index++), 'o');
	}
}
