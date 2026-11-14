package sqlike.frm.log;

public final class WalTests {
    public static void main(String[] args) {
        LogManagerTests.runAll();
        WalFormatTests.runAll();
        System.out.printf("%nFR2: %d passed, %d failed%n", WalTestSupport.passed, WalTestSupport.failed);
        if (WalTestSupport.failed != 0) {
            System.exit(1);
        }
    }
}
