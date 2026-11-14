# Kontrak Fitur Maintenance Basis Data: Checkpoint dan Point-in-Time Recovery

Dokumen ini menjelaskan cara memakai setiap kontrak pada fitur **maintenance basis data**:

- membuat titik pemulihan lewat **checkpoint** manual dan periodik (FR-06);
- memulihkan basis data ke **waktu atau transaksi tertentu** (FR-05).

Kode berada di `failure-recovery/src/main/java/sqlike/frm/`, di package `checkpoint`, `pitr`, dan `maintenance`.

Penanda **[Usulan M2]** berarti kebutuhan itu belum ada di kontrak supergroup dan masih perlu disepakati dengan komponen terkait.

---

## Daftar isi

1. [Siapa yang membutuhkan dokumen ini](#1-siapa-yang-membutuhkan-dokumen-ini)
2. [Gambaran alur](#2-gambaran-alur)
3. [MaintenanceService: pintu masuk fitur](#3-maintenanceservice-pintu-masuk-fitur)
4. [CheckpointLogPort: kebutuhan checkpoint terhadap WAL](#4-checkpointlogport-kebutuhan-checkpoint-terhadap-wal)
5. [HistoricalLogPort: kebutuhan PITR terhadap WAL](#5-historicallogport-kebutuhan-pitr-terhadap-wal)
6. [WalRecoveryLog: WAL sebagai sumber log maintenance](#6-walrecoverylog-wal-sebagai-sumber-log-maintenance)
7. [Kebutuhan terhadap Storage Manager (SmPort)](#7-kebutuhan-terhadap-storage-manager-smport)
8. [Kebutuhan terhadap Concurrency Control (CcmPort)](#8-kebutuhan-terhadap-concurrency-control-ccmport)
9. [CheckpointManager dan CheckpointInfo](#9-checkpointmanager-dan-checkpointinfo)
10. [PointInTimeRecovery, HistoricalRecoveryPlanner, HistoricalRecoveryPlan](#10-pointintimerecovery-historicalrecoveryplanner-historicalrecoveryplan)
11. [SnapshotCatalog: katalog titik pemulihan](#11-snapshotcatalog-katalog-titik-pemulihan)
12. [MockRecoveryLog dan pengujian](#12-mockrecoverylog-dan-pengujian)
13. [Ringkasan perilaku saat gagal](#13-ringkasan-perilaku-saat-gagal)

---

## 1. Siapa yang membutuhkan dokumen ini

| Pembaca | Kebutuhan | Bagian yang relevan |
|---|---|---|
| **FR1**: facade `FailureRecoveryManager` dan driver | Menyambungkan facade ke `MaintenanceService`, menyediakan adapter `SmPort` dan `CcmPort`, menyiapkan skenario PITR dan checkpoint di driver, melengkapi snapshot di `MockStorageManager` | 3, 7, 8, 12, 13 |
| **FR2**: `LogManager` / WAL | Mengimplementasikan perilaku yang dibutuhkan `CheckpointLogPort` dan `HistoricalLogPort` lewat `WalRecoveryLog`: `ensureDurable`, penanda CHECKPOINT, pembacaan log arsip, dan pemisahan log dengan LSN berlanjut | 4, 5, 6 |
| **FR5**: crash recovery | Memahami keadaan log setelah PITR: log lama diarsipkan, dan log aktif baru diawali penanda CHECKPOINT. Memahami arti `checkpointLSN` dan `flushedThroughLSN` | 2, 9, 10 |
| **FR3**: rollback | Hanya perlu tahu bahwa transaksi ABORT tidak pernah di-replay PITR, dan bahwa checkpoint menunggu semua transaksi aktif selesai, termasuk yang sedang rollback | 10, 13 |
| **SM1, SM2, SM4**: Storage Manager | Menyediakan `flushAll`, `createSnapshot`, `restoreSnapshot`, dan `restoreRecord` yang idempoten, dengan jaminan buffer, katalog, statistik, dan indeks [Usulan M2] | 7 |
| **SM5**: indeks | Indeks harus konsisten setelah `restoreSnapshot` dan replay | 7 |
| **CC1**: Concurrency Control | Menyediakan pause, menunggu sampai idle dengan batas waktu [Usulan M2], resume, dan `initAfterRecovery` | 8 |
| **QP1**: Query Processor | Kemungkinan perlu jika perintah checkpoint atau pemulihan historis dibuka lewat CLI; perintah itu diteruskan ke facade FRM | 3, 13 |
| **Asisten / penguji** | Memahami jaminan fitur dan cara menjalankan pengujiannya saat demo | 2, 12, 13 |

---

## 2. Gambaran alur

### Checkpoint

```
pause transaksi baru
  -> tunggu transaksi aktif selesai (dengan timeout)
  -> ensureDurable(LSN terakhir)          log persisten dulu
  -> flushAll()                           baru halaman data
  -> createSnapshot()                     snapshot dasar
  -> tulis penanda CHECKPOINT
  -> ensureDurable(LSN penanda)           checkpoint dianggap selesai
  -> catat snapshot di SnapshotCatalog
  -> resume transaksi                     selalu, baik berhasil maupun gagal
```

### Point-in-time recovery

```
pause -> tunggu idle
  -> readHistory()                        baca seluruh log
  -> susun rencana: target LSN, snapshot dasar, transaksi committed, perubahan yang di-replay
                                          (gagal di sini = data tidak berubah, transaksi dibuka lagi)
  -> restoreSnapshot(snapshot dasar)
  -> replay after-image ke RID tercatat
  -> flushAll()
  -> separateHistory(snapshot, targetLSN) arsipkan log lama; log baru diawali penanda CHECKPOINT
  -> catat rentang LSN yang dibuang (ABANDON)
  -> checkpoint baru                      titik awal timeline baru
  -> initAfterRecovery(maxTxId) -> resume
```

---

## 3. `MaintenanceService`: pintu masuk fitur

Paket: `sqlike.frm.maintenance`

Kelas ini menyatukan checkpoint manual, checkpoint periodik, dan PITR di belakang **satu gerbang maintenance** dan **satu katalog titik pemulihan**.

### Konstruktor

```java
public MaintenanceService(
        SmPort storage,
        CcmPort concurrency,
        CheckpointLogPort checkpointLog,
        HistoricalLogPort historicalLog,
        Path catalogPath,          // null = katalog hanya di memori (khusus tes)
        Duration periodicInterval, // <= 0 menonaktifkan checkpoint periodik
        long idleTimeoutMs,        // batas tunggu transaksi aktif
        Lock maintenanceGate)      // opsional; overload tanpa parameter ini membuat ReentrantLock sendiri
```

### Method

| Method | Fungsi | Hasil / exception |
|---|---|---|
| `CheckpointInfo checkpoint()` | Membuat titik pemulihan sekarang | `IllegalStateException` jika timeout; exception dari log atau SM diteruskan |
| `HistoricalRecoveryPlan recoverTo(RecoveryTarget)` | Memulihkan ke waktu atau transaksi | `IllegalArgumentException` untuk target tidak valid atau transaksi yang tidak COMMIT; `IllegalStateException` jika tidak ada snapshot atau timeout |
| `startPeriodicCheckpoints()` / `stopPeriodicCheckpoints()` | Menyalakan dan mematikan checkpoint periodik | Aman dipanggil berulang |
| `isPeriodicCheckpointRunning()` | Status jadwal periodik | `boolean` |
| `Optional<PeriodicFailure> lastPeriodicFailure()` | Kegagalan pada eksekusi periodik terakhir | Kosong lagi setelah eksekusi berikutnya berhasil |
| `Optional<SnapshotEntry> latestRecoveryPoint()` | Titik pemulihan terbaru yang masih bisa dipakai | Kosong jika belum pernah checkpoint |
| `Lock maintenanceGate()` | Gerbang yang dipakai bersama | Untuk operasi lain yang tidak boleh berjalan bersamaan dengan maintenance |
| `close()` | Menghentikan scheduler | Pakai `try-with-resources` |

### Contoh: menyambungkan ke facade

```java
// di konstruktor FailureRecoveryManager
this.maintenance = new MaintenanceService(
        smPort, ccmPort,
        recoveryLog, recoveryLog,              // WalRecoveryLog atau MockRecoveryLog
        Path.of(logPath).resolveSibling(MaintenanceService.SNAPSHOT_CATALOG_FILE),
        checkpointInterval,                    // Duration.ZERO = nonaktif
        MAINTENANCE_TIMEOUT_MS,
        maintenanceLock);                      // lock yang sama dengan recover() bila perlu

@Override
public void checkpoint() {
    ensureReady();
    maintenance.checkpoint();
}

@Override
public void recoverTo(RecoveryTarget target) {
    ensureReady();
    state = RecoveryState.RUNNING;
    try {
        maintenance.recoverTo(target);
        state = RecoveryState.READY;
    } catch (RuntimeException e) {
        state = RecoveryState.FAILED;
        throw e;
    }
}
```

> Jangan membuat `CheckpointManager` dan `PointInTimeRecovery` secara terpisah. Keduanya harus memakai **katalog dan gerbang yang sama**. Kalau tidak, PITR tidak akan melihat snapshot hasil checkpoint, dan checkpoint bisa berjalan bersamaan dengan restore.

---

## 4. `CheckpointLogPort`: kebutuhan checkpoint terhadap WAL

Paket: `sqlike.frm.checkpoint`

```java
public interface CheckpointLogPort {
    long getLastLsn();
    void ensureDurable(long lsn);
    CheckpointInfo logCheckpoint(long flushedThroughLSN, String snapshotId);
}
```

| Method | Perilaku yang diharapkan |
|---|---|
| `getLastLsn()` | Mengembalikan LSN terakhir yang sudah dialokasikan, atau `0` bila log kosong |
| `ensureDurable(lsn)` | Baru kembali setelah semua catatan sampai **dan termasuk** `lsn` tersimpan permanen. Jika tidak bisa dijamin, melempar exception. `ensureDurable(0)` pada log kosong harus sukses |
| `logCheckpoint(flushed, snapshotId)` | Menambahkan catatan CHECKPOINT dengan LSN baru (`> flushed`) dan mengembalikan `CheckpointInfo`. Penanda **tidak perlu** langsung persisten, karena pemanggil memanggil `ensureDurable` setelahnya |

Urutan pemanggilan oleh checkpoint:

```java
long flushed = log.getLastLsn();
log.ensureDurable(flushed);            // sebelum flushAll
sm.flushAll();
String snap = sm.createSnapshot();
CheckpointInfo info = log.logCheckpoint(flushed, snap);
log.ensureDurable(info.checkpointLSN());
```

---

## 5. `HistoricalLogPort`: kebutuhan PITR terhadap WAL

Paket: `sqlike.frm.pitr`

```java
public interface HistoricalLogPort {
    List<LogRecord> readHistory();
    void separateHistory(String snapshotId, long targetLSN);
}
```

| Method | Perilaku yang diharapkan |
|---|---|
| `readHistory()` | Semua catatan **valid** dari log arsip, lalu log aktif, terurut naik menurut LSN. Setiap catatan butuh LSN, txId, jenis, waktu (terutama COMMIT), dan untuk INSERT/UPDATE/DELETE juga `DataChange` lengkap (tabel, RID, before-image, after-image). Log kosong mengembalikan list kosong, bukan error |
| `separateHistory(snapshotId, targetLSN)` | Memindahkan log aktif ke arsip dan membuka log aktif baru yang diawali penanda CHECKPOINT untuk `snapshotId`. **LSN harus berlanjut**: catatan pertama di log baru memakai LSN terakhir + 1. Catatan dengan LSN di atas `targetLSN` milik timeline yang dibuang. Hanya boleh dipanggil saat tidak ada transaksi yang belum selesai di log aktif |

Setelah `separateHistory`, crash recovery hanya membaca log aktif yang baru. Isinya diawali dua penanda CHECKPOINT (dari `separateHistory` dan dari checkpoint pasca-restore), sehingga transaksi dari timeline yang dibuang tidak pernah di-redo.

`LogManager.readHistory()` mengembalikan semua catatan dari semua epoch, **termasuk rentang yang dibuang**. Karena itu `HistoricalRecoveryPlanner` menyaringnya memakai entri ABANDON di `SnapshotCatalog`.

---

## 6. `WalRecoveryLog`: WAL sebagai sumber log maintenance

Paket: `sqlike.frm.maintenance`

Implementasi `CheckpointLogPort` dan `HistoricalLogPort` di atas `LogManager`. Facade membuatnya dari `LogManager` yang sama dengan yang dipakai untuk pencatatan log:

```java
WalRecoveryLog wal = new WalRecoveryLog(logManager);
new MaintenanceService(sm, ccm, wal, wal, catalogPath, interval, timeoutMs, maintenanceLock);
```

| Method port | Diteruskan ke `LogManager` |
|---|---|
| `getLastLsn()` | `getLastLsn()` |
| `ensureDurable(lsn)` | `ensureDurable(lsn)` |
| `logCheckpoint(flushed, snapshotId)` | `logCheckpoint(flushed, snapshotId)`. `LogManager` mensyaratkan `flushed` sama dengan LSN terakhir dan tidak ada transaksi yang belum selesai |
| `readHistory()` | `readHistory()` (semua epoch) |
| `separateHistory(snapshotId, targetLSN)` | `archiveActiveLog()` lalu `startNewLog(snapshotId, targetLSN)` |

---

## 7. Kebutuhan terhadap Storage Manager (`SmPort`)

`SmPort` milik facade, tetapi fitur ini bergantung pada perilaku berikut:

| Method `SmPort` | Padanan di `IStorageManager` | Perilaku yang dibutuhkan |
|---|---|---|
| `flushAll()` | `flushAll()` | Menulis semua halaman kotor. SM boleh memanggil `ensureDurable(pageLSN)`; setelah checkpoint memastikan log persisten, panggilan itu selalu sukses |
| `createSnapshot()` | **[Usulan M2]** belum ada | Dipanggil tepat setelah `flushAll()` tanpa transaksi aktif. Menyalin berkas tabel dan katalog. Mengembalikan ID unik tanpa karakter `\|` atau baris baru |
| `restoreSnapshot(id)` | **[Usulan M2]** belum ada | Mengganti data dengan isi snapshot. Sebelum kembali, buffer lama dibuang, katalog dimuat ulang, dan statistik serta indeks disalin atau dibangun ulang. ID yang tidak dikenal harus melempar exception **tanpa mengubah data** |
| `writeAt(table, rid, image, lsn)` | `restoreRecord(table, rid, image, lsn)` | Menerapkan image pada RID tersebut secara idempoten. `exists=false` berarti menghapus (tetap sukses bila record sudah tidak ada). Tidak menulis log DML |

---

## 8. Kebutuhan terhadap Concurrency Control (`CcmPort`)

| Method `CcmPort` | Padanan di `IConcurrencyControlManager` | Perilaku yang dibutuhkan |
|---|---|---|
| `pauseNewTransactions()` | `pauseNewTransactions()` | Menahan transaksi baru. Transaksi yang sedang aktif tetap boleh COMMIT atau ABORT |
| `waitUntilIdle(timeoutMs)` | `awaitNoActiveTransactions()` **tanpa timeout**. [Usulan M2]: tambahkan varian dengan timeout | Mengembalikan `true` saat tidak ada transaksi aktif, atau `false` bila waktu habis |
| `resumeTransactions()` | `resumeTransactions()` | Membuka kembali penerimaan transaksi |
| `initAfterRecovery(maxTxId)` | `initAfterRecovery(lastTxId)` | Mereset state CCM; txId berikutnya harus di atas `maxTxId`. Implementasi CCM saat ini **tidak** membuka kembali transaksi, sehingga PITR memanggil `resumeTransactions()` setelahnya |

---

## 9. `CheckpointManager` dan `CheckpointInfo`

Paket: `sqlike.frm.checkpoint`. Biasanya dipakai lewat `MaintenanceService`.

| Method | Kapan dipakai |
|---|---|
| `CheckpointInfo checkpoint()` | Checkpoint lengkap: mengambil gerbang, pause, menunggu idle, menjalankan langkah-langkah checkpoint, lalu resume |
| `CheckpointInfo checkpointWhileQuiesced()` | Hanya langkah-langkah checkpoint, **tanpa** pause/resume. Pemanggil wajib sudah memegang gerbang dan memastikan tidak ada transaksi aktif (dipakai PITR) |

```java
public record CheckpointInfo(
        long checkpointLSN,      // LSN penanda CHECKPOINT
        long flushedThroughLSN,  // semua perubahan <= LSN ini sudah ada di disk dan di snapshot
        Instant createdAt,
        String snapshotId)
```

Bagi crash recovery: semua perubahan dengan LSN ≤ `flushedThroughLSN` sudah berada di disk, dan saat checkpoint tidak ada transaksi aktif.

---

## 10. `PointInTimeRecovery`, `HistoricalRecoveryPlanner`, `HistoricalRecoveryPlan`

Paket: `sqlike.frm.pitr`

### Membuat target

```java
// ke waktu tertentu
new RecoveryTarget(RecoveryTargetKind.TIME, Optional.of(Instant.parse("2026-11-06T10:00:00Z")), Optional.empty());

// sampai transaksi tertentu (harus pernah COMMIT)
new RecoveryTarget(RecoveryTargetKind.TRANSACTION, Optional.empty(), Optional.of(42L));
```

Target TIME hanya boleh berisi `time`. Target TRANSACTION hanya boleh berisi `txId` yang positif.

### Aturan resolusi

| Hal | Aturan |
|---|---|
| Target TIME `t` | Target LSN = LSN tertinggi dari catatan dengan waktu ≤ `t` (`0` jika tidak ada) |
| Target TRANSACTION `x` | Target LSN = LSN catatan COMMIT milik `x`; ditolak bila `x` tidak pernah COMMIT |
| Transaksi committed | COMMIT-nya memiliki LSN ≤ target. Transaksi yang menulis sebelum target tetapi COMMIT setelahnya **tidak** ikut; transaksi ABORT tidak pernah ikut |
| Snapshot dasar | Snapshot terbaru yang masih berlaku dengan base LSN ≤ target |
| Perubahan yang di-replay | INSERT/UPDATE/DELETE dari transaksi committed dengan base LSN < LSN ≤ target, urut naik LSN, berupa after-image di RID tercatat |
| `maxTxId` | txId terbesar di **seluruh** riwayat, termasuk rentang yang dibuang |

### Hasil: `HistoricalRecoveryPlan`

```java
public record HistoricalRecoveryPlan(
        String snapshotId, long baseLSN, long targetLSN, long maxTxId,
        Set<Long> committedTxIds, List<LogRecord> changesToReplay)
```

Hasil ini dikembalikan oleh `recoverTo`. Driver atau CLI bisa menampilkannya sebagai ringkasan: snapshot yang dipakai, target LSN, dan jumlah perubahan yang di-replay.

`HistoricalRecoveryPlanner.plan(target, history)` hanya menyusun rencana dan **tidak menyentuh data**, jadi bisa dipakai untuk pratinjau.

---

## 11. `SnapshotCatalog`: katalog titik pemulihan

Paket: `sqlike.frm.checkpoint`. Dikelola otomatis oleh `MaintenanceService`.

- **Lokasi bawaan:** `<folder WAL>/snapshots.catalog`.
- **Format:** append-only, satu entri per baris, setiap baris di-`force` ke disk.

```
SNAPSHOT|<id>|<baseLSN>|<checkpointLSN>|<createdAtMillis>
ABANDON|<fromLSN>|<toLSN>
```

- **`SNAPSHOT`** dicatat setiap kali checkpoint selesai.
- **`ABANDON`** dicatat setiap kali PITR berhasil. Catatan log dalam rentang itu tidak pernah di-replay lagi. Snapshot yang dibuat **sebelum** ABANDON dan base LSN-nya berada di rentang itu tidak dipakai lagi.
- **Reset driver:** hapus file ini bersama berkas data dan log supaya kembali ke keadaan awal.
- **File rusak** menyebabkan exception saat katalog dimuat.

---

## 12. `MockRecoveryLog` dan pengujian

`MockRecoveryLog` (`sqlike.frm.mock`) adalah WAL in-memory yang mengimplementasikan kedua port log. Mock ini dipakai sampai `LogManager` selesai.

```java
AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-11-06T10:00:00Z"));
MockRecoveryLog log = new MockRecoveryLog(clock::get);   // waktu catatan bisa diatur

log.logBegin(1);
log.logChange(1, new DataChange(ChangeOperation.INSERT, "student", rid, before, after));
log.logCommit(1);                 // langsung persisten
log.logAbort(2);
log.simulateDurabilityFailure(true);   // uji kegagalan ensureDurable
log.activeLog();                  // isi log aktif setelah separateHistory
```

Menjalankan pengujian fitur (JDK 21+), dari folder `failure-recovery/`:

```bash
./run-maintenance-tests.sh
```

Skrip ini meng-compile `shared/` di root bersama seluruh modul FRM, lalu menjalankan `MaintenanceTests`. Fake SM dan CCM ada di dalam `MaintenanceTests.java`, sehingga pengujian tidak bergantung pada SM, CCM, atau FR3 yang asli. Exit code `0` hanya jika semua pemeriksaan lulus.

---

## 13. Ringkasan perilaku saat gagal

| Kejadian | Data | Log | Transaksi baru |
|---|---|---|---|
| Checkpoint: timeout menunggu idle | Tidak berubah | Tidak ada penanda | Dibuka kembali |
| Checkpoint: `ensureDurable` gagal | Tidak di-flush | Tidak ada penanda | Dibuka kembali |
| Checkpoint: `flushAll` / `createSnapshot` gagal | Mungkin sebagian di-flush (aman karena log sudah persisten) | Tidak ada penanda | Dibuka kembali |
| Checkpoint periodik gagal | Seperti di atas | Seperti di atas | Dibuka kembali; kegagalan bisa dilihat di `lastPeriodicFailure()` dan jadwal tetap berjalan |
| PITR: target tidak valid, transaksi tidak COMMIT, tidak ada snapshot, atau timeout | Tidak berubah | Tidak berubah | Dibuka kembali |
| PITR: gagal setelah `restoreSnapshot` dimulai | Mungkin setengah pulih | Mungkin belum dipisah | **Tetap ditahan**; facade sebaiknya menandai FAILED. Jalankan `recoverTo` lagi atau restart |

**Risiko dan celah yang diketahui**

- Crash di tengah PITR, setelah restore tetapi sebelum `separateHistory`, dapat membuat crash recovery memakai checkpoint terbaru di atas data snapshot lama. Penanda "PITR sedang berjalan" yang dibaca crash recovery belum dibuat.
- Setelah restart yang mengikuti PITR, `RecoveryResult.lastTxId` bernilai 0 karena `RecoveryManager` hanya membaca log aktif, padahal `LogManager.getMaxTxId()` dan header epoch menyimpan txId tertinggi dari seluruh riwayat. Perbaikannya ada di sisi crash recovery.
- `LogManager.archiveActiveLog()` dan `logCheckpoint` menolak bila log aktif masih memuat transaksi tanpa COMMIT atau ABORT. Checkpoint dan PITR yang berjalan pada keadaan seperti itu akan gagal dengan "Maintenance requires all transactions to finish".
