package com.nettarion.hyperborea.program

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.nettarion.hyperborea.core.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Service-owned sound, so backgrounding the activity does not silence countdowns. */
@Singleton
class ProgramCues @Inject constructor(@ApplicationContext context: Context, private val logger: AppLogger) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var focused = false
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0) synchronized(this) { focused = false; tone?.stopTone() }
    }
    private val releaseFocus = Runnable { abandonFocus() }

    @Synchronized @Suppress("DEPRECATION")
    fun play(count: Int): Boolean = try {
        handler.removeCallbacks(releaseFocus)
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) false
        else {
            if (!focused) {
                val result = if (Build.VERSION.SDK_INT >= 26) {
                    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setOnAudioFocusChangeListener(focusListener, handler).build()
                    focusRequest = request
                    audio.requestAudioFocus(request)
                } else audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                focused = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            if (!focused) false else {
                val generator = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 90).also { tone = it }
                val ok = generator.startTone(if (count == 0) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, if (count == 0) 300 else 150)
                if (ok) logger.d(TAG, "Countdown cue $count")
                handler.postDelayed(releaseFocus, if (count == 0) 500 else 1500)
                ok
            }
        }
    } catch (e: Exception) { logger.e(TAG, "Countdown audio failed", e); false }

    @Synchronized @Suppress("DEPRECATION") private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        else audio.abandonAudioFocus(focusListener)
        focusRequest = null; focused = false
    }
    @Synchronized fun stop() { handler.removeCallbacks(releaseFocus); tone?.stopTone(); abandonFocus() }
    @Synchronized fun close() { stop(); tone?.release(); tone = null }
    private companion object { const val TAG = "ProgramCues" }
}
