package com.company.attendance.repository;

import com.company.attendance.entity.LeaveBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {

    Optional<LeaveBalance> findByEmployeeIdAndLeaveTypeIdAndLeaveYear(
            Long employeeId, Long leaveTypeId, short leaveYear);

    /**
     * Locks the ledger row for the duration of a reservation or approval.
     *
     * <p>The optimistic @Version alone would let two concurrent applies both
     * read the same available figure and both pass the sufficiency check; one
     * would then fail on commit, but only after the user was told it worked.
     * Taking the row lock first serialises the check and the write together.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM LeaveBalance b WHERE b.employee.id = :employeeId AND b.leaveType.id = :leaveTypeId AND b.leaveYear = :year")
    Optional<LeaveBalance> findForUpdate(@Param("employeeId") Long employeeId,
                                         @Param("leaveTypeId") Long leaveTypeId,
                                         @Param("year") short year);

    @Query("SELECT b FROM LeaveBalance b JOIN FETCH b.leaveType t WHERE b.employee.id = :employeeId AND b.leaveYear = :year AND t.active = true ORDER BY t.displayOrder")
    List<LeaveBalance> findForEmployeeAndYear(@Param("employeeId") Long employeeId, @Param("year") short year);
}
