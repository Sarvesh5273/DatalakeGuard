import React, {useState, useCallback, useEffect} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  Alert,
  RefreshControl,
} from 'react-native';
import {
  syncPendingRecords,
  getPendingCount,
  getAuthLogs,
  clearAllData,
} from '../modules/faceAuth';
import NetInfo from '@react-native-community/netinfo';

export default function AdminScreen() {
  const [pendingCount, setPendingCount] = useState({pendingEmbeddings: 0, pendingLogs: 0});
  const [logs, setLogs] = useState<any[]>([]);
  const [isSyncing, setIsSyncing] = useState(false);
  const [isOnline, setIsOnline] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [lastSyncResult, setLastSyncResult] = useState<string>('');
  
  const loadData = useCallback(async () => {
    try {
      const count = await getPendingCount();
      setPendingCount(count);
      
      const authLogs = await getAuthLogs();
      setLogs(authLogs);
    } catch (error: any) {
      console.error('Load data error:', error.message);
    }
  }, []);
  
  useEffect(() => {
    loadData();
    
    const unsubscribe = NetInfo.addEventListener(state => {
      setIsOnline(state.isConnected || false);
    });
    
    return () => unsubscribe();
  }, [loadData]);
  
  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadData();
    setRefreshing(false);
  }, [loadData]);
  
  const handleSync = useCallback(async () => {
    if (!isOnline) {
      Alert.alert('Offline', 'No network connection. Sync will queue for later.');
      return;
    }
    
    setIsSyncing(true);
    try {
      const result = await syncPendingRecords();
      setLastSyncResult(`Uploaded: ${result.uploaded}, Failed: ${result.failed}, Remaining: ${result.remaining}`);
      Alert.alert('Sync Complete', result.message || 'Sync completed');
      await loadData();
    } catch (error: any) {
      Alert.alert('Sync Error', error.message || 'Sync failed');
    } finally {
      setIsSyncing(false);
    }
  }, [isOnline, loadData]);
  
  const handleClearData = useCallback(() => {
    Alert.alert(
      'Clear All Data',
      'This will delete all enrolled faces, auth logs, and sync queue. This cannot be undone.',
      [
        {text: 'Cancel', style: 'cancel'},
        {
          text: 'Clear',
          style: 'destructive',
          onPress: async () => {
            try {
              await clearAllData();
              await loadData();
              Alert.alert('Success', 'All data cleared');
            } catch (error: any) {
              Alert.alert('Error', error.message);
            }
          },
        },
      ]
    );
  }, [loadData]);
  
  return (
    <ScrollView
      style={styles.container}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
    >
      <Text style={styles.title}>Admin Dashboard</Text>
      
      <View style={[styles.card, isOnline ? styles.onlineCard : styles.offlineCard]}>
        <Text style={styles.cardTitle}>Network Status</Text>
        <Text style={[styles.statusText, isOnline ? styles.onlineText : styles.offlineText]}>
          {isOnline ? 'Online' : 'Offline'}
        </Text>
      </View>
      
      <View style={styles.card}>
        <Text style={styles.cardTitle}>Pending Sync</Text>
        <Text style={styles.infoText}>Embeddings: {pendingCount.pendingEmbeddings}</Text>
        <Text style={styles.infoText}>Auth Logs: {pendingCount.pendingLogs}</Text>
        <TouchableOpacity
          style={[styles.button, isSyncing && styles.disabledButton]}
          onPress={handleSync}
          disabled={isSyncing}
        >
          <Text style={styles.buttonText}>
            {isSyncing ? 'Syncing...' : 'Sync Now'}
          </Text>
        </TouchableOpacity>
        {lastSyncResult && (
          <Text style={styles.syncResult}>{lastSyncResult}</Text>
        )}
      </View>
      
      <View style={styles.card}>
        <Text style={styles.cardTitle}>Recent Auth Logs</Text>
        {logs.length === 0 ? (
          <Text style={styles.emptyText}>No auth logs yet</Text>
        ) : (
          logs.map((log, index) => (
            <View key={index} style={styles.logItem}>
              <Text style={styles.logWorker}>{log.workerId || 'Unknown'}</Text>
              <Text style={[styles.logResult, log.result === 'success' ? styles.success : styles.failure]}>
                {log.result.toUpperCase()}
              </Text>
              <Text style={styles.logDetail}>
                {new Date(log.timestamp).toLocaleString()}
              </Text>
              {log.livenessPassed && (
                <Text style={styles.logDetail}>Liveness: ✓</Text>
              )}
            </View>
          ))
        )}
      </View>
      
      <View style={[styles.card, styles.dangerCard]}>
        <Text style={[styles.cardTitle, styles.dangerTitle]}>Danger Zone</Text>
        <TouchableOpacity style={styles.dangerButton} onPress={handleClearData}>
          <Text style={styles.dangerButtonText}>Clear All Data</Text>
        </TouchableOpacity>
      </View>
    </ScrollView>
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
  card: {
    backgroundColor: '#fff',
    padding: 20,
    borderRadius: 12,
    marginBottom: 15,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 2},
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  onlineCard: {
    borderLeftWidth: 4,
    borderLeftColor: '#34C759',
  },
  offlineCard: {
    borderLeftWidth: 4,
    borderLeftColor: '#FF3B30',
  },
  cardTitle: {
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 10,
    color: '#333',
  },
  statusText: {
    fontSize: 16,
    fontWeight: '600',
  },
  onlineText: {
    color: '#34C759',
  },
  offlineText: {
    color: '#FF3B30',
  },
  infoText: {
    fontSize: 14,
    color: '#666',
    marginBottom: 5,
  },
  button: {
    backgroundColor: '#007AFF',
    padding: 12,
    borderRadius: 8,
    alignItems: 'center',
    marginTop: 10,
  },
  disabledButton: {
    opacity: 0.6,
  },
  buttonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  syncResult: {
    fontSize: 12,
    color: '#666',
    marginTop: 8,
    textAlign: 'center',
  },
  emptyText: {
    fontSize: 14,
    color: '#999',
    fontStyle: 'italic',
    textAlign: 'center',
  },
  logItem: {
    borderBottomWidth: 1,
    borderBottomColor: '#eee',
    paddingVertical: 10,
  },
  logWorker: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
  },
  logResult: {
    fontSize: 12,
    fontWeight: '600',
    marginTop: 2,
  },
  logDetail: {
    fontSize: 12,
    color: '#666',
    marginTop: 2,
  },
  success: {
    color: '#34C759',
  },
  failure: {
    color: '#FF3B30',
  },
  dangerCard: {
    borderColor: '#FF3B30',
    borderWidth: 1,
  },
  dangerTitle: {
    color: '#FF3B30',
  },
  dangerButton: {
    backgroundColor: '#FF3B30',
    padding: 12,
    borderRadius: 8,
    alignItems: 'center',
    marginTop: 10,
  },
  dangerButtonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
});