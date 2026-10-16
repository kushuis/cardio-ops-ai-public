package io.cardioops.domain.scheduledmiss;

import java.util.List;
import java.util.Objects;

/**
 * 模型在限定原因里写的一条假设。解释不能空。支持和反证都只能引用这次工具返回过的证据。
 */
public record ScheduledMissHypothesis(
        //假设类型
        ScheduledMissHypothesisCode code,
        //假设证据编号
        List<String> supportingEvidenceRefs,
        //和假设冲突的编号
        List<String> contradictingEvidenceRefs,
        //这条假设还缺什么
        List<String> missingEvidence,
        //假设的文字解释
        String explanation
) {
    public ScheduledMissHypothesis {
        Objects.requireNonNull(code, "code must not be null");
        supportingEvidenceRefs = List.copyOf(supportingEvidenceRefs);
        contradictingEvidenceRefs = List.copyOf(contradictingEvidenceRefs);
        missingEvidence = List.copyOf(missingEvidence);
        Objects.requireNonNull(explanation, "explanation must not be null");
        if (explanation.isBlank()) {
            throw new IllegalArgumentException("hypothesis explanation must not be blank");
        }
    }
}
