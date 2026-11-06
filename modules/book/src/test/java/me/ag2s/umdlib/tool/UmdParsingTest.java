package me.ag2s.umdlib.tool;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.Assert.*;

public class UmdParsingTest {
    private StreamReader shortReader(byte... bytes) throws IOException {
        return new StreamReader(new FilterInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, 1));
            }
        });
    }

    @Test
    public void readsFieldsAcrossShortReads() throws Exception {
        StreamReader reader = shortReader((byte) 0xff, (byte) 0x80,
                (byte) 1, (byte) 2, (byte) 3, (byte) 4,
                (byte) 5, (byte) 6, (byte) 7, (byte) 8,
                (byte) 9, (byte) 10, (byte) 11, (byte) 12);
        assertEquals(255, reader.readUint8());
        assertEquals((byte) 0x80, reader.readByte());
        assertEquals(0x0102, reader.readShort());
        assertEquals(0x0403, reader.readShortLe());
        assertEquals(0x05060708, reader.readInt());
        assertEquals(0x0c0b0a09, reader.readIntLe());
        assertEquals(14, reader.getOffset());
    }

    @Test
    public void fillsArraysAndPreservesBytesOutsideRange() throws Exception {
        StreamReader reader = shortReader((byte) 1, (byte) 2, (byte) 3, (byte) 4,
                (byte) 5, (byte) 6, (byte) 7, (byte) 8);
        assertArrayEquals(new byte[]{1, 2}, reader.readBytes(2));
        assertArrayEquals(new byte[]{3, 4}, reader.read(new byte[2]));
        byte[] range = {9, 9, 9, 9};
        assertSame(range, reader.read(range, 1, 2));
        assertArrayEquals(new byte[]{9, 5, 6, 9}, range);
        assertEquals("0708", reader.readHex(2));
        assertEquals(8, reader.getOffset());
    }

    @Test
    public void reportsTruncationAndActualOffset() throws Exception {
        StreamReader reader = shortReader((byte) 1, (byte) 2);
        assertThrows(EOFException.class, reader::readIntLe);
        assertEquals(2, reader.getOffset());
        assertThrows(EOFException.class, reader::readByte);
    }

    @Test(timeout = 1000)
    public void toleratesZeroLengthProgressFromStream() throws Exception {
        InputStream input = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override
            public int read(byte[] b, int off, int len) { return 0; }
        };
        StreamReader reader = new StreamReader(input);
        assertArrayEquals(new byte[]{1, 2, 3}, reader.readBytes(3));
        assertThrows(EOFException.class, reader::readByte);
    }

    @Test
    public void skipChecksForTruncatedInput() throws Exception {
        StreamReader reader = shortReader((byte) 1, (byte) 2);
        reader.skip(1);
        assertEquals(1, reader.getOffset());
        assertThrows(EOFException.class, () -> reader.skip(2));
        assertEquals(2, reader.getOffset());
    }

    @Test
    public void validatesArrayRangeWithoutConsumingInput() throws Exception {
        StreamReader reader = shortReader((byte) 1);
        assertThrows(IndexOutOfBoundsException.class, () -> reader.read(new byte[1], 1, 1));
        assertEquals(0, reader.getOffset());
        assertEquals(1, reader.readUint8());
    }

    @Test
    public void retainsLegacyZeroLengthReadAndLongOffsets() throws Exception {
        StreamReader reader = shortReader((byte) 1);
        assertNull(reader.readBytes(0));
        reader.setOffset(Integer.MAX_VALUE);
        reader.readByte();
        assertEquals(2147483648L, reader.getOffset());
    }

    private byte[] compressed(byte[] plain, byte[] dictionary) throws IOException {
        Deflater deflater = new Deflater();
        try {
            if (dictionary != null) deflater.setDictionary(dictionary);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (DeflaterOutputStream stream = new DeflaterOutputStream(output, deflater)) {
                stream.write(plain);
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    @Test(timeout = 1000)
    public void decompressesMultipleBuffersAndEmptyContent() throws Exception {
        byte[] plain = new byte[40000];
        Arrays.fill(plain, (byte) 'a');
        assertArrayEquals(plain, UmdUtils.decompress(compressed(plain, null)));
        assertArrayEquals(new byte[0], UmdUtils.decompress(compressed(new byte[0], null)));
    }

    @Test(timeout = 1000)
    public void rejectsTruncatedHeaderAndPayload() throws Exception {
        assertThrows(EOFException.class, () -> UmdUtils.decompress(new byte[0]));
        assertThrows(EOFException.class, () -> UmdUtils.decompress(new byte[]{0x78, (byte) 0x9c}));
        byte[] data = compressed("reading".getBytes(StandardCharsets.UTF_8), null);
        assertThrows(EOFException.class, () -> UmdUtils.decompress(Arrays.copyOf(data, data.length - 1)));
    }

    @Test(timeout = 1000)
    public void rejectsDictionaryAndInvalidData() throws Exception {
        byte[] plain = "reading".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> UmdUtils.decompress(compressed(plain, plain)));
        assertThrows(java.util.zip.DataFormatException.class,
                () -> UmdUtils.decompress(new byte[]{1, 2, 3, 4}));
        assertArrayEquals(plain, UmdUtils.decompress(compressed(plain, null)));
    }
}
