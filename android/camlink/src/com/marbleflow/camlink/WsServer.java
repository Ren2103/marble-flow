package com.marbleflow.camlink;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Minimal WebSocket server (RFC 6455), one client at a time.
 * The PC reaches it over the USB cable with:  adb forward tcp:47865 tcp:47865
 * Text frames  -> JSON (stats, motion flow, camera info)
 * Binary frames -> JPEG camera frames (for the page's preview + hand tracking)
 * Sending happens on its own thread; video frames are "latest wins" so a slow link never
 * backs up the camera.
 */
final class WsServer extends Thread {
    interface Listener { void onText(String msg); void onClient(boolean connected); }

    private final int port;
    private final Listener listener;
    private volatile ServerSocket server;
    private volatile Socket client;
    private volatile OutputStream out;
    private final ArrayBlockingQueue<String> texts = new ArrayBlockingQueue<>(16);
    private volatile byte[] pendingJpeg;
    private volatile boolean running = true;
    private final Object writeLock = new Object();

    WsServer(int port, Listener listener) {
        super("ws-server");
        this.port = port; this.listener = listener;
        setDaemon(true);
    }

    boolean hasClient() { return out != null; }

    void sendText(String s) { if (out != null && !texts.offer(s)) { texts.poll(); texts.offer(s); } }
    void sendJpeg(byte[] jpg) { if (out != null) pendingJpeg = jpg; }

    void shutdown() {
        running = false;
        closeClient();
        try { if (server != null) server.close(); } catch (IOException ignored) {}
    }

    @Override public void run() {
        Thread sender = new Thread(this::sendLoop, "ws-sender");
        sender.setDaemon(true);
        sender.start();
        try {
            server = new ServerSocket(port, 4, InetAddress.getByName("0.0.0.0"));
            while (running) {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                try {
                    InputStream in = new BufferedInputStream(s.getInputStream());
                    if (!handshake(in, s.getOutputStream())) { s.close(); continue; }
                    closeClient();                       // newest client wins
                    client = s; out = s.getOutputStream();
                    listener.onClient(true);
                    final Socket mine = s;
                    Thread r = new Thread(() -> readLoop(mine, in), "ws-reader");
                    r.setDaemon(true);
                    r.start();
                } catch (IOException e) { try { s.close(); } catch (IOException ignored) {} }
            }
        } catch (IOException e) {
            // server closed
        }
    }

    private boolean handshake(InputStream in, OutputStream o) throws IOException {
        String key = null, line;
        boolean first = true;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            if (first) { first = false; if (!line.startsWith("GET")) return false; }
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Sec-WebSocket-Key")) key = line.substring(c + 1).trim();
        }
        if (key == null) {
            o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
                    + "Marble CamLink is running. Connect from the Marble Flow page.\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
            return false;
        }
        String accept;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] d = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
            accept = Base64.encodeToString(d, Base64.NO_WRAP);
        } catch (Exception e) { return false; }
        o.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        o.flush();
        return true;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 8192) throw new IOException("header too long");
        }
        return b == -1 && sb.length() == 0 ? null : sb.toString();
    }

    private void readLoop(Socket s, InputStream in) {
        try {
            while (running && s == client) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) break;
                int op = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF); }
                if (len > (1 << 20)) break;
                byte[] mask = new byte[4];
                boolean masked = (b1 & 0x80) != 0;
                if (masked) readFully(in, mask);
                byte[] payload = new byte[(int) len];
                readFully(in, payload);
                if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                if (op == 1) listener.onText(new String(payload, StandardCharsets.UTF_8));
                else if (op == 8) break;
                else if (op == 9) writeFrame(0xA, payload);          // ping -> pong
            }
        } catch (IOException ignored) {
        }
        if (s == client) closeClient();
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) { int n = in.read(buf, off, buf.length - off); if (n < 0) throw new IOException("eof"); off += n; }
    }

    private void sendLoop() {
        while (running) {
            try {
                String t = texts.poll(4, TimeUnit.MILLISECONDS);
                if (t != null) writeFrame(0x1, t.getBytes(StandardCharsets.UTF_8));
                byte[] j = pendingJpeg;
                if (j != null) { pendingJpeg = null; writeFrame(0x2, j); }
            } catch (InterruptedException e) { return; }
        }
    }

    private void writeFrame(int opcode, byte[] payload) {
        OutputStream o = out;
        if (o == null) return;
        synchronized (writeLock) {
            try {
                int len = payload.length;
                byte[] head;
                if (len < 126) head = new byte[]{(byte) (0x80 | opcode), (byte) len};
                else if (len < 65536) head = new byte[]{(byte) (0x80 | opcode), 126, (byte) (len >> 8), (byte) len};
                else {
                    head = new byte[10];
                    head[0] = (byte) (0x80 | opcode); head[1] = 127;
                    for (int i = 0; i < 8; i++) head[9 - i] = (byte) ((long) len >> (8 * i));
                }
                o.write(head);
                o.write(payload);
                o.flush();
            } catch (IOException e) {
                closeClient();
            }
        }
    }

    private void closeClient() {
        Socket s = client;
        boolean had = out != null;
        out = null; client = null; pendingJpeg = null; texts.clear();
        if (s != null) try { s.close(); } catch (IOException ignored) {}
        if (had) listener.onClient(false);
    }
}
