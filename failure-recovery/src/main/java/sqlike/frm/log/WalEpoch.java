package sqlike.frm.log;
import java.util.Optional;

public record WalEpoch(long baseLSN, long targetLSN, long maxTxId, Optional<String> snapshotId, boolean isActive){}