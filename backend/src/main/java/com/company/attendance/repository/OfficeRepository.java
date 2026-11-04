package com.company.attendance.repository;

import com.company.attendance.entity.Office;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OfficeRepository extends JpaRepository<Office, Long> {

    Optional<Office> findByCode(String code);

    List<Office> findByActiveTrueOrderByNameAsc();
}
