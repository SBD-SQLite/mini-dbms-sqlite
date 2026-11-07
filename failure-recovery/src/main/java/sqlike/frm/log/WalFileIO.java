package sqlike.frm.log;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import static java.nio.file.StandardOpenOption.*;

// Membaca dan menulis frame WAL serta mendeteksi frame yang terpotong atau rusak.
final class WalFileIO {
    static final int MAX = 64 * 1024 * 1024;
    private static final int HEADER_SIZE = 3 * Integer.BYTES;

    static int crc(byte[] data) {
        CRC32 checksum = new CRC32();
        checksum.update(data);
        return (int) checksum.getValue();
    }

    static byte[] frame(byte[] payload) throws IOException {
        if (payload.length > MAX) {
            throw new IOException("WAL record exceeds 64 MiB");
        }

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buffer.putInt(payload.length);
        buffer.putInt(~payload.length);
        buffer.putInt(crc(payload));
        buffer.put(payload);
        return buffer.array();
    }

    static void write(FileChannel file, byte[] data) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
            file.write(buffer);
        }
    }

    static List<byte[]> read(Path path, boolean repairTail) throws IOException {
        List<byte[]> records = new ArrayList<>();
        Set<StandardOpenOption> options = repairTail ? Set.of(READ, WRITE) : Set.of(READ);

        try (FileChannel file = FileChannel.open(path, options)) {
            long fileSize = file.size();
            long offset = 0;

            while (offset < fileSize) {
                if (fileSize - offset < HEADER_SIZE) {
                    handleIncompleteTail(file, offset, repairTail);
                    break;
                }

                ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
                readFully(file, header);
                header.flip();

                int size = header.getInt();
                int complement = header.getInt();
                int savedChecksum = header.getInt();
                if (size < 0 || size > MAX || complement != ~size) {
                    throw new IOException("Corrupt frame header at " + offset);
                }

                if (fileSize - file.position() < size) {
                    handleIncompleteTail(file, offset, repairTail);
                    break;
                }

                ByteBuffer buffer = ByteBuffer.allocate(size);
                readFully(file, buffer);
                byte[] payload = buffer.array();
                if (crc(payload) != savedChecksum) {
                    throw new IOException("Checksum mismatch at " + offset);
                }

                records.add(payload);
                offset = file.position();
            }
        }
        return records;
    }

    private static void readFully(FileChannel file, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (file.read(buffer) < 0) {
                throw new EOFException("Unexpected end of WAL file");
            }
        }
    }

    private static void handleIncompleteTail(FileChannel file, long offset,
                                             boolean repairTail) throws IOException {
        if (!repairTail) {
            throw new IOException("Incomplete WAL frame at " + offset);
        }
        file.truncate(offset);
        file.force(true);
    }

    static void forceDirectory(Path directory) throws IOException {
        try (FileChannel file = FileChannel.open(directory, READ)) {
            file.force(true);
        }
    }

    static void create(Path path, byte[] data) throws IOException {
        Path filePath = path.toAbsolutePath();
        try (FileChannel file = FileChannel.open(filePath, CREATE_NEW, WRITE)) {
            write(file, data);
            file.force(true);
        }
        forceDirectory(filePath.getParent());
    }

    static void replaceFileAtomically(Path path, byte[] data) throws IOException {
        Path filePath = path.toAbsolutePath();
        Path temp = filePath.resolveSibling(filePath.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            create(temp, data);
            Files.move(temp, filePath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(filePath.getParent());
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}

