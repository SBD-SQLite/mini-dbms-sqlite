package sqlike.frm.driver.scenario;

import java.util.Map;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.frm.driver.TestScenario;
import sqlike.frm.port.SmPort;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;

/** An unfinished transaction whose changes already reached disk is undone after a crash. */
public class UncommittedFlushedScenario extends TestScenario {

    public UncommittedFlushedScenario() {
        super("uncommitted-flushed");
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
