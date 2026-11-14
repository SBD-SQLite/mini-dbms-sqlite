package sqlike.qo;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import sqlike.contract.IStorageManager;
import sqlike.shared.*;

public final class DdlValidator {

    public PlanNode validate(PlanNode plan, IStorageManager storage) {
        if (plan == null) throw QoErrors.invalidValue("plan is null", 0);
        if (storage == null) throw new IllegalArgumentException("storage is required");
        return switch (plan.kind()) {
            case CREATE_TABLE -> {
                validateCreate(plan.createTable().orElseThrow(), storage);
                yield plan;
            }
            case DROP_TABLE -> PlanNodes.dropTable(validateDrop(plan.dropTable().orElseThrow(), storage));
            default -> throw QoErrors.invalidState("DdlValidator only handles CREATE_TABLE and DROP_TABLE, got " + plan.kind());
        };
    }

    private void validateCreate(CreateTableSpec spec, IStorageManager storage) {
        String table = spec.tableName();
        if (table == null || table.isBlank()) throw QoErrors.semantic("table name is empty");
        if (catalogName(storage, table).isPresent())
            throw QoErrors.semantic("table '" + table + "' already exists");

        List<ColumnSchema> columns = spec.columns();
        if (columns == null || columns.isEmpty())
            throw QoErrors.semantic("table '" + table + "' must have at least one column");
        Set<String> names = new HashSet<>();
        for (ColumnSchema column : columns) {
            if (column.name() == null || column.name().isBlank())
                throw QoErrors.semantic("column name is empty in table '" + table + "'");
            if (!names.add(key(column.name())))
                throw QoErrors.semantic("duplicate column '" + column.name() + "' in table '" + table + "'");
            checkLength(column);
        }

        Set<String> primaryKey = new HashSet<>();
        for (String name : spec.primaryKey()) {
            if (!names.contains(key(name)))
                throw QoErrors.semantic("primary key column '" + name + "' does not exist in table '" + table + "'");
            if (!primaryKey.add(key(name)))
                throw QoErrors.semantic("duplicate primary key column '" + name + "'");
        }

        for (ForeignKeySpec fk : spec.foreignKeys()) validateForeignKey(spec, fk, storage);
    }

    private static void checkLength(ColumnSchema column) {
        Optional<Integer> length = column.maxLength();
        switch (column.dataType()) {
            case CHAR, VARCHAR -> {
                if (length.isEmpty())
                    throw QoErrors.semantic(column.dataType() + " column '" + column.name() + "' requires a maximum length");
                if (length.get() <= 0)
                    throw QoErrors.semantic(column.dataType() + " column '" + column.name() + "' must have a maximum length greater than 0");
            }
            case INTEGER, FLOAT -> {
                if (length.isPresent())
                    throw QoErrors.semantic(column.dataType() + " column '" + column.name() + "' must not have a maximum length");
            }
        }
    }

    private void validateForeignKey(CreateTableSpec spec, ForeignKeySpec fk, IStorageManager storage) {
        String table = spec.tableName();
        for (String name : fk.columnNames()) {
            if (column(spec.columns(), name).isEmpty())
                throw QoErrors.semantic("foreign key column '" + name + "' does not exist in table '" + table + "'");
        }
        if (fk.columnNames().size() != fk.referencedColumns().size())
            throw QoErrors.semantic("foreign key on table '" + table + "' has " + fk.columnNames().size()
                    + " column(s) but references " + fk.referencedColumns().size());

        // The table being created may reference itself.
        List<ColumnSchema> referenced;
        if (fk.referencedTable().equalsIgnoreCase(table)) {
            referenced = spec.columns();
        } else {
            String actual = catalogName(storage, fk.referencedTable()).orElseThrow(() ->
                    QoErrors.semantic("foreign key references unknown table '" + fk.referencedTable() + "'"));
            referenced = storage.getSchema(actual).columns();
        }
        for (int i = 0; i < fk.columnNames().size(); i++) {
            ColumnSchema local = column(spec.columns(), fk.columnNames().get(i)).orElseThrow();
            String targetName = fk.referencedColumns().get(i);
            ColumnSchema target = column(referenced, targetName).orElseThrow(() ->
                    QoErrors.semantic("foreign key references unknown column '" + fk.referencedTable() + "." + targetName + "'"));
            if (local.dataType() != target.dataType())
                throw QoErrors.semantic("foreign key column '" + local.name() + "' (" + local.dataType()
                        + ") does not match referenced column '" + fk.referencedTable() + "." + target.name()
                        + "' (" + target.dataType() + ")");
        }
    }

    private DropTableSpec validateDrop(DropTableSpec spec, IStorageManager storage) {
        String actual = catalogName(storage, spec.tableName()).orElseThrow(() ->
                QoErrors.semantic("unknown table '" + spec.tableName() + "'"));
        if (spec.behavior() == DropBehavior.RESTRICT) {
            for (String other : storage.listTables()) {
                if (other.equalsIgnoreCase(actual)) continue; // a self-reference does not block the drop
                for (ForeignKeySpec fk : storage.getSchema(other).foreignKeys()) {
                    if (fk.referencedTable().equalsIgnoreCase(actual))
                        throw QoErrors.semantic("cannot drop table '" + actual + "': referenced by a foreign key of table '"
                                + other + "' (use CASCADE)");
                }
            }
        }
        return new DropTableSpec(actual, spec.behavior());
    }

    private static Optional<String> catalogName(IStorageManager storage, String table) {
        return storage.listTables().stream().filter(name -> name.equalsIgnoreCase(table)).findFirst();
    }

    private static Optional<ColumnSchema> column(List<ColumnSchema> columns, String name) {
        return columns.stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst();
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
}
