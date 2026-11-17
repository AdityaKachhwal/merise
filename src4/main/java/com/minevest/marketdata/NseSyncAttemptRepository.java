package com.minevest.marketdata;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface NseSyncAttemptRepository
        extends JpaRepository<NseSyncAttemptEntity, LocalDate> {

    List<NseSyncAttemptEntity> findAllByOrderByDateAsc();
}
