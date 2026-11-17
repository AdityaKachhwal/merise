package com.minevest.history;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRunEntity, String> {
    List<AnalysisRunEntity> findAllByOrderByCreatedAtDesc();
}
