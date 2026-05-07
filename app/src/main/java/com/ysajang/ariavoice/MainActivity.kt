package com.ysajang.ariavoice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ysajang.ariavoice.data.ConversationEntry
import com.ysajang.ariavoice.data.PreferencesManager
import com.ysajang.ariavoice.service.AriaForegroundService
import com.ysajang.ariavoice.service.AriaState
import com.ysajang.ariavoice.ui.navigation.Screen
import com.ysajang.ariavoice.ui.screen.HistoryScreen
import com.ysajang.ariavoice.ui.screen.MainScreen
import com.ysajang.ariavoice.ui.screen.SettingsScreen
import com.ysajang.ariavoice.ui.theme.AriaVoiceTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri

class MainActivity : ComponentActivity() {

    private var ariaService: AriaForegroundService? = null
    private var serviceBound = false
    private val serviceBindState = mutableStateOf(false)

    // Default flows for when service is not bound
    private val defaultState = MutableStateFlow(AriaState.IDLE)
    private val defaultError = MutableStateFlow<String?>(null)
    private val defaultConfirmation = MutableStateFlow<ConversationEntry?>(null)
    private val defaultConversations = MutableStateFlow<List<ConversationEntry>>(emptyList())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AriaForegroundService.AriaBinder
            ariaService = binder.getService()
            serviceBound = true
            serviceBindState.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ariaService = null
            serviceBound = false
            serviceBindState.value = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startAriaService()
        }
    }

    private lateinit var prefsManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 배터리 최적화 해제 요청
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }

        prefsManager = PreferencesManager(this)

        setContent {
            AriaVoiceTheme {
                val navController = rememberNavController()
                val coroutineScope = rememberCoroutineScope()

                val isBound by serviceBindState
                val stateFlow = remember(isBound) { ariaService?.state ?: defaultState }
                val errorFlow = remember(isBound) { ariaService?.lastError ?: defaultError }
                val confirmFlow = remember(isBound) { ariaService?.pendingConfirmation ?: defaultConfirmation }
                val convFlow = remember(isBound) { ariaService?.conversationRepo?.conversations ?: defaultConversations }

                val state by stateFlow.collectAsState()
                val lastError by errorFlow.collectAsState()
                val pendingConfirmation by confirmFlow.collectAsState()
                val conversations by convFlow.collectAsState()

                val serverUrl by prefsManager.serverUrl.collectAsState(
                    initial = PreferencesManager.DEFAULT_SERVER_URL
                )
                val apiKey by prefsManager.apiKey.collectAsState(initial = "")
                val sensitivity by prefsManager.wakeWordSensitivity.collectAsState(
                    initial = PreferencesManager.DEFAULT_SENSITIVITY
                )
                val wakeWordModel by prefsManager.wakeWordModel.collectAsState(
                    initial = PreferencesManager.DEFAULT_WAKE_WORD_MODEL
                )

                var isServiceRunning by remember { mutableStateOf(false) }

                Scaffold(
                    bottomBar = {
                        val navBackStackEntry by navController.currentBackStackEntryAsState()
                        val currentRoute = navBackStackEntry?.destination?.route

                        NavigationBar {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Home, "홈") },
                                label = { Text("홈") },
                                selected = currentRoute == Screen.Main.route,
                                onClick = {
                                    navController.navigate(Screen.Main.route) {
                                        popUpTo(Screen.Main.route) { inclusive = true }
                                    }
                                }
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.History, "기록") },
                                label = { Text("기록") },
                                selected = currentRoute == Screen.History.route,
                                onClick = {
                                    navController.navigate(Screen.History.route) {
                                        popUpTo(Screen.Main.route)
                                    }
                                }
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Settings, "설정") },
                                label = { Text("설정") },
                                selected = currentRoute == Screen.Settings.route,
                                onClick = {
                                    navController.navigate(Screen.Settings.route) {
                                        popUpTo(Screen.Main.route)
                                    }
                                }
                            )
                        }
                    }
                ) { padding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Main.route,
                        modifier = Modifier.padding(padding)
                    ) {
                        composable(Screen.Main.route) {
                            MainScreen(
                                state = state,
                                isServiceRunning = isServiceRunning,
                                lastError = lastError,
                                pendingConfirmation = pendingConfirmation,
                                lastConversation = conversations.firstOrNull(),
                                onMicClick = {
                                    if (isBound) {
                                        ariaService?.manualTrigger()
                                    }
                                },
                                onToggleService = {
                                    if (isServiceRunning) {
                                        stopAriaService()
                                        isServiceRunning = false
                                    } else {
                                        requestPermissionsAndStart()
                                        isServiceRunning = true
                                    }
                                },
                                onConfirm = { confirmationId, confirmed ->
                                    ariaService?.confirmAction(confirmationId, confirmed)
                                }
                            )
                        }

                        composable(Screen.History.route) {
                            HistoryScreen(conversations = conversations)
                        }

                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                serverUrl = serverUrl,
                                apiKey = apiKey,
                                sensitivity = sensitivity,
                                wakeWordModel = wakeWordModel,
                                onServerUrlChange = { url ->
                                    coroutineScope.launch {
                                        prefsManager.setServerUrl(url)
                                    }
                                },
                                onApiKeyChange = { key ->
                                    coroutineScope.launch {
                                        prefsManager.setApiKey(key)
                                    }
                                },
                                onSensitivityChange = { sens ->
                                    coroutineScope.launch {
                                        prefsManager.setWakeWordSensitivity(sens)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startAriaService()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startAriaService() {
        val intent = Intent(this, AriaForegroundService::class.java).apply {
            action = AriaForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        bindService(
            Intent(this, AriaForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun stopAriaService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            serviceBindState.value = false
        }
        val intent = Intent(this, AriaForegroundService::class.java).apply {
            action = AriaForegroundService.ACTION_STOP
        }
        startService(intent)
        ariaService = null
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
