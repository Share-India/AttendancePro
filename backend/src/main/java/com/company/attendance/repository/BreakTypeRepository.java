package com.company.attendance.repository;

import com.company.attendance.entity.BreakType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BreakTypeRepository extends JpaRepository<BreakType, Long> {

    Optional<BreakType> findByCode(String code);

    List<BreakType> findByActiveTrueOrderByDisplayOrderAsc();
}
