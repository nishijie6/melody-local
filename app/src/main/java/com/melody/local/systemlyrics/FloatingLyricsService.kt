package com.melody.local.systemlyrics

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import com.melody.local.MainActivity
import com.melody.local.R
import kotlin.math.roundToInt

class FloatingLyricsService : Service() {
    private lateinit var settings: SystemLyricsSettings
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var currentLineView: TextView? = null
    private var nextLineView: TextView? = null
    private var latestSnapshot = SystemLyricsRuntime.latestSnapshot
    private val snapshotListener: (SystemLyricSnapshot) -> Unit = ::render

    override fun onCreate() {
        super.onCreate()
        settings = SystemLyricsSettings(this)
        windowManager = getSystemService(WindowManager::class.java)
        SystemLyricsRuntime.addListener(snapshotListener)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            settings.overlayEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!settings.overlayEnabled || !Settings.canDrawOverlays(this)) {
            settings.overlayEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground(latestSnapshot)
        showOverlayIfNeeded()
        render(SystemLyricsRuntime.latestSnapshot)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        SystemLyricsRuntime.removeListener(snapshotListener)
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun showOverlayIfNeeded() {
        if (overlayView != null) return
        val root = createOverlayView()
        val positionPreferences = getSharedPreferences(POSITION_PREFERENCES, MODE_PRIVATE)
        val width = minOf(
            (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(160)),
            dp(380),
        )
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = positionPreferences.getInt(KEY_POSITION_X, dp(16))
                .coerceIn(0, (resources.displayMetrics.widthPixels - width).coerceAtLeast(0))
            y = positionPreferences.getInt(KEY_POSITION_Y, dp(96))
                .coerceIn(0, resources.displayMetrics.heightPixels - dp(64))
        }
        runCatching { windowManager.addView(root, params) }
            .onSuccess {
                overlayView = root
                layoutParams = params
            }
            .onFailure {
                settings.overlayEnabled = false
                stopSelf()
            }
    }

    private fun createOverlayView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(10), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(224, 255, 255, 255))
                setStroke(dp(1), Color.argb(80, 63, 81, 181))
            }
            elevation = dp(8).toFloat()
        }
        val header = DragHandleLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val label = TextView(this).apply {
            text = getString(R.string.floating_lyrics_title)
            setTextColor(Color.rgb(63, 81, 181))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(this).apply {
            text = "×"
            contentDescription = getString(R.string.close_floating_lyrics)
            gravity = Gravity.CENTER
            textSize = 24f
            setTextColor(Color.rgb(70, 70, 70))
            setPadding(dp(12), 0, dp(4), dp(4))
            setOnClickListener {
                settings.overlayEnabled = false
                stopSelf()
            }
        }
        header.addView(label)
        header.addView(close)
        root.addView(header)

        currentLineView = TextView(this).apply {
            setTextColor(Color.rgb(28, 28, 32))
            textSize = 18f
            maxLines = 2
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), dp(6), 0)
        }
        nextLineView = TextView(this).apply {
            setTextColor(Color.rgb(100, 100, 108))
            textSize = 14f
            maxLines = 2
            setPadding(0, dp(4), dp(6), 0)
        }
        root.addView(currentLineView)
        root.addView(nextLineView)
        header.setOnTouchListener(OverlayDragListener())
        return root
    }

    private fun render(snapshot: SystemLyricSnapshot) {
        latestSnapshot = snapshot
        val current = snapshot.currentLine.ifBlank {
            if (snapshot.songId == null) {
                getString(R.string.floating_lyrics_waiting_for_playback)
            } else {
                getString(R.string.floating_lyrics_missing)
            }
        }
        val next = snapshot.nextLine.ifBlank {
            if (snapshot.songId == null) "" else getString(R.string.floating_lyrics_no_next_line)
        }
        currentLineView?.text = current
        nextLineView?.text = next
        if (overlayView != null) updateForegroundNotification(snapshot)
    }

    private fun startAsForeground(snapshot: SystemLyricSnapshot) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(snapshot),
            type,
        )
    }

    private fun updateForegroundNotification(snapshot: SystemLyricSnapshot) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun buildNotification(snapshot: SystemLyricSnapshot) = NotificationCompat.Builder(
        this,
        NOTIFICATION_CHANNEL_ID,
    )
        .setSmallIcon(R.drawable.ic_stat_music)
        .setContentTitle(getString(R.string.floating_lyrics_notification_title))
        .setContentText(
            snapshot.currentLine.ifBlank { getString(R.string.floating_lyrics_notification_text) },
        )
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            R.drawable.ic_stat_music,
            getString(R.string.close_floating_lyrics),
            PendingIntent.getService(
                this,
                1,
                Intent(this, FloatingLyricsService::class.java).setAction(ACTION_CLOSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.floating_lyrics_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.floating_lyrics_channel_description)
                setSound(null, null)
            },
        )
    }

    private inner class OverlayDragListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = (initialX + event.rawX - touchX).roundToInt()
                        .coerceIn(0, (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
                    params.y = (initialY + event.rawY - touchY).roundToInt()
                        .coerceIn(0, resources.displayMetrics.heightPixels - dp(64))
                    overlayView?.let { windowManager.updateViewLayout(it, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    getSharedPreferences(POSITION_PREFERENCES, MODE_PRIVATE).edit {
                        putInt(KEY_POSITION_X, params.x)
                        putInt(KEY_POSITION_Y, params.y)
                    }
                    // Preserve a semantic click path for keyboard and accessibility services.
                    view.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    getSharedPreferences(POSITION_PREFERENCES, MODE_PRIVATE).edit {
                        putInt(KEY_POSITION_X, params.x)
                        putInt(KEY_POSITION_Y, params.y)
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    /** Gives the draggable header an explicit accessibility click contract. */
    private class DragHandleLayout(context: Context) : LinearLayout(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    companion object {
        internal const val ACTION_SHOW = "com.melody.local.systemlyrics.SHOW"
        internal const val ACTION_CLOSE = "com.melody.local.systemlyrics.CLOSE"
        private const val NOTIFICATION_CHANNEL_ID = "floating_lyrics"
        private const val NOTIFICATION_ID = 13_021
        private const val POSITION_PREFERENCES = "floating_lyrics_position"
        private const val KEY_POSITION_X = "x"
        private const val KEY_POSITION_Y = "y"
    }
}
