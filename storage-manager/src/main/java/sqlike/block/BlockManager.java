package sqlike.block;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import sqlike.shared.PageId;
import sqlike.storage.StorageErrorCode;
import sqlike.storage.StorageException;
/**
 * Lapisan paling bawah: memetakan (tableId, pageNumber) ke rentang byte di sebuah berkas
 * dan melakukan I/O page mentah. Tidak tahu apa-apa soal cache, WAL, record, atau skema.
 * Hanya BufferPoolManager yang seharusnya memanggil kelas ini.
 *
 * Format berkas: page 0, page 1, page 2, ... berurutan, tanpa header dan tanpa pemisah.
 * Page n berada di byte [n * pageSize, (n + 1) * pageSize).
 */
public class BlockManager {
    public static final int DEFAULT_PAGE_SIZE = 4096;

    /** tableId hanya boleh huruf, angka, underscore, dan minus (mencegah path traversal). */
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_\\-]+");
    private static final String INDEX_PREFIX = "idx_";

    private Path dataDirectory;
    private int pageSize;
    private Map<String, FileChannel> openFiles = new ConcurrentHashMap<>();

    public BlockManager(Path dataDirectory) throws StorageException {
        this(dataDirectory, DEFAULT_PAGE_SIZE);
    }

    public BlockManager(Path dataDirectory, int pageSize) throws StorageException {
        if (pageSize <= 0) {
            throw new StorageException(StorageErrorCode.INVALID_VALUE, "pageSize harus > 0: " + pageSize);
        }
        this.dataDirectory = dataDirectory;
        this.pageSize = pageSize;
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR,
                    "Gagal membuat direktori data: " + dataDirectory, e);
        }
    }

    // ------------------------------------------------------------------ operasi publik

    /** Baca block ke memori. Page harus sudah dialokasikan (0 <= pageNumber < jumlah page). */
    public Page readPage(PageId id) throws StorageException {
        FileChannel ch = channelFor(id.tableId());
        checkInRange(id, pageCountOf(ch, id.tableId()));

        ByteBuffer buf = ByteBuffer.allocate(pageSize);
        long offset = offsetOf(id.pageNumber());
        try {
            // read() boleh mengembalikan kurang dari yang diminta, jadi diulang sampai penuh
            while (buf.hasRemaining()) {
                int n = ch.read(buf, offset + buf.position());
                if (n < 0) {
                    throw new StorageException(StorageErrorCode.CORRUPTED_DATA,
                            "Berkas berakhir di tengah page " + id);
                }
            }
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Gagal membaca page " + id, e);
        }
        return new Page(id, buf.array());
    }

    /**
     * Timpa block dengan isi page. Hanya menulis ke OS (belum tentu permanen);
     * kedurabilitasan datang dari force(). Page harus sudah dialokasikan.
     */
    public void writePage(Page page) throws StorageException {
        PageId id = page.getPageId();
        byte[] data = page.getData();
        if (data.length != pageSize) {
            throw new StorageException(StorageErrorCode.INVALID_VALUE,
                    "Ukuran page " + data.length + " != " + pageSize);
        }
        FileChannel ch = channelFor(id.tableId());
        checkInRange(id, pageCountOf(ch, id.tableId()));
        writeFully(ch, ByteBuffer.wrap(data), offsetOf(id.pageNumber()), id);
    }

    /**
     * Bubuhkan satu page nol di akhir berkas dan kembalikan PageId-nya (= jumlah page sebelumnya).
     * Page benar-benar ditulis sehingga berkas bertambah; jumlah page selalu diturunkan dari ukuran berkas.
     * Isi page masih nol: penginisialisasi (mis. header slotted page) adalah tugas pemanggil.
     */
    public synchronized PageId allocatePage(String tableId) throws StorageException {
        FileChannel ch = channelFor(tableId);
        long number = pageCountOf(ch, tableId);
        PageId id = new PageId(tableId, number);
        writeFully(ch, ByteBuffer.allocate(pageSize), offsetOf(number), id);
        return id;
    }

    /** Jumlah page di berkas = ukuran berkas / pageSize. Dipakai untuk statistik b_r dan batas scan. */
    public long getPageCount(String tableId) throws StorageException {
        return pageCountOf(channelFor(tableId), tableId);
    }

    /** fsync berkas ini (data dan metadata, karena allocatePage mengubah panjang berkas). */
    public void force(String tableId) throws StorageException {
        FileChannel ch = openFiles.get(tableId);
        if (ch == null) {
            return; // belum pernah dibuka: tidak ada yang perlu di-flush
        }
        try {
            ch.force(true);
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Gagal force berkas " + tableId, e);
        }
    }

    /** Kosongkan berkas menjadi 0 page (untuk CLEAR TABLE). */
    public synchronized void truncate(String tableId) throws StorageException {
        FileChannel ch = channelFor(tableId);
        try {
            ch.truncate(0);
            ch.force(true);
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Gagal truncate " + tableId, e);
        }
    }

    /** Tutup dan hapus berkas (untuk DROP TABLE / DROP INDEX). */
    public synchronized void deleteFile(String tableId) throws StorageException {
        FileChannel ch = openFiles.remove(tableId);
        try {
            if (ch != null) {
                ch.close();
            }
            Files.deleteIfExists(filePath(tableId));
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Gagal menghapus berkas " + tableId, e);
        }
    }

    /** Force lalu tutup semua berkas. Aman dipanggil berulang kali. */
    public synchronized void close() throws StorageException {
        StorageException first = null;
        for (Map.Entry<String, FileChannel> entry : openFiles.entrySet()) {
            try {
                entry.getValue().force(true);
                entry.getValue().close();
            } catch (IOException e) {
                if (first == null) {
                    first = new StorageException(StorageErrorCode.IO_ERROR,
                            "Gagal menutup berkas " + entry.getKey(), e);
                }
            }
        }
        openFiles.clear();
        if (first != null) {
            throw first;
        }
    }

    public int getPageSize() {
        return pageSize;
    }

    // ------------------------------------------------------------------ helper privat

    /** tableId -> path. "idx_*" menjadi .idx, lainnya .tbl. */
    private Path filePath(String tableId) throws StorageException {
        if (tableId == null || !VALID_ID.matcher(tableId).matches()) {
            throw new StorageException(StorageErrorCode.INVALID_VALUE, "tableId tidak valid: " + tableId);
        }
        String ext = tableId.startsWith(INDEX_PREFIX) ? ".idx" : ".tbl";
        return dataDirectory.resolve(tableId + ext);
    }

    /** Buka berkas saat pertama kali dipakai (dibuat jika belum ada), lalu simpan di cache. */
    private synchronized FileChannel channelFor(String tableId) throws StorageException {
        FileChannel existing = openFiles.get(tableId);
        if (existing != null) {
            return existing;
        }

        try {
            FileChannel opened = FileChannel.open(filePath(tableId),
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            openFiles.put(tableId, opened);
            return opened;
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Gagal membuka berkas " + tableId, e);
        }
    }

    /** synchronized agar tidak membaca ukuran berkas di tengah allocatePage yang sedang menulis. */
    private synchronized long pageCountOf(FileChannel ch, String tableId) throws StorageException {
        try {
            long size = ch.size();
            if (size % pageSize != 0) {
                throw new StorageException(StorageErrorCode.CORRUPTED_DATA,
                        "Ukuran berkas " + tableId + " (" + size + ") bukan kelipatan " + pageSize);
            }
            return size / pageSize;
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Gagal membaca ukuran " + tableId, e);
        }
    }

    private void checkInRange(PageId id, long pageCount) throws StorageException {
        if (id.pageNumber() < 0 || id.pageNumber() >= pageCount) {
            throw new StorageException(StorageErrorCode.INVALID_VALUE,
                    "Page di luar jangkauan: " + id + " (jumlah page " + pageCount + ")");
        }
    }

    private long offsetOf(long pageNumber) {
        return pageNumber * (long) pageSize; // long, supaya tidak overflow di berkas besar
    }

    /** write() boleh menulis sebagian, jadi diulang sampai buffer habis. */
    private void writeFully(FileChannel ch, ByteBuffer buf, long offset, PageId id) throws StorageException {
        try {
            long position = offset;
            while (buf.hasRemaining()) {
                position += ch.write(buf, position);
            }
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Gagal menulis page " + id, e);
        }
    }
}
