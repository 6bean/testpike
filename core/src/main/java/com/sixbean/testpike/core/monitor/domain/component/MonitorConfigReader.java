package com.sixbean.testpike.core.monitor.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.stereotype.Component;

import java.util.Map;


@Component
public class MonitorConfigReader {

    public static final String KEY_MIN_INTERVAL = "monitor.min-interval-seconds";
    public static final String KEY_BACKOFF_LADDER = "monitor.backoff-ladder-seconds";
    public static final String KEY_STABLE_CYCLES = "monitor.stable-cycles-to-advance";
    public static final String KEY_FAST_WINDOW = "monitor.fast-window-minutes";
    public static final String KEY_FAST_FAILS = "monitor.fast-fail-threshold";
    public static final String KEY_SLOW_WINDOW = "monitor.slow-window-minutes";
    public static final String KEY_SLOW_MIN_SAMPLES = "monitor.slow-min-samples";
    public static final String KEY_RECOVER_SUCCESSES = "monitor.recover-successes";
    public static final String KEY_SLOW_ONLINE_RATE = "monitor.slow-online-rate-threshold";
    public static final String KEY_FLAP_WINDOW = "monitor.flap-window-minutes";
    public static final String KEY_FLAP_EPISODES = "monitor.flap-episode-count";
    public static final String KEY_RENOTIFY_INTERVAL = "monitor.renotify-interval-minutes";
    public static final String KEY_STALL_MULTIPLIER = "monitor.stall-multiplier";
    public static final String KEY_SCAN_HEARTBEAT_SECONDS = "monitor.scan-heartbeat-seconds";

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry(KEY_MIN_INTERVAL, "60"),
            Map.entry(KEY_BACKOFF_LADDER, "300,900"),
            Map.entry(KEY_STABLE_CYCLES, "3"),
            Map.entry(KEY_FAST_WINDOW, "1"),
            Map.entry(KEY_FAST_FAILS, "3"),
            Map.entry(KEY_SLOW_WINDOW, "15"),
            Map.entry(KEY_SLOW_MIN_SAMPLES, "10"),
            Map.entry(KEY_RECOVER_SUCCESSES, "2"),
            Map.entry(KEY_SLOW_ONLINE_RATE, "80"),
            Map.entry(KEY_FLAP_WINDOW, "30"),
            Map.entry(KEY_FLAP_EPISODES, "3"),
            Map.entry(KEY_RENOTIFY_INTERVAL, "60"),
            Map.entry(KEY_STALL_MULTIPLIER, "2"),
            Map.entry(KEY_SCAN_HEARTBEAT_SECONDS, "15"));

    private final ConfigService configService;

    public MonitorConfigReader(ConfigService configService) {
        this.configService = configService;
    }

    
    public String value(String key) {
        return configService.getValue(key, DEFAULTS.getOrDefault(key, ""));
    }

    
    public int intValue(String key) {
        try {
            return Integer.parseInt(value(key).trim());
        } catch (NumberFormatException e) {
            try {
                return Integer.parseInt(DEFAULTS.getOrDefault(key, "0").trim());
            } catch (NumberFormatException illegalDefault) {
                return 0;
            }
        }
    }
}
