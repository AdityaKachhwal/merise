package com.minevest.fincrux;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FincruxSnapshotRepository
        extends JpaRepository<FincruxSnapshotEntity, FincruxSnapshotEntity.SnapshotId> {
}
