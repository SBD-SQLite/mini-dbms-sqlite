package sqlike.qp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.LongSupplier;
import sqlike.contract.IConcurrencyControlManager;
import sqlike.contract.IFailureRecoveryManager;
import sqlike.contract.IQueryOptimizer;
import sqlike.contract.IStorageManager;
import sqlike.contract.IWalDurability;
import sqlike.shared.ComponentType;
import sqlike.shared.DropBehavior;
import sqlike.shared.ErrorCode;
import sqlike.shared.IndexMetadata;
import sqlike.shared.ProtocolType;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.shared.RecordImage;
import sqlike.shared.SchemaImage;
import sqlike.shared.TableSchema;
import sqlike.storage.StorageException;

/**
 * Production SQLike mDBMS server entrypoint wiring all 5 real Supergroup components
 * (Query Processor, Query Optimizer, Storage Manager, Concurrency Control Manager,
 * and Failure Recovery Manager) through {@link QpBootstrap#forComponents}.
 *
 * <p>Compiles strictly against {@code sqlike.contract.*} and {@code sqlike.shared.*} so
 * isolated component test scripts (including {@code concurrency-control/run-qp-integration.sh}
 * and {@code query-processor/run-tests.sh}) remain 100% build-safe, while instantiating the
 * concrete provider classes at runtime.
 *
 * <p>Usage:
 * <pre>
 *   java sqlike.qp.QpServerMain [--data DIR] [--port N] [--protocol lock|timestamp]
 *       [--max-clients N] [--join-buffer BYTES] [--index HASH|BPLUS_TREE]
 *       [--buffer-frames N] [--temp DIR] [--no-sample]
 * </pre>
 */
public final class QpServerMain {
    private static final int DEFAULT_BUFFER_FRAMES = 16;
    private static final long DEFAULT_DEADLOCK_TIMEOUT_MS = 5000L;

    private QpServerMain() {
    }

    public static final class RunningServer implements AutoCloseable {
        private final Path dataDir;
        private final Object blockManager;
        private final IStorageManager storage;
        private final IFailureRecoveryManager recovery;
        private final IConcurrencyControlManager concurrency;
        private final IQueryOptimizer optimizer;
        private final QueryProcessor processor;
        private final QueryServer server;
        private final QpBootstrap bootstrap;
        private final ProtocolType protocol;
        private final String protocolDescription;
        private volatile boolean closed;

        RunningServer(
                Path dataDir,
                Object blockManager,
                IStorageManager storage,
                IFailureRecoveryManager recovery,
                IConcurrencyControlManager concurrency,
                IQueryOptimizer optimizer,
                QueryProcessor processor,
                QueryServer server,
                QpBootstrap bootstrap,
                ProtocolType protocol,
                String protocolDescription) {
            this.dataDir = dataDir;
            this.blockManager = blockManager;
            this.storage = storage;
            this.recovery = recovery;
            this.concurrency = concurrency;
            this.optimizer = optimizer;
            this.processor = processor;
            this.server = server;
            this.bootstrap = bootstrap;
            this.protocol = protocol;
            this.protocolDescription = protocolDescription;
        }

        public Path dataDir() { return dataDir; }
        public IStorageManager storage() { return storage; }
        public IFailureRecoveryManager recovery() { return recovery; }
        public IConcurrencyControlManager concurrency() { return concurrency; }
        public IQueryOptimizer optimizer() { return optimizer; }
        public QueryProcessor processor() { return processor; }
        public QueryServer server() { return server; }
        public QpBootstrap bootstrap() { return bootstrap; }
        public ProtocolType protocol() { return protocol; }
        public String describeProtocol() { return protocolDescription; }
        public int boundPort() { return server.getBoundPort(); }

        /** Simulates an abrupt crash without running graceful shutdown or aborting active transactions. */
        public void simulateCrash() {
            closed = true;
            try { server.stop(); } catch (RuntimeException ignored) {}
            try { invokeQuietly(recovery, "close"); } catch (RuntimeException ignored) {}
            try { invokeQuietly(blockManager, "close"); } catch (RuntimeException ignored) {}
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try { bootstrap.stop(); } catch (RuntimeException ignored) {}
            try { storage.close(); } catch (RuntimeException ignored) {}
            try { invokeQuietly(recovery, "close"); } catch (RuntimeException ignored) {}
        }
    }

    public static ProtocolType parseProtocol(String raw) {
        if (raw == null || raw.isBlank()) {
            return ProtocolType.LOCK_BASED;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "lock", "lock_based", "lock-based", "2pl" -> ProtocolType.LOCK_BASED;
            case "timestamp", "timestamp_based", "timestamp-based", "ts", "to" -> ProtocolType.TIMESTAMP_BASED;
            default -> throw new IllegalArgumentException(
                    "Unknown protocol '" + raw + "'. Expected: lock | timestamp (or LOCK_BASED | TIMESTAMP_BASED)");
        };
    }

    public static RunningServer start(
            Path rootDir,
            QpConfiguration config,
            ProtocolType protocol,
            boolean loadSampleIfEmpty) {
        return start(rootDir, config, protocol, loadSampleIfEmpty, DEFAULT_BUFFER_FRAMES);
    }

    public static RunningServer start(
            Path rootDir,
            QpConfiguration config,
            ProtocolType protocol,
            boolean loadSampleIfEmpty,
            int bufferFrames) {
        Objects.requireNonNull(rootDir, "rootDir cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        ProtocolType effectiveProtocol = protocol != null ? protocol : ProtocolType.LOCK_BASED;
        if (bufferFrames <= 0) {
            throw new IllegalArgumentException("bufferFrames must be positive: " + bufferFrames);
        }

        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create data directory " + rootDir, e);
        }

        try {
            ClassLoader cl = QpServerMain.class.getClassLoader();
            Class<?> blockManagerClass = Class.forName("sqlike.block.BlockManager", true, cl);
            Class<?> catalogManagerClass = Class.forName("sqlike.catalog.CatalogManager", true, cl);
            Class<?> smWalClass = Class.forName("sqlike.contract.IWalDurability", true, cl);
            Class<?> replacementPolicyClass = Class.forName("sqlike.replacement_durability.ReplacementPolicy", true, cl);
            Class<?> lruPolicyClass = Class.forName("sqlike.replacement_durability.LRUReplacementPolicy", true, cl);
            Class<?> bufferPoolClass = Class.forName("sqlike.buffer.BufferPoolManager", true, cl);
            Class<?> statsManagerClass = Class.forName("sqlike.statistics.StatisticsManager", true, cl);
            Class<?> serializerClass = Class.forName("sqlike.record.RecordSerializer", true, cl);
            Class<?> recordManagerClass = Class.forName("sqlike.record.RecordManager", true, cl);
            Class<?> indexManagerClass = Class.forName("sqlike.index.IndexManager", true, cl);
            Class<?> storageManagerClass = Class.forName("sqlike.storage.StorageManager", true, cl);
            Class<?> sampleLoaderClass = Class.forName("sqlike.utils.SampleDatabaseLoader", true, cl);
            Class<?> ccmPortClass = Class.forName("sqlike.frm.port.CcmPort", true, cl);
            Class<?> ccmAdapterClass = Class.forName("sqlike.frm.adapter.ConcurrencyControlAdapter", true, cl);
            Class<?> smPortClass = Class.forName("sqlike.frm.port.SmPort", true, cl);
            Class<?> frmClass = Class.forName("sqlike.frm.FailureRecoveryManager", true, cl);
            Class<?> frmExceptionClass = Class.forName("sqlike.frm.FrmException", true, cl);
            Class<?> ccmConfigClass = Class.forName("sqlike.ccm.CCMConfig", true, cl);
            Class<?> ccmClass = Class.forName("sqlike.ccm.ConcurrencyControlManager", true, cl);
            Class<?> qoClass = Class.forName("sqlike.qo.QueryOptimizer", true, cl);

            Object blockManager = blockManagerClass.getConstructor(Path.class).newInstance(rootDir.resolve("data"));
            Object catalog = catalogManagerClass.getConstructor(Path.class).newInstance(rootDir.resolve("catalog.dat"));
            catalogManagerClass.getMethod("load").invoke(catalog);

            Object lruPolicy = lruPolicyClass.getConstructor().newInstance();
            IWalDurability[] walHolder = new IWalDurability[1];
            Object smWalProxy = Proxy.newProxyInstance(cl, new Class<?>[]{smWalClass}, (proxy, method, methodArgs) -> {
                if ("ensureDurable".equals(method.getName()) && methodArgs != null && methodArgs.length == 1) {
                    long lsn = ((Number) methodArgs[0]).longValue();
                    if (walHolder[0] != null && lsn > 0) {
                        walHolder[0].ensureDurable(lsn);
                    }
                    return null;
                }
                return handleObjectMethod(proxy, method, methodArgs);
            });

            Object bufferPool = bufferPoolClass
                    .getConstructor(blockManagerClass, smWalClass, replacementPolicyClass, int.class)
                    .newInstance(blockManager, smWalProxy, lruPolicy, bufferFrames);
            Object stats = statsManagerClass
                    .getConstructor(catalogManagerClass, blockManagerClass)
                    .newInstance(catalog, blockManager);
            Object serializer = serializerClass.getConstructor().newInstance();
            Object records = recordManagerClass
                    .getConstructor(bufferPoolClass, serializerClass, catalogManagerClass)
                    .newInstance(bufferPool, serializer, catalog);

            Object[] frmHolder = new Object[1];
            Method readLogsMethod = frmClass.getMethod("readLogs", Optional.class);
            LongSupplier buildLsnSupplier = () -> {
                Object f = frmHolder[0];
                if (f != null) {
                    try {
                        List<?> logs = (List<?>) readLogsMethod.invoke(f, Optional.empty());
                        if (logs != null && !logs.isEmpty()) {
                            Object last = logs.get(logs.size() - 1);
                            long lsn = ((Number) last.getClass().getMethod("lsn").invoke(last)).longValue();
                            return Math.max(1L, lsn);
                        }
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
                return 1L;
            };

            Object indexes = indexManagerClass
                    .getConstructor(catalogManagerClass, recordManagerClass, bufferPoolClass, serializerClass, LongSupplier.class)
                    .newInstance(catalog, records, bufferPool, serializer, buildLsnSupplier);
            IStorageManager realSm = (IStorageManager) storageManagerClass
                    .getConstructor(recordManagerClass, catalogManagerClass, statsManagerClass, indexManagerClass, bufferPoolClass)
                    .newInstance(records, catalog, stats, indexes, bufferPool);
            storageManagerClass.getMethod("setBlockManager", blockManagerClass).invoke(realSm, blockManager);

            // Load sample database strictly offline on a fresh/empty database (PRD §14 & §16, SM-14).
            if (loadSampleIfEmpty && realSm.listTables().isEmpty()) {
                Object loader = sampleLoaderClass
                        .getConstructor(storageManagerClass, catalogManagerClass)
                        .newInstance(realSm, catalog);
                sampleLoaderClass.getMethod("loadAtStartup").invoke(loader);
                realSm.flushAll();
            }

            IConcurrencyControlManager[] ccmHolder = new IConcurrencyControlManager[1];
            Object ccmPortProxy = Proxy.newProxyInstance(cl, new Class<?>[]{ccmPortClass}, (proxy, method, methodArgs) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return handleObjectMethod(proxy, method, methodArgs);
                }
                IConcurrencyControlManager targetCcm = ccmHolder[0];
                if (targetCcm == null) {
                    return "waitUntilIdle".equals(method.getName()) ? Boolean.TRUE : null;
                }
                Object adapter = ccmAdapterClass.getConstructor(IConcurrencyControlManager.class).newInstance(targetCcm);
                return invokeUnwrapped(adapter, method.getName(), method.getParameterTypes(), methodArgs);
            });

            Object smPortProxy = createSmPortProxy(
                    cl, smPortClass, frmExceptionClass, realSm, catalog, indexes, bufferPool, blockManager);

            IFailureRecoveryManager realFrm = (IFailureRecoveryManager) frmClass
                    .getConstructor(smPortClass, ccmPortClass, String.class)
                    .newInstance(smPortProxy, ccmPortProxy, rootDir.resolve("wal").resolve("wal.log").toString());
            frmHolder[0] = realFrm;
            walHolder[0] = realFrm::ensureDurable;

            Object ccmConfig = ccmConfigClass
                    .getConstructor(ProtocolType.class, long.class)
                    .newInstance(effectiveProtocol, DEFAULT_DEADLOCK_TIMEOUT_MS);
            IConcurrencyControlManager realCcm = (IConcurrencyControlManager) ccmClass
                    .getConstructor(IFailureRecoveryManager.class, ccmConfigClass)
                    .newInstance(realFrm, ccmConfig);
            ccmHolder[0] = realCcm;
            String protocolDesc = String.valueOf(ccmClass.getMethod("describeProtocol").invoke(realCcm));

            IQueryOptimizer realQo = (IQueryOptimizer) qoClass
                    .getConstructor(IStorageManager.class)
                    .newInstance(realSm);

            QpBootstrap bootstrap = QpBootstrap.forComponents(realQo, realSm, realCcm, realFrm, config);
            bootstrap.start();

            // Ensure all persisted indexes are loaded and consistent after startup recovery (PRD §14).
            for (String tbl : realSm.listTables()) {
                if (!realSm.getIndexes(tbl).isEmpty()) {
                    invokeUnwrapped(indexes, "rebuildTable", new Class<?>[]{String.class}, new Object[]{tbl});
                }
            }

            return new RunningServer(
                    rootDir, blockManager, realSm, realFrm, realCcm, realQo,
                    bootstrap.getProcessor(), bootstrap.getServer(), bootstrap,
                    effectiveProtocol, protocolDesc);
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            if (target instanceof RuntimeException re) throw re;
            throw new IllegalStateException("Failed to start SQLike server: " + target.getMessage(), target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Real DBMS component classes are missing from classpath: " + e.getMessage(), e);
        }
    }

    private static Object createSmPortProxy(
            ClassLoader cl,
            Class<?> smPortClass,
            Class<?> frmExceptionClass,
            IStorageManager realSm,
            Object catalog,
            Object indexes,
            Object bufferPool,
            Object blockManager) {
        return Proxy.newProxyInstance(cl, new Class<?>[]{smPortClass}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, method, args);
            }
            try {
                return switch (method.getName()) {
                    case "readAt" -> {
                        String table = (String) args[0];
                        RID rid = (RID) args[1];
                        Optional<Record> record = realSm.fetch(table, rid);
                        yield record
                                .map(found -> new RecordImage(true, List.copyOf(found.values())))
                                .orElseGet(() -> new RecordImage(false, List.of()));
                    }
                    case "writeAt" -> {
                        String table = (String) args[0];
                        RID rid = (RID) args[1];
                        RecordImage image = (RecordImage) args[2];
                        long lsn = ((Number) args[3]).longValue();
                        boolean exists = (boolean) invokeUnwrapped(catalog, "tableExists", new Class<?>[]{String.class}, new Object[]{table});
                        if (!exists) {
                            yield null;
                        }
                        realSm.restoreRecord(table, rid, image, lsn);
                        if (!realSm.getIndexes(table).isEmpty()) {
                            invokeUnwrapped(indexes, "rebuildTable", new Class<?>[]{String.class}, new Object[]{table});
                        }
                        yield null;
                    }
                    case "restoreSchema" -> {
                        SchemaImage image = (SchemaImage) args[0];
                        restoreSchemaOnRealSm(image, realSm, catalog, indexes, bufferPool, blockManager);
                        yield null;
                    }
                    case "flushAll" -> {
                        realSm.flushAll();
                        yield null;
                    }
                    case "createSnapshot" -> {
                        realSm.flushAll();
                        yield "snapshot-" + System.currentTimeMillis();
                    }
                    case "restoreSnapshot" -> {
                        invokeUnwrapped(realSm, "onSnapshotRestored", new Class<?>[0], new Object[0]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException("Unsupported SmPort method: " + method.getName());
                };
            } catch (StorageException error) {
                throw wrapAsFrmException(frmExceptionClass, method.getName(), error);
            }
        });
    }

    private static void restoreSchemaOnRealSm(
            SchemaImage image,
            IStorageManager realSm,
            Object catalog,
            Object indexes,
            Object bufferPool,
            Object blockManager) throws ReflectiveOperationException {
        if (image == null) {
            return;
        }
        String tableName = image.tableName();
        boolean exists = (boolean) invokeUnwrapped(catalog, "tableExists", new Class<?>[]{String.class}, new Object[]{tableName});
        if (image.schema().isEmpty()) {
            if (exists) {
                for (IndexMetadata idx : List.copyOf(realSm.getIndexes(tableName))) {
                    invokeUnwrapped(catalog, "unregisterIndex", new Class<?>[]{String.class}, new Object[]{idx.indexName()});
                    invokeUnwrapped(bufferPool, "truncateTable", new Class<?>[]{String.class}, new Object[]{"idx_" + idx.indexName()});
                    invokeUnwrapped(blockManager, "deleteFile", new Class<?>[]{String.class}, new Object[]{"idx_" + idx.indexName()});
                }
                realSm.dropTable(tableName, DropBehavior.CASCADE);
            }
            return;
        }
        TableSchema targetSchema = image.schema().get();
        boolean targetExists = (boolean) invokeUnwrapped(
                catalog, "tableExists", new Class<?>[]{String.class}, new Object[]{targetSchema.tableName()});
        if (targetSchema.tableId() == null || targetSchema.tableId().isBlank()) {
            String id = targetExists
                    ? realSm.getSchema(targetSchema.tableName()).tableId()
                    : (String) invokeUnwrapped(catalog, "generateTableId", new Class<?>[]{String.class}, new Object[]{targetSchema.tableName()});
            targetSchema = new TableSchema(
                    id, targetSchema.tableName(), targetSchema.columns(),
                    targetSchema.primaryKey(), targetSchema.foreignKeys());
        }
        if (!targetExists) {
            invokeUnwrapped(catalog, "registerTable", new Class<?>[]{TableSchema.class}, new Object[]{targetSchema});
        } else {
            TableSchema existing = realSm.getSchema(targetSchema.tableName());
            if (!existing.tableId().equals(targetSchema.tableId())) {
                targetSchema = new TableSchema(
                        existing.tableId(), targetSchema.tableName(), targetSchema.columns(),
                        targetSchema.primaryKey(), targetSchema.foreignKeys());
            }
            invokeUnwrapped(catalog, "replaceSchema", new Class<?>[]{TableSchema.class}, new Object[]{targetSchema});
        }
        for (IndexMetadata wantedIdx : image.indexes()) {
            boolean idxExists = realSm.getIndexes(targetSchema.tableName()).stream()
                    .anyMatch(e -> e.indexName().equalsIgnoreCase(wantedIdx.indexName()));
            if (!idxExists) {
                invokeUnwrapped(catalog, "registerIndex", new Class<?>[]{IndexMetadata.class}, new Object[]{wantedIdx});
            }
        }
        invokeUnwrapped(catalog, "save", new Class<?>[0], new Object[0]);
        if (!image.indexes().isEmpty()) {
            invokeUnwrapped(indexes, "rebuildTable", new Class<?>[]{String.class}, new Object[]{targetSchema.tableName()});
        }
    }

    private static RuntimeException wrapAsFrmException(
            Class<?> frmExceptionClass,
            String operation,
            StorageException error) {
        ErrorCode code = error.getCode() == null ? ErrorCode.IO_ERROR : switch (error.getCode()) {
            case TABLE_NOT_FOUND, RECORD_NOT_FOUND, INDEX_NOT_FOUND -> ErrorCode.NOT_FOUND;
            case INVALID_VALUE, INVALID_RESERVATION -> ErrorCode.INVALID_VALUE;
            case INVALID_STATE -> ErrorCode.INVALID_STATE;
            case UNSUPPORTED_OPERATION -> ErrorCode.UNSUPPORTED_OPERATION;
            case WAL_FAILURE -> ErrorCode.WAL_FAILURE;
            case IO_ERROR, CORRUPTED_DATA, BUFFER_FULL -> ErrorCode.IO_ERROR;
        };
        try {
            return (RuntimeException) frmExceptionClass
                    .getConstructor(ErrorCode.class, ComponentType.class, String.class, Throwable.class)
                    .newInstance(code, ComponentType.SM, "SM " + operation + " failed: " + error.getMessage(), error);
        } catch (ReflectiveOperationException e) {
            return new QpException(code, "SM " + operation + " failed: " + error.getMessage());
        }
    }

    private static Object invokeUnwrapped(Object target, String name, Class<?>[] paramTypes, Object[] args)
            throws ReflectiveOperationException {
        try {
            return target.getClass().getMethod(name, paramTypes).invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof RuntimeException re) {
                throw re;
            }
            if (e.getTargetException() instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    private static void invokeQuietly(Object target, String methodName) {
        if (target == null) return;
        try {
            target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Object handleObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> proxy.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(proxy));
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
    }

    public static void main(String[] args) throws InterruptedException {
        Path dataDir = Path.of("sqlike-data");
        ProtocolType protocol = ProtocolType.LOCK_BASED;
        boolean loadSample = true;
        int bufferFrames = DEFAULT_BUFFER_FRAMES;
        List<String> configArgs = new ArrayList<>();

        try {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--data" -> {
                        if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for --data");
                        dataDir = Path.of(args[++i]);
                    }
                    case "--protocol" -> {
                        if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for --protocol");
                        protocol = parseProtocol(args[++i]);
                    }
                    case "--buffer-frames" -> {
                        if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for --buffer-frames");
                        bufferFrames = Integer.parseInt(args[++i]);
                    }
                    case "--no-sample" -> loadSample = false;
                    case "--help", "-h" -> {
                        printUsage();
                        return;
                    }
                    default -> configArgs.add(arg);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        QpConfiguration config;
        try {
            config = QpConfiguration.fromArgs(configArgs.toArray(String[]::new));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        RunningServer running;
        try {
            running = start(dataDir, config, protocol, loadSample, bufferFrames);
        } catch (RuntimeException e) {
            System.err.println("SQLike server failed to start: " + e.getMessage());
            System.exit(1);
            return;
        }

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.close();
            stopped.countDown();
        }, "sqlike-server-shutdown"));

        System.out.println("======================================================================");
        System.out.println("  SQLike mDBMS Server " + running.bootstrap().getState()
                + " on port " + running.boundPort());
        System.out.println("  " + running.describeProtocol());
        System.out.println("  Data directory : " + dataDir.toAbsolutePath().normalize());
        System.out.println("  Tables loaded  : " + running.storage().listTables());
        System.out.println("  Max clients    : " + config.getMaxClients()
                + " | Join buffer: " + config.getJoinBufferBytes() + " bytes");
        System.out.println("  Connect client : ./query-processor/run-client.sh localhost " + running.boundPort());
        System.out.println("  Press Ctrl+C to stop cleanly.");
        System.out.println("======================================================================");
        stopped.await();
    }

    private static void printUsage() {
        System.out.println("Usage: sqlike.qp.QpServerMain [options]");
        System.out.println("  --data DIR                 Data & WAL directory (default: sqlike-data)");
        System.out.println("  --port N                   TCP port (default: 5432)");
        System.out.println("  --protocol lock|timestamp  Concurrency control protocol (default: lock)");
        System.out.println("  --max-clients N            Max concurrent clients (default: 16)");
        System.out.println("  --join-buffer BYTES        BNLJ outer buffer size in bytes (default: 65536)");
        System.out.println("  --index HASH|BPLUS_TREE    Default index type (default: HASH)");
        System.out.println("  --buffer-frames N          SM buffer pool frames (default: 16)");
        System.out.println("  --temp DIR                 Temporary directory");
        System.out.println("  --no-sample                Do not seed SampleDatabaseLoader on empty DB");
    }
}
