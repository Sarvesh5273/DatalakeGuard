import {NativeModules} from 'react-native';
import type {
  EnrollResult,
  AuthResult,
  SyncResult,
  AuthLog,
  PendingCount,
  LivenessChallenge,
} from './types';

const {FaceAuthModule} = NativeModules;

export const startEnrollment = (workerId: string): Promise<EnrollResult> => {
  return FaceAuthModule.startEnrollment(workerId);
};

export const processEnrollmentFrame = (imagePath: string): Promise<EnrollResult> => {
  return FaceAuthModule.processEnrollmentFrame(imagePath);
};

export const startAuthentication = (): Promise<AuthResult & LivenessChallenge> => {
  return FaceAuthModule.startAuthentication();
};

export const processAuthFrame = (imagePath: string): Promise<AuthResult> => {
  return FaceAuthModule.processAuthFrame(imagePath);
};

export const processLivenessFrameForMatching = (): Promise<AuthResult> => {
  return FaceAuthModule.processLivenessFrameForMatching();
};

export const syncPendingRecords = (): Promise<SyncResult> => {
  return FaceAuthModule.syncPendingRecords();
};

export const getPendingCount = (): Promise<PendingCount> => {
  return FaceAuthModule.getPendingCount();
};

export const getAuthLogs = (): Promise<AuthLog[]> => {
  return FaceAuthModule.getAuthLogs();
};

export const clearAllData = (): Promise<{success: boolean; message: string}> => {
  return FaceAuthModule.clearAllData();
};

export type {
  EnrollResult,
  AuthResult,
  SyncResult,
  AuthLog,
  PendingCount,
  LivenessChallenge,
  ChallengeType,
} from './types';
