package sqlike.ccm;

import java.util.Locale;
import java.util.Optional;

import sqlike.shared.RID;
import sqlike.shared.ResourceId;
import sqlike.shared.ResourceScope;

/**
 * CCM-side helpers for building and recognizing resources.
 *
 * <p>The shared {@link ResourceId} only holds data, so CCM builds its resources here and does not
 * depend on factory methods or on a CATALOG scope in the shared module. The catalog is a reserved
 * table name; a scope named CATALOG is also recognized in case the shared module adds one.
 */
final class Resources {

    static final String CATALOG_NAME = "__catalog__";

    private Resources() {
    }

    static ResourceId table(String name) {
        return new ResourceId(ResourceScope.TABLE, name, Optional.empty());
    }

    static ResourceId record(String name, RID rid) {
        return new ResourceId(ResourceScope.RECORD, name, Optional.of(rid));
    }

    static ResourceId catalog() {
        return table(CATALOG_NAME);
    }

    static boolean isCatalog(ResourceId resource) {
        return "CATALOG".equals(resource.scope().name())
                || CATALOG_NAME.equals(resource.tableName().toLowerCase(Locale.ROOT));
    }
}
