package com.intelliguard.repository;

import com.intelliguard.entity.ServiceDependency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServiceDependencyRepository extends JpaRepository<ServiceDependency, Long> {

    boolean existsByServiceIdAndDependsOnServiceId(Long serviceId, Long dependsOnServiceId);

    List<ServiceDependency> findAllByOrderByServiceIdAscDependsOnServiceIdAsc();
}
