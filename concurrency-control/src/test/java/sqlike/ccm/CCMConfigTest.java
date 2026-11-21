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
        check.test("the deadlock bound sets the wait-for graph check interval", CCMConfigTest::deadlockInterval);
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

    private static void deadlockInterval() {
        require(CCMConfig.defaults().deadlockCheckIntervalMs() == 100L, "the default bound checks every 100 ms");
        require(new CCMConfig(ProtocolType.LOCK_BASED, 500L).deadlockCheckIntervalMs() == 50L, "500 ms bound checks every 50 ms");
        require(new CCMConfig(ProtocolType.LOCK_BASED, 20L).deadlockCheckIntervalMs() == 10L, "never faster than 10 ms");
        require(new CCMConfig(ProtocolType.LOCK_BASED, 600_000L).deadlockCheckIntervalMs() == 100L, "never slower than 100 ms");
    }

    private static void invalid() throws Exception {
        rejects(IllegalArgumentException.class, () -> CCMConfig.defaults().withRollbackRetry(0, 10));
        rejects(IllegalArgumentException.class, () -> CCMConfig.defaults().withRollbackRetry(3, -1));
        rejects(IllegalArgumentException.class, () -> CCMConfig.defaults().withApplyDrainTimeoutMs(0));
    }
}
