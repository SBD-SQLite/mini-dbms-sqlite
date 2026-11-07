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

    // SM-09 reads the configured page size; no disk behavior is changed.
    public int getPageSize() {
        return pageSize;
    }
}
