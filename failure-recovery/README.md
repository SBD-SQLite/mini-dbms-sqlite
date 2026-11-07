# Failure Recovery Manager (FRM)

FRM menjamin WAL, durabilitas log, rollback, checkpoint, pemulihan historis (PITR), dan crash
recovery. Kontrak publiknya `sqlike.contract.IFailureRecoveryManager` (PRD v1.0 §3.5) dan
`sqlike.contract.IWalDurability` untuk Storage Manager.

| Role | Bagian | Kelas utama |
|---|---|---|
| FR1 | Facade, adapter, driver pengujian | `FailureRecoveryManager`, `frm.adapter.*`, `frm.driver.*`, `frm.mock.*` |
| FR2 | Log | `frm.log.LogManager`, `LogRecord`, `LogType` |
| FR3 | Undo/Rollback | `RollbackManager` (mengimplementasikan `frm.port.RollbackPort`) |
| FR4 | Checkpoint dan PITR | `frm.checkpoint.*`, `frm.pitr.*` |
| FR5 | Crash recovery | `frm.recovery.*`, `frm.model.*` |

## Menjalankan driver (FR-09)

Butuh JDK 17 atau lebih baru (`javac` dan `java` ada di PATH). Skrip meng-compile `shared/` di root
bersama modul FRM ke `failure-recovery/out/`, lalu menjalankan `sqlike.frm.driver.FRMDriver`.

```bash
# Linux / macOS / Git Bash
failure-recovery/run-driver.sh                 # semua skenario
failure-recovery/run-driver.sh rollback        # satu atau beberapa skenario
failure-recovery/run-driver.sh --list          # daftar skenario
failure-recovery/run-driver.sh --show-log committed-not-flushed      # isi WAL skenario
failure-recovery/run-driver.sh --show-log committed-not-flushed 2    # filter txId 2
```

```bat
rem Windows (cmd / PowerShell)
failure-recovery\run-driver.bat
failure-recovery\run-driver.bat --list
```

Exit code `0` hanya jika semua skenario PASS.

### Membaca hasil

Setiap skenario mencetak `PASS`/`FAIL`, isi tabel yang diharapkan (`expected`), isi tabel
sebenarnya (`actual`), dan `message` berisi pemeriksaan yang gagal. Untuk skenario crash,
`actual` adalah data **di disk** setelah recovery di proses baru.

```
[FAIL] uncommitted-flushed
  expected: account: (0,0)=[1, 'Ani', 100.0, 'BDG'] (0,1)=[2, 'Budi', 300.0, 'JKT'] ...
  actual  : account: (0,0)=[1, 'Ani', 20.0, 'SBY'] (0,1)=[2, 'Budi', 300.0, 'JKT'] ...
  message : data on disk after recovery differs from expected
```

## Keadaan awal yang diketahui

Setiap skenario mulai dari folder `failure-recovery/frm-data/<skenario>/` yang dihapus lalu diisi
fixture yang sama (`driver/Fixtures.java`), jadi pengujian bisa diulang tanpa mengubah kode.
Folder itu dibiarkan setelah skenario selesai agar log dan data bisa diperiksa.

```
account(id INTEGER, owner VARCHAR, balance FLOAT, branch CHAR)   page 0, slot 0..3 (slot 4 kosong)
loan(id INTEGER, account_id INTEGER, amount FLOAT)                page 0, slot 0..1
```

## Skenario

| Skenario | Jenis | Yang diuji |
|---|---|---|
| `startup-gate` | in-process | Log ditolak sebelum `recover()`, `ensureDurable` tetap boleh, `recover()` bisa diulang, `initAfterRecovery` dipanggil sekali oleh pemilik startup (QP) |
| `log-view` | in-process | LSN unik dan naik, BEGIN/INSERT/UPDATE/DELETE/COMMIT per transaksi, isi before/after dan RID, filter txId, membaca log tidak mengubahnya (FR-01–FR-03) |
| `rollback` | in-process | Undo urutan terbalik pada RID yang sama, transaksi lain aman, ABORT final hanya satu, rollback ulang tidak mengubah data, rollback transaksi committed/tidak dikenal ditolak (FR-04) |
| `checkpoint-timeout` | in-process | Checkpoint batal saat ada transaksi aktif tanpa flush, gate CCM selalu dibuka lagi, lalu berhasil dan mem-flush semua halaman (FR-06) |
| `committed-not-flushed` | crash | Redo tiga transaksi committed yang halamannya belum di-flush, termasuk dua UPDATE pada record yang sama |
| `uncommitted-flushed` | crash | Undo transaksi aktif yang halamannya sudah sampai disk (STEAL), transaksi committed yang berselang-seling tetap ada |
| `aborted-and-committed` | crash | Committed, aborted, dan aktif bersamaan; ABORT sudah durable tetapi hasil undo belum di-flush tetap harus di-undo (PRD §14) |
| `checkpoint-recovery` | crash | Recovery dengan checkpoint selesai: sebelum checkpoint aman, sesudahnya redo dan undo benar |
| `point-in-time-recovery` | crash | PITR ke transaksi; transaksi yang menulis sebelum target tetapi COMMIT sesudahnya tidak ikut; hasil bertahan setelah restart (FR-05) |

Skenario crash juga memeriksa bahwa restart kedua menghasilkan data yang sama dan
`RecoveryResult.lastTxId` tidak lebih kecil dari ID tertinggi yang tercatat durable.

## Cara kerja simulasi crash

1. Driver menulis fixture ke `frm-data/<skenario>/`.
2. `CrashSimulator` menjalankan skenario di **JVM anak** (`CrashWorker run`). Anak menjalankan
   startup recovery, transaksi skenario, lalu mencetak `FRM-CRASH-POINT` dan menunggu.
3. Driver langsung membunuh anak dengan `Process.destroyForcibly()`. Tidak ada shutdown normal,
   jadi data yang hanya ada di buffer `MockStorageManager` hilang.
4. Driver menjalankan JVM baru (`CrashWorker recover`) yang hanya memanggil `recover()` lalu keluar
   tanpa flush tambahan. Data di disk setelahnya adalah apa yang dibuat durable oleh recovery.
5. Langkah 4 diulang sekali untuk memastikan recovery dapat diulang.

## Mock

- `MockStorageManager` (implementasi `SmPort`): map di memori berperan sebagai buffer pool,
  `flushAll()`/`evict(table)` menulis ke `tables/*.tbl` secara atomik setelah memanggil
  `IWalDurability.ensureDurable(pageLSN)`. Snapshot disalin ke `snapshots/<id>/`.
  `loadFixture(Path)` membaca file berformat `table | page | slot | TYPE nilai | ...`.
- `MockCCM` (implementasi `CcmPort`): pause/resume, menunggu transaksi aktif dengan batas waktu,
  menolak ID transaksi yang dipakai ulang setelah `initAfterRecovery`, dan mencatat setiap panggilan.

FRM yang diuji dipilih dengan `-Dfrm.factory=<kelas FrmFactory>`; default-nya facade asli.

## Facade dan konfigurasi

`FailureRecoveryManager` meneruskan setiap method ke pemiliknya, memeriksa status
`NOT_STARTED → RUNNING → READY | FAILED`, memvalidasi input (txId, bentuk `DataChange`,
`RecoveryTarget`), dan melempar `FrmException` berisi `ErrorInfo`.

```java
FrmConfig config = FrmConfig.defaults(Path.of("data/wal.log"));   // checkpoint 60 s, idle timeout 30 s
FailureRecoveryManager frm = new FailureRecoveryManager(
        new StorageManagerAdapter(storageManager),
        new ConcurrencyControlAdapter(ccm),
        config,
        rollbackManager);                                           // FR3
```

Checkpoint berkala dimulai otomatis setelah `recover()` berhasil jika interval tidak nol.
Interval nol hanya untuk pengujian khusus. Checkpoint manual, checkpoint berkala, dan `recoverTo`
memakai satu maintenance lock.

## Status integrasi (Milestone 2)

Branch ini mengikuti `develop/frm`: `LogManager` FR2 (PR #64), `RecoveryManager` FR5, dan
`MaintenanceService` FR4 (checkpoint, checkpoint berkala, PITR, `WalRecoveryLog`). `WalRecoveryLog`
sudah disesuaikan dengan API FR2 (`logCheckpoint(flushedThroughLSN, snapshotId)`, `readHistory()`
semua epoch, `archiveActiveLog` + `startNewLog(snapshotId, targetLSN)`). Saat startup pertama, facade
membuat satu checkpoint sebagai snapshot dasar PITR. `lastTxId` hasil recovery memakai
`max(hasil FR5, LogManager.getMaxTxId())` supaya txId dari epoch arsip tidak dipakai ulang setelah PITR.
`close()` juga menutup `LogManager` (melepas `<wal>.lock`). Kegagalan WAL yang butuh recovery
(`WalException`), termasuk COMMIT yang hasilnya tidak pasti, membuat FRM FAILED (fail-stop) dan
`FrmException.isCommitUnknown()` bernilai true.

Hasil driver saat ini:

| Konfigurasi | Hasil |
|---|---|
| Modul FRM apa adanya | 5/9 PASS (startup-gate, log-view, checkpoint-timeout, committed-not-flushed, point-in-time-recovery) |
| FRM referensi sementara (di luar repo) | 9/9 PASS; 7 bug yang ditanam semuanya tertangkap |

FR2 mewajibkan setiap perubahan transaksi punya record COMPENSATION (`logCompensation(txId, lsnAsli)`,
urut LSN menurun) sebelum `logAbort` diterima; tanpa itu muncul `WalException "Rollback belum selesai untuk T<id>"`.

| Pemilik | Yang dibutuhkan agar skenario PASS |
|---|---|
| FR2 | Selesai (PR #64) |
| FR3 | Skenario `rollback`, `aborted-and-committed`. `feat/frm/rollback-v2` perlu di-rebase ke `develop/frm` terbaru, lalu: sebelum tiap undo panggil `clr = logManager.logCompensation(txId, record.lsn())` dan tulis before-image dengan `sm.writeAt(table, rid, before, clr.lsn())`; lewati perubahan yang sudah punya CLR (`undoOfLSN`); `RollbackTests` tidak boleh lagi `extends LogManager` (kelasnya `final`), pakai `LogManager` asli di folder sementara. Setelah merge, konstruktor facade memasang `new RollbackManager(log, sm)` |
| FR4 | Selesai (`WalRecoveryLog` disesuaikan di branch ini) |
| FR5 | Skenario `uncommitted-flushed`, `checkpoint-recovery`. Di fase undo, untuk transaksi tanpa COMMIT/ABORT: per perubahan yang belum punya CLR, urut LSN menurun, panggil `logCompensation(txId, rec.lsn())` lalu tulis before-image dengan LSN CLR tersebut; baru setelah itu `logAbort(txId)`. Abaikan record COMPENSATION saat klasifikasi redo/undo |
| Kontrak | Saat `contracts/` root ada: hapus salinan `IConcurrencyControlManager`, `IStorageManager`, `IWalDurability`, `sqlike.storage.*` di modul ini; ganti penantian di `ConcurrencyControlAdapter` dengan `awaitNoActiveTransactions(timeoutMs)`; tambah `logSchemaChange` (tipe `SchemaChange` sudah ada dari FR2) |
