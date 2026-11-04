package com.company.attendance.repository;

import com.company.attendance.entity.LocationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LocationPolicyRepository extends JpaRepository<LocationPolicy, Long> {
    Optional<LocationPolicy> findByCode(String code);
}
