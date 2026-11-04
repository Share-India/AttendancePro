package com.company.attendance.repository;

import com.company.attendance.entity.RegularizationApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RegularizationApprovalRepository extends JpaRepository<RegularizationApproval, Long> {

    List<RegularizationApproval> findByRegularizationIdOrderByLevelAsc(Long regularizationId);

    Optional<RegularizationApproval> findByRegularizationIdAndLevel(Long regularizationId, short level);
}
