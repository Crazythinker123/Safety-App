package com.example.safetyclick

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Watches volume key presses via ContentObserver.
 * Detects a 1.5-second hold on Volume Up and fires SentinelTrigger.
 * Trigger logic itself lives in SentinelTrigger — shared with BLE hardware.
 */
class VolumeObserver(
    handler: Handler,
    private val context: Context,
    private val trigger: SentinelTrigger
) : ContentObserver(handler) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var lastVolume   = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    private var originalVolumeBeforeHold = lastVolume

    private var isPressing           = false
    private var hasTriggeredThisHold = false

    private val tag         = "SENTINEL_VOLUME"
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onChange(selfChange: Boolean) {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume     = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // Volume Up press (or held at ceiling)
        if (currentVolume > lastVolume || (currentVolume == maxVolume && lastVolume == maxVolume)) {
            if (!isPressing) {
                isPressing               = true
                hasTriggeredThisHold     = false
                originalVolumeBeforeHold = lastVolume
                Log.d(tag, "Hold started at volume $originalVolumeBeforeHold")

                // Fire after 1.5 s hold
                mainHandler.postDelayed({
                    if (isPressing && !hasTriggeredThisHold) {
                        hasTriggeredThisHold = true
                        Log.d(tag, "1.5 s hold confirmed — firing trigger")
                        trigger.fire()
                        startLockdownLoop()
                    }
                }, 1500)
            }
        } else if (currentVolume < lastVolume) {
            // Volume Down — cancel
            isPressing           = false
            hasTriggeredThisHold = false
        }

        lastVolume = currentVolume
    }

    /** Locks the volume level steady while the button is still held after trigger. */
    private fun startLockdownLoop() {
        mainHandler.post(object : Runnable {
            override fun run() {
                if (isPressing) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolumeBeforeHold, 0)
                    mainHandler.postDelayed(this, 100)
                }
            }
        })
    }
}
