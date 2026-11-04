package com.company.attendance.service;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.response.leave.LeaveBalanceResponse;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.LeaveBalance;
import com.company.attendance.entity.LeavePolicy;
import com.company.attendance.entity.LeaveType;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.CompanySettingRepository;
import com.company.attendance.repository.LeaveBalanceRepository;
import com.company.attendance.repository.LeavePolicyRepository;
import com.company.attendance.repository.LeaveTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The leave ledger.
 *
 * <p>Every movement here is audited with its before and after values. A leave
 * balance that cannot be explained is one nobody trusts, and "the system says
 * so" is not an answer when someone is told they have no leave left.
 */
@Service
public class LeaveBalanceService {

    private static final Logger log = LoggerFactory.getLogger(LeaveBalanceService.class);

    private static final Map<String, Integer> SCOPE_SPECIFICITY = Map.of(
            "EMPLOYEE", 50,
            "DEPARTMENT", 40,
            "EMPLOYMENT_TYPE", 30,
            "OFFICE", 20,
            "GLOBAL", 10);

    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final LeavePolicyRepository leavePolicyRepository;
    private final CompanySettingRepository companySettingRepository;
    private final AuditLogService auditLogService;

    public LeaveBalanceService(LeaveBalanceRepository leaveBalanceRepository,
                               LeaveTypeRepository leaveTypeRepository,
                               LeavePolicyRepository leavePolicyRepository,
                               CompanySettingRepository companySettingRepository,
                               AuditLogService auditLogService) {
        this.leaveBalanceRepository = leaveBalanceRepository;
        this.leaveTypeRepository = leaveTypeRepository;
        this.leavePolicyRepository = leavePolicyRepository;
        this.companySettingRepository = companySettingRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * The leave year a date falls in.
     *
     * <p>Organisations differ: a company on an April–March year treats
     * 2026-02-14 as leave year 2025. The start month is a setting, so this is
     * never assumed to be the calendar year.
     */
    public short resolveLeaveYear(LocalDate date) {
        int startMonth = companySettingRepository.findBySettingKey("leave.year_start_month")
                .map(setting -> {
                    try {
                        return Integer.parseInt(setting.getSettingValue());
                    } catch (NumberFormatException e) {
                        log.warn("leave.year_start_month is not a number; falling back to January");
                        return 1;
                    }
                })
                .orElse(1);

        int year = date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
        return (short) year;
    }

    /**
     * Fetches the ledger row, creating it from policy on first use.
     *
     * <p>Balances are provisioned lazily rather than for every employee and
     * type up front: most types are never used by most people, and a table of
     * empty rows makes the ones that matter harder to find.
     */
    @Transactional
    public LeaveBalance getOrCreate(Employee employee, LeaveType leaveType, short leaveYear) {
        return leaveBalanceRepository
                .findByEmployeeIdAndLeaveTypeIdAndLeaveYear(employee.getId(), leaveType.getId(), leaveYear)
                .orElseGet(() -> provision(employee, leaveType, leaveYear));
    }

    private LeaveBalance provision(Employee employee, LeaveType leaveType, short leaveYear) {
        BigDecimal quota = resolveQuota(employee, leaveType, leaveYear);

        LeaveBalance balance = new LeaveBalance();
        balance.setEmployee(employee);
        balance.setLeaveType(leaveType);
        balance.setLeaveYear(leaveYear);
        balance.setOpeningBalance(quota);

        LeaveBalance saved = leaveBalanceRepository.save(balance);

        auditLogService.record(AuditAction.CREATE, "LeaveBalance", saved.getId(),
                "Opened " + leaveType.getCode() + " balance for " + employee.getEmployeeCode()
                        + " (" + leaveYear + ") with " + quota + " days",
                null, Map.of("openingBalance", quota));

        return saved;
    }

    /**
     * The entitlement this employee gets, pro-rated when they joined mid-year.
     *
     * <p>Pro-rating counts whole months remaining from the joining date. Giving
     * a December joiner a full year's leave is a real cost; giving them none is
     * a real grievance.
     */
    private BigDecimal resolveQuota(Employee employee, LeaveType leaveType, short leaveYear) {
        LocalDate yearStart = leaveYearStart(leaveYear);
        LocalDate yearEnd = yearStart.plusYears(1).minusDays(1);

        Optional<LeavePolicy> policy = leavePolicyRepository.findActiveForType(leaveType.getId()).stream()
                .filter(candidate -> candidate.isEffectiveOn(yearStart) || candidate.isEffectiveOn(yearEnd))
                .filter(candidate -> matchesScope(candidate, employee))
                .max(Comparator
                        .comparingInt((LeavePolicy p) -> SCOPE_SPECIFICITY.getOrDefault(p.getScopeType(), 0))
                        .thenComparingInt(LeavePolicy::getPriority));

        if (policy.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal quota = policy.get().getAnnualQuota();

        boolean joinedMidYear = employee.getJoiningDate() != null
                && employee.getJoiningDate().isAfter(yearStart);

        if (!policy.get().isProRataOnJoining() || !joinedMidYear) {
            return quota;
        }

        long monthsRemaining = ChronoUnit.MONTHS.between(
                employee.getJoiningDate().withDayOfMonth(1),
                yearEnd.withDayOfMonth(1)) + 1;
        monthsRemaining = Math.max(0, Math.min(12, monthsRemaining));

        return quota.multiply(BigDecimal.valueOf(monthsRemaining))
                .divide(BigDecimal.valueOf(12), 1, RoundingMode.DOWN);
    }

    private LocalDate leaveYearStart(short leaveYear) {
        int startMonth = companySettingRepository.findBySettingKey("leave.year_start_month")
                .map(setting -> {
                    try {
                        return Integer.parseInt(setting.getSettingValue());
                    } catch (NumberFormatException e) {
                        return 1;
                    }
                })
                .orElse(1);
        return LocalDate.of(leaveYear, startMonth, 1);
    }

    private boolean matchesScope(LeavePolicy policy, Employee employee) {
        return switch (policy.getScopeType()) {
            case "GLOBAL" -> true;
            case "EMPLOYEE" -> employee.getId().equals(policy.getScopeId());
            case "DEPARTMENT" -> employee.getDepartment() != null
                    && employee.getDepartment().getId().equals(policy.getScopeId());
            case "OFFICE" -> employee.getOffice() != null
                    && employee.getOffice().getId().equals(policy.getScopeId());
            case "EMPLOYMENT_TYPE" -> employee.getEmploymentType().name().equals(policy.getScopeValue());
            default -> false;
        };
    }

    // -----------------------------------------------------------------
    // Movements
    // -----------------------------------------------------------------

    /**
     * Reserves days against a submitted request.
     *
     * <p>The row is locked before the sufficiency check so the check and the
     * write are one atomic step. Without the lock, two applies could each read
     * the same available figure, both pass, and the second only fail on commit
     * — after the employee had been told it worked.
     */
    @Transactional
    public LeaveBalance reserve(Employee employee, LeaveType leaveType, short leaveYear, BigDecimal days) {
        getOrCreate(employee, leaveType, leaveYear);

        LeaveBalance balance = leaveBalanceRepository
                .findForUpdate(employee.getId(), leaveType.getId(), leaveYear)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "Leave balance could not be locked"));

        BigDecimal available = balance.getAvailableDays();

        if (!leaveType.isAllowNegativeBalance() && available.compareTo(days) < 0) {
            throw new BusinessException(ErrorCode.LEAVE_BALANCE_INSUFFICIENT,
                    "You have " + strip(available) + " day(s) of " + leaveType.getName()
                            + " available, and this request needs " + strip(days) + ".",
                    Map.of("leaveType", leaveType.getName(),
                            "requestedDays", days,
                            "availableDays", available,
                            "shortfallDays", days.subtract(available)));
        }

        BigDecimal before = balance.getPendingDays();
        balance.reserve(days);
        LeaveBalance saved = leaveBalanceRepository.save(balance);

        auditLogService.record(AuditAction.UPDATE, "LeaveBalance", saved.getId(),
                "Reserved " + strip(days) + " day(s) of " + leaveType.getCode(),
                Map.of("pendingDays", before), Map.of("pendingDays", saved.getPendingDays()));

        return saved;
    }

    /** Releases a reservation without consuming it: rejection or withdrawal. */
    @Transactional
    public void release(Employee employee, LeaveType leaveType, short leaveYear, BigDecimal days, String why) {
        LeaveBalance balance = leaveBalanceRepository
                .findForUpdate(employee.getId(), leaveType.getId(), leaveYear)
                .orElse(null);

        if (balance == null) {
            log.warn("Releasing {} days of {} for {} but no balance row exists",
                    days, leaveType.getCode(), employee.getEmployeeCode());
            return;
        }

        BigDecimal before = balance.getPendingDays();
        balance.release(days);
        leaveBalanceRepository.save(balance);

        auditLogService.record(AuditAction.UPDATE, "LeaveBalance", balance.getId(),
                "Released " + strip(days) + " reserved day(s) of " + leaveType.getCode() + " — " + why,
                Map.of("pendingDays", before), Map.of("pendingDays", balance.getPendingDays()));
    }

    /** Turns a reservation into consumption on approval. */
    @Transactional
    public void consume(Employee employee, LeaveType leaveType, short leaveYear, BigDecimal days) {
        LeaveBalance balance = leaveBalanceRepository
                .findForUpdate(employee.getId(), leaveType.getId(), leaveYear)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "Leave balance missing at approval"));

        Map<String, Object> before = Map.of(
                "pendingDays", balance.getPendingDays(), "usedDays", balance.getUsedDays());

        balance.consume(days);
        leaveBalanceRepository.save(balance);

        auditLogService.record(AuditAction.UPDATE, "LeaveBalance", balance.getId(),
                "Consumed " + strip(days) + " day(s) of " + leaveType.getCode() + " on approval",
                before, Map.of("pendingDays", balance.getPendingDays(), "usedDays", balance.getUsedDays()));
    }

    /** Refunds days already consumed — cancelling dates that have not passed. */
    @Transactional
    public void refund(Employee employee, LeaveType leaveType, short leaveYear, BigDecimal days, String why) {
        if (days.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        LeaveBalance balance = leaveBalanceRepository
                .findForUpdate(employee.getId(), leaveType.getId(), leaveYear)
                .orElse(null);

        if (balance == null) {
            return;
        }

        BigDecimal before = balance.getUsedDays();
        balance.refund(days);
        leaveBalanceRepository.save(balance);

        auditLogService.record(AuditAction.UPDATE, "LeaveBalance", balance.getId(),
                "Refunded " + strip(days) + " day(s) of " + leaveType.getCode() + " — " + why,
                Map.of("usedDays", before), Map.of("usedDays", balance.getUsedDays()));
    }

    /** An HR correction. The reason is required and recorded. */
    @Transactional
    public LeaveBalance adjust(Employee employee, LeaveType leaveType, short leaveYear,
                               BigDecimal days, String reason) {
        LeaveBalance balance = getOrCreate(employee, leaveType, leaveYear);

        BigDecimal before = balance.getAdjustedDays();
        balance.setAdjustedDays(before.add(days));

        // An adjustment must not drive the balance below what is already
        // committed, or the employee is left owing leave they have taken.
        if (balance.getAvailableDays().compareTo(BigDecimal.ZERO) < 0
                && !leaveType.isAllowNegativeBalance()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "That adjustment would leave a negative balance of "
                            + strip(balance.getAvailableDays()) + " days, and "
                            + leaveType.getName() + " does not permit one.");
        }

        LeaveBalance saved = leaveBalanceRepository.save(balance);

        auditLogService.record(AuditAction.UPDATE, "LeaveBalance", saved.getId(),
                "HR adjusted " + leaveType.getCode() + " by " + strip(days) + " day(s): " + reason,
                Map.of("adjustedDays", before),
                Map.of("adjustedDays", saved.getAdjustedDays(), "reason", reason));

        return saved;
    }

    // -----------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------

    /**
     * Every active leave type with this employee's position on it, provisioning
     * rows that do not exist yet so the card is never missing a type.
     */
    @Transactional
    public List<LeaveBalanceResponse> getBalances(Employee employee, short leaveYear) {
        leaveTypeRepository.findByActiveTrueOrderByDisplayOrderAsc()
                .forEach(type -> getOrCreate(employee, type, leaveYear));

        return leaveBalanceRepository.findForEmployeeAndYear(employee.getId(), leaveYear).stream()
                .map(this::toResponse)
                .toList();
    }

    private LeaveBalanceResponse toResponse(LeaveBalance balance) {
        LeaveType type = balance.getLeaveType();
        return new LeaveBalanceResponse(
                type.getId(), type.getCode(), type.getName(), type.isPaid(), balance.getLeaveYear(),
                balance.getEntitledDays(),
                balance.getOpeningBalance(),
                balance.getCreditedDays(),
                balance.getCarriedForwardDays(),
                balance.getAdjustedDays(),
                balance.getUsedDays(),
                balance.getPendingDays(),
                balance.getEncashedDays(),
                balance.getLapsedDays(),
                balance.getAvailableDays());
    }

    /** Trims trailing zeros so messages read "2" and "1.5", not "2.00". */
    private String strip(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
