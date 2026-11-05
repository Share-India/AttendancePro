package com.company.attendance.service;

import com.company.attendance.dto.request.master.WeeklyOffRequest;
import com.company.attendance.dto.response.master.WeeklyOffResponse;

import java.util.List;

/**
 * Recurring non-working days.
 *
 * <p>One behaviour governs everything here and is worth stating before
 * anyone adds a rule: <strong>every rule means "off", and a more specific
 * rule cannot turn a day back into a working day.</strong> Precedence picks
 * which off-rule applies, not whether the day is off at all. So a company-wide
 * "every Saturday" rule cannot be carved out per department — to have groups
 * working different Saturdays, there must be no company-wide Saturday rule,
 * and each group's own Saturdays are declared positively.
 */
public interface WeeklyOffService {

    List<WeeklyOffResponse> findAll(boolean activeOnly);

    WeeklyOffResponse findById(Long id);

    WeeklyOffResponse create(WeeklyOffRequest request);

    WeeklyOffResponse update(Long id, WeeklyOffRequest request);

    /** Removes a rule. The row stays inactive so the audit trail resolves. */
    void deactivate(Long id);
}
