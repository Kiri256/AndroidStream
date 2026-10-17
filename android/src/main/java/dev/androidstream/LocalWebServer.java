package dev.androidstream;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Minimal same-origin HTTP / RFC6455 server with fixed routes and bounded messages. */
final class LocalWebServer implements AutoCloseable {
    interface Events { void connected(Client c); void message(Client c, String message); void disconnected(Client c); }
    private final ServerSocket server;
    interface Assets { byte[] read(String name) throws IOException; }
    private final Assets assets;
    private final Events events;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private static final Map<String, String> ROUTES = Map.of("/", "index.html", "/app.js", "app.js", "/style.css", "style.css");
    LocalWebServer(Assets assets, InetAddress address, int port, Events events) throws IOException {
        this.assets = assets; this.events = events;
        server = new ServerSocket();
        try { server.setReuseAddress(true); server.bind(new InetSocketAddress(address, port), 8); }
        catch (IOException e) { server.close(); throw e; }
        new Thread(() -> {
            while (!closed) {
                try {
                    Socket s = server.accept(); s.setTcpNoDelay(true); s.setSoTimeout(12000);
                    sockets.add(s);
                    try { pool.execute(() -> serve(s)); }
                    catch (RejectedExecutionException e) { sockets.remove(s); s.close(); }
                } catch (IOException e) { if (!closed) android.util.Log.w("AndroidStream", "HTTP accept failed", e); }
            }
        }, "lan-http").start();
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return new String(bytes.toByteArray(), StandardCharsets.US_ASCII).replace("\r", "");
            if (bytes.size() >= 2048) throw new IOException("Header too long");
            bytes.write(c);
        }
        throw new EOFException();
    }
    private void serve(Socket socket) {
        Client client = null;
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String[] request = line(in).split(" ");
            if (request.length != 3 || !request[0].equals("GET") || !request[2].equals("HTTP/1.1")) { response(out, 400, "Bad request"); return; }
            Map<String, String> headers = new HashMap<>();
            int total = 0;
            for (String h; !(h = line(in)).isEmpty();) {
                total += h.length();
                if (total > 8192) throw new IOException("Headers too large");
                int index = h.indexOf(':'); if (index < 1) throw new IOException("Bad header");
                String key = h.substring(0, index).toLowerCase(Locale.ROOT);
                if (headers.put(key, h.substring(index + 1).trim()) != null) throw new IOException("Duplicate header");
            }
            String expectedHost = server.getInetAddress().getHostAddress() + ":" + server.getLocalPort();
            if (!expectedHost.equals(headers.get("host"))) { response(out, 403, "Host rejected: open the address shown on the phone"); return; }
            if (headers.containsKey("transfer-encoding") || !"0".equals(headers.getOrDefault("content-length", "0"))) { response(out, 400, "Body not supported"); return; }
            if (request[1].equals("/ws")) {
                if (!("http://" + expectedHost).equals(headers.get("origin")) || !"websocket".equalsIgnoreCase(headers.get("upgrade"))
                        || Arrays.stream(headers.getOrDefault("connection", "").split(",")).noneMatch(token -> token.trim().equalsIgnoreCase("upgrade"))
                        || !"13".equals(headers.get("sec-websocket-version"))) { response(out, 403, "Origin or upgrade rejected"); return; }
                String key = headers.get("sec-websocket-key");
                if (key == null || Base64.getDecoder().decode(key).length != 16) { response(out, 400, "Invalid websocket key"); return; }
                if (clients.size() >= 3) { response(out, 503, "Too many connections"); return; }
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
                client = new Client(socket, in, out); clients.add(client);
                client.startWriter(); events.connected(client); client.read();
            } else {
                String asset = ROUTES.get(request[1]);
                if (asset == null) { response(out, 404, "Not found"); return; }
                {
                    byte[] bytes = assets.read(asset);
                    String mime = asset.endsWith("js") ? "text/javascript" : asset.endsWith("css") ? "text/css" : "text/html";
                    String header = "HTTP/1.1 200 OK\r\nContent-Type: " + mime + "; charset=utf-8\r\nContent-Length: " + bytes.length
                            + "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer"
                            + "\r\nContent-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.US_ASCII)); out.write(bytes); out.flush();
                }
            }
        } catch (Exception e) { /* Untrusted or disconnected clients do not stop the server. */ }
        finally {
            if (client != null) { client.close(); clients.remove(client); events.disconnected(client); }
            sockets.remove(socket); try { socket.close(); } catch (IOException ignored) {}
        }
    }
    int port() { return server.getLocalPort(); }
    private static void response(OutputStream out, int code, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " Response\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\nContent-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b); out.flush();
    }
    final class Client implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(32);
        private volatile boolean dead;
        Client(Socket socket, InputStream in, OutputStream out) { this.socket = socket; this.in = in; this.out = out; }
        void text(String text) { enqueue(1, text.getBytes(StandardCharsets.UTF_8)); }
        void closeAfterWrites() { if (!queue.offer(new byte[0])) close(); }
        void audio(byte[] bytes) { if (queue.size() < 5) enqueue(2, bytes); }
        private void enqueue(int opcode, byte[] bytes) {
            if (dead) return;
            ByteArrayOutputStream frame = new ByteArrayOutputStream(bytes.length + 10);
            frame.write(0x80 | opcode);
            if (bytes.length < 126) frame.write(bytes.length);
            else if (bytes.length <= 65535) { frame.write(126); frame.write(bytes.length >> 8); frame.write(bytes.length); }
            else { close(); return; }
            frame.write(bytes, 0, bytes.length);
            if (!queue.offer(frame.toByteArray())) close();
        }
        void startWriter() {
            new Thread(() -> {
                try {
                    while (!dead) {
                        byte[] bytes = queue.poll(1, TimeUnit.SECONDS);
                        if (bytes != null && bytes.length == 0) { close(); break; }
                        if (bytes != null) { out.write(bytes); out.flush(); }
                    }
                } catch (Exception e) { close(); }
            }, "ws-writer").start();
        }
        void read() throws Exception {
            DataInputStream data = new DataInputStream(in);
            while (!dead) {
                int first = data.readUnsignedByte(), second = data.readUnsignedByte();
                int opcode = first & 15;
                if ((first & 0xf0) != 0x80 || (second & 0x80) == 0) throw new IOException("Fragmented or unmasked frame");
                int length = second & 127;
                if (length == 126) length = data.readUnsignedShort();
                else if (length == 127) throw new IOException("Message too large");
                if (length > 16384 || (opcode >= 8 && length > 125)) throw new IOException("Message too large");
                byte[] mask = new byte[4]; data.readFully(mask);
                byte[] bytes = new byte[length]; data.readFully(bytes);
                for (int i = 0; i < length; i++) bytes[i] ^= mask[i % 4];
                if (opcode == 8) return;
                if (opcode == 9) enqueue(10, bytes);
                else if (opcode == 1) events.message(this, StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString());
                else if (opcode != 10) throw new IOException("Unsupported frame");
            }
        }
        @Override public void close() { dead = true; queue.clear(); try { socket.close(); } catch (IOException ignored) {} }
    }
    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) {}
        for (Client c : clients) c.close();
        for (Socket s : sockets) try { s.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }
}
