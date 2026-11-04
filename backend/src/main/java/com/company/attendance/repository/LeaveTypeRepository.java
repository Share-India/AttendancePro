package com.company.attendance.repository;

import com.company.attendance.entity.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeaveTypeRepository extends JpaRepository<LeaveType, Long> {

    Optional<LeaveType> findByCode(String code);

    boolean existsByCode(String code);

    List<LeaveType> findByActiveTrueOrderByDisplayOrderAsc();
}
