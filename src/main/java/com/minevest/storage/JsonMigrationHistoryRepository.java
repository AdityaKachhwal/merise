package com.minevest.storage;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JsonMigrationHistoryRepository extends JpaRepository<JsonMigrationHistoryEntity, String> {
}
