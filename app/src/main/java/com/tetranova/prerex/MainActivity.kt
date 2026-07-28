package com.tetranova.prerex

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: DetectorViewModelV2

    // Nota: la richiesta/attesa del permesso USB è gestita interamente da
    // UsbCdcManager (che registra il proprio receiver per "USB_PERMISSION" e
    // richiede il permesso da connect() se manca). Prima questo receiver
    // duplicava sia la richiesta di permesso sia l'ascolto della concessione,
    // facendo reagire due percorsi indipendenti allo stesso singolo evento.
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                handleUsbIntentInternal(intent)
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return DetectorViewModelV2(application) as T
                }
            }
        )[DetectorViewModelV2::class.java]

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
            }
            else -> {
                registerReceiver(usbReceiver, filter)
            }
        }

        intent?.let { handleUsbIntentInternal(it) }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainContent(viewModel)
            }
        }
    }

    private fun handleUsbIntentInternal(intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                // Il controllo/richiesta del permesso è delegato a UsbCdcManager.connect(),
                // chiamato indirettamente da onUsbDeviceAttached() -> connectToUSB().
                device?.let { viewModel.onUsbDeviceAttached(it) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: IllegalArgumentException) { }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(vm: DetectorViewModelV2) {
    val connectionStatus by vm.connectionStatus.collectAsState()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()

    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "Menu",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Main") },
                    selected = navController.currentDestination?.route == "main",
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate("main") {
                            popUpTo("main") { inclusive = true }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Console") },
                    selected = navController.currentDestination?.route == "console",
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate("console")
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Comandi") },
                    selected = navController.currentDestination?.route == "commands",
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate("commands")
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("📊 Dati grezzi ESP32") },
                    selected = navController.currentDestination?.route == "rawdata",
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate("rawdata")
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("🔧 Calibrazione") },
                    selected = navController.currentDestination?.route == "calibration",
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate("calibration")
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    ) {
        Scaffold { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        when (connectionStatus) {
                            is ConnectionStatus.Connected -> MainScanDisplayV2(
                                vm = vm,
                                onNavigateToCalibration = { navController.navigate("calibration") }
                            )
                            is ConnectionStatus.Connecting -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator()
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text("Connessione USB in corso...")
                                    }
                                }
                            }
                            else -> AutomaticConnectionScreen(vm)
                        }
                    }
                    composable("console") {
                        ConsoleScreen(vm)
                    }
                    composable("commands") {
                        CommandsScreen(vm = vm)
                    }
                    composable("rawdata") {
                        RawDataScreen(vm)
                    }
                    composable("calibration") {
                        CalibrationScreen(
                            vm = vm,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AutomaticConnectionScreen(vm: DetectorViewModelV2) {
    val connectionStatus by vm.connectionStatus.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text("Connessione a Detector_ESP32 via USB...")
        Spacer(modifier = Modifier.height(8.dp))

        if (connectionStatus is ConnectionStatus.Error) {
            Text(
                text = "Errore: ${(connectionStatus as ConnectionStatus.Error).message}",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}