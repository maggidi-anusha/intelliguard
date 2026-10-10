package com.intelliguard.repository;

import com.intelliguard.entity.SecurityEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, Long> {
    List<SecurityEvent> findTop50ByOrderByTimestampDesc();

    long countByTimestampAfter(Instant timestamp);

    // Risk engine input: recent security events attributed to one service.
    long countByServiceIdAndTimestampAfter(Long serviceId, Instant timestamp);
}
