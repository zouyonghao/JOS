public class TestSwitch {
    // Test dense switch (should use tableswitch)
    static int testTableSwitch(int x) {
        int result;
        switch (x) {
            case 1: result = 10; break;
            case 2: result = 20; break;
            case 3: result = 30; break;
            case 4: result = 40; break;
            case 5: result = 50; break;
            default: result = 0; break;
        }
        return result;
    }
    
    // Test sparse switch (should use lookupswitch)
    static int testLookupSwitch(int x) {
        int result;
        switch (x) {
            case 1: result = 100; break;
            case 100: result = 200; break;
            case 1000: result = 300; break;
            case 10000: result = 400; break;
            default: result = -1; break;
        }
        return result;
    }
    
    // Test switch with gaps (may use either)
    static int testGapSwitch(int x) {
        int result;
        switch (x) {
            case 0: result = 1; break;
            case 5: result = 2; break;
            case 10: result = 3; break;
            default: result = 0; break;
        }
        return result;
    }
}
