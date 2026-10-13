package com.intelliguard.repository;

import com.intelliguard.entity.SecurityEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, Long> {
    List<SecurityEvent> findTop50ByOrderByTimestampDesc();

    long countByTimestampAfter(Instant timestamp);

    // Risk engine input: recent security events attributed to one service.
    long countByServiceIdAndTimestampAfter(Long serviceId, Instant timestamp);

    // Root-cause evidence: one service's security events inside one incident's window.
    long countByServiceIdAndTimestampBetween(Long serviceId, Instant from, Instant to);

    // Copilot context: [serviceId, eventType, count] for the incident's services inside its window.
    @Query("SELECT s.serviceId, s.eventType, COUNT(s) FROM SecurityEvent s WHERE s.serviceId IN :serviceIds " +
            "AND s.timestamp BETWEEN :from AND :to GROUP BY s.serviceId, s.eventType ORDER BY s.serviceId, s.eventType")
    List<Object[]> countByServiceAndTypeInWindow(@Param("serviceIds") Collection<Long> serviceIds,
                                                 @Param("from") Instant from, @Param("to") Instant to);
}
