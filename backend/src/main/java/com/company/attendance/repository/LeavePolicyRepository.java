package com.company.attendance.repository;

import com.company.attendance.entity.LeavePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeavePolicyRepository extends JpaRepository<LeavePolicy, Long> {

    @Query("SELECT p FROM LeavePolicy p WHERE p.leaveType.id = :leaveTypeId AND p.active = true")
    List<LeavePolicy> findActiveForType(@Param("leaveTypeId") Long leaveTypeId);
}
