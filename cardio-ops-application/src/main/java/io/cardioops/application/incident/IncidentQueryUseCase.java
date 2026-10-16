package io.cardioops.application.incident;

import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.incident.Incident;
import io.cardioops.domain.incident.IncidentEvidence;

import java.util.List;
import java.util.Optional;

/**
 * 给运营端的只读查询。改状态另走有版本和审计的人审，不从查询口改。
 */
public interface IncidentQueryUseCase {
    IncidentPage list(IncidentQuery query);

    Optional<Incident> findById(String incidentId);

    List<IncidentEvidence> listEvidence(String incidentId);

    List<AgentRun> listRuns(String incidentId);
}
