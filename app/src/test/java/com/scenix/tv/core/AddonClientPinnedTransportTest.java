package com.scenix.tv.core;

import android.content.ContextWrapper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Offline contract coverage for the transport half of the standalone add-on boundary. The public
 * endpoint resolver is intentionally bypassed here: a loopback socket is used only to prove that
 * the already-validated address is the address actually dialled, without requiring a TV, internet
 * access, or an external manifest.
 */
public final class AddonClientPinnedTransportTest {
    private static final int BODY_LIMIT_PLUS_ONE = 2 * 1024 * 1024 + 1;

    @Test
    public void configuredManifestQueryStaysAtTheEndOfEveryProtocolRoute() {
        AddonSpec spec = new AddonSpec(
            "manual", "Manual", "https://public.example/config/manifest.json?token=opaque", true
        );
        assertEquals(
            "https://public.example/config/catalog/movie/top/default.json?token=opaque",
            spec.endpoint("catalog/movie/top/default.json")
        );
    }

    @Test
    public void pinnedTransportDialsSuppliedAddressAndKeepsOriginHost() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    request.set(readHeaders(socket.getInputStream()));
                    byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    output.write(body);
                    output.flush();
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            }, "addon-pinned-transport-test");
            peer.start();

            URI origin = new URI("http://addon.example:" + server.getLocalPort() + "/manifest.json");
            Object response = invokePinned(origin, InetAddress.getLoopbackAddress(), 2_000);
            peer.join(2_000L);

            if (serverFailure.get() != null) throw new AssertionError(serverFailure.get());
            assertNotNull(response);
            assertEquals(200, statusOf(response));
            assertArrayEquals("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), bodyOf(response));
            assertNotNull(request.get());
            assertEquals(true, request.get().contains("Host: addon.example:" + server.getLocalPort() + "\r\n"));
        }
    }

    @Test
    public void pinnedTransportRejectsBodyOverLimitBeforeReadingIt() throws Exception {
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    readHeaders(socket.getInputStream());
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + BODY_LIMIT_PLUS_ONE
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    output.flush();
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            }, "addon-body-limit-test");
            peer.start();

            URI origin = new URI("http://addon.example:" + server.getLocalPort() + "/manifest.json");
            assertNull(invokePinned(origin, InetAddress.getLoopbackAddress(), 2_000));
            peer.join(2_000L);
            if (serverFailure.get() != null) throw new AssertionError(serverFailure.get());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void unframedKeepAliveRedirectDoesNotWaitForPeerClose() throws Exception {
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        CountDownLatch responseWritten = new CountDownLatch(1);
        CountDownLatch releasePeer = new CountDownLatch(1);
        Thread peer = null;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    readHeaders(socket.getInputStream());
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 302 Found\r\n"
                        + "Location: https://public.example/manifest.json\r\n"
                        + "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    output.flush();
                    responseWritten.countDown();
                    // A legacy close-delimited parser would wait here until its read timeout. Keep
                    // the peer deliberately open, then let finally release it after the assertion.
                    releasePeer.await(3, TimeUnit.SECONDS);
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                } finally {
                    responseWritten.countDown();
                }
            }, "addon-unframed-redirect-test");
            peer.start();

            URI origin = new URI("http://addon.example:" + server.getLocalPort() + "/manifest.json");
            long startedAt = System.nanoTime();
            Object response = invokePinned(origin, InetAddress.getLoopbackAddress(), 2_500);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertTrue("peer did not send redirect headers", responseWritten.await(1, TimeUnit.SECONDS));
            assertNotNull(response);
            assertEquals(302, statusOf(response));
            Map<String, String> headers = (Map<String, String>) response.getClass().getMethod("getHeaders").invoke(response);
            assertEquals("https://public.example/manifest.json", headers.get("location"));
            assertEquals(0, bodyOf(response).length);
            assertTrue("unframed redirect waited " + elapsedMs + "ms for peer close", elapsedMs < 1_000L);
        } finally {
            releasePeer.countDown();
            if (peer != null) peer.join(3_000L);
        }
        if (serverFailure.get() != null) throw new AssertionError(serverFailure.get());
    }

    @Test
    public void unframedNoBodySuccessDoesNotWaitForPeerClose() throws Exception {
        assertUnframedNoBodyResponseReturnsImmediately(204, "No Content");
        assertUnframedNoBodyResponseReturnsImmediately(205, "Reset Content");
    }

    private static Object invokePinned(URI uri, InetAddress address, int timeout) throws Exception {
        Method method = AddonClient.class.getDeclaredMethod(
            "fetchPinnedAddress", URI.class, InetAddress.class, int.class
        );
        method.setAccessible(true);
        return method.invoke(transportClient(), uri, address, timeout);
    }

    private static void assertUnframedNoBodyResponseReturnsImmediately(int status, String reason) throws Exception {
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        CountDownLatch responseWritten = new CountDownLatch(1);
        CountDownLatch releasePeer = new CountDownLatch(1);
        Thread peer = null;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    readHeaders(socket.getInputStream());
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 " + status + " " + reason + "\r\n"
                        + "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    output.flush();
                    responseWritten.countDown();
                    releasePeer.await(3, TimeUnit.SECONDS);
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                } finally {
                    responseWritten.countDown();
                }
            }, "addon-no-body-response-test");
            peer.start();

            URI origin = new URI("http://addon.example:" + server.getLocalPort() + "/manifest.json");
            long startedAt = System.nanoTime();
            Object response = invokePinned(origin, InetAddress.getLoopbackAddress(), 2_500);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertTrue("peer did not send " + status + " headers", responseWritten.await(1, TimeUnit.SECONDS));
            assertNotNull(response);
            assertEquals(status, statusOf(response));
            assertEquals(0, bodyOf(response).length);
            assertTrue("unframed " + status + " response waited " + elapsedMs + "ms for peer close", elapsedMs < 1_000L);
        } finally {
            releasePeer.countDown();
            if (peer != null) peer.join(3_000L);
        }
        if (serverFailure.get() != null) throw new AssertionError(serverFailure.get());
    }

    private static int statusOf(Object response) throws Exception {
        return (Integer) response.getClass().getMethod("getStatus").invoke(response);
    }

    private static byte[] bodyOf(Object response) throws Exception {
        return (byte[]) response.getClass().getMethod("getBody").invoke(response);
    }

    private static AddonClient transportClient() throws Exception {
        File filesDir = Files.createTempDirectory("scenix-addon-transport-test").toFile();
        return new AddonClient(new FilesContext(filesDir));
    }

    private static final class FilesContext extends ContextWrapper {
        private final File filesDir;

        FilesContext(File filesDir) {
            super(null);
            this.filesDir = filesDir;
        }

        @Override public File getFilesDir() {
            return filesDir;
        }
    }

    private static String readHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] marker = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int matched = 0;
        while (output.size() < 16 * 1024) {
            int next = input.read();
            if (next < 0) throw new IllegalStateException("peer closed before request headers");
            output.write(next);
            if (next == (marker[matched] & 0xff)) {
                matched++;
                if (matched == marker.length) return output.toString(StandardCharsets.ISO_8859_1.name());
            } else {
                matched = next == '\r' ? 1 : 0;
            }
        }
        throw new IllegalStateException("request headers exceeded limit");
    }
}
