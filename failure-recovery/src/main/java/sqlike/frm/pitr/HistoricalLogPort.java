package sqlike.frm.pitr;

import sqlike.frm.port.SmPort;
import sqlike.shared.RecoveryTarget;

/** Historical-log operations required by point-in-time recovery. */
public interface HistoricalLogPort {

    /** Resolves a validated user target to a base snapshot and inclusive WAL boundary. */
    HistoricalRecoveryPlan planFor(RecoveryTarget target);

    /** Replays committed changes after the snapshot through the plan's target LSN. */
    void replay(HistoricalRecoveryPlan plan, SmPort storageManager);
}
