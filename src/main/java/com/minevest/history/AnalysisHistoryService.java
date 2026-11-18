package com.minevest.history;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AnalysisHistoryService {
    private final AnalysisRunRepository runRepository;

    public AnalysisHistoryService(AnalysisRunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @Transactional
    public AnalysisSnapshot save(AnalysisSnapshot snapshot) {
        AnalysisRunEntity saved = runRepository.save(new AnalysisRunEntity(snapshot));
        return saved.toSnapshot();
    }

    @Transactional(readOnly = true)
    public boolean exists(String runId) {
        return runRepository.existsById(runId);
    }

    @Transactional(readOnly = true)
    public List<AnalysisSnapshot> findAll() {
        return runRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(AnalysisRunEntity::toSnapshot)
                .toList();
    }

    @Transactional(readOnly = true)
    public AnalysisSnapshot findByRunId(String runId) {
        return runRepository.findById(runId)
                .map(AnalysisRunEntity::toSnapshot)
                .orElseThrow(() -> new IllegalArgumentException("Analysis run not found: " + runId));
    }
}
