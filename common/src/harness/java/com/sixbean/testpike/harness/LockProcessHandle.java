package com.sixbean.testpike.harness;

import java.io.File;


public final class LockProcessHandle {

    private final String mainClass;
    private final Process process;
    private final StringBuilder out = new StringBuilder();

    LockProcessHandle(String mainClass, Process process) {
        this.mainClass = mainClass;
        this.process = process;
    }

    String mainClass() {
        return mainClass;
    }

    
    void waitForToken(String token, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            synchronized (out) {
                if (out.indexOf(token) >= 0) {
                    return;
                }
                if (!process.isAlive()) {
                    return;
                }
            }
            Thread.sleep(20);
        }
    }

    
    LockProcessResult awaitResult(long timeoutSeconds) throws InterruptedException {
        if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            System.out.println("FAIL " + mainClass + " :: " + timeoutSeconds + "s 未退出");
            System.exit(1);
        }
        synchronized (out) {
            return new LockProcessResult(mainClass, process.pid(), process.exitValue(), out.toString());
        }
    }

    void appendLine(String line) {
        synchronized (out) {
            out.append(line).append('\n');
        }
    }

    
    static String[] command(String mainClass, String... programArgs) {
        String javaBin = System.getProperty("java.home")
                + File.separator + "bin" + File.separator + "java";
        String[] cmd = new String[4 + programArgs.length];
        cmd[0] = javaBin;
        cmd[1] = "-cp";
        cmd[2] = System.getProperty("java.class.path");
        cmd[3] = "com.sixbean.testpike.harness." + mainClass;
        System.arraycopy(programArgs, 0, cmd, 4, programArgs.length);
        return cmd;
    }
}
