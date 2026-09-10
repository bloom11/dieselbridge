// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.wear.tiles.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.aaustralian.dieselbridge.R
import org.aaustralian.dieselbridge.ble.BatteryReader
import org.aaustralian.dieselbridge.ble.BlePeripheralController
import org.aaustralian.dieselbridge.data.MusicStore
import org.aaustralian.dieselbridge.data.NotificationActions
import org.aaustralian.dieselbridge.data.NotificationStore
import org.aaustralian.dieselbridge.debug.DeveloperRemoteAccessPolicy
import org.aaustralian.dieselbridge.debug.DeveloperSensorProbeCommandModule
import org.aaustralian.dieselbridge.debug.DeveloperSensorScanCommandModule
import org.aaustralian.dieselbridge.debug.SensorProbeStore
import org.aaustralian.dieselbridge.debug.SensorScanRunner
import org.aaustralian.dieselbridge.debug.DeveloperRuntimeAccess
import org.aaustralian.dieselbridge.debug.SafePlatformTestRunner
import org.aaustralian.dieselbridge.debug.WatchDeveloperExportRegistry
import org.aaustralian.dieselbridge.integration.legacy.LegacyBatteryProvider
import org.aaustralian.dieselbridge.integration.legacy.LegacyVibrationProvider
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorInventory
import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorRouteProbe
import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorSampler
import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorManagerSource
import org.aaustralian.dieselbridge.platform.sensor.AndroidHealthServicesSource
import org.aaustralian.dieselbridge.platform.sensor.HealthServicesProvider
import org.aaustralian.dieselbridge.platform.provider.ProviderAvailability
import org.aaustralian.dieselbridge.platform.sensor.SensorManagerRouteCatalog
import org.aaustralian.dieselbridge.platform.sensor.SensorManagerProvider
import org.aaustralian.dieselbridge.sensor.SensorMatrixExperiment
import org.aaustralian.dieselbridge.tile.MusicTileService
import org.aaustralian.dieselbridge.tile.PixelBridgeTileService

/**
 * connectedDevice-typed foreground service that owns the BLE peripheral stack so the link survives
 * screen-off / backgrounding. Also listens for Bluetooth on/off so advertising (re)starts
 * automatically when the user enables Bluetooth. A foreground service alone does NOT beat Doze —
 * the app also needs a battery-optimization exemption (see docs/architecture.md).
 */
class DieselBridgeService : Service() {

    private var controller: BlePeripheralController? = null

    /*
     * Lifecycle scope for Diesel platform routes/modules.
     * This service owns and cancels it.
     */
    private val platformScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate,
        )

    /*
     * Dedicated execution scope for generic Diesel protocol commands.
     *
     * Commands are serialized by DieselProtocolExecutionLane. Using Default
     * keeps command work off both the GATT callback thread and the UI/main
     * dispatcher.
     */
    private val protocolScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default,
        )

    private val platform =
        DieselPlatform(
            scope = platformScope,
        )

    private val safePlatformTestRunner =
        SafePlatformTestRunner(
            capabilities = platform.capabilities,
            diagnostics = platform.diagnostics,
        )

    private val legacyBatteryProvider =
        LegacyBatteryProvider()

    /** Coarse-signal watcher that pokes the tile to redraw when connection/battery/latest-notif change. */
    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> controller?.onBluetoothStateOn()
                BluetoothAdapter.STATE_OFF -> controller?.onBluetoothStateOff()
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return

            legacyBatteryProvider.update(
                BatteryReader.read(intent),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground()
        val vibrationProvider =
            LegacyVibrationProvider(applicationContext)

        val developerRemoteAccessPolicy =
            DeveloperRemoteAccessPolicy(
                applicationContext,
            )

        /*
         * One canonical SensorManager source owns the ordered process-visible
         * snapshot, exact Android Sensor objects, passive inventory metadata
         * and concrete route identity.
         *
         * It still performs census only: no listener or trigger sensor is
         * registered here.
         */
        val sensorSource =
            AndroidSensorManagerSource(
                applicationContext,
            )

        val sensorInventory =
            AndroidSensorInventory(
                sensorSource,
            )

        /*
         * The route catalog is another passive view of the same source.
         * M4.2b1b can therefore resolve an exported route id back to the
         * exact Sensor using the identical route-assignment implementation.
         */
        val sensorRouteCatalog =
            SensorManagerRouteCatalog(
                sensorSource,
            )

        val sensorSampler = AndroidSensorSampler(applicationContext)
        val sensorManagerProvider = SensorManagerProvider(sensorSource, sensorSampler)
        val sensorProbeStore = SensorProbeStore()
        val sensorScanRunner = SensorScanRunner(
            probe = AndroidSensorRouteProbe(source = sensorSource, sampler = sensorSampler),
            routes = { sensorRouteCatalog.snapshot() },
            store = sensorProbeStore,
            scope = platformScope,
        )
        sensorManagerProvider.capabilities().forEach { capability ->
            platform.capabilities.register(
                capability = capability,
                provider = sensorManagerProvider,
                priority = 10,
            )
        }

        // Health Services is an optional higher-priority source. Register it as standby until
        // the watch reports support; the SensorManager provider remains the safe fallback.
        val healthServicesSource = AndroidHealthServicesSource(applicationContext)
        val healthServicesProvider = HealthServicesProvider(healthServicesSource)
        healthServicesProvider.capabilities().forEach { capability ->
            platform.capabilities.register(
                capability = capability,
                provider = healthServicesProvider,
                priority = 20,
                initialAvailability = ProviderAvailability.UNAVAILABLE,
            )
        }
        platformScope.launch {
            healthServicesProvider.capabilities().forEach { capability ->
                runCatching {
                    val logicalId = capability.capabilityId.value.removePrefix("sensor.")
                    val supported = healthServicesSource.hasRequiredPermission(logicalId) &&
                        healthServicesSource.supports(logicalId)
                    if (supported) {
                        platform.capabilities.setAvailability(
                            capability.id,
                            healthServicesProvider.providerId,
                            ProviderAvailability.AVAILABLE,
                        )
                    }
                }
            }
        }

        platform.capabilities.register(
            capability = vibrationProvider,
            provider = vibrationProvider,
            priority = LegacyVibrationProvider.PRIORITY,
        )

        platform.capabilities.register(
            capability = legacyBatteryProvider,
            provider = legacyBatteryProvider,
            priority = LegacyBatteryProvider.PRIORITY,
        )

        DeveloperRuntimeAccess.attachSensorMatrixExperiment(
            SensorMatrixExperiment(platform.capabilities),
        )

        DeveloperRuntimeAccess.attach(
            platform = platform,
            safePlatformTestRunner = safePlatformTestRunner,
            sensorInventory = sensorInventory,
            developerRemoteAccessPolicy = developerRemoteAccessPolicy,
        )

        val developerExportRegistry =
            WatchDeveloperExportRegistry.create(
                context = applicationContext,
                platform = platform,
                sensorRouteCatalog =
                    sensorRouteCatalog,
                safeTestRunner = safePlatformTestRunner,
                sensorProbeStore = sensorProbeStore,
            )

        val bleController =
            BlePeripheralController(
                context = applicationContext,
                protocolScope = protocolScope,
                capabilities = platform.capabilities,
                batterySnapshot = {
                    platform.battery.current()
                },
                sensorRouteCatalog =
                    sensorRouteCatalog,
                developerRemoteAccessAuthorization =
                    developerRemoteAccessPolicy,
                developerExportRegistry =
                    developerExportRegistry,
                additionalCommandModules = listOf(
                    DeveloperSensorProbeCommandModule(
                        authorization = developerRemoteAccessPolicy,
                        probe = AndroidSensorRouteProbe(
                            source = sensorSource,
                            sampler = sensorSampler,
                        ),
                    ),
                    DeveloperSensorScanCommandModule(
                        authorization = developerRemoteAccessPolicy,
                        runner = sensorScanRunner,
                        store = sensorProbeStore,
                    ),
                ),
            )

        controller = bleController
        DeveloperRuntimeAccess.attachCommandDispatcher(bleController::dispatchFromDeveloperUi)

        /*
         * Reactive path for ordinary battery changes.
         * BLE subscription/reconnect uses batterySnapshot instead.
         */
        platformScope.launch {
            platform.battery.state.collect { state ->
                bleController.onBatteryStateChanged(state)
            }
        }

        ContextCompat.registerReceiver(
            this,
            bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        /*
         * ACTION_BATTERY_CHANGED is sticky. Bootstrap the provider from the
         * returned snapshot while also registering for subsequent changes.
         */
        val stickyBattery =
            ContextCompat.registerReceiver(
                this,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

        stickyBattery?.let {
            legacyBatteryProvider.update(
                BatteryReader.read(it),
            )
        }

        bleController.start()
        // Route UI action taps (dismiss / reply / open) to the controller's BLE back-channel.
        NotificationActions.handler = { id, action, reply -> controller?.sendAction(id, action, reply) }
        // Route find-phone taps to the controller so the watch can buzz the phone over BLE.
        NotificationActions.findPhoneHandler = { active -> controller?.sendFindPhone(active) }
        // Route call action taps (accept / reject / ignore / end) over the BLE back-channel.
        NotificationActions.callHandler = { action -> controller?.sendCall(action) }
        // Route music transport taps (play / pause / next / …) over the BLE back-channel.
        NotificationActions.musicHandler = { cmd -> controller?.sendMusic(cmd) }
        // Debounce a coarse status signature and ask the tiles to redraw when it changes.
        tileScope.launch {
            // Refresh the tiles when their content changes: the notification digest (count + the
            // top few ids) for the status tile, and the now-playing track/state for the music tile.
            combine(NotificationStore.items, MusicStore.state) { items, np ->
                listOf(items.size, items.take(3).map { it.id }, np?.playing, np?.track)
            }
                .distinctUntilChanged()
                .drop(1)
                .debounce(750)
                .collect {
                    TileService.getUpdater(applicationContext).apply {
                        requestUpdate(PixelBridgeTileService::class.java)
                        requestUpdate(MusicTileService::class.java)
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        NotificationActions.handler = null
        NotificationActions.findPhoneHandler = null
        NotificationActions.callHandler = null
        NotificationActions.musicHandler = null
        tileScope.cancel()

        /*
         * Cancel queued/in-flight Diesel commands before detaching shared
         * runtime state. Submissions into the cancelled scope cannot execute,
         * and suspended handlers are cancelled without emitting a stale
         * FAILED response.
         */
        protocolScope.cancel()

        DeveloperRuntimeAccess.detach(platform)
        platformScope.cancel()
        runCatching { unregisterReceiver(bluetoothReceiver) }
        runCatching { unregisterReceiver(batteryReceiver) }
        controller?.stop()
        controller = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        // minSdk 30 => the typed startForeground and the connectedDevice type constant always exist.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    // Android 10+ (API 29+) requires the foreground service type parameter
    startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
} else {
    // Android 9 (API 28) on your Diesel watch uses the legacy fallback version
    startForeground(NOTIF_ID, notification)
}
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "dieselbridge_link"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, DieselBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DieselBridgeService::class.java))
        }
    }
}
