/** Mirrors the backend meeting DTOs. See dto/response/meeting. */

export interface MeetingAudienceScope {
  scopeType: string;
  scopeId: number | null;
  label: string;
}

export interface Meeting {
  id: number;
  meetingCode: string;
  title: string;
  agenda: string | null;
  provider: string;
  hostEmployeeId: number;
  hostName: string;
  scheduledStartAt: string;
  scheduledEndAt: string;
  actualStartedAt: string | null;
  actualEndedAt: string | null;
  status: string;
  lateAfterMinutes: number | null;
  cancellationReason: string | null;
  invitedCount: number;
  joinedCount: number;
  canJoin: boolean;
  canStart: boolean;
  canEnd: boolean;
  canEdit: boolean;
  canCancel: boolean;
  myStatus: string | null;
  myJoinedAt: string | null;
  audience: MeetingAudienceScope[];
}

export interface MeetingParticipant {
  id: number;
  employeeId: number;
  employeeCode: string;
  employeeName: string;
  departmentName: string | null;
  status: string;
  firstJoinedAt: string | null;
  clickCount: number;
  attendedMinutesEstimate: number | null;
  attendanceSource: string | null;
  excuseReason: string | null;
}

export interface MeetingStatusPayload {
  meetingId: number;
  status: string;
  serverTime: string;
  scheduledStartAt: string;
  scheduledEndAt: string;
  actualStartedAt: string | null;
  actualEndedAt: string | null;
  invitedCount: number;
  joinedCount: number;
  canJoin: boolean;
  canStart: boolean;
  canEnd: boolean;
}

export const MEETING_STATUS_TONE: Record<string, 'success' | 'warning' | 'error' | 'info' | 'neutral'> = {
  DRAFT: 'neutral',
  SCHEDULED: 'info',
  OPEN: 'success',
  ENDED: 'neutral',
  FINALISED: 'neutral',
  CANCELLED: 'error',
};

export const PARTICIPANT_STATUS_TONE: Record<string, 'success' | 'warning' | 'error' | 'info' | 'neutral'> = {
  JOINED: 'success',
  JOINED_LATE: 'warning',
  NOT_JOINED: 'error',
  EXCUSED: 'info',
  INVITED: 'neutral',
};

/**
 * Deliberately careful wording.
 *
 * <p>The system knows whether somebody opened the join link. It does not know
 * whether they were in the call — a person can dial in from a calendar invite
 * and never touch our link. "Did not open the link" is the honest label;
 * "Absent" would be a claim we cannot support.
 */
export const PARTICIPANT_STATUS_LABEL: Record<string, string> = {
  JOINED: 'Joined',
  JOINED_LATE: 'Joined late',
  NOT_JOINED: 'Did not open the link',
  EXCUSED: 'Excused',
  INVITED: 'Not started',
};

export const EXCUSE_LABEL: Record<string, string> = {
  ON_LEAVE: 'On approved leave',
  WEEKLY_OFF: 'Weekly off',
  HOLIDAY: 'Holiday',
  NOT_EMPLOYED: 'Not employed on this date',
};

export const MEETING_STATUS_LABEL: Record<string, string> = {
  DRAFT: 'Draft',
  SCHEDULED: 'Scheduled',
  OPEN: 'In progress',
  ENDED: 'Ended',
  FINALISED: 'Finished',
  CANCELLED: 'Cancelled',
};

export const PROVIDER_LABEL: Record<string, string> = {
  GOOGLE_MEET: 'Google Meet',
  ZOOM: 'Zoom',
  MICROSOFT_TEAMS: 'Microsoft Teams',
  WEBEX: 'Webex',
  OTHER: 'Meeting link',
};

export const AUDIENCE_TYPES = [
  { value: 'TEAM', label: 'My team' },
  { value: 'GLOBAL', label: 'Everyone' },
  { value: 'DEPARTMENT', label: 'A department' },
  { value: 'OFFICE', label: 'An office' },
] as const;
