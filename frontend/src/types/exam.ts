/**
 * Mirrors the backend exam DTOs. See dto/response/exam.
 *
 * Two of these types are deliberately disjoint and must stay that way.
 * `ExamAttemptQuestion` is the paper as the candidate sits it and has no field
 * for the answer; `ExamResultQuestion` is the paper worked through afterwards
 * and carries the key. Merging them into one optional-field type would move the
 * answer key into the sitting payload the first time somebody got the
 * conditional wrong — see ExamSecurityIT on the server side.
 */

export type ExamStatus =
  | 'DRAFT'
  | 'QUESTIONS_LOADED'
  | 'PUBLISHED'
  | 'OPEN'
  | 'CLOSED'
  | 'CANCELLED';

export type ParticipantStatus = 'ASSIGNED' | 'IN_PROGRESS' | 'SUBMITTED' | 'NOT_ATTEMPTED';

export type ExamBucket = 'OPEN' | 'IN_PROGRESS' | 'UPCOMING' | 'COMPLETED' | 'MISSED';

export type FeedbackVisibility = 'IMMEDIATE' | 'AFTER_CLOSE' | 'NEVER';

export interface ExamSummary {
  id: number;
  examCode: string;
  title: string;
  status: ExamStatus;
  windowStart: string;
  windowEnd: string;
  durationMinutes: number;
  passMarks: number;
  totalMarks: number;
  setCount: number;
  questionCount: number;
  rosterSize: number;
  submittedCount: number;
  retake: boolean;
  parentExamId: number | null;
}

export interface ExamAudience {
  scopeType: string;
  scopeId: number | null;
  scopeName: string;
}

export interface ExamSet {
  id: number;
  setCode: string;
  questionCount: number;
  totalMarks: number;
}

export interface Exam {
  id: number;
  examCode: string;
  title: string;
  description: string | null;
  instructions: string | null;
  status: ExamStatus;
  windowStart: string;
  windowEnd: string;
  durationMinutes: number;
  entryClosesBeforeMinutes: number;
  marksPerQuestion: number;
  negativeMarks: number;
  totalMarks: number;
  passMarks: number;
  shuffleQuestions: boolean;
  shuffleOptions: boolean;
  partialCredit: boolean;
  feedbackVisibility: FeedbackVisibility;
  retake: boolean;
  parentExamId: number | null;
  parentExamCode: string | null;
  retakeRequiresUnseenSet: boolean;
  publishedAt: string | null;
  closedAt: string | null;
  cancellationReason: string | null;
  audience: ExamAudience[];
  sets: ExamSet[];
  rosterSize: number;
  publishBlockers: string[];
  canEdit: boolean;
  canPublish: boolean;
  canImportQuestions: boolean;
}

export interface MyExam {
  examId: number;
  examCode: string;
  title: string;
  description: string | null;
  instructions: string | null;
  windowStart: string;
  windowEnd: string;
  durationMinutes: number;
  entryClosesBeforeMinutes: number;
  questionCount: number;
  totalMarks: number;
  passMarks: number;
  retake: boolean;
  setCode: string;
  status: ParticipantStatus;
  bucket: ExamBucket;
  blockedReason: string | null;
  attemptId: number | null;
  score: number | null;
  maxScore: number | null;
  percentage: string | null;
  passed: boolean | null;
  submittedAt: string | null;
  feedbackAvailable: boolean;
}

/** One option on a live paper. No correctness flag, by design. */
export interface AttemptOption {
  label: string;
  text: string;
}

/** The sitting view. Nothing here can carry the answer. */
export interface AttemptQuestion {
  questionId: number;
  displayNumber: number;
  questionText: string;
  topic: string | null;
  marks: number;
  multiSelect: boolean;
  options: AttemptOption[];
  selectedLabels: string[];
}

export interface ExamAttempt {
  attemptId: number;
  examId: number;
  examTitle: string;
  instructions: string | null;
  setCode: string;
  questionCount: number;
  totalMarks: number;
  passMarks: number;
  durationMinutes: number;
  startedAt: string;
  /** The server's clock, and the only one that counts. */
  expiresAt: string;
  secondsRemaining: number;
  submitted: boolean;
  questions: AttemptQuestion[];
}

export interface ResultOption {
  label: string;
  text: string;
  correct: boolean;
  chosen: boolean;
}

/** The worked answer, after submission. This one does carry the key. */
export interface ResultQuestion {
  questionId: number;
  displayNumber: number;
  questionText: string;
  topic: string | null;
  marks: number;
  marksAwarded: number;
  correct: boolean;
  skipped: boolean;
  options: ResultOption[];
  chosenLabels: string[];
  correctLabels: string[];
  explanation: string | null;
  sourceReference: string | null;
}

export interface ExamResult {
  attemptId: number;
  examId: number;
  examTitle: string;
  setCode: string;
  score: number;
  maxScore: number;
  percentage: string | null;
  passMarks: number;
  passed: boolean;
  correctCount: number;
  questionCount: number;
  startedAt: string;
  submittedAt: string | null;
  secondsTaken: number;
  submissionSource: string | null;
  feedbackVisible: boolean;
  questions: ResultQuestion[];
}

export interface ExamParticipantRow {
  participantId: number;
  employeeId: number;
  employeeName: string;
  employeeCode: string | null;
  departmentName: string | null;
  setCode: string;
  participantKind: 'CANDIDATE' | 'TESTER';
  status: ParticipantStatus;
  attemptId: number | null;
  score: number | null;
  maxScore: number | null;
  percentage: string | null;
  passed: boolean | null;
  startedAt: string | null;
  submittedAt: string | null;
  secondsTaken: number | null;
  submissionSource: string | null;
}

export interface ExamPublishResult {
  examId: number;
  rosterSize: number;
  candidateCount: number;
  testerCount: number;
  perSet: Record<string, number>;
  message: string;
}

export interface ImportProblem {
  sheet: string | null;
  row: number | null;
  column: string | null;
  message: string;
}

export interface SetQuality {
  setCode: string;
  questionCount: number;
  answerSpread: Record<string, number>;
  correctIsLongest: number;
  correctIsLongestRate: number;
  absoluteRateCorrect: number;
  absoluteRateWrong: number;
  lopsidedOptionCount: number;
  shortOptionSetCount: number;
  warnings: string[];
}

export interface QualityReport {
  sets: SetQuality[];
  duplicateQuestions: string[];
  warnings: string[];
  clean: boolean;
}

export interface ImportResult {
  committed: boolean;
  accepted: boolean;
  questionCount: number;
  setCount: number;
  sets: { setCode: string; questionCount: number; totalMarks: number }[];
  problems: ImportProblem[];
  quality: QualityReport | null;
  fileName: string | null;
  fileSha256: string | null;
  message: string;
}

export interface ReviewOption {
  label: string;
  text: string;
  correct: boolean;
}

export interface ReviewQuestion {
  id: number;
  questionNumber: number;
  topic: string | null;
  difficulty: string | null;
  questionType: string;
  questionText: string;
  marks: number;
  explanation: string | null;
  sourceReference: string | null;
  correctLabels: string[];
  options: ReviewOption[];
}

export interface ReviewSet {
  setCode: string;
  questionCount: number;
  totalMarks: number;
  questions: ReviewQuestion[];
}

export interface QuestionStat {
  questionId: number;
  setCode: string;
  questionNumber: number;
  topic: string | null;
  difficulty: string | null;
  questionText: string;
  correctLabels: string[];
  asked: number;
  answered: number;
  skipped: number;
  correct: number;
  /** Null when nobody was asked: unmeasured, not failed. */
  correctRate: string | null;
  commonestWrongLabels: string | null;
  commonestWrongText: string | null;
  commonestWrongCount: number;
}

export interface TopicStat {
  topic: string;
  questionCount: number;
  asked: number;
  correct: number;
  correctRate: string | null;
}

export interface ExamAnalytics {
  examId: number;
  examCode: string;
  title: string;
  status: ExamStatus;
  includeTesters: boolean;
  rosterSize: number;
  candidateCount: number;
  testerCount: number;
  submittedCount: number;
  inProgressCount: number;
  notAttemptedCount: number;
  passedCount: number;
  failedCount: number;
  passRate: string | null;
  averageScore: string | null;
  averagePercentage: string | null;
  highestScore: number | null;
  lowestScore: number | null;
  averageSeconds: number;
  questions: QuestionStat[];
  topics: TopicStat[];
}

export interface RetakeResult {
  examId: number;
  examCode: string;
  title: string;
  cohortSize: number;
  failedCount: number;
  neverSatCount: number;
  excluded: { employeeId: number; employeeName: string; reason: string }[];
  message: string;
}

// ---------------------------------------------------------------- labels

export const EXAM_STATUS_LABEL: Record<string, string> = {
  DRAFT: 'Draft',
  QUESTIONS_LOADED: 'Questions loaded',
  PUBLISHED: 'Published',
  OPEN: 'Open now',
  CLOSED: 'Closed',
  CANCELLED: 'Cancelled',
};

export const EXAM_STATUS_TONE: Record<string, 'neutral' | 'info' | 'success' | 'warning' | 'error'> =
  {
    DRAFT: 'neutral',
    QUESTIONS_LOADED: 'info',
    PUBLISHED: 'info',
    OPEN: 'success',
    CLOSED: 'neutral',
    CANCELLED: 'error',
  };

export const PARTICIPANT_STATUS_LABEL: Record<string, string> = {
  ASSIGNED: 'Not started',
  IN_PROGRESS: 'In progress',
  SUBMITTED: 'Submitted',
  NOT_ATTEMPTED: 'Did not sit it',
};

export const PARTICIPANT_STATUS_TONE: Record<
  string,
  'neutral' | 'info' | 'success' | 'warning' | 'error'
> = {
  ASSIGNED: 'neutral',
  IN_PROGRESS: 'info',
  SUBMITTED: 'success',
  NOT_ATTEMPTED: 'warning',
};

export const BUCKET_LABEL: Record<ExamBucket, string> = {
  OPEN: 'Open now',
  IN_PROGRESS: 'In progress',
  UPCOMING: 'Coming up',
  COMPLETED: 'Done',
  MISSED: 'Missed',
};

/** Honest about what the source is, since the clock may have handed it in. */
export const SUBMISSION_LABEL: Record<string, string> = {
  SELF: 'Handed in',
  AUTO_TIMEOUT: 'Time ran out',
  AUTO_WINDOW_CLOSE: 'Exam closed',
};

export const AUDIENCE_TYPES = [
  { value: 'GLOBAL', label: 'Everybody' },
  { value: 'DEPARTMENT', label: 'A department' },
  { value: 'OFFICE', label: 'An office' },
  { value: 'DESIGNATION', label: 'A designation' },
] as const;

export const FEEDBACK_OPTIONS = [
  { value: 'IMMEDIATE', label: 'As soon as they submit' },
  { value: 'AFTER_CLOSE', label: 'Once the exam closes' },
  { value: 'NEVER', label: 'Never show the answers' },
] as const;
