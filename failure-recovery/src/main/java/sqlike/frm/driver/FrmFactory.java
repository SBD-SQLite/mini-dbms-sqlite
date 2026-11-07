package sqlike.frm.driver;

import java.nio.file.Path;
import java.time.Duration;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.frm.FailureRecoveryManager;
import sqlike.frm.FrmConfig;
import sqlike.frm.mock.MockCCM;
import sqlike.frm.mock.MockStorageManager;

/**
 * Builds the FRM under test for one process. The driver and every child process use the same
 * factory, chosen with {@code -Dfrm.factory=<class name>}; the default is the real facade.
 */
public interface FrmFactory {

    /** System property naming another factory class (needs a public no-argument constructor). */
    String PROPERTY = "frm.factory";

    IFailureRecoveryManager create(MockStorageManager sm, MockCCM ccm, Path dataDir, Duration idleTimeout);

    /** Factory selected by {@link #PROPERTY}, or {@link Default}. */
    static FrmFactory selected() {
        String className = System.getProperty(PROPERTY);
        if (className == null || className.isBlank()) {
            return new Default();
        }
        try {
            return (FrmFactory) Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException error) {
            throw new IllegalArgumentException("cannot use FRM factory " + className, error);
        }
    }

    /** The real {@link FailureRecoveryManager}; the periodic timer is off so tests stay deterministic. */
    final class Default implements FrmFactory {
        @Override
        public IFailureRecoveryManager create(
                MockStorageManager sm, MockCCM ccm, Path dataDir, Duration idleTimeout) {
            FrmConfig config = new FrmConfig(dataDir.resolve("wal.log"), Duration.ZERO, idleTimeout);
            return new FailureRecoveryManager(sm, ccm, config);
        }
    }
}
