package sqlike.ccm;

import static sqlike.ccm.Check.rejects;
import static sqlike.ccm.Check.require;

import sqlike.shared.ProtocolType;

/** Config defaults and validation. */
public final class CCMConfigTest {

    public static void main(String[] args) throws Exception {
        Check check = new Check("CCMConfig");
        check.test("defaults are documented values", CCMConfigTest::defaults);
        check.test("copies change only their own setting", CCMConfigTest::copies);
        check.test("invalid settings are rejected", CCMConfigTest::invalid);
        check.done();
    }

    private static void defaults() {
        CCMConfig config = CCMConfig.defaults();
        require(config.protocol() == ProtocolType.LOCK_BASED, "default protocol is lock-based");
        require(config.deadlockTimeoutMs() == 5000L, "deadlock bound is 5 s");
        require(config.rollbackAttempts() == 3, "3 rollback attempts");
        require(config.rollbackRetryDelayMs() == 100L, "100 ms between attempts");
        require(config.applyDrainTimeoutMs() == 30_000L, "30 s apply drain timeout");
        require(new CCMConfig(ProtocolType.TIMESTAMP_BASED, 1000L).rollbackAttempts() == 3,
                "the two-argument constructor keeps the defaults");
    }

    private static void copies() {
        CCMConfig config = CCMConfig.defaults().withRollbackRetry(5, 10).withApplyDrainTimeoutMs(250);
        require(config.rollbackAttempts() == 5 && config.rollbackRetryDelayMs() == 10, "retry copy");
        require(config.applyDrainTimeoutMs() == 250, "drain copy");
        require(config.protocol() == ProtocolType.LOCK_BASED && config.deadlockTimeoutMs() == 5000L,
                "other settings are kept");
    }

    private static void invalid() throws Exception {
        rejects(IllegalArgumentException.class, () -> CCMConfig.defaults().withRollbackRetry(0, 10));
        rejects(IllegalArgumentException.class, () -> CCMConfig.defaults().withRollbackRetry(3, -1));
        rejects(IllegalArgumentException.class, () -> CCMConfig.defaults().withApplyDrainTimeoutMs(0));
    }
}
