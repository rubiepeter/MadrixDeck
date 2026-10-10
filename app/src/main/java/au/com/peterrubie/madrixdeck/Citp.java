package au.com.peterrubie.madrixdeck;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CITP 1.0 / MSEX 1.0-1.2 packets used for MADRIX's live Main Output stream.
 * All integers are little-endian; cookies are their ASCII characters in order.
 * Pure Java so it can be tested off the device.
 */
public final class Citp {

    public static final int PORT = 4809;
    public static final String GROUP = "239.224.0.180";
    public static final String LEGACY_GROUP = "224.0.0.180";
    public static final int HEADER = 20;
    public static final int MSEX_HEADER = 26;

    private Citp() {
    }

    // ---------- building ----------

    public static byte[] packet(String layer, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(HEADER + body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(cookie("CITP"));
        b.put((byte) 1);
        b.put((byte) 0);
        b.putShort((short) 0);            // request index (unused)
        b.putInt(HEADER + body.length);   // message size incl. header
        b.putShort((short) 1);            // part count
        b.putShort((short) 0);            // part index (0-based)
        b.put(cookie(layer));
        b.put(body);
        return b.array();
    }

    public static byte[] pnam(String name) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        write(o, cookie("PNam"));
        write(o, ucs1(name));
        return packet("PINF", o.toByteArray());
    }

    public static byte[] msex(int minor, String type, byte[] body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(1);
        o.write(minor);
        write(o, cookie(type));
        write(o, body);
        return packet("MSEX", o.toByteArray());
    }

    public static byte[] gvsr(int minor) {
        return msex(minor, "GVSr", new byte[0]);
    }

    /** Ask for frames from one video source; must be repeated before the timeout runs out. */
    public static byte[] rqst(int minor, int source, String format, int width, int height, int fps, int timeoutSec) {
        ByteBuffer b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) source);
        b.put(cookie(format));
        b.putShort((short) width);
        b.putShort((short) height);
        b.put((byte) fps);
        b.put((byte) timeoutSec);
        return msex(minor, "RqSt", b.array());
    }

    // ---------- parsing ----------

    public static final class Message {
        public String layer;      // "PINF", "MSEX", ...
        public String type;       // "PLoc", "PNam", "SInf", "VSrc", "StFr", "Nack", ...
        public int msexMinor;     // MSEX version minor (0, 1, 2), -1 for non-MSEX
        public int size;          // whole message size from the header
        public int part;
        public int partCount;
        public ByteBuffer body;   // positioned at the start of the type-specific body
    }

    public static final class Source {
        public int id;
        public String name;
        public int width;
        public int height;
    }

    public static final class Frame {
        public int source;
        public String format;     // "JPEG", "RGB8", ...
        public int width;
        public int height;
        public byte[] data;
        public int msexMinor;
    }

    /** Parses one packet; null when it isn't a CITP message we can read. */
    public static Message parse(byte[] data, int offset, int length) {
        if (length < HEADER + 4) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(data, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN);
        if (!"CITP".equals(readCookie(b))) {
            return null;
        }
        b.get();                        // version major
        b.get();                        // version minor
        b.getShort();                   // request index
        Message m = new Message();
        m.size = b.getInt();
        m.partCount = b.getShort() & 0xFFFF;
        m.part = b.getShort() & 0xFFFF;
        m.layer = readCookie(b);
        m.msexMinor = -1;
        if ("MSEX".equals(m.layer)) {
            if (b.remaining() < 6) {
                return null;
            }
            b.get();                    // MSEX major
            m.msexMinor = b.get() & 0xFF;
            m.type = readCookie(b);
        } else if ("PINF".equals(m.layer)) {
            m.type = readCookie(b);
        } else {
            m.type = b.remaining() >= 4 ? readCookie(b) : "";
        }
        m.body = b.slice().order(ByteOrder.LITTLE_ENDIAN);
        return m;
    }

    /** Listening TCP port from a PINF/PLoc (or old multicast PNam) announcement, or -1. */
    public static int listeningPort(Message m) {
        if (!"PINF".equals(m.layer) || m.body.remaining() < 2) {
            return -1;
        }
        if ("PLoc".equals(m.type) || "PNam".equals(m.type)) {
            return m.body.duplicate().order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
        }
        return -1;
    }

    /** PLoc fields after the port: type, name, state. */
    public static String[] plocText(Message m) {
        ByteBuffer b = m.body.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 2) {
            return new String[]{"", "", ""};
        }
        b.getShort();
        return new String[]{readUcs1(b), readUcs1(b), readUcs1(b)};
    }

    public static String sinfName(Message m) {
        return readUcs2(m.body.duplicate().order(ByteOrder.LITTLE_ENDIAN));
    }

    public static List<Source> vsrc(Message m) {
        List<Source> out = new ArrayList<>();
        ByteBuffer b = m.body.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        try {
            int count = b.getShort() & 0xFFFF;
            for (int i = 0; i < count; i++) {
                Source s = new Source();
                s.id = b.getShort() & 0xFFFF;
                s.name = readUcs2(b);
                b.get();                // physical output
                b.get();                // layer number
                b.getShort();           // flags
                s.width = b.getShort() & 0xFFFF;
                s.height = b.getShort() & 0xFFFF;
                out.add(s);
            }
        } catch (RuntimeException ignored) {
            // truncated list: keep what was read
        }
        return out;
    }

    public static Frame stfr(Message m) {
        if (!"StFr".equals(m.type)) {
            return null;
        }
        ByteBuffer b = m.body.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        try {
            if (m.msexMinor >= 2) {
                b.position(b.position() + 36); // media server UUID
            }
            Frame f = new Frame();
            f.msexMinor = m.msexMinor;
            f.source = b.getShort() & 0xFFFF;
            f.format = readCookie(b);
            f.width = b.getShort() & 0xFFFF;
            f.height = b.getShort() & 0xFFFF;
            int size = b.getShort() & 0xFFFF;
            size = Math.min(size, b.remaining());
            f.data = new byte[size];
            b.get(f.data);
            return f;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** RGB8 frame to ARGB pixels. MSEX 1.0 sends BGR, 1.1 and later RGB. */
    public static int[] rgb8ToArgb(Frame f) {
        int n = f.width * f.height;
        if (n <= 0 || f.data.length < n * 3) {
            return null;
        }
        boolean bgr = f.msexMinor == 0;
        int[] px = new int[n];
        for (int i = 0; i < n; i++) {
            int a = f.data[i * 3] & 0xFF;
            int g = f.data[i * 3 + 1] & 0xFF;
            int c = f.data[i * 3 + 2] & 0xFF;
            int r = bgr ? c : a;
            int bl = bgr ? a : c;
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
        return px;
    }

    // ---------- helpers ----------

    static byte[] cookie(String s) {
        byte[] out = new byte[4];
        byte[] src = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, out, 0, Math.min(4, src.length));
        for (int i = src.length; i < 4; i++) {
            out[i] = ' ';
        }
        return out;
    }

    static String readCookie(ByteBuffer b) {
        byte[] c = new byte[4];
        b.get(c);
        return new String(c, StandardCharsets.US_ASCII);
    }

    static byte[] ucs1(String s) {
        byte[] t = s.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[t.length + 1];
        System.arraycopy(t, 0, out, 0, t.length);
        return out;
    }

    static String readUcs1(ByteBuffer b) {
        StringBuilder sb = new StringBuilder();
        while (b.hasRemaining()) {
            int c = b.get() & 0xFF;
            if (c == 0) {
                break;
            }
            sb.append((char) c);
        }
        return sb.toString();
    }

    static String readUcs2(ByteBuffer b) {
        StringBuilder sb = new StringBuilder();
        while (b.remaining() >= 2) {
            char c = (char) (b.getShort() & 0xFFFF);
            if (c == 0) {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static void write(ByteArrayOutputStream o, byte[] d) {
        o.write(d, 0, d.length);
    }
}
