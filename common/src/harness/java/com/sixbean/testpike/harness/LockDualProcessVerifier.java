package com.sixbean.testpike.harness;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;


public final class LockDualProcessVerifier {

    private LockDualProcessVerifier() {
    }

    public static void main(String[] args) throws Exception {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String mainKey = "harness:lock:dual:" + runId + ":main";
        String crashKey = "harness:lock:dual:" + runId + ":crash";
        System.out.println("RUN_ID=" + runId);
        long t0 = System.currentTimeMillis();
        long orchPid = ProcessHandle.current().pid();

        LockProcessResult c = finish(launch("LockProcessC", crashKey));
        expectLine(c, "C_LOCKED_EXITING_DIRTY");

        LockProcessHandle a = launch("LockProcessA", mainKey, crashKey);
        a.waitForToken("A_LOCKED", 15_000);
        a.waitForToken("A_CRASH_RELEASED_MS", 20_000);

        LockProcessHandle b = launch("LockProcessB", mainKey);
        b.waitForToken("B_TRYLOCK_DENIED", 15_000);

        LockProcessResult aRes = finish(a);
        expectLine(aRes, "A_WATCHDOG_HELD");
        expectLine(aRes, "A_RELEASED");
        expectLine(aRes, "A_EXIT_0");
        LockProcessResult bRes = finish(b);
        expectLine(bRes, "B_ACQUIRED_AFTER_RELEASE");
        expectLine(bRes, "B_EXIT_0");

        if (!aRes.exitedOk() || !bRes.exitedOk()) {
            System.out.println("FAIL subprocess-exit :: A=" + aRes.exit() + " B=" + bRes.exit()
                    + " C=" + c.exit());
            System.exit(1);
        }
        long wall = System.currentTimeMillis() - t0;
        if (wall < 6000) {
            System.out.println("FAIL wall-clock :: " + wall + "ms < 6000ms（未真正跨租期，判别力不足）");
            System.exit(1);
        }
        System.out.println("DUAL_PROCESS_PASS ORCH_PID=" + orchPid + " A_PID=" + aRes.pid()
                + " B_PID=" + bRes.pid() + " C_PID=" + c.pid() + " WALL_MS=" + wall);
    }

    private static LockProcessHandle launch(String mainClass, String... programArgs) throws Exception {
        Process p = new ProcessBuilder(LockProcessHandle.command(mainClass, programArgs))
                .redirectErrorStream(true).start();
        LockProcessHandle h = new LockProcessHandle(mainClass, p);
        CompletableFuture.runAsync(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("[" + mainClass + "] " + line);
                    h.appendLine(line);
                }
            } catch (Exception e) {
                System.out.println("[" + mainClass + "] <pump-error> " + e);
            }
        });
        return h;
    }

    private static LockProcessResult finish(LockProcessHandle h) throws InterruptedException {
        return h.awaitResult(60);
    }

    private static void expectLine(LockProcessResult r, String token) {
        if (!r.printed(token)) {
            System.out.println("FAIL missing-token :: " + r.mainClass() + " 输出缺少 " + token);
            System.exit(1);
        }
    }
}
