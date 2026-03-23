package org.freewheel.compose

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import org.freewheel.compose.di.AppModule
import org.freewheel.compose.di.WheelViewModelFactory
import org.freewheel.compose.service.WheelService
import org.freewheel.compose.navigation.AppNavigation
import org.freewheel.core.service.BluetoothAdapterState
import org.freewheel.ui.theme.AppTheme

class ComposeActivity : ComponentActivity() {

    private val viewModel: WheelViewModel by viewModels {
        WheelViewModelFactory(application)
    }
    private val bluetoothManager by lazy { AppModule.bluetoothManager }
    private var serviceBound = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    viewModel.setBluetoothAdapterState(BluetoothAdapterState.POWERED_ON)
                    if (!serviceBound) bindWheelService()
                }
                BluetoothAdapter.STATE_OFF -> {
                    viewModel.setBluetoothAdapterState(BluetoothAdapterState.POWERED_OFF)
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as WheelService.LocalBinder
            viewModel.attachService(binder.service, binder.service.connectionManager, binder.service.bleManager)
            viewModel.startupScan()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            viewModel.detachService()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bleGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
                permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else {
            true
        }

        if (bleGranted) {
            updateBluetoothState()
            bindWheelService()
        } else {
            viewModel.setBluetoothAdapterState(BluetoothAdapterState.UNAUTHORIZED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AppTheme {
                AppNavigation(viewModel = viewModel)
            }
        }

        registerReceiver(
            bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )

        requestBlePermissions()
    }

    override fun onResume() {
        super.onResume()
        updateBluetoothState()
        // If permissions were granted while in Settings and service isn't bound, try binding
        if (!serviceBound && hasBlePermissions() && bluetoothManager?.adapter?.isEnabled == true) {
            bindWheelService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothReceiver)
        serviceBound = false
        try {
            unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
            // Service not bound
        }
    }

    private fun requestBlePermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            updateBluetoothState()
            bindWheelService()
        }
    }

    private fun updateBluetoothState() {
        val adapter = bluetoothManager?.adapter
        val state = when {
            adapter == null -> BluetoothAdapterState.UNSUPPORTED
            !hasBlePermissions() -> BluetoothAdapterState.UNAUTHORIZED
            !adapter.isEnabled -> BluetoothAdapterState.POWERED_OFF
            else -> BluetoothAdapterState.POWERED_ON
        }
        viewModel.setBluetoothAdapterState(state)
    }

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun bindWheelService() {
        if (serviceBound) return
        if (bluetoothManager?.adapter?.isEnabled == true) {
            val intent = Intent(this, WheelService::class.java)
            ContextCompat.startForegroundService(this, intent)
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
            serviceBound = true
        }
    }
}
