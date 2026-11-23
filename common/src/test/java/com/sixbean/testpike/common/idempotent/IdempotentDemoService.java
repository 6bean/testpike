package com.sixbean.testpike.common.idempotent;


public class IdempotentDemoService {

    
    private int invocations;

    
    @Idempotent(key = "#input")
    public String echo(String input) {
        invocations++;
        return "echo:" + input;
    }

    
    @Idempotent(key = "#input")
    public String slowEcho(String input) {
        invocations++;
        try {
            Thread.sleep(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "slow:" + input;
    }

    /**
 * In-flight-TTL probe : the marker window comes from the
 * method parameter via SpEL; the body sleeps so the test can read the E:
 * TTL while the run is in flight.
 */
    @Idempotent(key = "#input", inflightTtl = "#inflightTtlSeconds")
    public String gatedEcho(String input, long inflightTtlSeconds) {
        invocations++;
        try {
            Thread.sleep(700);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "gated:" + input;
    }

    
    @Idempotent(key = "#input")
    public String alwaysFails(String input) {
        invocations++;
        throw new IllegalStateException("boom-" + input);
    }

    
    @Idempotent(key = "#input")
    public java.util.Map<String, Object> stats(String input) {
        invocations++;
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("count", 42);
        map.put("name", input);
        return map;
    }

 
    @Idempotent(key = "#input", ttlSeconds = 1)
    public String echoWithShortTtl(String input) {
        invocations++;
        return "echo:" + input;
    }

    
    @Idempotent
    public String echoDerived(String input) {
        invocations++;
        return "echo:" + input;
    }

    public int invocations() {
        return invocations;
    }
}
