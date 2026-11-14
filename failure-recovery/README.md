# Failure Recovery Manager (FRM)

FRM menjamin WAL, durabilitas log, rollback, checkpoint, pemulihan historis (PITR), dan crash
recovery. Kontrak publiknya `sqlike.contract.IFailureRecoveryManager` (PRD v1.0 §3.5) dan
`sqlike.contract.IWalDurability` untuk Storage Manager.

| Role | Bagian | Kelas utama |
|---|---|---|
| FR1 | Facade, adapter, driver pengujian | `FailureRecoveryManager`, `frm.adapter.*`, `frm.driver.*`, `frm.mock.*` |
| FR2 | Log | `frm.log.LogManager`, `LogRecord`, `LogType` |
| FR3 | Undo/Rollback | `frm.rollback.RollbackManager` (mengimplementasikan `frm.port.RollbackPort`) |
| FR4 | Checkpoint dan PITR | `frm.checkpoint.*`, `frm.pitr.*` |
| FR5 | Crash recovery | `frm.recovery.*`, `frm.model.*` |

## Kompilasi dengan Maven

Semua komponen terdaftar sebagai modul Maven di `pom.xml` root, dan FRM bergantung pada modul
`shared`. Jalankan dari **root repositori** (butuh Maven 3.9+):

```bash
mvn -pl failure-recovery -am compile        # kompilasi FRM beserta modul shared
mvn -pl failure-recovery -am test-compile   # kompilasi kode utama + unit test
mvn test-compile                            # kompilasi seluruh komponen sekaligus
```

Hasil kompilasi ada di `failure-recovery/target/classes` dan `failure-recovery/target/test-classes`.
Unit test FRM bukan JUnit, jadi `mvn test` hanya mengompilasi dan tidak menjalankannya.
Jalankan driver atau test langsung dengan `java`:

```bash
# Linux / macOS
java -cp "failure-recovery/target/classes:failure-recovery/target/test-classes:shared/target/classes" sqlike.frm.MaintenanceTests
java -cp "failure-recovery/target/classes:shared/target/classes" sqlike.frm.driver.FRMDriver --data-dir failure-recovery/frm-data

# Windows (ganti : dengan ;)
java -cp "failure-recovery/target/classes;failure-recovery/target/test-classes;shared/target/classes" sqlike.frm.MaintenanceTests
java -cp "failure-recovery/target/classes;shared/target/classes" sqlike.frm.driver.FRMDriver --data-dir failure-recovery/frm-data
```

## Menjalankan driver (FR-09)

Butuh JDK 21 atau lebih baru (`javac` dan `java` ada di PATH), sama dengan
`maven.compiler.release` di `pom.xml` root. Skrip meng-compile `shared/` di root
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
| `schema-change-log` | in-process | `logSchemaChange` menulis record SCHEMA_CHANGE dengan isi `SchemaImage` utuh dan urut LSN bersama record data; rollback transaksi berisi perubahan skema ditolak `UNSUPPORTED_OPERATION` tanpa menulis COMPENSATION; permintaan tidak valid ditolak sebelum ada record |
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

## Unit test

Setelah `run-driver` sekali (kelas sudah ada di `failure-recovery/out`):

```bash
javac -d failure-recovery/out-test -cp failure-recovery/out failure-recovery/src/test/java/sqlike/frm/*.java
java -cp "failure-recovery/out:failure-recovery/out-test" sqlike.frm.RollbackTests      # FR3, 12 test
java -cp "failure-recovery/out:failure-recovery/out-test" sqlike.frm.MaintenanceTests   # FR4, 13 test
```

Di Windows, ganti `:` pada `-cp` dengan `;`.

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

## Status integrasi

FR1 sampai FR5 sudah terintegrasi di `main`: `LogManager` FR2 (PR #64), `RecoveryManager` FR5,
`RollbackManager` FR3 (memakai `LogManager` yang sama), dan `MaintenanceService` FR4 (checkpoint,
checkpoint berkala, PITR, `WalRecoveryLog` yang sudah mengikuti API FR2 sejak PR #65). Saat startup
pertama, facade membuat satu checkpoint sebagai snapshot dasar PITR. `lastTxId` hasil recovery
memakai `max(hasil FR5, LogManager.getMaxTxId())` supaya txId dari epoch arsip tidak dipakai ulang
setelah PITR.
`close()` juga menutup `LogManager` (melepas `<wal>.lock`). Kegagalan WAL yang butuh recovery
(`WalException`), termasuk COMMIT yang hasilnya tidak pasti, membuat FRM FAILED (fail-stop) dan
`FrmException.isCommitUnknown()` bernilai true.

Hasil pengujian (Windows 11, JDK 21):

| Pengujian | Hasil |
|---|---|
| `FRMDriver` | 10/10 skenario PASS |
| `RollbackTests` (FR3) | 12/12 |
| `MaintenanceTests` (FR4) | 13/13 |

FR2 mewajibkan setiap perubahan transaksi punya record COMPENSATION (`logCompensation(txId, lsnAsli)`,
urut LSN menurun) sebelum `logAbort` diterima; tanpa itu muncul `WalException "Rollback belum selesai untuk T<id>"`.

| Pemilik | Status |
|---|---|
| FR2 | Selesai (PR #64) |
| FR3 | Selesai (PR #70): tiap undo menulis COMPENSATION lalu before-image dengan LSN CLR tersebut |
| FR4 | Selesai (PR #65) |
| FR5 | Selesai (PR #68): fase undo menulis COMPENSATION sebelum ABORT, dan record COMPENSATION diabaikan saat klasifikasi redo/undo |

### Batasan yang diketahui

| Hal | Keterangan |
|---|---|
| Tipe skema di shared | `SchemaChange` dan `SchemaImage` masih punya tiga salinan (FRM, QP, CCM) dan **sudah beda semantik**: versi FRM memvalidasi ketat (pasangan before/after wajib ada, nama tabel cocok dan unik), versi QP/CCM menerima apa saja. Harus disepakati satu versi sebelum dipindah ke `shared/` |
| `contracts/` root | Salinan `sqlike.contract.*` masih tersebar: `IStorageManager` 4 salinan, `IFailureRecoveryManager` dan `IConcurrencyControlManager` masing-masing 3. Saat folder root-nya ada, hapus salinan di modul ini beserta `sqlike.storage.*` |
| `IWalDurability` | Ada di tiga package berbeda: `sqlike.contract` (FRM, QP), `sqlike.frm.log` (internal FR2), dan `sqlike.replacement_durability` (SM). Karena package-nya beda, SM belum benar-benar menyambung ke kontrak FRM (FR-08) |
| Undo perubahan skema | `SmPort.restoreSchema` belum ada, jadi FR3 menolak rollback transaksi yang punya record SCHEMA_CHANGE dengan `UNSUPPORTED_OPERATION` |
| LSN CLR di FR5 | `ChangeApplier.undo` menulis before-image dengan LSN record asli, bukan LSN CLR, sehingga page LSN bisa mundur. Tidak membuat skenario gagal |
| Milestone 3 | Integrasi dengan SM dan CCM asli (bukan mock) serta skenario akses berkonflik |

Tiga hal pertama menyentuh modul di luar `failure-recovery/`, jadi perlu koordinasi supergroup
lebih dulu (lihat aturan "Only modify code within your own group's component directory" di
`CONTRIBUTING.md`).
