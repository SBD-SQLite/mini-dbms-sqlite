package sqlike.shared;
import java.util.List;
import java.util.HashSet;

public record SchemaChange(List<SchemaImage> before, List<SchemaImage> after) {
    public SchemaChange {
        before = List.copyOf(before);
        after = List.copyOf(after);
        if (before.isEmpty() || before.size() != after.size()) throw new IllegalArgumentException("Schema image pairs required");
        var names = new HashSet<String>();
        for (int i = 0; i < before.size(); i++) {
            String name = before.get(i).tableName();
            if (!name.equals(after.get(i).tableName()) || !names.add(name)) throw new IllegalArgumentException("Schema pairs must have matching unique names");
        }
    }
}