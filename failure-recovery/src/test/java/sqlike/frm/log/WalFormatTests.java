package sqlike.frm.log;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import sqlike.shared.*;
import static sqlike.frm.log.WalTestSupport.*;

final class WalFormatTests {
    static void runAll() {
        run("Encode/decode menyimpan isi record", WalFormatTests::encoder);
        run("Checksum menolak data rusak", WalFormatTests::checksum);
        run("Frame terakhir yang terpotong bisa diperbaiki", WalFormatTests::tailRepair);
    }

    private static void encoder() throws Exception {
        List<Value> values = new ArrayList<>();
        values.add(new Value(DataType.INTEGER, Optional.of(10), Optional.empty(), Optional.empty()));
        values.add(new Value(DataType.FLOAT, Optional.empty(), Optional.of(1.5), Optional.empty()));
        values.add(new Value(DataType.CHAR, Optional.empty(), Optional.empty(), Optional.of("A")));
        values.add(new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of("nama\n雪")));
        RecordImage data = new RecordImage(true, values);
        LogRecord log = new LogRecord(2, TIME, Optional.of(1L), LogType.INSERT, Optional.of(change(ChangeOperation.INSERT, ABSENT, data)), Optional.empty());
        byte[] bytes = LogRecordEncoder.encode(log);
        eq(log, LogRecordEncoder.decode(bytes));
        expect(IOException.class, () -> LogRecordEncoder.decode(Arrays.copyOf(bytes, bytes.length - 1)));
    }

    private static void checksum() throws Exception {
        try (TestLog test = new TestLog()) {
            Path path = test.dir.resolve("frame.log");
            byte[] data = {1, 2, 3};
            byte[] frame = WalFileIO.frame(data);
            Files.write(path, frame);
            check(Arrays.equals(data, WalFileIO.read(path, false).get(0)), "Isi frame berubah");
            frame[frame.length - 1] ^= 1;
            Files.write(path, frame);
            expect(IOException.class, () -> WalFileIO.read(path, true));
            check(Arrays.equals(frame, Files.readAllBytes(path)), "File rusak dipotong diam-diam");
        }
    }

    private static void tailRepair() throws Exception {
        try (TestLog test = new TestLog()) {
            Path path = test.dir.resolve("tail.log");
            byte[] first = WalFileIO.frame(new byte[] {1, 2});
            byte[] last = WalFileIO.frame(new byte[] {3, 4});
            Files.write(path, first);
            Files.write(path, Arrays.copyOf(last, last.length - 1), StandardOpenOption.APPEND);
            expect(IOException.class, () -> WalFileIO.read(path, false));
            eq(1, WalFileIO.read(path, true).size());
            check(Arrays.equals(first, Files.readAllBytes(path)), "Frame lengkap ikut terhapus");
        }
    }
}
