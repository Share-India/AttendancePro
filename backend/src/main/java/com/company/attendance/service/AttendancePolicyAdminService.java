package com.company.attendance.service;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.master.AttendancePolicyRequest;
import com.company.attendance.dto.response.master.AttendancePolicyResponse;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.Shift;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.exception.ResourceNotFoundException;
import com.company.attendance.repository.AttendancePolicyRepository;
import com.company.attendance.repository.ShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading and changing the attendance rules.
 *
 * <p>Every number here was configurable in the database from the start and
 * changeable nowhere, so each adjustment meant an UPDATE typed against
 * production. The mistakes that followed were not hypothetical: a full day set
 * equal to the length of the shift made a full day unreachable for eighteen
 * people, and two of them lost half a day's pay before anybody noticed.
 */
@Service
public class AttendancePolicyAdminService {

    private final AttendancePolicyRepository policyRepository;
    private final ShiftRepository shiftRepository;
    private final AuditLogService auditLogService;

    public AttendancePolicyAdminService(AttendancePolicyRepository policyRepository,
                                        ShiftRepository shiftRepository,
                                        AuditLogService auditLogService) {
        this.policyRepository = policyRepository;
        this.shiftRepository = shiftRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<AttendancePolicyResponse> findAll() {
        boolean overridden = shiftsSetTheirOwnDayLengths();
        return policyRepository.findAll().stream()
                .map(policy -> AttendancePolicyResponse.from(policy, overridden))
                .toList();
    }

    @Transactional
    public AttendancePolicyResponse update(Long id, AttendancePolicyRequest request) {
        AttendancePolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No attendance policy with id " + id));

        assertThresholdsAreOrdered(request);

        Map<String, Object> before = snapshot(policy);

        policy.setName(request.name());
        policy.setDescription(request.description());

        policy.setFullDayMinutes(request.fullDayMinutes());
        policy.setShortDayMinutes(request.shortDayMinutes());
        policy.setHalfDayMinutes(request.halfDayMinutes());
        policy.setMinAttendanceMinutes(request.minAttendanceMinutes());

        policy.setGracePeriodMinutes(request.gracePeriodMinutes());
        policy.setEarlyExitGraceMinutes(request.earlyExitGraceMinutes());
        policy.setLateMarksPerPenalty(request.lateMarksPerPenalty());
        policy.setLateMarkPenaltyDays(request.lateMarkPenaltyDays());

        policy.setMaxBreakMinutes(request.maxBreakMinutes());
        policy.setDeductBreakFromWorking(request.deductBreakFromWorking());
        policy.setAllowPunchOnHoliday(request.allowPunchOnHoliday());
        policy.setAllowPunchOnWeeklyOff(request.allowPunchOnWeeklyOff());

        policy.setRegularizationWindowDays(request.regularizationWindowDays());
        policy.setMaxRegularizationsPerMonth(request.maxRegularizationsPerMonth());
        policy.setMaxWfhDaysPerMonth(request.maxWfhDaysPerMonth());
        policy.setWfhMinNoticeDays(request.wfhMinNoticeDays());

        policy.setPunchInReminderEnabled(request.punchInReminderEnabled());
        policy.setPunchInReminderDelayMinutes(request.punchInReminderDelayMinutes());

        AttendancePolicy saved = policyRepository.save(policy);

        // POLICY_CHANGE rather than UPDATE, and with both sides recorded.
        // These numbers decide what people are paid; when a figure moves,
        // somebody will eventually need to know what it was and who moved it.
        auditLogService.record(AuditAction.POLICY_CHANGE, "AttendancePolicy", saved.getId(),
                "Changed attendance policy " + saved.getCode(),
                before, snapshot(saved));

        return AttendancePolicyResponse.from(saved, shiftsSetTheirOwnDayLengths());
    }

    /**
     * The four day-length thresholds have to descend.
     *
     * <p>Checked here rather than by annotation because the rule is about how
     * the numbers relate, not about any one of them, and because the message
     * is worth writing properly: a half day above a full day is not a typo the
     * person will spot from "must be less than".
     */
    private void assertThresholdsAreOrdered(AttendancePolicyRequest request) {
        if (request.shortDayMinutes() > request.fullDayMinutes()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A short day (" + request.shortDayMinutes() + " min) cannot be longer than a "
                            + "full day (" + request.fullDayMinutes() + " min). Nothing would ever "
                            + "reach it.");
        }
        if (request.halfDayMinutes() > request.shortDayMinutes()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A half day (" + request.halfDayMinutes() + " min) cannot be longer than a "
                            + "short day (" + request.shortDayMinutes() + " min). The half day "
                            + "would swallow the short day.");
        }
        if (request.minAttendanceMinutes() > request.halfDayMinutes()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The minimum (" + request.minAttendanceMinutes() + " min) cannot be longer "
                            + "than a half day (" + request.halfDayMinutes() + " min).");
        }
    }

    /**
     * Whether any active shift sets its own day lengths.
     *
     * <p>A shift's own value wins whenever it is above zero, so when every
     * shift carries one the policy's thresholds are a fallback nobody falls
     * back to. The screen says so instead of letting an administrator change a
     * number and watch nothing happen.
     */
    private boolean shiftsSetTheirOwnDayLengths() {
        return shiftRepository.findByActiveTrueOrderByNameAsc().stream()
                .anyMatch(shift -> shift.getFullDayMinutes() > 0);
    }

    private Map<String, Object> snapshot(AttendancePolicy policy) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", policy.getName());
        values.put("fullDayMinutes", policy.getFullDayMinutes());
        values.put("shortDayMinutes", policy.getShortDayMinutes());
        values.put("halfDayMinutes", policy.getHalfDayMinutes());
        values.put("minAttendanceMinutes", policy.getMinAttendanceMinutes());
        values.put("gracePeriodMinutes", policy.getGracePeriodMinutes());
        values.put("earlyExitGraceMinutes", policy.getEarlyExitGraceMinutes());
        values.put("lateMarksPerPenalty", policy.getLateMarksPerPenalty());
        values.put("lateMarkPenaltyDays", policy.getLateMarkPenaltyDays());
        values.put("maxBreakMinutes", policy.getMaxBreakMinutes());
        values.put("deductBreakFromWorking", policy.isDeductBreakFromWorking());
        values.put("allowPunchOnHoliday", policy.isAllowPunchOnHoliday());
        values.put("allowPunchOnWeeklyOff", policy.isAllowPunchOnWeeklyOff());
        values.put("regularizationWindowDays", policy.getRegularizationWindowDays());
        values.put("maxRegularizationsPerMonth", policy.getMaxRegularizationsPerMonth());
        values.put("maxWfhDaysPerMonth", policy.getMaxWfhDaysPerMonth());
        values.put("wfhMinNoticeDays", policy.getWfhMinNoticeDays());
        values.put("punchInReminderEnabled", policy.isPunchInReminderEnabled());
        values.put("punchInReminderDelayMinutes", policy.getPunchInReminderDelayMinutes());
        return values;
    }
}
