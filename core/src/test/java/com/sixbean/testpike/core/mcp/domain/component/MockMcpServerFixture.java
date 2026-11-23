package com.sixbean.testpike.core.mcp.domain.component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;


public final class MockMcpServerFixture implements AutoCloseable {

    
    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path SCRIPT = REPO_ROOT.resolve("scripts/mock-mcp-server.py");

    private final Process process;
    private final int port;
    private final Path authLog;

    private MockMcpServerFixture(Process process, int port, Path authLog) {
        this.process = process;
        this.port = port;
        this.authLog = authLog;
    }

    
    public static MockMcpServerFixture start(String mode) throws IOException, InterruptedException {
        int port = freePort();
        Path authLog = Files.createTempFile("mockmcp-auth-" + mode + "-", ".txt");
        ProcessBuilder pb = new ProcessBuilder("python3", SCRIPT.toString(),
                "--port", String.valueOf(port), "--mode", mode,
                "--auth-log", authLog.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        waitReady(port, p);
        return new MockMcpServerFixture(p, port, authLog);
    }

    public String endpoint() {
        return "http://127.0.0.1:" + port + "/mcp";
    }

    
    public String authLogContent() throws IOException {
        return Files.readString(authLog);
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try {
            Files.deleteIfExists(authLog);
        } catch (IOException ignored) {
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void waitReady(int port, Process p) throws InterruptedException, IOException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive()) {
                throw new IOException("mock-mcp-server 进程异常退出（mode 参数或 python3 环境问题）");
            }
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException e) {
                Thread.sleep(150);
            }
        }
        throw new IOException("mock-mcp-server 端口 " + port + " 10s 内未就绪");
    }
}
