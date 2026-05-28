package com.ngr.blefwu;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

final class SmpCodec {
    static final int DEFAULT_SMP_PAYLOAD_SIZE = 384;
    static final int SMP_GROUP_IMAGE = 1;
    static final int SMP_ID_IMAGE_UPLOAD = 1;
    static final int SMP_OP_WRITE = 2;
    static final int SMP_OP_WRITE_RSP = 3;
    static final int DEFAULT_SMP_WINDOW_SIZE = 10;
    static final int DEFAULT_SMP_RETRY_COUNT = 3;

    private SmpCodec() {
    }

    static byte[] imageUploadRequest(int sequence, int slot, int offset, byte[] data, int totalSize) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        int items = offset == 0 ? 4 : 2;
        payload.write(0xA0 + items);
        if (offset == 0) {
            writeText(payload, "image");
            writeUInt(payload, slot + 1);
            writeText(payload, "len");
            writeUInt(payload, totalSize);
        }
        writeText(payload, "off");
        writeUInt(payload, offset);
        writeText(payload, "data");
        writeBytes(payload, data);

        byte[] body = payload.toByteArray();
        ByteBuffer header = ByteBuffer.allocate(8 + body.length).order(ByteOrder.BIG_ENDIAN);
        header.put((byte) SMP_OP_WRITE);
        header.put((byte) 0);
        header.putShort((short) body.length);
        header.putShort((short) SMP_GROUP_IMAGE);
        header.put((byte) sequence);
        header.put((byte) SMP_ID_IMAGE_UPLOAD);
        header.put(body);
        return header.array();
    }

    static SmpResponse parseImageUploadResponse(byte[] packet) throws SmpResponseException {
        if (packet.length < 8) {
            throw new SmpResponseException("SMP response is shorter than the 8-byte header");
        }
        ByteBuffer header = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        int op = header.get() & 0xFF;
        header.get();
        int length = header.getShort() & 0xFFFF;
        int group = header.getShort() & 0xFFFF;
        int sequence = header.get() & 0xFF;
        int command = header.get() & 0xFF;
        if ((op != SMP_OP_WRITE_RSP && op != SMP_OP_WRITE) || group != SMP_GROUP_IMAGE || command != SMP_ID_IMAGE_UPLOAD) {
            throw new SmpResponseException("Unexpected SMP response header");
        }
        if (length == 0) {
            return new SmpResponse(sequence, -1);
        }

        Object decoded;
        try {
            decoded = decodeValue(Arrays.copyOfRange(packet, 8, 8 + length), new Index());
        } catch (RuntimeException e) {
            throw new SmpResponseException("Could not decode SMP CBOR response: " + e.getMessage(), e);
        }
        if (!(decoded instanceof Map)) {
            throw new SmpResponseException("Expected CBOR map in SMP response");
        }
        Map<?, ?> values = (Map<?, ?>) decoded;
        Object err = values.get("err");
        if (err instanceof Map) {
            Map<?, ?> error = (Map<?, ?>) err;
            throw new SmpResponseException(
                    "SMP upload failed with group=" + error.get("group") + " rc=" + error.get("rc"));
        }
        Object rc = values.get("rc");
        if (rc instanceof Number && ((Number) rc).intValue() != 0) {
            throw new SmpResponseException("SMP upload failed with rc=" + rc);
        }
        Object off = values.get("off");
        return new SmpResponse(sequence, off instanceof Number ? ((Number) off).intValue() : -1);
    }

    private static void writeUInt(ByteArrayOutputStream out, int value) {
        if (value < 24) {
            out.write(value);
        } else if (value <= 0xFF) {
            out.write(0x18);
            out.write(value);
        } else if (value <= 0xFFFF) {
            out.write(0x19);
            out.write((value >> 8) & 0xFF);
            out.write(value & 0xFF);
        } else {
            out.write(0x1A);
            out.write((value >> 24) & 0xFF);
            out.write((value >> 16) & 0xFF);
            out.write((value >> 8) & 0xFF);
            out.write(value & 0xFF);
        }
    }

    private static void writeText(ByteArrayOutputStream out, String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        out.write(0x60 + raw.length);
        out.write(raw, 0, raw.length);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] value) {
        int length = value.length;
        if (length < 24) {
            out.write(0x40 + length);
        } else if (length <= 0xFF) {
            out.write(0x58);
            out.write(length);
        } else {
            out.write(0x59);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        }
        out.write(value, 0, value.length);
    }

    private static Object decodeValue(byte[] data, Index index) {
        int head = data[index.value++] & 0xFF;
        int major = head >> 5;
        int value = head & 0x1F;
        if (major == 0) {
            return readLength(data, index, value);
        }
        if (major == 1) {
            return -1 - readLength(data, index, value);
        }
        if (major == 2) {
            int length = readLength(data, index, value);
            if (length < 0) {
                throw new IllegalArgumentException("Indefinite CBOR bytes are not supported");
            }
            byte[] bytes = Arrays.copyOfRange(data, index.value, index.value + length);
            index.value += length;
            return bytes;
        }
        if (major == 3) {
            int length = readLength(data, index, value);
            if (length < 0) {
                throw new IllegalArgumentException("Indefinite CBOR text is not supported");
            }
            String text = new String(data, index.value, length, StandardCharsets.UTF_8);
            index.value += length;
            return text;
        }
        if (major == 4) {
            int length = readLength(data, index, value);
            Object[] values;
            if (length < 0) {
                java.util.ArrayList<Object> items = new java.util.ArrayList<>();
                while ((data[index.value] & 0xFF) != 0xFF) {
                    items.add(decodeValue(data, index));
                }
                index.value++;
                return items;
            }
            values = new Object[length];
            for (int i = 0; i < length; i++) {
                values[i] = decodeValue(data, index);
            }
            return java.util.Arrays.asList(values);
        }
        if (major == 5) {
            int length = readLength(data, index, value);
            Map<Object, Object> values = new LinkedHashMap<>();
            if (length < 0) {
                while ((data[index.value] & 0xFF) != 0xFF) {
                    Object key = decodeValue(data, index);
                    Object item = decodeValue(data, index);
                    values.put(key, item);
                }
                index.value++;
                return values;
            }
            for (int i = 0; i < length; i++) {
                Object key = decodeValue(data, index);
                Object item = decodeValue(data, index);
                values.put(key, item);
            }
            return values;
        }
        if (major == 7) {
            if (value == 20) return false;
            if (value == 21) return true;
            if (value == 22 || value == 23) return null;
        }
        throw new IllegalArgumentException("Unsupported CBOR type major=" + major + " value=" + value);
    }

    private static int readLength(byte[] data, Index index, int value) {
        if (value < 24) return value;
        if (value == 24) return data[index.value++] & 0xFF;
        if (value == 25) {
            int parsed = ((data[index.value] & 0xFF) << 8) | (data[index.value + 1] & 0xFF);
            index.value += 2;
            return parsed;
        }
        if (value == 26) {
            int parsed = ((data[index.value] & 0xFF) << 24)
                    | ((data[index.value + 1] & 0xFF) << 16)
                    | ((data[index.value + 2] & 0xFF) << 8)
                    | (data[index.value + 3] & 0xFF);
            index.value += 4;
            return parsed;
        }
        if (value == 27) {
            long parsed = 0;
            for (int i = 0; i < 8; i++) {
                parsed = (parsed << 8) | (data[index.value + i] & 0xFFL);
            }
            index.value += 8;
            if (parsed > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("CBOR integer is too large for this SMP field");
            }
            return (int) parsed;
        }
        if (value == 31) return -1;
        throw new IllegalArgumentException("Unsupported CBOR integer width");
    }

    static final class SmpResponse {
        final int sequence;
        final int nextOffset;

        SmpResponse(int sequence, int nextOffset) {
            this.sequence = sequence;
            this.nextOffset = nextOffset;
        }
    }

    static final class SmpResponseException extends Exception {
        SmpResponseException(String message) {
            super(message);
        }

        SmpResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Index {
        int value;
    }
}
