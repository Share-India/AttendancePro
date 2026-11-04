package com.company.attendance.repository;

import com.company.attendance.entity.LeaveApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeaveApprovalRepository extends JpaRepository<LeaveApproval, Long> {

    List<LeaveApproval> findByLeaveRequestIdOrderByLevelAsc(Long leaveRequestId);

    Optional<LeaveApproval> findByLeaveRequestIdAndLevel(Long leaveRequestId, short level);
}
