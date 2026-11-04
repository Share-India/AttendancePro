package com.company.attendance.service;

import com.company.attendance.dto.request.master.HolidayRequest;
import com.company.attendance.dto.response.master.HolidayResponse;

import java.util.List;

/**
 * The company holiday calendar.
 *
 * <p>Holidays are read all over the system — {@code CalendarService} marks
 * the day, and a holiday is why somebody is EXCUSED rather than ABSENT on
 * it, in attendance, in meetings and in exam windows. Declaring one is
 * therefore not a cosmetic act, which is why every write here is audited
 * and why withdrawing is a deactivation rather than a delete.
 */
public interface HolidayService {

    /**
     * @param year       restrict to one calendar year, or null for all
     * @param activeOnly hide withdrawn holidays
     */
    List<HolidayResponse> findAll(Integer year, boolean activeOnly);

    HolidayResponse findById(Long id);

    HolidayResponse create(HolidayRequest request);

    HolidayResponse update(Long id, HolidayRequest request);

    /** Withdraws a holiday. The row stays, so the audit trail survives. */
    void deactivate(Long id);
}
