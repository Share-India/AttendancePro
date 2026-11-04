package com.company.attendance.repository;

import com.company.attendance.entity.Shift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShiftRepository extends JpaRepository<Shift, Long> {

    Optional<Shift> findByCode(String code);

    boolean existsByCode(String code);

    List<Shift> findByActiveTrueOrderByNameAsc();

    Optional<Shift> findFirstByDefaultShiftTrueAndActiveTrue();
}
