package au.com.peterrubie.madrixdeck;

import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** MADRIX Remote HTTP requests over the Wi-Fi network (works with mobile data on). */
final class HttpLink implements HttpStatus.Fetcher {

    private final OscClient wifiSource;
    private volatile String host = "";
    private volatile int port = 80;
    private volatile boolean lastOk = false;
    private volatile String lastError = "";

    HttpLink(OscClient wifiSource) {
        this.wifiSource = wifiSource;
    }

    void configure(String host, int port) {
        this.host = host;
        this.port = port;
    }

    String url(String command) {
        return "http://" + host + ":" + port + "/RemoteCommands/?" + command;
    }

    @Override
    public byte[] bytes(String command) throws IOException {
        URL url = new URL(url(command));
        Network net = wifiSource.network();
        HttpURLConnection c = (HttpURLConnection) (net != null ? net.openConnection(url) : url.openConnection());
        c.setConnectTimeout(1500);
        c.setReadTimeout(3000);
        c.setUseCaches(false);
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                lastOk = true;
                lastError = "";
                return out.toByteArray();
            }
        } catch (IOException e) {
            lastOk = false;
            lastError = String.valueOf(e.getMessage());
            throw e;
        } finally {
            c.disconnect();
        }
    }

    @Override
    public String text(String command) throws IOException {
        return new String(bytes(command), StandardCharsets.UTF_8).trim();
    }

    /** Sends a Set command; true when MADRIX answered. Called from background threads only. */
    boolean send(String command) {
        try {
            bytes(command);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    boolean lastOk() {
        return lastOk;
    }

    String lastError() {
        return lastError;
    }
}
