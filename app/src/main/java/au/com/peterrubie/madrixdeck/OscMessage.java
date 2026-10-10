package au.com.peterrubie.madrixdeck;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Minimal OSC 1.0 message: encodes int, float and string arguments; decodes messages and bundles. */
public final class OscMessage {

    public final String address;
    public final List<Object> args;

    public OscMessage(String address, List<Object> args) {
        this.address = address;
        this.args = args == null ? Collections.emptyList() : args;
    }

    public static OscMessage of(String address, Object... args) {
        List<Object> list = new ArrayList<>();
        Collections.addAll(list, args);
        return new OscMessage(address, list);
    }

    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, address);
        StringBuilder tags = new StringBuilder(",");
        for (Object a : args) {
            if (a instanceof Integer || a instanceof Long || a instanceof Short) {
                tags.append('i');
            } else if (a instanceof Float || a instanceof Double) {
                tags.append('f');
            } else {
                tags.append('s');
            }
        }
        writeString(out, tags.toString());
        for (Object a : args) {
            if (a instanceof Integer || a instanceof Long || a instanceof Short) {
                writeInt(out, ((Number) a).intValue());
            } else if (a instanceof Float || a instanceof Double) {
                writeInt(out, Float.floatToIntBits(((Number) a).floatValue()));
            } else {
                writeString(out, String.valueOf(a));
            }
        }
        return out.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
        int pad = 4 - (b.length % 4);
        for (int i = 0; i < pad; i++) {
            out.write(0);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    /** Decodes a UDP packet (message or bundle). Malformed data is ignored. */
    public static List<OscMessage> decodePacket(byte[] data, int offset, int length) {
        List<OscMessage> out = new ArrayList<>();
        try {
            decodeInto(data, offset, length, out, 0);
        } catch (RuntimeException ignored) {
            // Malformed packet: return whatever was decoded so far.
        }
        return out;
    }

    private static void decodeInto(byte[] data, int offset, int length, List<OscMessage> out, int depth) {
        if (length < 4 || depth > 8) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, offset, length);
        String first = readString(buf);
        if ("#bundle".equals(first)) {
            buf.getLong(); // time tag
            while (buf.remaining() >= 4) {
                int size = buf.getInt();
                if (size <= 0 || size > buf.remaining()) {
                    return;
                }
                int pos = buf.position();
                decodeInto(data, pos, size, out, depth + 1);
                buf.position(pos + size);
            }
            return;
        }
        List<Object> args = new ArrayList<>();
        if (buf.remaining() > 0) {
            String tags = readString(buf);
            if (tags.startsWith(",")) {
                for (int i = 1; i < tags.length(); i++) {
                    char t = tags.charAt(i);
                    switch (t) {
                        case 'i': args.add(buf.getInt()); break;
                        case 'f': args.add(buf.getFloat()); break;
                        case 's': args.add(readString(buf)); break;
                        case 'h': args.add((int) buf.getLong()); break;
                        case 'd': args.add((float) buf.getDouble()); break;
                        case 'T': args.add(1); break;
                        case 'F': args.add(0); break;
                        case 'b': {
                            int n = buf.getInt();
                            buf.position(buf.position() + ((n + 3) & ~3));
                            break;
                        }
                        default: break; // N, I and unknown tags carry no data we use
                    }
                }
            }
        }
        out.add(new OscMessage(first, args));
    }

    private static String readString(ByteBuffer buf) {
        int start = buf.position();
        int end = start;
        int limit = buf.limit();
        byte[] arr = buf.array();
        while (end < limit && arr[end] != 0) {
            end++;
        }
        String s = new String(arr, start, end - start, StandardCharsets.UTF_8);
        int consumed = (end - start) + 1;
        int padded = (consumed + 3) & ~3;
        buf.position(Math.min(limit, start + padded));
        return s;
    }

    public Double number(int index) {
        if (index >= args.size()) {
            return null;
        }
        Object a = args.get(index);
        return a instanceof Number ? ((Number) a).doubleValue() : null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(address);
        for (Object a : args) {
            sb.append(' ').append(a instanceof Float ? trimFloat((Float) a) : String.valueOf(a));
        }
        return sb.toString();
    }

    static String trimFloat(float f) {
        if (f == Math.rint(f)) {
            return String.format(java.util.Locale.US, "%.1f", f);
        }
        String s = String.format(java.util.Locale.US, "%.4f", f);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
