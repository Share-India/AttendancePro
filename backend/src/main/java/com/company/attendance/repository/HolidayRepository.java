package com.company.attendance.repository;

import com.company.attendance.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    List<Holiday> findByHolidayDateAndActiveTrue(LocalDate holidayDate);

    List<Holiday> findByHolidayDateBetweenAndActiveTrueOrderByHolidayDateAsc(LocalDate from, LocalDate to);

    List<Holiday> findByHolidayYearAndActiveTrueOrderByHolidayDateAsc(short year);

    List<Holiday> findByHolidayYearOrderByHolidayDateAsc(short year);

    List<Holiday> findAllByOrderByHolidayDateDesc();

    /**
     * Guards against the same day being declared twice. Only active rows
     * count, so a withdrawn holiday never blocks re-declaring the date.
     */
    Optional<Holiday> findFirstByHolidayDateAndActiveTrue(LocalDate holidayDate);
}
