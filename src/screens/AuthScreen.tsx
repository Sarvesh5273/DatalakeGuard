import React, {useState, useRef, useCallback, useEffect} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Alert,
  ActivityIndicator,
  NativeEventEmitter,
  NativeModules,
} from 'react-native';
import {Camera, useCameraDevice} from 'react-native-vision-camera';
import {
  startAuthentication,
  processAuthFrame,
} from '../modules/faceAuth';

const {FaceAuthModule} = NativeModules;
const livenessEmitter = new NativeEventEmitter(FaceAuthModule);

export default function AuthScreen() {
  const [isAuthenticating, setIsAuthenticating] = useState(false);
  const [challenge, setChallenge] = useState<string>('');
  const [status, setStatus] = useState('');
  const [permission, setPermission] = useState(false);
  const [capturedFrames, setCapturedFrames] = useState(0);
  const [showOutline, setShowOutline] = useState(false);
  const [liveMetrics, setLiveMetrics] = useState<any>(null);
  
  const camera = useRef<Camera>(null);
  const device = useCameraDevice('front');
  const livenessSub = useRef<any>(null);
  
  const requestPermission = useCallback(async () => {
    const cameraPermission = await Camera.requestCameraPermission();
    setPermission(cameraPermission === 'granted');
  }, []);
  
  const startAuth = useCallback(async () => {
    try {
      const result = await startAuthentication();
      setIsAuthenticating(true);
      setCapturedFrames(0);
      setChallenge(result.challenge1);
      setStatus(`Challenge 1: ${result.challenge1}`);
      setShowOutline(true);
      
      // Start CameraX liveness camera
      await FaceAuthModule.startLivenessCamera();
      
      // Listen to live frame data
      livenessSub.current = livenessEmitter.addListener('onLivenessFrame', (data) => {
        setLiveMetrics(data);
        
        if (data.status === 'landmarks_detected') {
          // Check for challenge completion based on metrics
          if (data.blinkDetected && challenge === 'BLINK') {
            handleChallengePassed('blink');
          }
          // Add smile/turn detection similarly
        }
      });
      
    } catch (error: any) {
      Alert.alert('Error', error.message || 'Failed to start authentication');
    }
  }, [challenge]);
  
  const handleChallengePassed = useCallback((challengeType: string) => {
    setCapturedFrames(prev => prev + 1);
    setStatus(`${challengeType} detected! Processing...`);
    
    // Capture final frame for matching
    captureForMatching();
  }, []);
  
  const captureForMatching = useCallback(async () => {
    if (!camera.current) return;
    
    try {
      const photo = await camera.current.takePhoto({
        qualityPrioritization: 'speed',
        flash: 'off',
      });
      
      const result = await processAuthFrame(photo.path);
      
      if (result.matched) {
        cleanup();
        Alert.alert('Success', `Welcome ${result.workerId}!`);
      } else if (result.status === 'challenge_passed') {
        setChallenge(result.currentChallenge || '');
        setStatus(`Next: ${result.currentChallenge}`);
      }
    } catch (error: any) {
      Alert.alert('Error', error.message);
    }
  }, []);
  
  const cleanup = useCallback(() => {
    livenessSub.current?.remove();
    FaceAuthModule.stopLivenessCamera();
    setIsAuthenticating(false);
    setShowOutline(false);
    setLiveMetrics(null);
  }, []);
  
  useEffect(() => {
    return () => cleanup();
  }, [cleanup]);
  
  if (!permission) {
    return (
      <View style={styles.container}>
        <Text style={styles.title}>Camera Permission Required</Text>
        <TouchableOpacity style={styles.button} onPress={requestPermission}>
          <Text style={styles.buttonText}>Grant Camera Permission</Text>
        </TouchableOpacity>
      </View>
    );
  }
  
  if (!device) {
    return (
      <View style={styles.container}>
        <Text style={styles.title}>No Camera Found</Text>
      </View>
    );
  }
  
  return (
    <View style={styles.container}>
      <Text style={styles.title}>Authenticate</Text>
      
      <View style={styles.cameraContainer}>
        <Camera
          ref={camera}
          style={styles.camera}
          device={device}
          isActive={true}
          photo={true}
        />
        
        {showOutline && (
          <View style={styles.overlay} pointerEvents="none">
            <View style={styles.faceOutline}>
              <View style={styles.faceOval} />
            </View>
            
            {challenge && (
              <View style={styles.challengeBanner}>
                <Text style={styles.challengeText}>{challenge}</Text>
              </View>
            )}
            
            {liveMetrics && (
              <View style={styles.metricsPanel}>
                <Text style={styles.metricText}>
                  EAR: {liveMetrics.ear?.toFixed(3) || '--'}
                </Text>
                <Text style={styles.metricText}>
                  MAR: {liveMetrics.mar?.toFixed(3) || '--'}
                </Text>
                <Text style={styles.metricText}>
                  Yaw: {liveMetrics.yaw?.toFixed(3) || '--'}
                </Text>
                {liveMetrics.blinkDetected && (
                  <Text style={styles.blinkText}>BLINK!</Text>
                )}
              </View>
            )}
            
            {status && (
              <View style={styles.statusBanner}>
                <Text style={styles.statusText}>{status}</Text>
              </View>
            )}
          </View>
        )}
      </View>
      
      {!isAuthenticating ? (
        <TouchableOpacity style={styles.button} onPress={startAuth}>
          <Text style={styles.buttonText}>Start Authentication</Text>
        </TouchableOpacity>
      ) : (
        <TouchableOpacity 
          style={[styles.button, styles.cancelButton]} 
          onPress={cleanup}
        >
          <Text style={styles.buttonText}>Cancel</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
    padding: 20,
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    marginBottom: 20,
    textAlign: 'center',
  },
  cameraContainer: {
    width: '100%',
    height: 400,
    borderRadius: 16,
    overflow: 'hidden',
    backgroundColor: '#000',
    marginBottom: 20,
    position: 'relative',
  },
  camera: {
    width: '100%',
    height: '100%',
  },
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
    backgroundColor: 'rgba(0,0,0,0.7)',
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 20,
  },
  challengeText: {
    color: '#fff',
    fontSize: 20,
    fontWeight: 'bold',
  },
  metricsPanel: {
    position: 'absolute',
    top: 100,
    right: 10,
    backgroundColor: 'rgba(0,0,0,0.6)',
    padding: 10,
    borderRadius: 8,
  },
  metricText: {
    color: '#0f0',
    fontSize: 12,
    fontFamily: 'monospace',
  },
  blinkText: {
    color: '#ff0',
    fontSize: 16,
    fontWeight: 'bold',
  },
  statusBanner: {
    position: 'absolute',
    bottom: 80,
    backgroundColor: 'rgba(0,0,0,0.6)',
    paddingHorizontal: 15,
    paddingVertical: 8,
    borderRadius: 12,
  },
  statusText: {
    color: '#fff',
    fontSize: 14,
  },
  button: {
    backgroundColor: '#007AFF',
    padding: 16,
    borderRadius: 12,
    alignItems: 'center',
  },
  cancelButton: {
    backgroundColor: '#FF3B30',
  },
  buttonText: {
    color: '#fff',
    fontSize: 18,
    fontWeight: '600',
  },
});