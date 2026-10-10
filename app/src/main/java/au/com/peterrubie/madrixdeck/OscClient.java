package au.com.peterrubie.madrixdeck;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * UDP OSC link to MADRIX, pinned to the Wi-Fi network even when Android has marked it
 * "no internet" and mobile data is the default. One socket both sends and listens on the
 * feedback port, so MADRIX feedback reaches us whichever port it replies to.
 */
final class OscClient implements Engine.Sender {

    interface StatusListener {
        void onStatusChanged();
    }

    interface Incoming {
        void onMessage(OscMessage m);
    }

    private final ConnectivityManager cm;
    private final StatusListener statusListener;
    private volatile Incoming incoming;
    private ConnectivityManager.NetworkCallback callback;

    private volatile Network wifi;
    private volatile DatagramSocket socket;
    private volatile InetAddress address;

    private volatile String host;
    private volatile int port;
    private volatile int feedbackPort;

    private volatile boolean lastSendOk = true;
    private volatile boolean feedbackOnPort = false;
    private volatile String lastError = "";

    OscClient(Context ctx, StatusListener statusListener) {
        this.cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.statusListener = statusListener;
    }

    void setIncoming(Incoming incoming) {
        this.incoming = incoming;
    }

    void configure(String host, int port, int feedbackPort) {
        boolean reopen = this.feedbackPort != feedbackPort && socket != null;
        this.host = host;
        this.port = port;
        this.feedbackPort = feedbackPort;
        this.address = null;
        if (reopen) {
            new Thread(this::openSocket, "osc-reopen").start();
        }
        statusListener.onStatusChanged();
    }

    void start() {
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                wifi = network;
                cm.bindProcessToNetwork(network);
                openSocket();
                // Ask MADRIX for its current state (place, speed, colours) if feedback is enabled.
                send(OscMessage.of("/FeedbackAll"));
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(wifi)) {
                    wifi = null;
                    cm.bindProcessToNetwork(null);
                    closeSocket();
                    statusListener.onStatusChanged();
                }
            }
        };
        cm.requestNetwork(request, callback);
    }

    void stop() {
        if (callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (IllegalArgumentException ignored) {
                // already unregistered
            }
        }
        cm.bindProcessToNetwork(null);
        closeSocket();
    }

    private synchronized void openSocket() {
        closeSocket();
        Network net = wifi;
        if (net == null) {
            return;
        }
        DatagramSocket s;
        try {
            try {
                s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(feedbackPort));
                feedbackOnPort = true;
            } catch (Exception bindFailed) {
                s = new DatagramSocket();
                feedbackOnPort = false;
            }
            net.bindSocket(s);
        } catch (Exception e) {
            lastError = String.valueOf(e.getMessage());
            statusListener.onStatusChanged();
            return;
        }
        socket = s;
        address = null;
        final DatagramSocket rxSocket = s;
        Thread rx = new Thread(() -> receiveLoop(rxSocket), "osc-rx");
        rx.setDaemon(true);
        rx.start();
        statusListener.onStatusChanged();
    }

    private synchronized void closeSocket() {
        DatagramSocket s = socket;
        socket = null;
        if (s != null) {
            s.close();
        }
    }

    private void receiveLoop(DatagramSocket s) {
        byte[] buf = new byte[16384];
        while (!s.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                List<OscMessage> msgs = OscMessage.decodePacket(p.getData(), p.getOffset(), p.getLength());
                Incoming in = incoming;
                if (in != null) {
                    for (OscMessage m : msgs) {
                        in.onMessage(m);
                    }
                }
            } catch (Exception e) {
                if (s.isClosed()) {
                    return;
                }
            }
        }
    }

    /** Called from background threads only (Android forbids network on the main thread). */
    @Override
    public boolean send(OscMessage m) {
        DatagramSocket s = socket;
        Network net = wifi;
        if (s == null || net == null) {
            setSendOk(false, "No Wi-Fi");
            return false;
        }
        try {
            InetAddress a = address;
            if (a == null) {
                a = net.getByName(host);
                address = a;
            }
            byte[] b = m.encode();
            s.send(new DatagramPacket(b, b.length, a, port));
            setSendOk(true, "");
            return true;
        } catch (Exception e) {
            address = null;
            setSendOk(false, String.valueOf(e.getMessage()));
            return false;
        }
    }

    private void setSendOk(boolean ok, String error) {
        boolean changed = ok != lastSendOk;
        lastSendOk = ok;
        lastError = error;
        if (changed) {
            statusListener.onStatusChanged();
        }
    }

    /** The Wi-Fi network MADRIX is on, or null while waiting for Wi-Fi. */
    Network network() {
        return wifi;
    }

    boolean wifiUp() {
        return wifi != null && socket != null;
    }

    boolean lastSendOk() {
        return lastSendOk;
    }

    boolean feedbackOnPort() {
        return feedbackOnPort;
    }

    String lastError() {
        return lastError;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }
}
