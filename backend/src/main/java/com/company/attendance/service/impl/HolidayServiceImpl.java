package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.master.HolidayRequest;
import com.company.attendance.dto.response.master.HolidayResponse;
import com.company.attendance.entity.Holiday;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.exception.ResourceNotFoundException;
import com.company.attendance.repository.HolidayRepository;
import com.company.attendance.service.HolidayService;
import com.company.attendance.util.DateTimeUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class HolidayServiceImpl implements HolidayService {

    /**
     * The types that apply to everybody.
     *
     * <p>The column also permits OPTIONAL and RESTRICTED, which mean a day
     * each employee may choose to take. Nothing in this application models
     * that choice: {@code CalendarService} excuses everyone from any active
     * holiday whatever its type, so storing one would quietly hand the whole
     * company a day off.
     *
     * <p>Checked here as well as by the @Pattern on the request record,
     * because that annotation only runs where something applies @Valid — at
     * the controller. An importer, a migration helper or a future caller
     * reaching the service directly would otherwise walk straight past it,
     * and the cost of being wrong is a day's attendance for everyone.
     */
    private static final Set<String> COMPANY_WIDE_TYPES = Set.of("NATIONAL", "STATE", "COMPANY");

    private final HolidayRepository holidayRepository;
    private final AuditLogService auditLogService;

    public HolidayServiceImpl(HolidayRepository holidayRepository, AuditLogService auditLogService) {
        this.holidayRepository = holidayRepository;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HolidayResponse> findAll(Integer year, boolean activeOnly) {
        List<Holiday> holidays;
        if (year != null) {
            short y = (short) year.intValue();
            holidays = activeOnly
                    ? holidayRepository.findByHolidayYearAndActiveTrueOrderByHolidayDateAsc(y)
                    : holidayRepository.findByHolidayYearOrderByHolidayDateAsc(y);
        } else {
            // Newest first without a year filter: an unfiltered list is being
            // browsed, and the current year is what someone is looking for.
            holidays = holidayRepository.findAllByOrderByHolidayDateDesc().stream()
                    .filter(h -> !activeOnly || h.isActive())
                    .toList();
        }
        LocalDate today = today();
        return holidays.stream().map(h -> toResponse(h, today)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public HolidayResponse findById(Long id) {
        return toResponse(requireHoliday(id), today());
    }

    @Override
    @Transactional
    public HolidayResponse create(HolidayRequest request) {
        assertDateFree(request.holidayDate(), null);

        Holiday holiday = new Holiday();
        apply(holiday, request);
        // Not taken from the request: holiday_year is a denormalised copy of
        // the date's year, and the two must never disagree — the year-filtered
        // queries would silently skip any row where they did.
        holiday.setHolidayYear((short) request.holidayDate().getYear());
        holiday.setActive(request.active() == null || request.active());

        Holiday saved = holidayRepository.save(holiday);
        auditLogService.record(AuditAction.CREATE, "Holiday", saved.getId(),
                "Declared holiday " + saved.getName() + " on " + saved.getHolidayDate(),
                null, snapshot(saved));
        return toResponse(saved, today());
    }

    @Override
    @Transactional
    public HolidayResponse update(Long id, HolidayRequest request) {
        Holiday holiday = requireHoliday(id);
        Object before = snapshot(holiday);

        assertDateFree(request.holidayDate(), id);

        apply(holiday, request);
        holiday.setHolidayYear((short) request.holidayDate().getYear());
        if (request.active() != null) {
            holiday.setActive(request.active());
        }

        Holiday saved = holidayRepository.save(holiday);
        // Before and after both go on the record: moving a holiday changes
        // which day the whole company was excused on, including days already
        // past, because attendance day types are derived on read.
        auditLogService.record(AuditAction.UPDATE, "Holiday", saved.getId(),
                "Updated holiday " + saved.getName(), before, snapshot(saved));
        return toResponse(saved, today());
    }

    @Override
    @Transactional
    public void deactivate(Long id) {
        Holiday holiday = requireHoliday(id);
        if (!holiday.isActive()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION, "This holiday is already withdrawn");
        }

        holiday.setActive(false);
        holidayRepository.save(holiday);
        auditLogService.record(AuditAction.DEACTIVATE, "Holiday", id,
                "Withdrew holiday " + holiday.getName() + " on " + holiday.getHolidayDate());
    }

    /**
     * Two holidays on one day is almost always a double entry, and it reads
     * as a duplicate on every calendar that shows it. Withdrawn rows are
     * ignored, so a date can be re-declared after a mistake.
     */
    private void assertDateFree(LocalDate date, Long excludingId) {
        holidayRepository.findFirstByHolidayDateAndActiveTrue(date)
                .filter(existing -> !existing.getId().equals(excludingId))
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                            "'" + existing.getName() + "' is already declared on " + date
                                    + ". Withdraw it first, or amend it instead.");
                });
    }

    private void apply(Holiday holiday, HolidayRequest request) {
        String type = request.holidayType() == null ? ""
                : request.holidayType().trim().toUpperCase(Locale.ROOT);
        if (!COMPANY_WIDE_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Holiday type must be NATIONAL, STATE or COMPANY");
        }

        holiday.setName(request.name().trim());
        holiday.setHolidayDate(request.holidayDate());
        holiday.setHolidayType(type);
        holiday.setDescription(request.description() == null || request.description().isBlank()
                ? null : request.description().trim());
    }

    private Holiday requireHoliday(Long id) {
        return holidayRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Holiday", id));
    }

    /**
     * The office zone, not the JVM's. A holiday declared from a UTC-pinned
     * server would otherwise flip to "past" five and a half hours early.
     */
    private LocalDate today() {
        return LocalDate.now(DateTimeUtils.FALLBACK_ZONE);
    }

    private HolidayResponse toResponse(Holiday holiday, LocalDate today) {
        return new HolidayResponse(
                holiday.getId(),
                holiday.getName(),
                holiday.getHolidayDate(),
                holiday.getHolidayType(),
                holiday.getDescription(),
                holiday.getHolidayYear(),
                holiday.isActive(),
                holiday.getHolidayDate().isBefore(today));
    }

    private Object snapshot(Holiday holiday) {
        return Map.of(
                "name", holiday.getName(),
                "holidayDate", holiday.getHolidayDate().toString(),
                "holidayType", holiday.getHolidayType(),
                "active", holiday.isActive());
    }
}
