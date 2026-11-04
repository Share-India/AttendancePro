package com.company.attendance.service;

import com.company.attendance.dto.request.exam.ExamCancelRequest;
import com.company.attendance.dto.request.exam.ExamRequest;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.exam.ExamParticipantResponse;
import com.company.attendance.dto.response.exam.ExamPublishResponse;
import com.company.attendance.dto.response.exam.ExamQuestionReviewResponse;
import com.company.attendance.dto.response.exam.ExamResponse;
import com.company.attendance.dto.response.exam.ExamSummaryResponse;
import com.company.attendance.dto.response.exam.MyExamResponse;
import com.company.attendance.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.util.List;

/** Weekly study exams: setting them, publishing them, and who sat what. */
public interface ExamService {

    PageResponse<ExamSummaryResponse> search(UserPrincipal principal, String status, Pageable pageable);

    ExamResponse get(UserPrincipal principal, Long examId);

    ExamResponse create(UserPrincipal principal, ExamRequest request);

    ExamResponse update(UserPrincipal principal, Long examId, ExamRequest request);

    ExamResponse cancel(UserPrincipal principal, Long examId, ExamCancelRequest request);

    /** Fixes the roster, deals the papers and tells everybody. */
    ExamPublishResponse publish(UserPrincipal principal, Long examId);

    /** The roster with results, scoped to the employees the caller may see. */
    List<ExamParticipantResponse> participants(UserPrincipal principal, Long examId);

    /** The whole bank with its keys, for proof-reading. Never scoped. */
    List<ExamQuestionReviewResponse> questionReview(UserPrincipal principal, Long examId);

    /** The exams one person has been given, with Start already decided. */
    List<MyExamResponse> myExams(UserPrincipal principal);
}
