export interface EnrollResult {
  success: boolean;
  workerId?: string;
  error?: string;
  message?: string;
  framesCaptured?: number;
  framesNeeded?: number;
  status?: string;
}

export interface AuthResult {
  matched: boolean;
  workerId?: string;
  confidence?: number;
  livenessPass: boolean;
  error?: string;
  message?: string;
  status?: string;
  authTimeMs?: number;
  currentChallenge?: string;
  framesCaptured?: number; 
}

export interface SyncResult {
  uploaded: number;
  failed: number;
  remaining: number;
  message?: string;
}

export interface AuthLog {
  workerId: string | null;
  result: string;
  livenessPassed: boolean;
  confidence: number;
  timestamp: number;
}

export interface PendingCount {
  pendingEmbeddings: number;
  pendingLogs: number;
}

export type ChallengeType = 'BLINK' | 'SMILE' | 'TURN_LEFT' | 'TURN_RIGHT';

export interface LivenessChallenge {
  challenge1: ChallengeType;
  challenge2: ChallengeType;
}