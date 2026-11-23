package com.sixbean.testpike.core.judge.domain.component;

import com.sixbean.testpike.common.exception.ValidationException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;


public final class CalibrationSampler {

    private CalibrationSampler() {
    }

    
    public static SamplingPlan plan(List<ScoredSample> candidates, int pairCount,
                                    double rollingKappa, CalibrationParams params,
                                    Random random) {
        if (candidates == null) {
            throw new ValidationException("抽样候选缺失（candidates=null——派发侧必须先取本 Run 评分记录）");
        }
        if (params == null) {
            throw new ValidationException("校准参数缺失（params=null——六键须经 JudgeCalibrationConfig 读取注入，禁件内兜底造默认）");
        }
        if (candidates.isEmpty()) {
            return new SamplingPlan(CalibrationStage.BOOTSTRAP, List.of(), 0, 0);
        }

        if (pairCount < params.bootstrapSamples() || Double.isNaN(rollingKappa)) {
            List<Long> all = candidates.stream().map(ScoredSample::recordId).toList();
            return new SamplingPlan(CalibrationStage.BOOTSTRAP, all, all.size(), all.size());
        }

        CalibrationStage stage = rollingKappa < params.kappaThreshold()
                ? CalibrationStage.BOOSTED : CalibrationStage.STEADY;
        double farRate = stage == CalibrationStage.BOOSTED
                ? params.boostRate() : params.sentinelRate();

        List<ScoredSample> boundary = new ArrayList<>();
        List<ScoredSample> far = new ArrayList<>();
        for (ScoredSample s : candidates) {
            if (Math.abs(s.finalScore() - params.passThreshold()) <= params.boundaryBand()) {
                boundary.add(s);
            } else {
                far.add(s);
            }
        }
        boundary.sort(Comparator.comparingInt(s -> Math.abs(s.finalScore() - params.passThreshold())));

        List<Integer> farOrder = new ArrayList<>();
        for (int i = 0; i < far.size(); i++) {
            farOrder.add(i);
        }
        java.util.Collections.shuffle(farOrder, random);
        int farPicked = (int) Math.floor(far.size() * farRate + 1e-9);
        List<Integer> pickedIdx = farOrder.subList(0, Math.min(farPicked, far.size())).stream()
                .sorted().toList();

        List<Long> ids = new ArrayList<>(boundary.stream().map(ScoredSample::recordId).toList());
        for (int idx : pickedIdx) {
            ids.add(far.get(idx).recordId());
        }
        return new SamplingPlan(stage, ids, boundary.size(), ids.size());
    }
}
