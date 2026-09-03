package com.melody.local.systemlyrics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemLyricsSettingsInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences(SystemLyricsSettings.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun persistsFeatureTogglesAndPerRouteCalibration() {
        val settings = SystemLyricsSettings(context)
        assertFalse(settings.overlayEnabled)
        assertTrue(settings.notificationLyricsEnabled)
        assertTrue(settings.automaticLatencyCompensationEnabled)

        settings.overlayEnabled = true
        settings.notificationLyricsEnabled = false
        settings.automaticLatencyCompensationEnabled = false
        settings.setManualDelayMs(AudioOutputRoute.BLUETOOTH_CLASSIC, 325L)
        settings.setManualDelayMs(AudioOutputRoute.WIRED, -40L)

        val restored = SystemLyricsSettings(context)
        assertTrue(restored.overlayEnabled)
        assertFalse(restored.notificationLyricsEnabled)
        assertFalse(restored.automaticLatencyCompensationEnabled)
        assertEquals(325L, restored.manualDelayMs(AudioOutputRoute.BLUETOOTH_CLASSIC))
        assertEquals(-40L, restored.manualDelayMs(AudioOutputRoute.WIRED))
        assertEquals(325L, restored.appliedDelayMs(AudioOutputRoute.BLUETOOTH_CLASSIC))
    }

    @Test
    fun clampsUnsafeCalibrationBeforePersistence() {
        val settings = SystemLyricsSettings(context)
        settings.setManualDelayMs(AudioOutputRoute.USB, Long.MAX_VALUE)
        assertEquals(LyricsTimingPolicy.MAX_MANUAL_DELAY_MS, settings.manualDelayMs(AudioOutputRoute.USB))
    }
}
