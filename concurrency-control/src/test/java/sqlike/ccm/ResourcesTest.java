package sqlike.ccm;

import static sqlike.ccm.Check.require;

import java.util.Optional;

import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.ResourceId;
import sqlike.shared.ResourceScope;

/** Checks for the CCM-side resource helpers. */
public final class ResourcesTest {

    public static void main(String[] args) throws Exception {
        Check check = new Check("Resources");
        check.test("table and record resources carry the right scope", ResourcesTest::builders);
        check.test("the catalog is the reserved table name", ResourcesTest::catalog);
        check.test("only the reserved name is recognized as the catalog", ResourcesTest::recognition);
        check.done();
    }

    private static void builders() {
        ResourceId table = Resources.table("accounts");
        require(table.scope() == ResourceScope.TABLE && table.rid().isEmpty(), "a table resource has no RID");
        require(table.tableName().equals("accounts"), "the table name is kept");

        RID rid = new RID(new PageId("accounts", 0L), 3);
        ResourceId record = Resources.record("accounts", rid);
        require(record.scope() == ResourceScope.RECORD, "a record resource has the RECORD scope");
        require(record.rid().equals(Optional.of(rid)), "the record RID is kept");
    }

    private static void catalog() {
        require(Resources.catalog().equals(Resources.table("__catalog__")), "the catalog is a reserved table name");
        require(Resources.catalog().rid().isEmpty(), "the catalog has no RID");
    }

    private static void recognition() {
        require(Resources.isCatalog(Resources.catalog()), "the catalog is recognized");
        require(Resources.isCatalog(Resources.table("__CATALOG__")), "recognition ignores case");
        require(!Resources.isCatalog(Resources.table("accounts")), "a normal table is not the catalog");
        require(!Resources.isCatalog(Resources.record("accounts", new RID(new PageId("accounts", 0L), 0))),
                "a record is not the catalog");
    }
}
