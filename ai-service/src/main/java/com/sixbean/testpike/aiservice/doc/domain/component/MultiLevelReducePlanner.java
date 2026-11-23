package com.sixbean.testpike.aiservice.doc.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.ArrayList;
import java.util.List;


public class MultiLevelReducePlanner {

    private final BudgetPolicy policy;

    public MultiLevelReducePlanner(BudgetPolicy policy) {
        this.policy = policy;
    }

    
    public ReducePlan plan(int firstRoundGroups) {
        if (firstRoundGroups <= 0) {
            throw new IllegalArgumentException("归并计划要求组数 ≥1，实际 " + firstRoundGroups);
        }
        List<Integer> levels = new ArrayList<>();
        levels.add(firstRoundGroups);
        int current = firstRoundGroups;
        while (current > policy.reduceTriggerGroups()) {
            if (levels.size() > policy.reduceMaxLevels()) {
                throw new ServiceException("文档分块组数 " + firstRoundGroups
                        + " 超出压缩层级上限（触发值 " + policy.reduceTriggerGroups()
                        + "，最多 " + policy.reduceMaxLevels() + " 级仍无法收敛），"
                        + "请调大 ai.doc.reduce-trigger-groups / ai.doc.reduce-max-levels"
                        + " 或提高 ai.doc.token-budget 后重试",
                        BizErrorCode.AI_FORMAT_ERROR);
            }
            current = (current + policy.reduceTriggerGroups() - 1) / policy.reduceTriggerGroups();
            levels.add(current);
        }
        if (levels.size() > policy.reduceMaxLevels()) {
            throw new ServiceException("文档分块组数 " + firstRoundGroups + " 需 " + levels.size()
                    + " 级归并，超出压缩层级上限 " + policy.reduceMaxLevels() + "，已零摘要调用中止",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        int total = levels.stream().mapToInt(Integer::intValue).sum();
        return new ReducePlan(levels.size(), List.copyOf(levels), total);
    }

    
    public <T> List<List<T>> bucketize(List<T> units) {
        if (units == null || units.isEmpty()) {
            return List.of();
        }
        int trigger = policy.reduceTriggerGroups();
        int bucketCount = (units.size() + trigger - 1) / trigger;
        bucketCount = Math.min(bucketCount, units.size());
        int base = units.size() / bucketCount;
        int remainder = units.size() % bucketCount;
        List<List<T>> buckets = new ArrayList<>(bucketCount);
        int index = 0;
        for (int b = 0; b < bucketCount; b++) {
            int size = base + (b < remainder ? 1 : 0);
            buckets.add(List.copyOf(units.subList(index, index + size)));
            index += size;
        }
        return buckets;
    }
}
