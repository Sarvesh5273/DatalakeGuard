import {NativeModules, NativeEventEmitter} from 'react-native';

const AuthSyncModule = NativeModules.AuthSyncModule;
const syncEmitter = AuthSyncModule ? new NativeEventEmitter(AuthSyncModule) : null;

export interface AuthLog {
  id: number;
  worker_id: string;
  confidence: number;
  liveness_pass: boolean;
  timestamp: number;
  synced: boolean;
}

export const logAuthEvent = async (
  workerId: string,
  confidence: number,
  livenessPass: boolean,
): Promise<string> => {
  if (!AuthSyncModule) throw new Error('AuthSyncModule not available');
  return AuthSyncModule.logAuthEvent(workerId, confidence, livenessPass);
};

export const getPendingSyncCount = async (): Promise<number> => {
  if (!AuthSyncModule) return 0;
  return AuthSyncModule.getPendingCount();
};

export const getAllAuthLogs = async (): Promise<AuthLog[]> => {
  if (!AuthSyncModule) return [];
  return AuthSyncModule.getAllLogs();
};

export const syncToAWS = async (): Promise<string> => {
  if (!AuthSyncModule) throw new Error('AuthSyncModule not available');
  return AuthSyncModule.syncToAWS();
};

export const addSyncListener = (callback: (event: {syncedCount: number; pendingCount: number}) => void) => {
  if (!syncEmitter) return {remove: () => {}};
  return syncEmitter.addListener('onSyncComplete', callback);
};