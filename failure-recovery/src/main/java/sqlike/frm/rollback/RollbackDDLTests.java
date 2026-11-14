package sqlike.frm.rollback;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sqlike.frm.log.LogManager;
import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.frm.port.SmPort;
import sqlike.shared.DataChange;
import sqlike.shared.DataType;
import sqlike.shared.IndexMetadata;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.SchemaChange;
import sqlike.shared.SchemaImage;
import sqlike.shared.TableSchema;

class RollbackDdlTests {

    @TempDir
    Path tempDir;

    private LogManager logManager;
    private SpySmPort spySm;
    private RollbackManager rollbackManager;

    @BeforeEach
    void setUp() {
        logManager = new LogManager(tempDir.resolve("wal.log").toString());
        spySm = new SpySmPort();
        rollbackManager = new RollbackManager(logManager, spySm);
    }

    @Test
    void rollbackCreateTable_restoresAbsentSchemaAndLogsCompensationAndAbort() {
        long txId = 101L;
        logManager.logBegin(txId);

        // Sebelum CREATE TABLE: tabel "mahasiswa" belum ada
        SchemaImage before = new SchemaImage("mahasiswa", Optional.empty(), List.of(), Optional.empty());
        // Sesudah CREATE TABLE: tabel "mahasiswa" terbentuk
        TableSchema createdSchema = sampleSchema("mahasiswa", List.of("nim", "nama"));
        SchemaImage after = new SchemaImage("mahasiswa", Optional.of(createdSchema), List.of(), Optional.empty());

        spySm.currentSchemas.put("mahasiswa", after);
        LogRecord ddlLog = logManager.logSchemaChange(txId, new SchemaChange(List.of(before), List.of(after)));

        // Eksekusi rollback & akhiri dengan ABORT
        rollbackManager.rollback(txId);
        logManager.logAbort(txId);

        // Verifikasi SM menerima restoreSchema dengan before-image (absent)
        assertEquals(1, spySm.restoredHistory.size());
        SchemaImage restored = spySm.restoredHistory.get(0);
        assertEquals("mahasiswa", restored.tableName());
        assertTrue(restored.schema().isEmpty(), "Tabel hasil CREATE TABLE harus dihapus saat rollback");
        assertTrue(spySm.currentSchemas.get("mahasiswa").schema().isEmpty());

        // Verifikasi log berisi COMPENSATION untuk LSN DDL tersebut dan diakhiri ABORT
        List<LogRecord> logs = logManager.readLogs(txId);
        assertTrue(logs.stream().anyMatch(r ->
                r.type() == LogType.COMPENSATION && r.undoOfLSN().equals(Optional.of(ddlLog.lsn()))));
        assertEquals(LogType.ABORT, logs.get(logs.size() - 1).type());
    }

    @Test
    void rollbackAlterTable_revertsToPreviousColumnsAndIndexes() {
        long txId = 102L;
        logManager.logBegin(txId);

        TableSchema oldSchema = sampleSchema("dosen", List.of("nip", "nama"));
        TableSchema newSchema = sampleSchema("dosen", List.of("nip", "nama", "email"));

        SchemaImage before = new SchemaImage("dosen", Optional.of(oldSchema), List.of(), Optional.empty());
        SchemaImage after = new SchemaImage("dosen", Optional.of(newSchema), List.of(), Optional.empty());

        spySm.currentSchemas.put("dosen", after);
        logManager.logSchemaChange(txId, new SchemaChange(List.of(before), List.of(after)));

        rollbackManager.rollback(txId);
        logManager.logAbort(txId);

        SchemaImage current = spySm.currentSchemas.get("dosen");
        assertTrue(current.schema().isPresent());
        assertEquals(oldSchema, current.schema().get(), "Skema harus kembali ke definisi sebelum ALTER TABLE");
    }

    @Test
    void rollbackDropTable_restoresSchemaAndSnapshotId() {
        long txId = 103L;
        logManager.logBegin(txId);

        TableSchema existingSchema = sampleSchema("matakuliah", List.of("kode", "sks"));
        SchemaImage before = new SchemaImage(
                "matakuliah",
                Optional.of(existingSchema),
                List.of(),
                Optional.of("snap-drop-103")
        );
        SchemaImage after = new SchemaImage("matakuliah", Optional.empty(), List.of(), Optional.empty());

        spySm.currentSchemas.put("matakuliah", after);
        logManager.logSchemaChange(txId, new SchemaChange(List.of(before), List.of(after)));

        rollbackManager.rollback(txId);
        logManager.logAbort(txId);

        SchemaImage current = spySm.currentSchemas.get("matakuliah");
        assertTrue(current.schema().isPresent(), "Skema tabel yang di-DROP harus dipulihkan");
        assertEquals(Optional.of("snap-drop-103"), current.snapshotId(), "Snapshot data tabel harus ikut dipulihkan");
    }

    @Test
    void rollbackMixedDmlAndDdl_executesInStrictReverseOrder() {
        long txId = 104L;
        logManager.logBegin(txId);

        // Langkah 1 (DDL): CREATE TABLE nilai
        TableSchema v1Schema = sampleSchema("nilai", List.of("id", "skor"));
        SchemaImage s0 = new SchemaImage("nilai", Optional.empty(), List.of(), Optional.empty());
        SchemaImage s1 = new SchemaImage("nilai", Optional.of(v1Schema), List.of(), Optional.empty());
        logManager.logSchemaChange(txId, new SchemaChange(List.of(s0), List.of(s1)));

        // Langkah 2 (DML): INSERT 1 baris ke tabel nilai
        RID rid = new RID(1, 0);
        RecordImage emptyRec = new RecordImage(false, List.of());
        RecordImage insertedRec = new RecordImage(true, List.of());
        DataChange insertChange = new DataChange(
                sqlike.shared.ChangeOperation.INSERT, "nilai", rid, emptyRec, insertedRec);
        logManager.logChange(txId, insertChange);

        // Langkah 3 (DDL): ALTER TABLE nilai tambah kolom grade
        TableSchema v2Schema = sampleSchema("nilai", List.of("id", "skor", "grade"));
        SchemaImage s2 = new SchemaImage("nilai", Optional.of(v2Schema), List.of(), Optional.empty());
        logManager.logSchemaChange(txId, new SchemaChange(List.of(s1), List.of(s2)));

        spySm.currentSchemas.put("nilai", s2);

        // Eksekusi rollback
        rollbackManager.rollback(txId);
        logManager.logAbort(txId);

        // Verifikasi urutan operasi ke SM harus terbalik:
        // 1. restoreSchema(v1Schema) -> undo ALTER TABLE
        // 2. writeAt(emptyRec)       -> undo INSERT
        // 3. restoreSchema(absent)   -> undo CREATE TABLE
        assertEquals(List.of("RESTORE_SCHEMA:nilai", "WRITE_AT:nilai", "RESTORE_SCHEMA:nilai"), spySm.operationOrder);
        assertTrue(spySm.currentSchemas.get("nilai").schema().isEmpty(), "Pada akhir rollback, tabel nilai kembali tidak ada");
        assertFalse(spySm.lastWrittenRecord.exists(), "Record hasil INSERT harus terhapus");
    }

    // --- Helper & Spy Storage Manager untuk Test Lokal ---

    private TableSchema sampleSchema(String tableName, List<String> colNames) {
        // Sesuaikan dengan konstruktor TableSchema di sqlike.shared
        List<TableSchema.ColumnDef> cols = colNames.stream()
                .map(c -> new TableSchema.ColumnDef(c, DataType.VARCHAR, false))
                .toList();
        return new TableSchema(tableName, cols);
    }

    private static class SpySmPort implements SmPort {
        final Map<String, SchemaImage> currentSchemas = new HashMap<>();
        final List<SchemaImage> restoredHistory = new ArrayList<>();
        final List<String> operationOrder = new ArrayList<>();
        RecordImage lastWrittenRecord;

        @Override
        public RecordImage readAt(String table, RID rid) {
            return lastWrittenRecord != null ? lastWrittenRecord : new RecordImage(false, List.of());
        }

        @Override
        public void writeAt(String table, RID rid, RecordImage image, long lsn) {
            this.lastWrittenRecord = image;
            this.operationOrder.add("WRITE_AT:" + table);
        }

        @Override
        public void restoreSchema(SchemaImage image, long lsn) {
            this.currentSchemas.put(image.tableName(), image);
            this.restoredHistory.add(image);
            this.operationOrder.add("RESTORE_SCHEMA:" + image.tableName());
        }

        @Override
        public void flushAll() {}

        @Override
        public String createSnapshot() {
            return "snap-test";
        }

        @Override
        public void restoreSnapshot(String snapshotId) {}
    }
}