package com.sixbean.testpike.core.judge.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.stereotype.Component;


@Component
public class JudgeCalibrationConfig {

    static final double DEFAULT_KAPPA = 0.6;
    static final int DEFAULT_PASS = 60;
    static final int DEFAULT_BOOTSTRAP = 200;
    static final double DEFAULT_SENTINEL = 0.02;
    static final double DEFAULT_BOOST = 0.25;
    static final int DEFAULT_BAND = 5;

    private final ConfigService configService;

    public JudgeCalibrationConfig(ConfigService configService) {
        this.configService = configService;
    }

    
    public CalibrationParams load() {
        return new CalibrationParams(
                readDouble("judge.calibration.kappa-threshold", DEFAULT_KAPPA, 0.0, 1.0),
                readInt("judge.calibration.pass-threshold", DEFAULT_PASS, 0, 100),
                readInt("judge.calibration.bootstrap-samples", DEFAULT_BOOTSTRAP, 1, Integer.MAX_VALUE),
                readDouble("judge.calibration.sentinel-rate", DEFAULT_SENTINEL, 0.0, 1.0),
                readDouble("judge.calibration.boost-rate", DEFAULT_BOOST, 0.0, 1.0),
                readInt("judge.calibration.boundary-band", DEFAULT_BAND, 0, 100));
    }

    private double readDouble(String key, double fallback, double lo, double hi) {
        Double v = JudgeConfigValues.parseDouble(configService.getValue(key, ""), lo, hi);
        return v == null ? fallback : v;
    }

    private int readInt(String key, int fallback, int lo, int hi) {
        Integer v = JudgeConfigValues.parseInt(configService.getValue(key, ""), lo, hi);
        return v == null ? fallback : v;
    }
}
