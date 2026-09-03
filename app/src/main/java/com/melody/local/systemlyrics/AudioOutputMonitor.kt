package com.melody.local.systemlyrics

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build
import android.os.Handler
import android.os.Looper

internal object AudioOutputRouteResolver {
    @SuppressLint("InlinedApi")
    fun resolve(deviceTypes: Collection<Int>): AudioOutputRoute {
        val types = deviceTypes.toSet()
        return when {
            types.any {
                it == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    it == AudioDeviceInfo.TYPE_BLE_BROADCAST ||
                    it == AudioDeviceInfo.TYPE_HEARING_AID
            } -> AudioOutputRoute.BLUETOOTH_LE

            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types ||
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types ->
                AudioOutputRoute.BLUETOOTH_CLASSIC

            AudioDeviceInfo.TYPE_WIRED_HEADSET in types ||
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES in types ||
                AudioDeviceInfo.TYPE_LINE_ANALOG in types ||
                AudioDeviceInfo.TYPE_LINE_DIGITAL in types ||
                AudioDeviceInfo.TYPE_AUX_LINE in types -> AudioOutputRoute.WIRED

            AudioDeviceInfo.TYPE_USB_HEADSET in types ||
                AudioDeviceInfo.TYPE_USB_DEVICE in types ||
                AudioDeviceInfo.TYPE_USB_ACCESSORY in types -> AudioOutputRoute.USB

            AudioDeviceInfo.TYPE_HDMI in types ||
                AudioDeviceInfo.TYPE_HDMI_ARC in types ||
                AudioDeviceInfo.TYPE_HDMI_EARC in types -> AudioOutputRoute.HDMI

            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in types ||
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE in types ||
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE in types -> AudioOutputRoute.SPEAKER

            else -> AudioOutputRoute.UNKNOWN
        }
    }

    /**
     * Legacy MediaRouter identifies whether the selected live-audio route is Bluetooth, TV or the
     * local speaker. Only consult the connected-device list for details the route does not expose;
     * in particular, a merely connected Bluetooth device must never outrank a selected speaker.
     */
    @SuppressLint("InlinedApi")
    fun resolveSelectedMediaRoute(
        selectedDeviceType: Int,
        connectedDeviceTypes: Collection<Int>,
    ): AudioOutputRoute = when (selectedDeviceType) {
        MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH -> {
            val bluetoothTypes = connectedDeviceTypes.filter(::isBluetoothType)
            resolve(bluetoothTypes).takeIf {
                it == AudioOutputRoute.BLUETOOTH_CLASSIC || it == AudioOutputRoute.BLUETOOTH_LE
            } ?: AudioOutputRoute.BLUETOOTH_CLASSIC
        }
        MediaRouter.RouteInfo.DEVICE_TYPE_TV -> AudioOutputRoute.HDMI
        MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> AudioOutputRoute.SPEAKER
        else -> resolveLocalDefault(connectedDeviceTypes)
    }

    @SuppressLint("InlinedApi")
    private fun isBluetoothType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            type == AudioDeviceInfo.TYPE_BLE_BROADCAST ||
            type == AudioDeviceInfo.TYPE_HEARING_AID

    private fun resolveLocalDefault(deviceTypes: Collection<Int>): AudioOutputRoute {
        // Bluetooth routes require an explicit MediaRouter selection on these releases. Removing
        // them here prevents a paired-but-inactive headset from introducing false latency.
        val localTypes = deviceTypes.filterNot(::isBluetoothType)
        return resolve(localTypes)
    }
}

/** Watches connected media outputs and exposes the best available latency profile. */
internal class AudioOutputMonitor(
    context: Context,
    private val onRouteChanged: (AudioOutputRoute) -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    @Suppress("DEPRECATION")
    private val mediaRouter = appContext.getSystemService(MediaRouter::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var started = false
    private var currentRoute = AudioOutputRoute.UNKNOWN

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    @Suppress("DEPRECATION")
    private val mediaRouteCallback = object : MediaRouter.SimpleCallback() {
        override fun onRouteSelected(
            router: MediaRouter,
            type: Int,
            info: MediaRouter.RouteInfo,
        ) = refresh()

        override fun onRouteUnselected(
            router: MediaRouter,
            type: Int,
            info: MediaRouter.RouteInfo,
        ) = refresh()

        override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) = refresh()
    }

    fun start() {
        if (started) return
        started = true
        audioManager.registerAudioDeviceCallback(callback, mainHandler)
        @Suppress("DEPRECATION")
        mediaRouter.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, mediaRouteCallback)
        refresh(force = true)
    }

    fun stop() {
        if (!started) return
        started = false
        audioManager.unregisterAudioDeviceCallback(callback)
        @Suppress("DEPRECATION")
        mediaRouter.removeCallback(mediaRouteCallback)
    }

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun refresh(force: Boolean = false) {
        val route = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ reports the devices Android would actually route a media AudioTrack to.
                runCatching {
                    AudioOutputRouteResolver.resolve(
                        audioManager.getAudioDevicesForAttributes(mediaAttributes).map { it.type },
                    )
                }.getOrElse { resolveLegacyRoute() }
            } else {
                resolveLegacyRoute()
            }
        }.getOrDefault(currentRoute)
        if (force || route != currentRoute) {
            currentRoute = route
            onRouteChanged(route)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacyRoute(): AudioOutputRoute {
        val selectedRoute = mediaRouter.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        return AudioOutputRouteResolver.resolveSelectedMediaRoute(
            selectedDeviceType = selectedRoute.deviceType,
            connectedDeviceTypes = audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .map { it.type },
        )
    }
}
