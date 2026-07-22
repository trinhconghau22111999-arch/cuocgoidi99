package com.h.simplecall.call

import android.telecom.Call
import android.telecom.VideoProfile

object CallManager {

    var currentCall: Call? = null
        private set

    private val listeners = mutableListOf<(Call?, Int) -> Unit>()

    fun addListener(l: (Call?, Int) -> Unit) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: (Call?, Int) -> Unit) { listeners.remove(l) }

    fun onCallAdded(call: Call) {
        currentCall = call
        call.registerCallback(callback)
        notifyListeners(call.state)
    }

    fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        if (currentCall == call) currentCall = null
        notifyListeners(Call.STATE_DISCONNECTED)
    }

    fun answer()  { currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY) }
    fun reject()  { currentCall?.reject(false, null) }
    fun hangup()  { currentCall?.disconnect() }

    fun toggleMute(mute: Boolean) { MyInCallService.instance?.muteCall(mute) }
    fun setSpeaker(on: Boolean)   { MyInCallService.instance?.setSpeaker(on) }
    fun playDtmf(digit: Char)     { MyInCallService.instance?.playDtmf(digit) }
    fun stopDtmf()                { MyInCallService.instance?.stopDtmf() }

    fun callerNumber(call: Call?): String =
        call?.details?.handle?.schemeSpecificPart ?: ""

    fun callerName(call: Call?): String =
        call?.details?.callerDisplayName ?: ""

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = notifyListeners(state)
    }

    private fun notifyListeners(state: Int) {
        listeners.toList().forEach { it(currentCall, state) }
    }
}
