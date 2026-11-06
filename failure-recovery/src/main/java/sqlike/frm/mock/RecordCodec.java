package sqlike.frm.mock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import sqlike.shared.DataType;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.Value;

/**
 * Text encoding used by {@link MockStorageManager} for table files and fixtures.
 *
 * <p>Table file line: {@code pageNumber<TAB>slotId<TAB>value<TAB>value...}. A value is
 * {@code I:<int>}, {@code F:<double>}, {@code C:<base64>}, or {@code V:<base64>}, so any text
 * survives the round trip. Fixture lines are easier to edit by hand; see {@link #parseFixtureValue}.
 */
public final class RecordCodec {

    /** Orders RIDs like a scan: by table, page, then slot. */
    public static final Comparator<RID> RID_ORDER = Comparator
            .comparing((RID rid) -> rid.pageId().tableId())
            .thenComparingLong(rid -> rid.pageId().pageNumber())
            .thenComparingInt(RID::slotId);

    private RecordCodec() {
    }

    public static String encodeLine(RID rid, List<Value> values) {
        StringBuilder line = new StringBuilder();
        line.append(rid.pageId().pageNumber()).append('\t').append(rid.slotId());
        for (Value value : values) {
            line.append('\t').append(encodeValue(value));
        }
        return line.toString();
    }

    public static RID decodeRid(String table, String line) {
        String[] parts = line.split("\t", -1);
        return new RID(new PageId(table, Long.parseLong(parts[0])), Integer.parseInt(parts[1]));
    }

    public static List<Value> decodeValues(String line) {
        String[] parts = line.split("\t", -1);
        List<Value> values = new ArrayList<>();
        for (int i = 2; i < parts.length; i++) {
            values.add(decodeValue(parts[i]));
        }
        return values;
    }

    public static String encodeValue(Value value) {
        return switch (value.type()) {
            case INTEGER -> "I:" + value.integerValue().orElseThrow();
            case FLOAT -> "F:" + value.floatValue().orElseThrow();
            case CHAR -> "C:" + base64(value.textValue().orElseThrow());
            case VARCHAR -> "V:" + base64(value.textValue().orElseThrow());
        };
    }

    public static Value decodeValue(String encoded) {
        if (encoded.length() < 2 || encoded.charAt(1) != ':') {
            throw new IllegalArgumentException("bad encoded value: " + encoded);
        }
        String body = encoded.substring(2);
        return switch (encoded.charAt(0)) {
            case 'I' -> integer(Integer.parseInt(body));
            case 'F' -> floating(Double.parseDouble(body));
            case 'C' -> fixedChar(unbase64(body));
            case 'V' -> varchar(unbase64(body));
            default -> throw new IllegalArgumentException("unknown value type: " + encoded);
        };
    }

    /** Fixture value: {@code INTEGER 7}, {@code FLOAT 2.5}, {@code CHAR AB}, {@code VARCHAR Alice}. */
    public static Value parseFixtureValue(String text) {
        String trimmed = text.trim();
        int space = trimmed.indexOf(' ');
        String type = space < 0 ? trimmed : trimmed.substring(0, space);
        String body = space < 0 ? "" : trimmed.substring(space + 1);
        return switch (DataType.valueOf(type.toUpperCase())) {
            case INTEGER -> integer(Integer.parseInt(body.trim()));
            case FLOAT -> floating(Double.parseDouble(body.trim()));
            case CHAR -> fixedChar(body);
            case VARCHAR -> varchar(body);
        };
    }

    public static Value integer(int value) {
        return new Value(DataType.INTEGER, Optional.of(value), Optional.empty(), Optional.empty());
    }

    public static Value floating(double value) {
        return new Value(DataType.FLOAT, Optional.empty(), Optional.of(value), Optional.empty());
    }

    public static Value fixedChar(String value) {
        return new Value(DataType.CHAR, Optional.empty(), Optional.empty(), Optional.of(value));
    }

    public static Value varchar(String value) {
        return new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of(value));
    }

    /** Short display form, e.g. {@code [1, 'Alice', 100.0]}. */
    public static String show(List<Value> values) {
        List<String> shown = new ArrayList<>();
        for (Value value : values) {
            shown.add(switch (value.type()) {
                case INTEGER -> String.valueOf(value.integerValue().orElseThrow());
                case FLOAT -> String.valueOf(value.floatValue().orElseThrow());
                case CHAR, VARCHAR -> "'" + value.textValue().orElseThrow() + "'";
            });
        }
        return shown.toString();
    }

    public static String show(RID rid) {
        return rid.pageId().tableId() + "(" + rid.pageId().pageNumber() + "," + rid.slotId() + ")";
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String unbase64(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }
}
