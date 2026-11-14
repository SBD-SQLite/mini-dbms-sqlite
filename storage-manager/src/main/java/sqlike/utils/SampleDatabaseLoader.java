package sqlike.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sqlike.catalog.CatalogManager;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataType;
import sqlike.shared.ForeignKeySpec;
import sqlike.shared.InsertReservation;
import sqlike.shared.TableSchema;
import sqlike.shared.Value;
import sqlike.storage.StorageException;
import sqlike.storage.StorageManager;

public class SampleDatabaseLoader {
    public static final int CATEGORY_ROWS = 50;
    public static final int PRODUCT_ROWS = 120;
    public static final int CUSTOMER_ROWS = 80;
    public static final int ORDER_ROWS = 200;

    private static final String[] CITIES = {"Bandung", "Jakarta", "Surabaya", "Medan", "Makassar", "Yogyakarta", "Semarang", "Denpasar"};
    private static final String[] FIRST = {"Agus", "Budi", "Citra", "Dewi", "Eko", "Fitri", "Gita", "Hadi", "Indah", "Joko"};
    private static final String[] LAST = {"Santoso", "Wijaya", "Pratama", "Lestari", "Hidayat", "Saputra", "Kusuma", "Nugroho"};
    private static final String[] STATUS = {"NEW", "PAID", "SHIPPED", "DONE"};

    StorageManager storage;
    CatalogManager catalog;

    public SampleDatabaseLoader(StorageManager storage, CatalogManager catalog){
        this.storage = storage;
        this.catalog = catalog;
    }

    public StorageManager getStorage() {
        return storage;
    }

    public void setStorage(StorageManager storage) {
        this.storage = storage;
    }

    public CatalogManager getCatalog() {
        return catalog;
    }

    public void setCatalog(CatalogManager catalog) {
        this.catalog = catalog;
    }

    public void initialize() throws StorageException {
        for (TableSchema schema : sampleSchemas()) {
            if (!catalog.tableExists(schema.tableName())) storage.createTable(schema);
        }
    }

    public void loadSampleData() throws StorageException {
        Map<String, List<List<Value>>> rows = sampleRows();
        final long lsn = 1;
        for (TableSchema schema : sampleSchemas()) {
            if (storage.getStatistics(schema.tableName()).recordCount() > 0) continue;
            for (List<Value> row : rows.get(schema.tableName())) {
                InsertReservation reservation = storage.reserveInsert(schema.tableName(), row);
                storage.insert(reservation, lsn);
            }
        }
    }

    public void loadAtStartup() throws StorageException {
        initialize();
        loadSampleData();
    }

    public static List<TableSchema> sampleSchemas() {
        TableSchema categories = new TableSchema("tbl_categories", "categories", List.of(
                col("category_id", DataType.INTEGER, 0),
                col("name", DataType.VARCHAR, 40),
                col("code", DataType.CHAR, 4)),
                List.of("category_id"), List.of());
        TableSchema products = new TableSchema("tbl_products", "products", List.of(
                col("product_id", DataType.INTEGER, 0),
                col("name", DataType.VARCHAR, 50),
                col("category_id", DataType.INTEGER, 0),
                col("price", DataType.FLOAT, 0),
                col("stock", DataType.INTEGER, 0)),
                List.of("product_id"),
                List.of(new ForeignKeySpec(List.of("category_id"), "categories", List.of("category_id"))));
        TableSchema customers = new TableSchema("tbl_customers", "customers", List.of(
                col("customer_id", DataType.INTEGER, 0),
                col("name", DataType.VARCHAR, 50),
                col("city", DataType.VARCHAR, 30),
                col("tier", DataType.CHAR, 1)),
                List.of("customer_id"), List.of());
        TableSchema orders = new TableSchema("tbl_orders", "orders", List.of(
                col("order_id", DataType.INTEGER, 0),
                col("customer_id", DataType.INTEGER, 0),
                col("product_id", DataType.INTEGER, 0),
                col("quantity", DataType.INTEGER, 0),
                col("total", DataType.FLOAT, 0),
                col("status", DataType.VARCHAR, 10)),
                List.of("order_id"),
                List.of(new ForeignKeySpec(List.of("customer_id"), "customers", List.of("customer_id")),
                        new ForeignKeySpec(List.of("product_id"), "products", List.of("product_id"))));
        return List.of(categories, products, customers, orders);
    }

    public static Map<String, List<List<Value>>> sampleRows() {
        Map<String, List<List<Value>>> rows = new LinkedHashMap<>();

        List<List<Value>> categories = new ArrayList<>();
        for (int i = 1; i <= CATEGORY_ROWS; i++) {
            categories.add(List.of(i(i), s(DataType.VARCHAR, "Category " + i), s(DataType.CHAR, String.format("C%03d", i))));
        }
        rows.put("categories", categories);

        double[] prices = new double[PRODUCT_ROWS + 1];
        List<List<Value>> products = new ArrayList<>();
        for (int i = 1; i <= PRODUCT_ROWS; i++) {
            prices[i] = 5.0 + (i * 37 % 200) + (i % 4) * 0.25;
            products.add(List.of(i(i), s(DataType.VARCHAR, "Product " + i), i((i - 1) % CATEGORY_ROWS + 1), f(prices[i]), i(10 + i * 13 % 90)));
        }
        rows.put("products", products);

        List<List<Value>> customers = new ArrayList<>();
        for (int i = 1; i <= CUSTOMER_ROWS; i++) {
            customers.add(List.of(i(i), s(DataType.VARCHAR, FIRST[i % FIRST.length] + " " + LAST[i % LAST.length] + " " + i), s(DataType.VARCHAR, CITIES[i % CITIES.length]), s(DataType.CHAR, String.valueOf("GSB".charAt(i % 3)))));
        }
        rows.put("customers", customers);

        List<List<Value>> orders = new ArrayList<>();
        for (int i = 1; i <= ORDER_ROWS; i++) {
            int product = i * 7 % PRODUCT_ROWS + 1;
            int quantity = i % 5 + 1;
            orders.add(List.of(i(i), i(i * 11 % CUSTOMER_ROWS + 1), i(product), i(quantity), f(prices[product] * quantity), s(DataType.VARCHAR, STATUS[i % STATUS.length])));
        }
        rows.put("orders", orders);
        return rows;
    }

    private static ColumnSchema col(String name, DataType type, int length) {
        boolean text = type == DataType.CHAR || type == DataType.VARCHAR;
        return new ColumnSchema(name, type, text ? Optional.of(length) : Optional.empty());
    }

    private static Value i(int v) {
        return new Value(DataType.INTEGER, Optional.of(v), Optional.empty(), Optional.empty());
    }

    private static Value f(double v) {
        return new Value(DataType.FLOAT, Optional.empty(), Optional.of(v), Optional.empty());
    }

    private static Value s(DataType type, String v) {
        return new Value(type, Optional.empty(), Optional.empty(), Optional.of(v));
    }
}
