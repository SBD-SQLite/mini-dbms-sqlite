package sqlike;

import static org.junit.jupiter.api.Assertions.*;
import static sqlike.Fixtures.*;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import sqlike.block.Page;
import sqlike.record.RecordSerializer;
import sqlike.record.SlotStatus;
import sqlike.record.SlottedPage;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataType;
import sqlike.shared.PageId;
import sqlike.shared.TableSchema;
import sqlike.shared.Value;
import sqlike.storage.StorageErrorCode;

/** Slotted page layout and record (de)serialization (SM-05, SM-06). */
class PageAndSerializerTest {

    @Nested
    @DisplayName("SlottedPage")
    class Slotted {
        SlottedPage fresh(int size) {
            SlottedPage sp = new SlottedPage(new Page(new PageId("t", 0), new byte[size]));
            sp.initialize();
            return sp;
        }

        @Test void initializedPageIsEmpty() {
            SlottedPage sp = fresh(256);
            assertEquals(0, sp.getSlotCount());
            assertEquals(0, sp.getPageLSN());
            assertEquals(256 - 16, sp.getFreeSpace());
        }

        @Test void reserveInsertReadLifecycle() {
            SlottedPage sp = fresh(256);
            int slot = sp.reserveSlot(10).orElseThrow();
            assertEquals(SlotStatus.RESERVED, sp.getSlotStatus(slot));
            assertTrue(sp.getRecordBytes(slot).isEmpty(), "reserved slot must be invisible");
            byte[] rec = "0123456789".getBytes(StandardCharsets.UTF_8);
            sp.insertAtSlot(slot, rec);
            assertEquals(SlotStatus.OCCUPIED, sp.getSlotStatus(slot));
            assertArrayEquals(rec, sp.getRecordBytes(slot).orElseThrow());
        }

        @Test void releaseFreesTheSpaceAndSlotIsReused() {
            SlottedPage sp = fresh(256);
            int free = sp.getFreeSpace();
            int slot = sp.reserveSlot(20).orElseThrow();
            sp.releaseSlot(slot);
            assertEquals(SlotStatus.EMPTY, sp.getSlotStatus(slot));
            assertEquals(slot, sp.reserveSlot(20).orElseThrow(), "empty slot id must be reused");
            assertTrue(sp.getFreeSpace() <= free);
        }

        @Test void deleteMarksSlotDeletedAndHidesRecord() {
            SlottedPage sp = fresh(256);
            int slot = sp.reserveSlot(4).orElseThrow();
            sp.insertAtSlot(slot, new byte[] {1, 2, 3, 4});
            sp.deleteSlot(slot);
            assertEquals(SlotStatus.DELETED, sp.getSlotStatus(slot));
            assertTrue(sp.getRecordBytes(slot).isEmpty());
            assertDoesNotThrow(() -> sp.deleteSlot(slot), "double delete is a no-op");
        }

        @Test void updateInPlaceShrinkAndGrow() {
            SlottedPage sp = fresh(256);
            int a = sp.reserveSlot(8).orElseThrow();
            sp.insertAtSlot(a, new byte[8]);
            int b = sp.reserveSlot(8).orElseThrow();
            sp.insertAtSlot(b, "BBBBBBBB".getBytes());
            assertTrue(sp.updateRecordBytes(a, new byte[] {9, 9}));
            assertArrayEquals(new byte[] {9, 9}, sp.getRecordBytes(a).orElseThrow());
            assertTrue(sp.updateRecordBytes(a, new byte[30]), "growth fits in free space");
            assertEquals(30, sp.getRecordBytes(a).orElseThrow().length);
            assertArrayEquals("BBBBBBBB".getBytes(), sp.getRecordBytes(b).orElseThrow(), "neighbour must be intact");
        }

        @Test void updateThatCannotFitReturnsFalse() {
            SlottedPage sp = fresh(64);
            int a = sp.reserveSlot(8).orElseThrow();
            sp.insertAtSlot(a, new byte[8]);
            assertFalse(sp.updateRecordBytes(a, new byte[200]));
        }

        @Test void fullPageRefusesReservations() {
            SlottedPage sp = fresh(64);
            int count = 0;
            while (sp.reserveSlot(10).isPresent()) count++;
            assertTrue(count >= 1);
            assertTrue(sp.reserveSlot(10).isEmpty());
            assertFalse(sp.canFit(10));
        }

        @Test void insertValidatesStateAndSize() {
            SlottedPage sp = fresh(128);
            int slot = sp.reserveSlot(4).orElseThrow();
            assertThrows(IllegalArgumentException.class, () -> sp.insertAtSlot(slot, new byte[64]));
            sp.insertAtSlot(slot, new byte[4]);
            assertThrows(IllegalStateException.class, () -> sp.insertAtSlot(slot, new byte[4]));
            assertThrows(IllegalStateException.class, () -> sp.releaseSlot(slot));
        }

        @Test void putAtSlotRestoresAtSpecificSlotAndExtendsDirectory() {
            SlottedPage sp = fresh(256);
            sp.putAtSlot(3, new byte[] {7, 7});
            assertEquals(4, sp.getSlotCount());
            assertEquals(SlotStatus.EMPTY, sp.getSlotStatus(0));
            assertArrayEquals(new byte[] {7, 7}, sp.getRecordBytes(3).orElseThrow());
        }

        @Test void pageLsnOnlyMovesForwardAndMustBePositive() {
            SlottedPage sp = fresh(128);
            sp.setPageLSN(10);
            sp.setPageLSN(4);
            assertEquals(10, sp.getPageLSN());
            assertThrows(IllegalArgumentException.class, () -> sp.setPageLSN(0));
            assertEquals(10, sp.getPage().getPageLSN());
        }

        @Test void corruptHeaderIsRejected() {
            byte[] raw = new byte[64];
            raw[11] = 100; // slot count far beyond capacity
            SlottedPage sp = new SlottedPage(new Page(new PageId("t", 0), raw));
            assertThrows(IllegalStateException.class, sp::getSlotCount);
        }
    }

    @Nested
    @DisplayName("RecordSerializer")
    class Serializer {
        final RecordSerializer ser = new RecordSerializer();
        final TableSchema schema = items("t_items", "items");

        @Test void roundTripAllFourTypes() {
            List<Value> row = item(-5, "héllo wörld", 3.14159, "AB");
            byte[] bytes = ser.serialize(row, schema);
            assertEquals(row, ser.deserialize(bytes, schema));
            assertEquals(bytes.length, ser.serializedSize(row, schema));
        }

        @Test void extremesAndEmptyText() {
            List<Value> row = List.of(i(Integer.MIN_VALUE), vc(""), f(-0.0), ch(""));
            assertEquals(row, ser.deserialize(ser.serialize(row, schema), schema));
            assertEquals(Integer.MAX_VALUE,
                    ser.deserialize(ser.serialize(item(Integer.MAX_VALUE, "x", 1, "x"), schema), schema)
                            .get(0).integerValue().orElseThrow());
        }

        @Test void maxLengthCountsCodePointsNotBytes() {
            TableSchema s = new TableSchema("t", "t", List.of(col("c", DataType.VARCHAR, 3)));
            assertDoesNotThrow(() -> ser.serialize(List.of(vc("日本語")), s));
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> ser.serialize(List.of(vc("日本語!")), s)));
        }

        @Test void wrongArityTypeAndNullAreRejected() {
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> ser.serialize(List.of(i(1)), schema)));
            assertEquals(StorageErrorCode.INVALID_VALUE,
                    codeOf(() -> ser.serialize(List.of(vc("x"), vc("n"), f(1), ch("c")), schema)));
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> ser.serialize(null, schema)));
            assertEquals(StorageErrorCode.INVALID_VALUE,
                    codeOf(() -> ser.serialize(item(1, "n", 1, "c").subList(0, 3), schema)));
        }

        @Test void nonFiniteFloatIsRejected() {
            assertEquals(StorageErrorCode.INVALID_VALUE,
                    codeOf(() -> ser.serialize(item(1, "n", Double.NaN, "c"), schema)));
            assertEquals(StorageErrorCode.INVALID_VALUE,
                    codeOf(() -> ser.serialize(item(1, "n", Double.POSITIVE_INFINITY, "c"), schema)));
        }

        @Test void valueWithTwoPayloadsOrNoPayloadIsRejected() {
            Value both = new Value(DataType.INTEGER, Optional.of(1), Optional.of(1.0), Optional.empty());
            Value none = new Value(DataType.INTEGER, Optional.empty(), Optional.empty(), Optional.empty());
            TableSchema s = new TableSchema("t", "t", List.of(col("c", DataType.INTEGER)));
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> ser.serialize(List.of(both), s)));
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> ser.serialize(List.of(none), s)));
        }

        @Test void invalidColumnDefinitionsAreRejected() {
            TableSchema numericWithLen = new TableSchema("t", "t",
                    List.of(new ColumnSchema("c", DataType.INTEGER, Optional.of(4))));
            TableSchema textNoLen = new TableSchema("t", "t", List.of(col("c", DataType.VARCHAR)));
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> ser.serialize(List.of(i(1)), numericWithLen)));
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> ser.serialize(List.of(vc("a")), textNoLen)));
        }

        @Test void corruptedBytesAreDetectedOnDeserialize() {
            byte[] good = ser.serialize(item(1, "abc", 1, "cd"), schema);
            byte[] truncated = java.util.Arrays.copyOf(good, good.length - 3);
            assertEquals(StorageErrorCode.IO_ERROR, codeOf(() -> ser.deserialize(truncated, schema)));
            byte[] trailing = java.util.Arrays.copyOf(good, good.length + 2);
            assertEquals(StorageErrorCode.IO_ERROR, codeOf(() -> ser.deserialize(trailing, schema)));
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> ser.deserialize(null, schema)));
        }

        @Test void encodeDecodeSingleValueAndSizes() {
            ColumnSchema c = col("n", DataType.VARCHAR, 10);
            byte[] enc = ser.encodeValue(vc("abc"), c);
            assertEquals(4 + 3, enc.length);
            assertEquals(enc.length, ser.encodedValueSize(vc("abc"), c));
            assertEquals(vc("abc"), ser.decodeValue(enc, 0, c));
            assertEquals(4, ser.encodedValueSize(i(1), col("i", DataType.INTEGER)));
            assertEquals(8, ser.encodedValueSize(f(1), col("f", DataType.FLOAT)));
        }

        @Test void onlyBigEndianIsAccepted() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RecordSerializer(StandardCharsets.UTF_8, ByteOrder.LITTLE_ENDIAN));
            TableSchema s = new TableSchema("t", "t", List.of(col("c", DataType.INTEGER)));
            assertEquals(1, ser.serialize(List.of(i(1)), s)[3]);
        }
    }
}
