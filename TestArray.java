public class TestArray {
    // Test array operations
    public static void testIntArray() {
        int[] arr = new int[5];
        arr[0] = 10;
        arr[1] = 20;
        arr[2] = arr[0] + arr[1];
        int len = arr.length;
    }
    
    public static void testLongArray() {
        long[] arr = new long[3];
        arr[0] = 100L;
        arr[1] = 200L;
        arr[2] = arr[0] + arr[1];
    }
    
    public static void testByteArray() {
        byte[] arr = new byte[4];
        arr[0] = (byte)1;
        arr[1] = (byte)2;
        arr[2] = (byte)(arr[0] + arr[1]);
    }
    
    public static void testCharArray() {
        char[] arr = new char[4];
        arr[0] = 'A';
        arr[1] = 'B';
        arr[2] = arr[0];
    }
    
    public static void main(String[] args) {
        testIntArray();
        testLongArray();
        testByteArray();
        testCharArray();
    }
}
