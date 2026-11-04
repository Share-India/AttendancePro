package com.company.attendance.dto.response.exam;

import com.company.attendance.enums.ExamParticipantKind;
import com.company.attendance.enums.ExamParticipantStatus;
import com.company.attendance.enums.ExamSubmissionSource;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One person on the roster, with their result if they have handed in.
 *
 * <p>The roster and the results are one list rather than two screens joined by
 * eye: the question anybody actually asks is "who has not sat it", and that is
 * only answerable with both.
 */
public record ExamParticipantResponse(
        Long participantId,
        Long employeeId,
        String employeeName,
        String employeeCode,
        String departmentName,
        String setCode,
        ExamParticipantKind participantKind,
        ExamParticipantStatus status,
        Long attemptId,
        Integer score,
        Integer maxScore,
        BigDecimal percentage,
        Boolean passed,
        Instant startedAt,
        Instant submittedAt,
        Long secondsTaken,
        ExamSubmissionSource submissionSource
) {
}
