package com.company.attendance.repository;

import com.company.attendance.entity.WfhApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WfhApprovalRepository extends JpaRepository<WfhApproval, Long> {

    List<WfhApproval> findByWfhRequestIdOrderByLevelAsc(Long wfhRequestId);

    Optional<WfhApproval> findByWfhRequestIdAndLevel(Long wfhRequestId, short level);
}
