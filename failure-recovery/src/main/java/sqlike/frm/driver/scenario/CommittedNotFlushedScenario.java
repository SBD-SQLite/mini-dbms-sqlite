package sqlike.frm.driver.scenario;

import java.util.Map;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.frm.driver.TestScenario;
import sqlike.frm.port.SmPort;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;

/** A committed transaction whose changes never reached disk is redone after a crash. */
public class CommittedNotFlushedScenario extends TestScenario {

    public CommittedNotFlushedScenario() {
        super("committed-not-flushed");
    }

    @Override
    public void setup(IFailureRecoveryManager frm, SmPort sm) {
        // MOCK: no-op. TODO(FR1): load this scenario's initial data.
    }

    @Override
    public void run(IFailureRecoveryManager frm, SmPort sm) {
        // MOCK: TODO(FR1): run the transactions described above.
        throw new UnsupportedOperationException("scenario not implemented yet");
    }

    @Override
    public Map<RID, RecordImage> expected() {
        // MOCK: TODO(FR1): return the data expected after the scenario.
        return Map.of();
    }
}
