package dev.androidstream;

import org.junit.*;
import static org.junit.Assert.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class LocalWebServerTest {
    private LocalWebServer server;
    private final BlockingQueue<String> messages = new ArrayBlockingQueue<>(8);
    private final BlockingQueue<LocalWebServer.Client> connections = new ArrayBlockingQueue<>(8);
    @Before public void start() throws Exception {
        server = new LocalWebServer(name -> ("asset:" + name).getBytes(StandardCharsets.UTF_8), InetAddress.getByName("127.0.0.1"), 0,
                new LocalWebServer.Events() {
                    public void connected(LocalWebServer.Client c) { connections.offer(c); }
                    public void message(LocalWebServer.Client c, String message) { messages.offer(message); }
                    public void disconnected(LocalWebServer.Client c) {}
                });
    }
    @After public void stop() { server.close(); }
    private String host() { return "127.0.0.1:" + server.port(); }
    private String get(String path, String host) throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            s.setSoTimeout(3000);
            s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test public void onlyServesFixedRoutesAndExpectedHost() throws Exception {
        String response = get("/", host());
        assertTrue(response.startsWith("HTTP/1.1 200"));
        assertTrue(response.endsWith("asset:index.html"));
        assertTrue(response.contains("frame-ancestors 'none'"));
        assertTrue(get("/../AGENTS.md", host()).startsWith("HTTP/1.1 404"));
        assertTrue(get("/codec-worker.js", host()).startsWith("HTTP/1.1 404"));
        assertTrue(get("/ca.crt", host()).startsWith("HTTP/1.1 404"));
        assertTrue(get("/", "attacker.test").startsWith("HTTP/1.1 403"));
    }
    private Socket upgrade(String origin, int expectedStatus) throws Exception {
        return upgrade(origin, "Upgrade", expectedStatus);
    }
    private Socket upgrade(String origin, String connection, int expectedStatus) throws Exception {
        Socket s = new Socket("127.0.0.1", server.port()); s.setSoTimeout(3000);
        String request = "GET /ws HTTP/1.1\r\nHost: " + host() + "\r\nOrigin: " + origin
                + "\r\nUpgrade: websocket\r\nConnection: " + connection + "\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
        s.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        int c; String text = "";
        while ((c = s.getInputStream().read()) != -1) {
            response.write(c); text = response.toString(StandardCharsets.US_ASCII);
            if (text.endsWith("\r\n\r\n")) break;
        }
        assertTrue(text, text.startsWith("HTTP/1.1 " + expectedStatus));
        if (expectedStatus == 101) assertTrue(text.contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
        return s;
    }
    @Test public void rejectsCrossOriginWebsocket() throws Exception {
        try (Socket ignored = upgrade("http://attacker.test", 403)) {}
    }
    @Test public void upgradeRequiresExactConnectionToken() throws Exception {
        try (Socket ignored = upgrade("http://" + host(), "not-upgrade", 403)) {}
        try (Socket ignored = upgrade("http://" + host(), "keep-alive, UpGrAdE", 101)) {}
    }
    @Test public void maskedMessagesDeliveredAndUnmaskedRejected() throws Exception {
        try (Socket s = upgrade("http://" + host(), 101)) {
            byte[] payload = "ping".getBytes(StandardCharsets.UTF_8), mask = {1,2,3,4};
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81); frame.write(0x80 | payload.length); frame.write(mask);
            for (int i=0;i<payload.length;i++) frame.write(payload[i] ^ mask[i%4]);
            s.getOutputStream().write(frame.toByteArray());
            assertEquals("ping", messages.poll(2, TimeUnit.SECONDS));
            s.getOutputStream().write(new byte[]{(byte)0x81, 1, 'x'});
            assertEquals(-1, s.getInputStream().read());
            assertNull(messages.poll(50, TimeUnit.MILLISECONDS));
        }
    }
    @Test public void oversizedFrameRejectedBeforeAllocation() throws Exception {
        try (Socket s = upgrade("http://" + host(), 101)) {
            s.getOutputStream().write(new byte[]{(byte)0x81, (byte)0xfe, 0x7f, (byte)0xff});
            assertEquals(-1, s.getInputStream().read());
        }
    }
    @Test public void malformedUtf8BinaryAndFragmentedMessagesAreRejected() throws Exception {
        byte[][] frames = {
                {(byte)0x81, (byte)0x82, 0, 0, 0, 0, (byte)0xc3, 0x28},
                {(byte)0x82, (byte)0x80, 0, 0, 0, 0},
                {0x01, (byte)0x80, 0, 0, 0, 0}
        };
        for (byte[] frame : frames) {
            try (Socket s = upgrade("http://" + host(), 101)) {
                s.getOutputStream().write(frame);
                assertEquals(-1, s.getInputStream().read());
                assertNull(messages.poll(50, TimeUnit.MILLISECONDS));
            }
        }
    }
    @Test public void rejectionMessageDeliveredBeforeClosing() throws Exception {
        try (Socket s = upgrade("http://" + host(), 101)) {
            LocalWebServer.Client client = connections.poll(2, TimeUnit.SECONDS);
            assertNotNull(client);
            client.text("busy");
            client.closeAfterWrites();
            assertArrayEquals(new byte[]{(byte) 0x81, 4, 'b', 'u', 's', 'y'}, s.getInputStream().readAllBytes());
        }
    }
}
