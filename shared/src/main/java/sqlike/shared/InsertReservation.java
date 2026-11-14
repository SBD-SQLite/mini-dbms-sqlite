package sqlike.shared;

import java.util.List;

public record InsertReservation(
        String reservationId,
        String tableName,
        RID rid,
        List<Value> values) {
    public InsertReservation {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
