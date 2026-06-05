import React, {useState, useRef, useCallback, useEffect} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Alert,
  NativeEventEmitter,
  NativeModules,
  requireNativeComponent,
  PermissionsAndroid,
  Platform,
  LogBox,
  ActivityIndicator,
  Linking,
} from 'react-native';

import {startAuthentication, processLivenessFrameForMatching} from '../modules/faceAuth';
import {getPendingSyncCount, syncToAWS} from '../modules/faceAuthSync';

const {FaceAuthModule, AuthSyncModule} = NativeModules;
const livenessEmitter = new NativeEventEmitter(FaceAuthModule);

const CameraPreviewView = Platform.OS === 'android' ? requireNativeComponent<{style: any}>('CameraPreviewView') : null;

LogBox.ignoreLogs(['new NativeEventEmitter']);

const requestCameraPermission = async (): Promise<boolean> => {
  if (Platform.OS === 'android') {
    const granted = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.CAMERA,
      {
        title: 'Camera Permission',
        message: 'This app needs camera access for face authentication',
        buttonPositive: 'OK',
      },
    );
    return granted === PermissionsAndroid.RESULTS.GRANTED;
  }
  
  // iOS: Try starting camera to check permission
  try {
    await FaceAuthModule.startLivenessCamera();
    await FaceAuthModule.stopLivenessCamera();
    return true;
  } catch (e: any) {
    Alert.alert(
      'Camera Permission Required',
      'Please enable camera access in Settings > Privacy > Camera > DatalakeGuard',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Open Settings', onPress: () => Linking.openURL('app-settings:') },
      ]
    );
    return false;
  }
};

const CHALLENGE_TIMEOUT = 20000;
const YAW_SMOOTHING_WINDOW = 3;
const MATCHING_TIMEOUT = 8000;

export default function AuthScreen() {
  const [isAuthenticating, setIsAuthenticating] = useState(false);
  const [status, setStatus] = useState('');
  const [liveMetrics, setLiveMetrics] = useState<any>(null);
  const [challengeDisplay, setChallengeDisplay] = useState('');
  const [challengeIndex, setChallengeIndex] = useState(0);
  const [pendingSync, setPendingSync] = useState(0);
  const [isSyncing, setIsSyncing] = useState(false);
  const [hasPermission, setHasPermission] = useState<boolean | null>(null);

  const challengesRef = useRef<string[]>([]);
  const currentChallengeIndexRef = useRef(0);
  const livenessSub = useRef<any>(null);
  const isProcessingRef = useRef(false);
  const cameraStartedRef = useRef(false);
  const timeoutRef = useRef<NodeJS.Timeout | null>(null);
  const stableFrameCountRef = useRef(0);
  const REQUIRED_STABLE_FRAMES = 2;
  const gracePeriodRef = useRef(false);
  const yawHistoryRef = useRef<number[]>([]);

  useEffect(() => {
    checkPermission();
  }, []);

  const checkPermission = async () => {
    try {
      await FaceAuthModule.startLivenessCamera();
      await FaceAuthModule.stopLivenessCamera();
      setHasPermission(true);
    } catch (e) {
      setHasPermission(false);
    }
  };

  const clearChallengeTimeout = () => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
  };

  const startChallengeTimeout = () => {
    clearChallengeTimeout();
    timeoutRef.current = setTimeout(() => {
      cleanup();
      Alert.alert('Timeout', 'Challenge timed out. Please try again.');
    }, CHALLENGE_TIMEOUT);
  };

  const cleanup = useCallback(() => {
    clearChallengeTimeout();
    livenessSub.current?.remove();
    livenessSub.current = null;
    FaceAuthModule.stopLivenessCamera?.();
    cameraStartedRef.current = false;
    setIsAuthenticating(false);
    setLiveMetrics(null);
    setChallengeDisplay('');
    setChallengeIndex(0);
    challengesRef.current = [];
    currentChallengeIndexRef.current = 0;
    stableFrameCountRef.current = 0;
    gracePeriodRef.current = false;
    yawHistoryRef.current = [];
    isProcessingRef.current = false;
  }, []);

  useEffect(() => () => cleanup(), [cleanup]);

  const loadPendingCount = useCallback(async () => {
    try {
      const count = await getPendingSyncCount();
      setPendingSync(count);
    } catch (e) {
      console.log('Sync count error', e);
    }
  }, []);

  useEffect(() => {
    loadPendingCount();
    const interval = setInterval(loadPendingCount, 5000);
    return () => clearInterval(interval);
  }, [loadPendingCount]);

  const performFaceMatching = useCallback(async () => {
    if (isProcessingRef.current) {
      console.log('performFaceMatching: already processing, skipping');
      return;
    }
    isProcessingRef.current = true;
    clearChallengeTimeout();
    setStatus('Verifying face...');

    try {
      console.log('performFaceMatching: calling native module...');
      const result = (await Promise.race([
        processLivenessFrameForMatching(),
        new Promise((_, reject) =>
          setTimeout(
            () => reject(new Error('Face matching timed out after 8s')),
            MATCHING_TIMEOUT,
          ),
        ),
      ])) as any;

      console.log('performFaceMatching: native returned', result);

      if (result?.status === 'complete') {
        // Queue auth log from JS side (bypasses native queue bug)
        try {
          await AuthSyncModule.logAuthEvent(
            result.workerId || 'unknown',
            result.confidence || 0,
            result.livenessPass || false,
          );
          console.log('JS: Auth log queued successfully');
        } catch (e) {
          console.log('JS: Auth log queue failed', e);
        }

        if (result.matched) {
          cleanup();
          Alert.alert(
            'Authenticated',
            `Welcome, ${result.workerId}!\nConfidence: ${(result.confidence * 100).toFixed(1)}%`,
          );
        } else {
          cleanup();
          Alert.alert('Not Recognised', 'Face not found in database.');
        }
      } else {
        cleanup();
        Alert.alert('Error', 'Unexpected verification response.');
      }
    } catch (e: any) {
      console.log('performFaceMatching: error', e.message);
      Alert.alert('Error', e.message);
      isProcessingRef.current = false;
    }
  }, [cleanup]);

  const advanceChallenge = useCallback(() => {
    console.log('advanceChallenge: entering');
    const nextIndex = currentChallengeIndexRef.current + 1;

    if (nextIndex < challengesRef.current.length) {
      currentChallengeIndexRef.current = nextIndex;
      const nextChallenge = challengesRef.current[nextIndex];
      setChallengeDisplay(nextChallenge);
      setChallengeIndex(nextIndex + 1);
      setStatus(
        `Passed! Now: ${nextChallenge === 'TURN_LEFT' ? 'Turn LEFT' : 'Turn RIGHT'}`,
      );
      isProcessingRef.current = false;
      stableFrameCountRef.current = 0;
      yawHistoryRef.current = [];
      
      gracePeriodRef.current = true;
      setTimeout(() => {
        gracePeriodRef.current = false;
      }, 500);
      
      startChallengeTimeout();
      console.log('advanceChallenge: next challenge started', nextChallenge);
    } else {
      console.log('advanceChallenge: all challenges done, starting matching');
      isProcessingRef.current = false;
      performFaceMatching();
    }
  }, [performFaceMatching]);

  const startCameraAfterDelay = useCallback(async () => {
    setTimeout(async () => {
      if (!cameraStartedRef.current) {
        try {
          await FaceAuthModule.startLivenessCamera();
          cameraStartedRef.current = true;
        } catch (e: any) {
          console.error('AuthScreen: Failed to start camera:', e.message);
        }
      }
    }, 1500);
  }, []);

  const startAuth = useCallback(async () => {
    try {
      const permitted = await requestCameraPermission();
      if (!permitted) {
        setHasPermission(false);
        return;
      }
      setHasPermission(true);

      await startAuthentication();

      const sequence =
        Math.random() > 0.5
          ? ['TURN_LEFT', 'TURN_RIGHT']
          : ['TURN_RIGHT', 'TURN_LEFT'];

      challengesRef.current = sequence;
      currentChallengeIndexRef.current = 0;
      stableFrameCountRef.current = 0;
      gracePeriodRef.current = false;
      yawHistoryRef.current = [];

      const firstChallenge = sequence[0];
      setChallengeDisplay(firstChallenge);
      setChallengeIndex(1);
      setIsAuthenticating(true);
      setStatus(
        `Step 1: ${firstChallenge === 'TURN_LEFT' ? 'Turn LEFT' : 'Turn RIGHT'}`,
      );
      startChallengeTimeout();

      startCameraAfterDelay();

      livenessSub.current = livenessEmitter.addListener(
        'onLivenessFrame',
        (data: any) => {
          setLiveMetrics(data);

          if (data.status !== 'landmarks_detected') {
            stableFrameCountRef.current = 0;
            return;
          }

          if (gracePeriodRef.current) {
            stableFrameCountRef.current = 0;
            return;
          }

          const faceScore = data.faceScore ?? 0;
          if (faceScore < 0.5) {
            stableFrameCountRef.current = 0;
            return;
          }

          if (isProcessingRef.current) return;

          const current = challengesRef.current[currentChallengeIndexRef.current];
          if (!current) return;

          if (data.yaw != null) {
            yawHistoryRef.current.push(data.yaw);
            if (yawHistoryRef.current.length > YAW_SMOOTHING_WINDOW) {
              yawHistoryRef.current.shift();
            }
          }
          const avgYaw =
            yawHistoryRef.current.length > 0
              ? yawHistoryRef.current.reduce((a, b) => a + b, 0) /
                yawHistoryRef.current.length
              : 0;

          let triggered = false;
          if (current === 'TURN_LEFT' && avgYaw > 0.03) {
            triggered = true;
          } else if (current === 'TURN_RIGHT' && avgYaw < -0.03) {
            triggered = true;
          }

          console.log(
            `Challenge: ${current}, Raw: ${data.yaw?.toFixed(3)}, Avg: ${avgYaw.toFixed(3)}, Stable: ${stableFrameCountRef.current}`,
          );

          if (triggered) {
            stableFrameCountRef.current += 1;
            if (stableFrameCountRef.current >= REQUIRED_STABLE_FRAMES) {
              console.log('listener: stable threshold reached, advancing');
              stableFrameCountRef.current = 0;
              yawHistoryRef.current = [];
              isProcessingRef.current = true;
              advanceChallenge();
            }
          } else {
            if (stableFrameCountRef.current > 0) {
              stableFrameCountRef.current -= 1;
            }
          }
        },
      );
    } catch (e: any) {
      Alert.alert('Error', e.message);
    }
  }, [advanceChallenge, startCameraAfterDelay]);

  const handleSync = async () => {
    setIsSyncing(true);
    try {
      const result = await syncToAWS();
      Alert.alert('Sync Complete', result);
      loadPendingCount();
    } catch (e: any) {
      Alert.alert('Sync Failed', e.message);
    } finally {
      setIsSyncing(false);
    }
  };

  const getChallengeLabel = (challenge: string) => {
    if (challenge === 'TURN_LEFT') return 'Turn Head LEFT';
    if (challenge === 'TURN_RIGHT') return 'Turn Head RIGHT';
    return challenge;
  };

  // Show permission request screen if no permission
  if (hasPermission === false) {
    return (
      <View style={styles.container}>
        <Text style={styles.title}>Camera Access Required</Text>
        <Text style={styles.subtitle}>
          This app needs camera access for face authentication.
        </Text>
        <TouchableOpacity style={styles.button} onPress={startAuth}>
          <Text style={styles.buttonText}>Grant Camera Permission</Text>
        </TouchableOpacity>
        <TouchableOpacity 
          style={[styles.button, styles.secondaryButton]} 
          onPress={() => Linking.openURL('app-settings:')}>
          <Text style={styles.buttonText}>Open Settings</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Authenticate</Text>

      <View style={styles.cameraContainer}>
        {Platform.OS === 'android' && CameraPreviewView ? <CameraPreviewView style={styles.cameraPreview} /> : <View style={[styles.cameraPreview, {backgroundColor: '#222', justifyContent: 'center', alignItems: 'center'}]}><Text style={{color: '#666', fontSize: 16}}>Camera Preview</Text><Text style={{color: '#444', fontSize: 12, marginTop: 8}}>(iOS Stub)</Text></View>}

        {isAuthenticating && (
          <View style={styles.overlay} pointerEvents="none">
            <View style={styles.faceOutline}>
              <View style={styles.faceOval} />
            </View>

            {challengeDisplay ? (
              <View style={styles.challengeBanner}>
                <Text style={styles.challengeStep}>
                  Step {challengeIndex} of {challengesRef.current.length}
                </Text>
                <Text style={styles.challengeText}>
                  {getChallengeLabel(challengeDisplay)}
                </Text>
              </View>
            ) : null}

            {liveMetrics ? (
              <View style={styles.metricsPanel}>
                <Text style={styles.metricText}>
                  Raw: {liveMetrics.yaw?.toFixed(3) ?? '--'}
                </Text>
                <Text style={styles.metricText}>
                  Avg: {yawHistoryRef.current.length > 0
                    ? (yawHistoryRef.current.reduce((a, b) => a + b, 0) / yawHistoryRef.current.length).toFixed(3)
                    : '--'}
                </Text>
                <Text style={styles.metricText}>
                  Face: {liveMetrics.faceScore ? (liveMetrics.faceScore * 100).toFixed(0) + '%' : '--'}
                </Text>
                <Text style={styles.metricText}>
                  Stable: {stableFrameCountRef.current}/{REQUIRED_STABLE_FRAMES}
                </Text>
              </View>
            ) : null}

            {status ? (
              <View style={styles.statusBanner}>
                <Text style={styles.statusText}>{status}</Text>
              </View>
            ) : null}
          </View>
        )}
      </View>

      {!isAuthenticating ? (
        <View>
          <TouchableOpacity style={styles.button} onPress={startAuth}>
            <Text style={styles.buttonText}>Start Authentication</Text>
          </TouchableOpacity>
          
          <TouchableOpacity 
            style={[styles.button, styles.syncButton]} 
            onPress={handleSync}
            disabled={isSyncing}>
            <Text style={styles.buttonText}>
              {isSyncing ? 'Syncing...' : `Sync to AWS (${pendingSync} pending)`}
            </Text>
          </TouchableOpacity>
        </View>
      ) : (
        <View style={styles.row}>
          <ActivityIndicator color="#007AFF" style={{marginRight: 12}} />
          <TouchableOpacity
            style={[styles.button, styles.cancelButton]}
            onPress={cleanup}>
            <Text style={styles.buttonText}>Cancel</Text>
          </TouchableOpacity>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#f5f5f5', padding: 20},
  title: {fontSize: 24, fontWeight: 'bold', marginBottom: 20, textAlign: 'center', color: '#111'},
  subtitle: {fontSize: 16, color: '#666', textAlign: 'center', marginBottom: 30, paddingHorizontal: 20},
  cameraContainer: {
    width: '100%',
    height: 400,
    backgroundColor: '#000',
    marginBottom: 20,
    position: 'relative',
    borderRadius: 16,
    overflow: 'hidden',
  },
  cameraPreview: {width: '100%', height: '100%'},
  overlay: {
    ...StyleSheet.absoluteFillObject,
    justifyContent: 'center',
    alignItems: 'center',
  },
  faceOutline: {
    width: 200,
    height: 260,
    borderRadius: 100,
    borderWidth: 3,
    borderColor: 'rgba(255,255,255,0.6)',
    borderStyle: 'dashed',
    justifyContent: 'center',
    alignItems: 'center',
  },
  faceOval: {
    width: 180,
    height: 240,
    borderRadius: 90,
    borderWidth: 2,
    borderColor: 'rgba(0,255,0,0.4)',
  },
  challengeBanner: {
    position: 'absolute',
    top: 40,
    backgroundColor: 'rgba(0,0,0,0.75)',
    paddingHorizontal: 24,
    paddingVertical: 12,
    borderRadius: 24,
    alignItems: 'center',
  },
  challengeStep: {
    color: '#aaa',
    fontSize: 12,
    textTransform: 'uppercase',
    letterSpacing: 1,
    marginBottom: 4,
  },
  challengeText: {color: '#fff', fontSize: 22, fontWeight: 'bold'},
  metricsPanel: {
    position: 'absolute',
    top: 110,
    right: 10,
    backgroundColor: 'rgba(0,0,0,0.6)',
    padding: 10,
    borderRadius: 8,
  },
  metricText: {color: '#0f0', fontSize: 12, fontFamily: 'monospace'},
  statusBanner: {
    position: 'absolute',
    bottom: 80,
    backgroundColor: 'rgba(0,0,0,0.6)',
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 12,
  },
  statusText: {color: '#fff', fontSize: 14},
  button: {
    backgroundColor: '#007AFF',
    padding: 16,
    borderRadius: 12,
    alignItems: 'center',
    marginBottom: 12,
  },
  secondaryButton: {backgroundColor: '#5856D6'},
  syncButton: {backgroundColor: '#34C759'},
  cancelButton: {backgroundColor: '#FF3B30'},
  buttonText: {color: '#fff', fontSize: 18, fontWeight: '600'},
  row: {flexDirection: 'row', alignItems: 'center', justifyContent: 'center'},
});