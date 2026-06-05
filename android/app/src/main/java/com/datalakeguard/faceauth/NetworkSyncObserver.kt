package com.datalakeguard.faceauth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.*

class NetworkSyncObserver(
    private val context: Context,
    private val syncManager: SyncManager
) {
    private val TAG = "NetworkSyncObserver"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectivityManager: ConnectivityManager? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network restored - triggering auto-sync")
            scope.launch {
                try {
                    val result = syncManager.syncPending()
                    Log.i(TAG, "Auto-sync: " + result.uploaded + " uploaded, " + result.remaining + " remaining")
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-sync failed: " + e.message)
                }
            }
        }
    }

    fun start() {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(request, networkCallback)
        Log.i(TAG, "Network observer started")
    }

    fun stop() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Unregister failed: " + e.message)
        }
        scope.cancel()
    }
}
