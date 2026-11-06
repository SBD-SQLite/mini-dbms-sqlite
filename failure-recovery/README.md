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

Driver sudah diverifikasi dengan implementasi referensi sementara di luar repo: 9/9 skenario PASS,
dan tujuh bug yang sengaja ditanam (redo hilang, undo ABORT dilewati, recovery tanpa flush, PITR
memasukkan commit setelah target, PITR tanpa epoch baru, ABORT ganda, gate checkpoint tidak
dibuka) masing-masing membuat skenario terkait FAIL.

Dengan modul FRM saat ini hanya `startup-gate` dan `checkpoint-timeout` yang PASS. Skenario lain
FAIL karena bagian berikut masih TODO:

| Pemilik | Yang dibutuhkan agar skenario PASS |
|---|---|
| FR2 | `LogManager`: tulis/baca WAL ke file, LSN unik, `ensureDurable`, `readLogs`, `showLogs`, simpan `snapshotId` di catatan checkpoint |
| FR3 | Pindahkan `RollbackManager` ke package bernama, implementasikan `RollbackPort`, jangan menulis ABORT final (PRD §11), lalu sambungkan di konstruktor facade |
| FR4 | `HistoricalLogPort` di atas `LogManager` menggantikan `MockRecoveryLog`, snapshot awal base LSN 0, epoch baru setelah PITR |
| FR5 | `RecoveryManager.recover()` lengkap; jangan memanggil `ccm.initAfterRecovery` saat startup (hanya QP, PRD §14); undo ulang transaksi ABORT yang hasil undo-nya belum aman |
| Kontrak | Saat `contracts/` root ada: hapus salinan `IConcurrencyControlManager`, `IStorageManager`, `IWalDurability`, `sqlike.storage.*` di modul ini; ganti penantian di `ConcurrencyControlAdapter` dengan `awaitNoActiveTransactions(timeoutMs)`; tambah `logSchemaChange` setelah `SchemaChange` ada di shared |
