package com.sixbean.testpike.core.judge.domain.component;

import com.sixbean.testpike.common.exception.ValidationException;

import java.util.List;


public final class JudgeCalibrationStats {

    private JudgeCalibrationStats() {
    }

    
    public static double spearman(List<Integer> machine, List<Integer> human) {
        requirePairs(machine, human);
        if (machine.size() < 2) {
            return Double.NaN;
        }
        double[] rankM = midRanks(machine);
        double[] rankH = midRanks(human);
        double meanM = mean(rankM);
        double meanH = mean(rankH);
        double cov = 0.0;
        double varM = 0.0;
        double varH = 0.0;
        for (int i = 0; i < rankM.length; i++) {
            double dm = rankM[i] - meanM;
            double dh = rankH[i] - meanH;
            cov += dm * dh;
            varM += dm * dm;
            varH += dh * dh;
        }
        if (varM == 0.0 || varH == 0.0) {
            return Double.NaN;
        }
        return cov / Math.sqrt(varM * varH);
    }

    
    public static double cohenKappaPass(List<Integer> machine, List<Integer> human,
                                        int passThreshold) {
        requirePairs(machine, human);
        int n = machine.size();
        int agree = 0;
        int mPass = 0;
        int hPass = 0;
        for (int i = 0; i < n; i++) {
            boolean mp = machine.get(i) >= passThreshold;
            boolean hp = human.get(i) >= passThreshold;
            if (mp == hp) {
                agree++;
            }
            if (mp) {
                mPass++;
            }
            if (hp) {
                hPass++;
            }
        }
        double po = (double) agree / n;
        double pmp = (double) mPass / n;
        double php = (double) hPass / n;
        double pe = pmp * php + (1 - pmp) * (1 - php);
        if (pe == 1.0) {
            return Double.NaN;
        }
        return (po - pe) / (1 - pe);
    }

    
    private static double[] midRanks(List<Integer> values) {
        Integer[] order = values.stream()
                .sorted()
                .toArray(Integer[]::new);
        double[] ranks = new double[values.size()];
        double[] sortedRank = new double[values.size()];
        int i = 0;
        while (i < order.length) {
            int j = i;
            while (j + 1 < order.length && order[j + 1].equals(order[i])) {
                j++;
            }
            double mid = (i + j) / 2.0 + 1.0;
            for (int k = i; k <= j; k++) {
                sortedRank[k] = mid;
            }
            i = j + 1;
        }
        for (int v = 0; v < values.size(); v++) {
            int target = values.get(v);
            int found = 0;
            int want = countBefore(values, v, target);
            for (int k = 0; k < order.length; k++) {
                if (order[k] == target) {
                    if (found == want) {
                        ranks[v] = sortedRank[k];
                        break;
                    }
                    found++;
                }
            }
        }
        return ranks;
    }

    
    private static int countBefore(List<Integer> values, int index, int target) {
        int count = 0;
        for (int k = 0; k < index; k++) {
            if (values.get(k) == target) {
                count++;
            }
        }
        return count;
    }

    private static double mean(double[] xs) {
        double sum = 0.0;
        for (double x : xs) {
            sum += x;
        }
        return sum / xs.length;
    }

    private static void requirePairs(List<Integer> machine, List<Integer> human) {
        if (machine == null || human == null || machine.isEmpty() || human.isEmpty()) {
            throw new ValidationException("一致率统计需要改判配对样本（机器分/人工分均为空=无样本可统计）");
        }
        if (machine.size() != human.size()) {
            throw new ValidationException("一致率统计配对长度不齐（机器 " + machine.size()
                    + " 对人工 " + human.size() + "）——样本必须同源配对，取数侧有 bug 请查流水查询");
        }
        if (machine.stream().anyMatch(v -> v == null) || human.stream().anyMatch(v -> v == null)) {
            throw new ValidationException("一致率统计配对含空分——流水里 org/new 任一为空即取数越界，禁静默丢样本凑数");
        }
    }
}
