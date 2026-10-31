package sqlike.shared;

import java.util.Optional;

public record ResourceId(
        ResourceScope scope,
        String tableName,
        Optional<RID> rid) {

    public ResourceId {
        rid = rid == null ? Optional.empty() : rid;
    }

    public static ResourceId ofTable(String tableName) {
        return new ResourceId(ResourceScope.TABLE, tableName, Optional.empty());
    }

    public static ResourceId ofRecord(String tableName, RID rid) {
        return new ResourceId(ResourceScope.RECORD, tableName, Optional.of(rid));
    }
}
