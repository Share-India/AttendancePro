package com.company.attendance.repository;

import com.company.attendance.entity.PolicyAssignment;
import com.company.attendance.enums.PolicyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PolicyAssignmentRepository extends JpaRepository<PolicyAssignment, Long> {

    @Query("SELECT pa FROM PolicyAssignment pa WHERE pa.policyType = :type AND pa.active = true")
    List<PolicyAssignment> findActiveByType(@Param("type") PolicyType type);
}
