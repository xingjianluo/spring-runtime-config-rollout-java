package com.github.xingjianluo.springruntimeconfigrolloutjava.domain;

/**
 * 一次灰度判定的完整结论。
 * found=false 时 reason 给出可区分的失败原因；found=true 时 basis 说明命中依据。
 */
public record DecisionResult(
        boolean found,
        String key,
        String subject,
        String value,
        long version,
        int schemaVersion,
        String source,
        Integer rolloutPercent,
        Integer bucket,
        boolean cohortHit,
        String basis,
        String reason
) {
}
