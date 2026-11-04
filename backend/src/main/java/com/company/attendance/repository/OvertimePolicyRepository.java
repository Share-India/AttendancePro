package com.company.attendance.repository;

import com.company.attendance.entity.OvertimePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OvertimePolicyRepository extends JpaRepository<OvertimePolicy, Long> {
    Optional<OvertimePolicy> findByCode(String code);
}
