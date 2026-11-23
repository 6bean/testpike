package com.sixbean.testpike.harness;


public record LockProcessResult(String mainClass, long pid, int exit, String stdout) {

    public boolean exitedOk() {
        return exit == 0;
    }

    public boolean printed(String token) {
        return stdout.contains(token);
    }
}
