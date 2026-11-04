import { apiClient, unwrap } from '../../api/axiosClient';
import { endpoints } from '../../api/endpoints';
import type { ApiResponse, PageResponse } from '../../types/api';
import type {
  Exam,
  ExamAnalytics,
  ExamAttempt,
  ExamParticipantRow,
  ExamPublishResult,
  ExamResult,
  ExamSummary,
  ImportResult,
  MyExam,
  RetakeResult,
  ReviewSet,
} from '../../types/exam';

type ListParams = { page?: number; size?: number; status?: string };

export interface ExamPayload {
  examCode: string;
  title: string;
  description?: string | null;
  instructions?: string | null;
  windowStart: string;
  windowEnd: string;
  durationMinutes: number;
  entryClosesBeforeMinutes: number;
  marksPerQuestion: number;
  negativeMarks: number;
  passMarks: number;
  shuffleQuestions: boolean;
  shuffleOptions: boolean;
  partialCredit: boolean;
  feedbackVisibility: string;
  audience: { scopeType: string; scopeId: number | null }[];
}

export interface RetakePayload {
  title?: string | null;
  windowStart: string;
  windowEnd: string;
  durationMinutes?: number | null;
}

export const examApi = {
  // ------------------------------------------------------------- setting one

  list: async (params: ListParams): Promise<PageResponse<ExamSummary>> =>
    unwrap(
      await apiClient.get<ApiResponse<PageResponse<ExamSummary>>>(endpoints.exams.base, { params }),
    ),

  get: async (id: number): Promise<Exam> =>
    unwrap(await apiClient.get<ApiResponse<Exam>>(endpoints.exams.byId(id))),

  create: async (payload: ExamPayload): Promise<Exam> =>
    unwrap(await apiClient.post<ApiResponse<Exam>>(endpoints.exams.base, payload)),

  update: async (id: number, payload: ExamPayload): Promise<Exam> =>
    unwrap(await apiClient.put<ApiResponse<Exam>>(endpoints.exams.byId(id), payload)),

  publish: async (id: number): Promise<ExamPublishResult> =>
    unwrap(await apiClient.put<ApiResponse<ExamPublishResult>>(endpoints.exams.publish(id), {})),

  cancel: async (id: number, reason: string): Promise<Exam> =>
    unwrap(await apiClient.put<ApiResponse<Exam>>(endpoints.exams.cancel(id), { reason })),

  participants: async (id: number): Promise<ExamParticipantRow[]> =>
    unwrap(
      await apiClient.get<ApiResponse<ExamParticipantRow[]>>(endpoints.exams.participants(id)),
    ),

  /** The bank with its answer key. EXAM_QUESTION_IMPORT only. */
  questions: async (id: number): Promise<ReviewSet[]> =>
    unwrap(await apiClient.get<ApiResponse<ReviewSet[]>>(endpoints.exams.questions(id))),

  analytics: async (id: number, includeTesters: boolean): Promise<ExamAnalytics> =>
    unwrap(
      await apiClient.get<ApiResponse<ExamAnalytics>>(endpoints.exams.analytics(id), {
        params: { includeTesters },
      }),
    ),

  retake: async (id: number, payload: RetakePayload): Promise<RetakeResult> =>
    unwrap(await apiClient.post<ApiResponse<RetakeResult>>(endpoints.exams.retake(id), payload)),

  // ------------------------------------------------------------- the import

  /**
   * Uploads a workbook. `commit: false` parses and reports without writing,
   * so a file expected to be wrong can be checked first.
   */
  importWorkbook: async (id: number, file: File, commit: boolean): Promise<ImportResult> => {
    const form = new FormData();
    form.append('file', file);
    return unwrap(
      await apiClient.post<ApiResponse<ImportResult>>(endpoints.exams.importWorkbook(id), form, {
        params: { commit },
        headers: { 'Content-Type': 'multipart/form-data' },
      }),
    );
  },

  /** Downloads the blank template through the authenticated client. */
  downloadTemplate: async (): Promise<void> => {
    const response = await apiClient.get(endpoints.exams.importTemplate, {
      responseType: 'blob',
    });
    // Fetched with the bearer token rather than linked to directly: a plain
    // <a href> would arrive unauthenticated and download a JSON error.
    const url = URL.createObjectURL(response.data as Blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'exam-question-template.xlsx';
    link.click();
    URL.revokeObjectURL(url);
  },

  // ----------------------------------------------------------- sitting one

  mine: async (): Promise<MyExam[]> =>
    unwrap(await apiClient.get<ApiResponse<MyExam[]>>(endpoints.exams.mine)),

  start: async (examId: number): Promise<ExamAttempt> =>
    unwrap(await apiClient.post<ApiResponse<ExamAttempt>>(endpoints.exams.start(examId), {})),

  attempt: async (attemptId: number): Promise<ExamAttempt> =>
    unwrap(await apiClient.get<ApiResponse<ExamAttempt>>(endpoints.exams.attempt(attemptId))),

  saveAnswer: async (
    attemptId: number,
    questionId: number,
    selectedLabels: string[],
  ): Promise<void> => {
    await apiClient.put(endpoints.exams.answers(attemptId), { questionId, selectedLabels });
  },

  submit: async (attemptId: number): Promise<ExamResult> =>
    unwrap(await apiClient.post<ApiResponse<ExamResult>>(endpoints.exams.submit(attemptId), {})),

  result: async (attemptId: number): Promise<ExamResult> =>
    unwrap(await apiClient.get<ApiResponse<ExamResult>>(endpoints.exams.result(attemptId))),
};
