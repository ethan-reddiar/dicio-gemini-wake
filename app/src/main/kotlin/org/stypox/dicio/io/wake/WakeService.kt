package org.stypox.dicio.io.wake

import android.Manifest.permission.RECORD_AUDIO
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.stypox.dicio.MainActivity
import org.stypox.dicio.MainActivity.Companion.ACTION_WAKE_WORD
import org.stypox.dicio.R
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.di.WakeDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@AndroidEntryPoint
class WakeService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private val listening = AtomicBoolean(false)

    @Inject
    lateinit var skillEvaluator: SkillEvaluator
    @Inject
    lateinit var sttInputDevice: SttInputDeviceWrapper
    @Inject
    lateinit var wakeDevice: WakeDeviceWrapper

    private val handler = Handler(Looper.getMainLooper())
    private val releaseSttResourcesRunnable = Runnable {
        if (MainActivity.isCreated <= 0) {
            // if the main activity is neither visible nor in the background,
            // then unload the STT after a while because it would be using resources uselessly
            sttInputDevice.reinitializeToReleaseResources()
        }
    }

    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(this, NotificationManager::class.java)!!

        scope.launch {
            // Recreate the notification so that it says the correct thing (i.e. there is a
            // different string for the "Hey Dicio" wake word and for a custom one).
            // Ignore the first one (i.e. the current value), which is handled in onStartCommand.
            wakeDevice.isHeyDicio.drop(1).collect { isHeyDicio ->
                createForegroundNotification(isHeyDicio)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_WAKE_SERVICE) {
            listening.set(false)
            return START_NOT_STICKY
        }

        try {
            createForegroundNotification(wakeDevice.isHeyDicio.value)
        } catch (t: Throwable) {
            stopWithMessage("could not create WakeService foreground notification", t)
            return START_NOT_STICKY
        }

        if (listening.getAndSet(true)) {
            return START_STICKY // if we were already listening, do nothing more
        }

        if (ContextCompat.checkSelfPermission(this, RECORD_AUDIO) != PERMISSION_GRANTED) {
            stopWithMessage("Could not start WakeService: microphone permission not granted")
            return START_NOT_STICKY
        }

        when (wakeDevice.state.value) {
            WakeState.NotLoaded,
            WakeState.Loading,
            WakeState.Loaded -> {}
            else -> {
                stopWithMessage("Could not start WakeService: wake word device not ready")
                return START_NOT_STICKY
            }
        }

        scope.launch {
            try {
                listenForWakeWord()
                stopWithMessage() // exit normally, as the user just stopped the service
            } catch (t: Throwable) {
                stopWithMessage("Cannot continue listening for wake word", t)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        listening.set(false)
        job.cancel()
        wakeDevice.reinitializeToReleaseResources()
        super.onDestroy()
    }

    private fun stopWithMessage(message: String = "", throwable: Throwable? = null) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()

        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else if (message.isNotEmpty()) {
            Log.e(TAG, message)
        }
    }

    private fun createForegroundNotification(isHeyDicio: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FOREGROUND_NOTIFICATION_CHANNEL_ID,
                getString(R.string.wake_service_label),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = getString(R.string.wake_service_foreground_notification_summary)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, FOREGROUND_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hearing_white)
            .setContentTitle(
                getString(
                    if (isHeyDicio) R.string.wake_service_foreground_notification
                    else R.string.wake_custom_service_foreground_notification
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(NotificationCompat.Action(
                R.drawable.ic_stop_circle_white,
                getString(R.string.stop),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, WakeService::class.java)
                        .apply { action = ACTION_STOP_WAKE_SERVICE },
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            ))
            .build()

        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun listenForWakeWord() {
        @SuppressLint("MissingPermission")
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            6400,
        )

        var audio = ShortArray(0)
        var nextWakeWordAllowed = Instant.MIN
        var resumeListeningAt = Instant.MIN

        try {
            ar.startRecording()
            while (listening.get()) {
                // gemini-wake patch: while the launched assistant (Gemini) is using the
                // microphone, release the mic and wait, otherwise Gemini would hear nothing.
                if (Instant.now() < resumeListeningAt) {
                    if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        ar.stop()
                    }
                    Thread.sleep(ASSISTANT_SESSION_POLL_MILLIS)
                    continue
                } else if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    @SuppressLint("MissingPermission")
                    ar.startRecording()
                }

                if (audio.size != wakeDevice.frameSize()) {
                    audio = ShortArray(wakeDevice.frameSize())
                }

                ar.read(audio, 0, audio.size)

                val wakeWordDetected = wakeDevice.processFrame(audio)
                if (wakeWordDetected && Instant.now() > nextWakeWordAllowed) {
                    nextWakeWordAllowed = Instant.now().plusMillis(WAKE_WORD_BACKOFF_MILLIS)
                    val assistantLaunched = onWakeWordDetected()
                    if (assistantLaunched) {
                        // free the microphone for the assistant for a while
                        resumeListeningAt = Instant.now().plusMillis(ASSISTANT_SESSION_MILLIS)
                    }
                }

                lastHeard.set(Instant.now())
            }
        } finally {
            ar.stop()
            ar.release()
        }
    }

    /**
     * gemini-wake patch: on wake word, first try to launch the default assistant (Gemini) in
     * voice mode, falling back to the Gemini app and then the Google app.
     *
     * @return true if an assistant was launched — in that case Dicio's own speech-to-text popup
     *         is NOT started, and the caller should pause wake word listening for a while to
     *         free the microphone for the assistant; false if Dicio should handle the wake word
     *         itself (original behavior).
     */
    private fun onWakeWordDetected(): Boolean {
        Log.d(TAG, "Wake word detected")

        val assistantIntent = buildAssistantIntent()
        if (assistantIntent != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || MainActivity.isInForeground > 0) {
                try {
                    startActivity(assistantIntent)
                    return true
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not start assistant activity directly", t)
                }
            } else {
                // Android 10+ does not allow starting activities from the background,
                // so show a full-screen notification instead, which does actually result in
                // starting the activity from the background if the phone is off and
                // Do Not Disturb is not active.
                postAssistantFullScreenNotification(assistantIntent)
                // cancel the notification after a while in case the user ignored it
                handler.postDelayed(
                    { notificationManager.cancel(TRIGGERED_NOTIFICATION_ID) },
                    ASSISTANT_NOTIFICATION_TIMEOUT_MILLIS,
                )
                return true
            }
        }

        // Original behavior: no assistant app found, let Dicio handle the wake word itself.
        val intent = Intent(this, MainActivity::class.java)
        intent.setAction(ACTION_WAKE_WORD)
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK)

        // Start listening and pass STT events to the skill evaluator.
        // Note that this works even if the MainActivity is opened later!
        sttInputDevice.tryLoad(skillEvaluator::processInputEvent)

        // Unload the STT after a while because it would be using RAM uselessly
        handler.removeCallbacks(releaseSttResourcesRunnable)
        handler.postDelayed(releaseSttResourcesRunnable, RELEASE_STT_RESOURCES_MILLIS)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || MainActivity.isInForeground > 0) {
            // start the activity directly on versions prior to Android 10,
            // or if the MainActivity is already running in the foreground
            startActivity(intent)

        } else {
            // Android 10+ does not allow starting activities from the background,
            // so show a full-screen notification instead, which does actually result in starting
            // the activity from the background if the phone is off and Do Not Disturb is not active
            // Maybe we could also use the "Display over other apps" permission?

            val channel = NotificationChannel(
                TRIGGERED_NOTIFICATION_CHANNEL_ID,
                getString(R.string.wake_service_triggered_notification),
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = getString(R.string.wake_service_triggered_notification_summary)
            notificationManager.createNotificationChannel(channel)

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(this, TRIGGERED_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hearing_white)
                .setContentTitle(getString(R.string.wake_service_triggered_notification))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    getString(R.string.wake_service_triggered_notification_summary)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(pendingIntent, true)
                .build()

            notificationManager.cancel(TRIGGERED_NOTIFICATION_ID)
            notificationManager.notify(TRIGGERED_NOTIFICATION_ID, notification)
        }

        return false
    }

    /**
     * gemini-wake patch: build an intent that opens the assistant in voice mode.
     *
     * Prefers [Intent.ACTION_VOICE_COMMAND], which opens whichever app is set as the default
     * digital assistant (ideally Gemini) straight into listening mode. Falls back to the
     * launcher intent of the Gemini app, then of the Google app.
     *
     * @return the intent to launch, or null if no assistant-like app is installed
     */
    private fun buildAssistantIntent(): Intent? {
        try {
            val voice = Intent(Intent.ACTION_VOICE_COMMAND)
                .setFlags(FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (voice.resolveActivity(packageManager) != null) {
                return voice
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ACTION_VOICE_COMMAND not available", t)
        }

        for (pkg in ASSISTANT_PACKAGES) {
            try {
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    return launch.addFlags(
                        FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not build launch intent for $pkg", t)
            }
        }

        return null
    }

    /**
     * gemini-wake patch: show a high-priority full-screen notification that surfaces the
     * assistant activity even when the app is in the background on Android 10+.
     */
    private fun postAssistantFullScreenNotification(assistantIntent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TRIGGERED_NOTIFICATION_CHANNEL_ID,
                getString(R.string.wake_service_triggered_notification),
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = getString(R.string.wake_service_triggered_notification_summary)
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            assistantIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, TRIGGERED_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hearing_white)
            .setContentTitle(getString(R.string.wake_service_triggered_notification))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                getString(R.string.wake_service_triggered_notification_summary)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        notificationManager.cancel(TRIGGERED_NOTIFICATION_ID)
        notificationManager.notify(TRIGGERED_NOTIFICATION_ID, notification)
    }

    companion object {
        /**
         * Starting from Android 11, it is not possible to start a foreground service
         * that accesses the microphone from a BOOT_COMPLETED broadcast. So we show a
         * notification instead, which starts the foreground service when clicked.
         * https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed
         */
        @RequiresApi(Build.VERSION_CODES.R)
        fun createNotificationToStartLater(context: Context) {
            val notificationManager = getSystemService(context, NotificationManager::class.java)
                ?: return

            val channel = NotificationChannel(
                START_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.wake_service_start_notification),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = context.getString(R.string.wake_service_start_notification_summary)
            notificationManager.createNotificationChannel(channel)

            val pendingIntent = PendingIntent.getForegroundService(
                context,
                0,
                Intent(context, WakeService::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, START_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hearing_white)
                .setContentTitle(context.getString(R.string.wake_service_start_notification))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.wake_service_start_notification_summary)))
                .setOngoing(false)
                .setShowWhen(false)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(START_NOTIFICATION_ID, notification)
        }

        /**
         * Start the service. Call this only from a foreground part of the app (e.g. the main
         * activity), or from BOOT_COMPLETED only before Android 11. For BOOT_COMPLETED on Android
         * 11+ use [createNotificationToStartLater] instead.
         */
        fun start(context: Context) {
            Log.d(TAG, "WakeService.start() called from ${Throwable().stackTrace[1]}")
            val intent = Intent(context, WakeService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, WakeService::class.java)
                    .apply { action = ACTION_STOP_WAKE_SERVICE })
            } catch (_: IllegalStateException) {
                // Must not have been running. No problem with that.
            }
        }

        // Consider the service running if it processed any audio data within the past half second.
        fun isRunning(): Boolean = lastHeard.get()?.isAfter(Instant.now().minusMillis(500)) == true

        /**
         * On Android 10+ cancels any notification telling the user that the Dicio wake word was
         * triggered, which is not needed anymore after the main activity starts.
         */
        fun cancelTriggeredNotification(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getSystemService(context, NotificationManager::class.java)
                    ?.cancel(TRIGGERED_NOTIFICATION_ID)
            }
        }

        private val lastHeard = AtomicReference<Instant>()

        private val TAG = WakeService::class.simpleName
        private const val FOREGROUND_NOTIFICATION_CHANNEL_ID =
            "org.stypox.dicio.io.wake.WakeService.FOREGROUND"
        private const val START_NOTIFICATION_CHANNEL_ID =
            "org.stypox.dicio.io.wake.WakeService.START"
        private const val TRIGGERED_NOTIFICATION_CHANNEL_ID =
            "org.stypox.dicio.io.wake.WakeService.TRIGGERED"
        private const val FOREGROUND_NOTIFICATION_ID = 19803672
        private const val START_NOTIFICATION_ID = 48019274
        private const val TRIGGERED_NOTIFICATION_ID = 601398647
        private const val WAKE_WORD_BACKOFF_MILLIS = 4000L

        // gemini-wake patch constants
        /** How long wake word listening pauses after launching the assistant, to free the mic. */
        private const val ASSISTANT_SESSION_MILLIS = 45_000L
        /** Poll interval while wake word listening is paused for an assistant session. */
        private const val ASSISTANT_SESSION_POLL_MILLIS = 250L
        /** Auto-cancel the assistant full-screen notification after this delay. */
        private const val ASSISTANT_NOTIFICATION_TIMEOUT_MILLIS = 30_000L
        /** Packages tried as assistant fallback, in order: Gemini app, then Google app. */
        private val ASSISTANT_PACKAGES = listOf(
            "com.google.android.apps.bard",
            "com.google.android.googlequicksearchbox",
        )
        private const val ACTION_STOP_WAKE_SERVICE =
            "org.stypox.dicio.io.wake.WakeService.ACTION_STOP"
        private const val RELEASE_STT_RESOURCES_MILLIS = 1000L * 60 * 5 // 5 minutes
    }
}
