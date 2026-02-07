package com.sendspindroid.ui.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.sendspindroid.SyncOffsetPreference
import com.sendspindroid.UserSettings
import com.sendspindroid.debug.DebugLogger
import com.sendspindroid.network.TransportType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel for Settings screen.
 * Manages preference state and broadcasts changes.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val SYNC_OFFSET_STEP_MS = 10
        private const val SYNC_OFFSET_MIN_MS = -5000
        private const val SYNC_OFFSET_MAX_MS = 5000
        private const val DEBUG_STATS_UPDATE_INTERVAL_MS = 2000L

        // Broadcast actions
        const val ACTION_DEBUG_LOGGING_CHANGED = "com.sendspindroid.ACTION_DEBUG_LOGGING_CHANGED"
        const val EXTRA_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"
        const val ACTION_HIGH_POWER_MODE_CHANGED = "com.sendspindroid.ACTION_HIGH_POWER_MODE_CHANGED"
        const val EXTRA_HIGH_POWER_MODE_ENABLED = "high_power_mode_enabled"
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)

    // Player settings
    private val _playerName = MutableStateFlow(UserSettings.getPlayerName())
    val playerName: StateFlow<String> = _playerName.asStateFlow()

    // Display settings
    private val _fullscreenMode = MutableStateFlow(UserSettings.fullScreenMode)
    val fullscreenMode: StateFlow<Boolean> = _fullscreenMode.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(UserSettings.keepScreenOn)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _miniPlayerPosition = MutableStateFlow(UserSettings.miniPlayerPosition)
    val miniPlayerPosition: StateFlow<UserSettings.MiniPlayerPosition> = _miniPlayerPosition.asStateFlow()

    // Audio settings
    private val _syncOffset = MutableStateFlow(UserSettings.getSyncOffsetMs())
    val syncOffset: StateFlow<Int> = _syncOffset.asStateFlow()

    private val _preferredCodec = MutableStateFlow(UserSettings.getPreferredCodec())
    val preferredCodec: StateFlow<String> = _preferredCodec.asStateFlow()

    // Network-specific codec settings
    private val _wifiCodec = MutableStateFlow(UserSettings.getCodecForNetwork(TransportType.WIFI))
    val wifiCodec: StateFlow<String> = _wifiCodec.asStateFlow()

    private val _cellularCodec = MutableStateFlow(UserSettings.getCodecForNetwork(TransportType.CELLULAR))
    val cellularCodec: StateFlow<String> = _cellularCodec.asStateFlow()

    // Performance settings
    private val _lowMemoryMode = MutableStateFlow(UserSettings.lowMemoryMode)
    val lowMemoryMode: StateFlow<Boolean> = _lowMemoryMode.asStateFlow()

    private val _highPowerMode = MutableStateFlow(UserSettings.highPowerMode)
    val highPowerMode: StateFlow<Boolean> = _highPowerMode.asStateFlow()

    private val _batteryOptExempt = MutableStateFlow(isBatteryOptimizationExempt())
    val batteryOptExempt: StateFlow<Boolean> = _batteryOptExempt.asStateFlow()

    // Debug settings
    private val _debugLogging = MutableStateFlow(DebugLogger.isEnabled)
    val debugLogging: StateFlow<Boolean> = _debugLogging.asStateFlow()

    private val _debugSampleCount = MutableStateFlow(DebugLogger.getSampleCount())
    val debugSampleCount: StateFlow<Int> = _debugSampleCount.asStateFlow()

    // App version
    private val _appVersion = MutableStateFlow(getAppVersion())
    val appVersion: StateFlow<String> = _appVersion.asStateFlow()

    init {
        // Start periodic debug stats updates
        startDebugStatsUpdates()
    }

    private fun startDebugStatsUpdates() {
        viewModelScope.launch {
            while (isActive) {
                _debugSampleCount.value = DebugLogger.getSampleCount()
                delay(DEBUG_STATS_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = getApplication<Application>().packageManager
                .getPackageInfo(getApplication<Application>().packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get version info", e)
            "Unknown"
        }
    }

    // Player settings
    fun setPlayerName(name: String) {
        UserSettings.setPlayerName(name)
        _playerName.value = UserSettings.getPlayerName()
    }

    // Display settings
    fun setFullscreenMode(enabled: Boolean) {
        prefs.edit().putBoolean(UserSettings.KEY_FULL_SCREEN_MODE, enabled).apply()
        _fullscreenMode.value = enabled
    }

    fun setKeepScreenOn(enabled: Boolean) {
        prefs.edit().putBoolean(UserSettings.KEY_KEEP_SCREEN_ON, enabled).apply()
        _keepScreenOn.value = enabled
    }

    fun setMiniPlayerPosition(position: UserSettings.MiniPlayerPosition) {
        UserSettings.setMiniPlayerPosition(position)
        _miniPlayerPosition.value = position
    }

    // Audio settings
    fun setSyncOffset(offset: Int) {
        val clamped = offset.coerceIn(SYNC_OFFSET_MIN_MS, SYNC_OFFSET_MAX_MS)
        UserSettings.setSyncOffsetMs(clamped)
        _syncOffset.value = clamped

        // Broadcast to PlaybackService
        val intent = Intent(SyncOffsetPreference.ACTION_SYNC_OFFSET_CHANGED).apply {
            putExtra(SyncOffsetPreference.EXTRA_OFFSET_MS, clamped)
        }
        LocalBroadcastManager.getInstance(getApplication()).sendBroadcast(intent)
    }

    fun adjustSyncOffset(delta: Int) {
        val newValue = (_syncOffset.value + delta).coerceIn(SYNC_OFFSET_MIN_MS, SYNC_OFFSET_MAX_MS)
        setSyncOffset(newValue)
    }

    fun increaseSyncOffset() = adjustSyncOffset(SYNC_OFFSET_STEP_MS)
    fun decreaseSyncOffset() = adjustSyncOffset(-SYNC_OFFSET_STEP_MS)

    fun setPreferredCodec(codec: String) {
        UserSettings.setPreferredCodec(codec)
        _preferredCodec.value = codec
    }

    fun setWifiCodec(codec: String) {
        UserSettings.setCodecForNetwork(TransportType.WIFI, codec)
        _wifiCodec.value = codec
    }

    fun setCellularCodec(codec: String) {
        UserSettings.setCodecForNetwork(TransportType.CELLULAR, codec)
        _cellularCodec.value = codec
    }

    // Performance settings
    /**
     * Sets low memory mode.
     * @return true if app restart is required
     */
    fun setLowMemoryMode(enabled: Boolean): Boolean {
        prefs.edit().putBoolean(UserSettings.KEY_LOW_MEMORY_MODE, enabled).apply()
        _lowMemoryMode.value = enabled
        return true // Always requires restart
    }

    fun setHighPowerMode(enabled: Boolean) {
        prefs.edit().putBoolean(UserSettings.KEY_HIGH_POWER_MODE, enabled).apply()
        _highPowerMode.value = enabled

        // Broadcast to PlaybackService
        val intent = Intent(ACTION_HIGH_POWER_MODE_CHANGED).apply {
            putExtra(EXTRA_HIGH_POWER_MODE_ENABLED, enabled)
        }
        LocalBroadcastManager.getInstance(getApplication()).sendBroadcast(intent)
    }

    /**
     * Check if the app is exempt from battery optimization (Doze mode).
     */
    fun isBatteryOptimizationExempt(): Boolean {
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
    }

    /**
     * Refresh the battery optimization exempt status (call after returning from system settings).
     */
    fun refreshBatteryOptExempt() {
        _batteryOptExempt.value = isBatteryOptimizationExempt()
    }

    // Debug settings
    fun setDebugLogging(enabled: Boolean) {
        DebugLogger.isEnabled = enabled
        // Save to preferences
        prefs.edit().putBoolean("debug_logging_enabled", enabled).apply()
        _debugLogging.value = enabled

        if (!enabled) {
            DebugLogger.clear()
            _debugSampleCount.value = 0
        }

        // Broadcast to PlaybackService
        val intent = Intent(ACTION_DEBUG_LOGGING_CHANGED).apply {
            putExtra(EXTRA_DEBUG_LOGGING_ENABLED, enabled)
        }
        LocalBroadcastManager.getInstance(getApplication()).sendBroadcast(intent)
    }
}
