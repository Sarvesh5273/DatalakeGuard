import React, {useState, useRef, useCallback} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  TextInput,
  Alert,
} from 'react-native';
import {Camera, useCameraDevice} from 'react-native-vision-camera';
import {startEnrollment, processEnrollmentFrame} from '../modules/faceAuth';

export default function EnrollScreen() {
  const [workerId, setWorkerId] = useState('');
  const [isEnrolling, setIsEnrolling] = useState(false);
  const [frameCount, setFrameCount] = useState(0);
  const [status, setStatus] = useState('');
  const [permission, setPermission] = useState(false);
  
  const camera = useRef<Camera>(null);
  const device = useCameraDevice('front');
  
  const requestPermission = useCallback(async () => {
    const cameraPermission = await Camera.requestCameraPermission();
    setPermission(cameraPermission === 'granted');
  }, []);
  
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
    if (!camera.current || !isEnrolling) return;
    
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
        setStatus('Enrollment complete!');
        Alert.alert('Success', `Worker ${result.workerId} enrolled successfully`);
        setWorkerId('');
        setFrameCount(0);
      } else if (result.status === 'no_face') {
        setStatus('No face detected. Please center your face.');
      }
    } catch (error: any) {
      Alert.alert('Error', error.message || 'Failed to process frame');
    }
  }, [isEnrolling]);
  
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
          <View style={styles.cameraContainer}>
            <Camera
              ref={camera}
              style={styles.camera}
              device={device}
              isActive={true}
              photo={true}
            />
            <View style={styles.overlay}>
              <Text style={styles.overlayText}>
                {frameCount}/5 frames captured
              </Text>
            </View>
          </View>
          
          <Text style={styles.status}>{status}</Text>
          
          <TouchableOpacity style={styles.captureButton} onPress={captureFrame}>
            <Text style={styles.captureButtonText}>Capture Frame</Text>
          </TouchableOpacity>
          
          <TouchableOpacity 
            style={[styles.button, styles.cancelButton]} 
            onPress={() => {
              setIsEnrolling(false);
              setFrameCount(0);
              setStatus('');
            }}
          >
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
  cameraContainer: {
    width: '100%',
    height: 300,
    borderRadius: 12,
    overflow: 'hidden',
    marginBottom: 20,
    position: 'relative',
  },
  camera: {
    width: '100%',
    height: '100%',
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
    marginBottom: 20,
    color: '#666',
  },
  captureButton: {
    backgroundColor: '#34C759',
    padding: 15,
    borderRadius: 8,
    alignItems: 'center',
    marginBottom: 10,
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