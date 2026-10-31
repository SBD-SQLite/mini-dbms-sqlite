package sqlike.frm.Log;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import sqlike.shared.DataChange;

// CheckpointInfo belum ada
public record LogRecord(long lsn, Instant time, Optional<Long> txID, LogType type, Optional<DataChange> change, Optional<CheckpointInfo> checkpoint){
    public LogRecord{
        if (lsn <= 0) throw new IllegalArgumentException("LSN harus positif!");

        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(txID, "txId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(checkpoint, "checkpoint");

        if (txID.isPresent() && txID.get() <= 0) {
            throw new IllegalArgumentException("ID transaksi harus positif!");
        }

        switch (type){
            case BEGIN, COMMIT, ABORT ->{
                if (txID.isEmpty()|| change.isPresent() || checkpoint.isPresent()) {
                    throw new IllegalArgumentException("LogRecord untuk BEGIN, COMMIT, dan ABORT harus memiliki txID dan tidak boleh memiliki change atau checkpoint.");
                }
            }
            case INSERT, UPDATE, DELETE ->{
                if (txID.isEmpty() || change.isEmpty() || checkpoint.isPresent()) {
                    throw new IllegalArgumentException("LogRecord untuk INSERT, UPDATE, dan DELETE harus memiliki txID dan change, dan tidak boleh memiliki checkpoint.");
                }

                LogType expectedType = 
                    switch (change.orElseThrow().operation()) {
                        case INSERT -> LogType.INSERT;
                        case UPDATE -> LogType.UPDATE;
                        case DELETE -> LogType.DELETE;
                    };

                if (type != expectedType) {
                    throw new IllegalArgumentException("LogRecord type tidak sesuai dengan operasi pada DataChange.");
                }
            }
            case CHECKPOINT ->{
                if (txID.isPresent() || change.isPresent() || checkpoint.isEmpty()) {
                    throw new IllegalArgumentException("LogRecord untuk CHECKPOINT harus memiliki checkpoint dan tidak boleh memiliki txID atau change.");
                }
                if (checkpoint.orElseThrow().checkpointLSN() != lsn){
                    throw new IllegalArgumentException("LSN pada LogRecord CHECKPOINT harus sama dengan checkpointLSN.");
                }
            }
        }
    }
}