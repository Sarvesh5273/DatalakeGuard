export {
  startEnrollment,
  processEnrollmentFrame,
  startAuthentication,
  processAuthFrame,
  syncPendingRecords,
  getPendingCount,
  getAuthLogs,
  clearAllData,
} from './FaceAuthModule';

export type {
  EnrollResult,
  AuthResult,
  SyncResult,
  AuthLog,
  PendingCount,
  LivenessChallenge,
  ChallengeType,
} from './types';