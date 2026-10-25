# Contributing

Branch `main` dikunci: tidak bisa push langsung, semua perubahan masuk lewat Pull Request.

## Alur kerja

1. Update `main`, lalu buat branch baru:
   ```bash
   git switch main && git pull
   git switch -c feat/qp/select-parser
   ```
2. Commit dengan format konvensi di bawah, lalu push branch.
3. Buka PR ke `main`. Judul PR juga ikut format konvensi.
4. PR bisa di-merge kalau:
   - check **Conventions** lulus, dan
   - sudah di-approve minimal 1 orang dari **@SBD-SQLite/group-leads**.

   Kalau ada commit baru setelah approve, approval-nya hangus dan perlu di-approve ulang.

## Konvensi

**Tipe:** `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `ci`, `chore`

**Komponen:** `qp` (Query Processor), `qo` (Query Optimizer), `sm` (Storage Manager), `ccm` (Concurrency Control), `frm` (Failure Recovery), `global` (lintas komponen / repo)

| | Format | Contoh |
|---|---|---|
| Branch | `<tipe>/<komponen>/<deskripsi-kebab-case>` | `fix/sm/buffer-flush` |
| Commit & judul PR | `<tipe>(<komponen>): <deskripsi>` (komponen opsional) | `feat(qo): add join reordering` |

Commit `Merge ...` dan `Revert ...` bawaan git diperbolehkan.

## Aturan tubes

- Hanya ubah kode di folder komponen group sendiri. Perubahan lintas komponen dikoordinasikan dulu.
- Hanya library standar (dilarang sqlite3, pandas, library parser SQL, dll).
