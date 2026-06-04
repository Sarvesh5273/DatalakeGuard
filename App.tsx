import React, {useState} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  SafeAreaView,
} from 'react-native';
import EnrollScreen from './src/screens/EnrollScreen';
import AuthScreen from './src/screens/AuthScreen';
import AdminScreen from './src/screens/AdminScreen';

type Screen = 'home' | 'enroll' | 'auth' | 'admin';

export default function App() {
  const [currentScreen, setCurrentScreen] = useState<Screen>('home');
  
  const renderScreen = () => {
    switch (currentScreen) {
      case 'enroll':
        return <EnrollScreen />;
      case 'auth':
        return <AuthScreen />;
      case 'admin':
        return <AdminScreen />;
      default:
        return (
          <View style={styles.homeContainer}>
            <Text style={styles.logo}>DatalakeGuard</Text>
            <Text style={styles.subtitle}>Offline Face Authentication</Text>
            
            <TouchableOpacity
              style={[styles.button, styles.enrollButton]}
              onPress={() => setCurrentScreen('enroll')}
            >
              <Text style={styles.buttonText}>Enroll Worker</Text>
            </TouchableOpacity>
            
            <TouchableOpacity
              style={[styles.button, styles.authButton]}
              onPress={() => setCurrentScreen('auth')}
            >
              <Text style={styles.buttonText}>Authenticate</Text>
            </TouchableOpacity>
            
            <TouchableOpacity
              style={[styles.button, styles.adminButton]}
              onPress={() => setCurrentScreen('admin')}
            >
              <Text style={styles.buttonText}>Admin Dashboard</Text>
            </TouchableOpacity>
          </View>
        );
    }
  };
  
  return (
    <SafeAreaView style={styles.container}>
      {currentScreen !== 'home' && (
        <TouchableOpacity
          style={styles.backButton}
          onPress={() => setCurrentScreen('home')}
        >
          <Text style={styles.backButtonText}>← Back</Text>
        </TouchableOpacity>
      )}
      {renderScreen()}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  homeContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  logo: {
    fontSize: 32,
    fontWeight: 'bold',
    color: '#007AFF',
    marginBottom: 10,
  },
  subtitle: {
    fontSize: 16,
    color: '#666',
    marginBottom: 40,
  },
  button: {
    width: '80%',
    padding: 18,
    borderRadius: 12,
    alignItems: 'center',
    marginBottom: 15,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 2},
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  enrollButton: {
    backgroundColor: '#34C759',
  },
  authButton: {
    backgroundColor: '#007AFF',
  },
  adminButton: {
    backgroundColor: '#5856D6',
  },
  buttonText: {
    color: '#fff',
    fontSize: 18,
    fontWeight: '600',
  },
  backButton: {
    padding: 15,
    backgroundColor: '#fff',
    borderBottomWidth: 1,
    borderBottomColor: '#eee',
  },
  backButtonText: {
    fontSize: 16,
    color: '#007AFF',
    fontWeight: '600',
  },
});