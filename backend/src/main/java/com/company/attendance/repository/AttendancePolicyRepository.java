package com.company.attendance.repository;

import com.company.attendance.entity.AttendancePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AttendancePolicyRepository extends JpaRepository<AttendancePolicy, Long> {
    Optional<AttendancePolicy> findByCode(String code);
}
