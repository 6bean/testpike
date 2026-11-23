package com.sixbean.testpike.core.cluster.domain.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loopback fixture origin for artifact reachability tests (top-level class —
 * inner classes are banned by project red lines).
 * <p>Serves deterministic HEAD/GET answers per path from a static route table
 * (status code + declared total size). Records every request method so tests
 * can assert the probe never falls back to GET (no full package download).
 * Raw sockets (not com.sun HttpServer) because HEAD semantics — declaring a
 * Content-Length without a body — must be byte-exact for the size-mismatch
 * spectrum to be provable.
 */
final class LoopbackArtifactStub implements AutoCloseable {

    /** path → {http status, declared content length}. Unknown path = 404/0. */
    private final Map<String, long[]> routes = new ConcurrentHashMap<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final ServerSocket server;
    private final Thread acceptor;

    LoopbackArtifactStub() throws IOException {
        server = new ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::acceptLoop, "artifact-stub");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    void route(String path, int status, long declaredLength) {
        routes.put(path, new long[] {status, declaredLength});
    }

    int port() {
        return server.getLocalPort();
    }

    List<String> seenMethods() {
        return List.copyOf(methods);
    }

    void clearMethods() {
        methods.clear();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> handle(socket), "artifact-stub-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                return; // server closed = stop accepting
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = in.readLine();
            if (requestLine == null) {
                return;
            }
            // drain headers until blank line (keep the stream sane, values unused)
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // no-op
            }
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts[1];
            methods.add(method);
            long[] route = routes.getOrDefault(path, new long[] {404, 0});
            int status = (int) route[0];
            long length = route[1];
            String reason = status == 404 ? "Not Found" : "OK";
            OutputStream out = s.getOutputStream();
            String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Length: " + length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            if (!"HEAD".equals(method) && length > 0) {
                byte[] body = new byte[(int) Math.min(length, 64)];
                out.write(body);
            }
            out.flush();
        } catch (IOException ignored) {
            // fixture best-effort: half-open probes must not kill the stub
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
