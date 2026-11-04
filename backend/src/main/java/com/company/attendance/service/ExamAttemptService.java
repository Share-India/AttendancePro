package com.company.attendance.service;

import com.company.attendance.dto.request.exam.AnswerSaveRequest;
import com.company.attendance.dto.response.exam.ExamAttemptResponse;
import com.company.attendance.dto.response.exam.ExamResultResponse;
import com.company.attendance.security.UserPrincipal;

/**
 * Sitting the exam: starting, saving, submitting and being told how it went.
 *
 * <p>Three things are held on the server rather than trusted from the browser.
 * The clock — {@code expiresAt} is fixed at start and a late answer is refused.
 * The score — the client submits which options were chosen and nothing else.
 * And the paper — each candidate's option order is generated here, stored, and
 * used to translate their clicks back to canonical labels on the way in.
 */
public interface ExamAttemptService {

    /** Starts the attempt, or hands back the one already running. Idempotent. */
    ExamAttemptResponse start(UserPrincipal principal, Long examId);

    /** The paper as it stands, so a refresh or a dropped connection costs nothing. */
    ExamAttemptResponse resume(UserPrincipal principal, Long attemptId);

    /** Records one answer as it is given. Nothing is marked yet. */
    void saveAnswer(UserPrincipal principal, Long attemptId, AnswerSaveRequest request);

    ExamResultResponse submit(UserPrincipal principal, Long attemptId);

    /** The result and the worked answers. Refuses until the paper is in. */
    ExamResultResponse result(UserPrincipal principal, Long attemptId);
}
