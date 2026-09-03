package com.melody.local.systemlyrics

import android.media.AudioDeviceInfo
import android.media.MediaRouter
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOutputRouteResolverTest {
    @Test
    fun selectedSpeakerIsNotOverriddenByMerelyConnectedBluetoothDevice() {
        assertEquals(
            AudioOutputRoute.SPEAKER,
            AudioOutputRouteResolver.resolveSelectedMediaRoute(
                selectedDeviceType = MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER,
                connectedDeviceTypes = listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                ),
            ),
        )
    }

    @Test
    fun selectedBluetoothRouteUsesConnectedTransportType() {
        assertEquals(
            AudioOutputRoute.BLUETOOTH_CLASSIC,
            AudioOutputRouteResolver.resolveSelectedMediaRoute(
                MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH,
                listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            ),
        )
        assertEquals(
            AudioOutputRoute.BLUETOOTH_LE,
            AudioOutputRouteResolver.resolveSelectedMediaRoute(
                MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH,
                listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLE_HEADSET),
            ),
        )
    }

    @Test
    fun unknownLegacyRouteIgnoresInactiveBluetoothButKeepsLocalPhysicalRoute() {
        assertEquals(
            AudioOutputRoute.WIRED,
            AudioOutputRouteResolver.resolveSelectedMediaRoute(
                MediaRouter.RouteInfo.DEVICE_TYPE_UNKNOWN,
                listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                ),
            ),
        )
    }

    @Test
    fun resolvesBleWiredUsbHdmiSpeakerAndUnknown() {
        assertEquals(
            AudioOutputRoute.BLUETOOTH_LE,
            AudioOutputRouteResolver.resolve(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)),
        )
        assertEquals(
            AudioOutputRoute.WIRED,
            AudioOutputRouteResolver.resolve(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)),
        )
        assertEquals(
            AudioOutputRoute.USB,
            AudioOutputRouteResolver.resolve(listOf(AudioDeviceInfo.TYPE_USB_HEADSET)),
        )
        assertEquals(
            AudioOutputRoute.HDMI,
            AudioOutputRouteResolver.resolve(listOf(AudioDeviceInfo.TYPE_HDMI)),
        )
        assertEquals(
            AudioOutputRoute.SPEAKER,
            AudioOutputRouteResolver.resolve(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)),
        )
        assertEquals(AudioOutputRoute.UNKNOWN, AudioOutputRouteResolver.resolve(emptyList()))
    }
}
