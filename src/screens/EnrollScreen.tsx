import React, {useState, useRef, useCallback, useEffect} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  TextInput,
  Alert,
  ActivityIndicator,
} from 'react-native';
import {Camera, useCameraDevice} from 'react-native-vision-camera';
import {startEnrollment, processEnrollmentFrame} from '../modules/faceAuth';

const ENROLL_STEPS = [
  {id: 0, label: 'Look Straight', desc: 'Center your face, neutral expression'},
  {id: 1, label: 'Turn Left', desc: 'Slowly turn your head to the LEFT'},
  {id: 2, label: 'Turn Right', desc: 'Slowly turn your head to the RIGHT'},
  {id: 3, label: 'Smile', desc: 'Give a natural smile'},
  {id: 4, label: 'Look Straight', desc: 'Final capture — center face again'},
];

export default function EnrollScreen() {
  const [workerId, setWorkerId] = useState('');
  const [isEnrolling, setIsEnrolling] = useState(false);
  const [frameCount, setFrameCount] = useState(0);
  const [status, setStatus] = useState('');
  const [permission, setPermission] = useState(false);
  const [isCapturing, setIsCapturing] = useState(false);

  // FIXED: was useRef<<Camera>(null) — extra < broke TS. Now correct:
  const camera = useRef<Camera>(null);
  const device = useCameraDevice('front');

  const requestPermission = useCallback(async () => {
    const cameraPermission = await Camera.requestCameraPermission();
    setPermission(cameraPermission === 'granted');
  }, []);

  useEffect(() => {
    requestPermission();
  }, [requestPermission]);

  const currentStep = ENROLL_STEPS[Math.min(frameCount, ENROLL_STEPS.length - 1)];

  const startEnroll = useCallback(async () => {
    if (!workerId.trim()) {
      Alert.alert('Error', 'Please enter a Worker ID');
      return;
    }

    try {
      const result = await startEnrollment(workerId.trim());
      setIsEnrolling(true);
      setFrameCount(0);
      setStatus(result.message || 'Enrollment started');
    } catch (error: any) {
      Alert.alert('Error', error.message || 'Failed to start enrollment');
    }
  }, [workerId]);

  const captureFrame = useCallback(async () => {
    if (!camera.current || !isEnrolling || isCapturing) return;

    setIsCapturing(true);
    try {
      const photo = await camera.current.takePhoto({
        qualityPrioritization: 'speed',
        flash: 'off',
      });

      const result = await processEnrollmentFrame(photo.path);

      if (result.status === 'capturing') {
        setFrameCount(result.framesCaptured || 0);
        setStatus(result.message || `Captured ${result.framesCaptured}/5 frames`);
      } else if (result.success) {
        setIsEnrolling(false);
        setFrameCount(0);
        setStatus('Enrollment complete!');
        Alert.alert('Success', `Worker ${result.workerId} enrolled successfully`);
        setWorkerId('');
      } else if (result.status === 'no_face') {
        setStatus('No face detected. Please center your face.');
      }
    } catch (error: any) {
      Alert.alert('Error', error.message || 'Failed to process frame');
    } finally {
      setIsCapturing(false);
    }
  }, [isEnrolling, isCapturing]);

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
      <Text style={styles.title}>Enroll Worker</Text>

      {!isEnrolling ? (
        <>
          <TextInput
            style={styles.input}
            placeholder="Enter Worker ID"
            value={workerId}
            onChangeText={setWorkerId}
            autoCapitalize="none"
          />
          <TouchableOpacity style={styles.button} onPress={startEnroll}>
            <Text style={styles.buttonText}>Start Enrollment</Text>
          </TouchableOpacity>
        </>
      ) : (
        <>
          {/* Progress bar */}
          <View style={styles.progressTrack}>
            <View style={[styles.progressFill, {width: `${(frameCount / 5) * 100}%`}]} />
          </View>
          <Text style={styles.progressText}>{frameCount} / 5 frames captured</Text>

          <View style={styles.cameraContainer}>
            <Camera
              ref={camera}
              style={styles.camera}
              device={device}
              isActive={true}
              photo={true}
            />

            {/* Guided pose instruction */}
            <View style={styles.guideOverlay}>
              <View style={styles.guideCard}>
                <Text style={styles.guideStep}>Step {currentStep.id + 1} of 5</Text>
                <Text style={styles.guideLabel}>{currentStep.label}</Text>
                <Text style={styles.guideDesc}>{currentStep.desc}</Text>
              </View>
            </View>

            {/* Face outline hint */}
            <View style={styles.faceOutline} pointerEvents="none">
              <View style={styles.faceOval} />
            </View>

            {/* Frame counter badge */}
            <View style={styles.overlay}>
              <Text style={styles.overlayText}>{frameCount}/5 frames</Text>
            </View>
          </View>

          <Text style={styles.status}>{status}</Text>

          <TouchableOpacity
            style={[styles.captureButton, isCapturing && styles.captureButtonDisabled]}
            onPress={captureFrame}
            disabled={isCapturing}>
            {isCapturing ? (
              <ActivityIndicator color="#fff" />
            ) : (
              <Text style={styles.captureButtonText}>Capture Frame</Text>
            )}
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.button, styles.cancelButton]}
            onPress={() => {
              setIsEnrolling(false);
              setFrameCount(0);
              setStatus('');
            }}>
            <Text style={styles.buttonText}>Cancel</Text>
          </TouchableOpacity>
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 20,
    backgroundColor: '#f5f5f5',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    marginBottom: 20,
    textAlign: 'center',
    color: '#333',
  },
  input: {
    borderWidth: 1,
    borderColor: '#ddd',
    borderRadius: 8,
    padding: 12,
    marginBottom: 20,
    fontSize: 16,
    backgroundColor: '#fff',
    color: '#333',
  },
  button: {
    backgroundColor: '#007AFF',
    padding: 15,
    borderRadius: 8,
    alignItems: 'center',
    marginBottom: 10,
  },
  buttonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  progressTrack: {
    height: 6,
    backgroundColor: '#ddd',
    borderRadius: 3,
    marginBottom: 6,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    backgroundColor: '#34C759',
    borderRadius: 3,
  },
  progressText: {
    color: '#666',
    textAlign: 'center',
    marginBottom: 12,
    fontSize: 13,
    fontWeight: '500',
  },
  cameraContainer: {
    width: '100%',
    height: 360,
    borderRadius: 12,
    overflow: 'hidden',
    marginBottom: 12,
    position: 'relative',
    backgroundColor: '#000',
  },
  camera: {
    width: '100%',
    height: '100%',
  },
  guideOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    justifyContent: 'flex-start',
    alignItems: 'center',
    paddingTop: 16,
  },
  guideCard: {
    backgroundColor: 'rgba(0,0,0,0.75)',
    paddingHorizontal: 20,
    paddingVertical: 12,
    borderRadius: 16,
    alignItems: 'center',
    minWidth: 220,
  },
  guideStep: {
    color: '#aaa',
    fontSize: 11,
    textTransform: 'uppercase',
    letterSpacing: 1,
    marginBottom: 4,
  },
  guideLabel: {
    color: '#fff',
    fontSize: 20,
    fontWeight: 'bold',
    marginBottom: 4,
  },
  guideDesc: {
    color: '#ccc',
    fontSize: 13,
    textAlign: 'center',
  },
  faceOutline: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    justifyContent: 'center',
    alignItems: 'center',
  },
  faceOval: {
    width: 180,
    height: 240,
    borderRadius: 90,
    borderWidth: 2,
    borderColor: 'rgba(255,255,255,0.3)',
  },
  overlay: {
    position: 'absolute',
    top: 10,
    left: 10,
    backgroundColor: 'rgba(0,0,0,0.7)',
    padding: 8,
    borderRadius: 4,
  },
  overlayText: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '600',
  },
  status: {
    fontSize: 16,
    textAlign: 'center',
    marginBottom: 12,
    color: '#666',
  },
  captureButton: {
    backgroundColor: '#34C759',
    padding: 15,
    borderRadius: 8,
    alignItems: 'center',
    marginBottom: 10,
  },
  captureButtonDisabled: {
    opacity: 0.6,
  },
  captureButtonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  cancelButton: {
    backgroundColor: '#FF3B30',
  },
});