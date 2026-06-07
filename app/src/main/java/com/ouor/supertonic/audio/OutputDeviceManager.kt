package com.ouor.supertonic.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Enumerates connected audio *output* devices (built-in speaker, wired, USB, and
 * each connected Bluetooth sink) and notifies on hot-plug changes. The user picks
 * one device; [PcmPlayer] routes playback to it via AudioTrack.setPreferredDevice.
 */
class OutputDeviceManager(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** A user-selectable output, wrapping the platform [AudioDeviceInfo]. */
    data class OutputDevice(
        val id: Int,
        val name: String,
        val typeLabel: String,
        val info: AudioDeviceInfo,
    )

    /** Current connected output devices, de-duplicated and labelled. */
    fun listOutputs(): List<OutputDevice> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in ROUTABLE_TYPES }
            .map { info ->
                OutputDevice(
                    id = info.id,
                    name = displayName(info),
                    typeLabel = typeLabel(info.type),
                    info = info,
                )
            }

    /** Resolve a previously selected device id back to a live [AudioDeviceInfo]. */
    fun findById(id: Int): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }

    /** Register for device connect/disconnect callbacks (returns the callback to unregister). */
    fun registerCallback(onChanged: () -> Unit): AudioDeviceCallback {
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onChanged()
        }
        audioManager.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        return cb
    }

    fun unregisterCallback(cb: AudioDeviceCallback) {
        audioManager.unregisterAudioDeviceCallback(cb)
    }

    private fun displayName(info: AudioDeviceInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val product = info.productName?.toString()?.trim()
            if (!product.isNullOrEmpty()) {
                // For built-in routes productName is the phone model; prefer the type label there.
                return if (info.type in BUILT_IN_TYPES) typeLabel(info.type) else product
            }
        }
        return typeLabel(info.type)
    }

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth speaker"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (call)"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_DOCK -> "Dock"
        else -> "Audio output"
    }

    private companion object {
        val BUILT_IN_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        )

        // Output device types worth offering as a routing target.
        val ROUTABLE_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_DOCK,
        )
    }
}
