package com.h.simplecall

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.h.simplecall.call.CallForwardManager
import com.h.simplecall.call.CallManager
import com.h.simplecall.databinding.ActivityInCallBinding

class InCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInCallBinding
    private var isMuted    = false
    private var isSpeaker  = false

    // Timer đếm giờ cuộc gọi active
    private val timerHandler = Handler(Looper.getMainLooper())
    private var callStartMs  = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - callStartMs) / 1000
            val h = elapsed / 3600; val m = (elapsed % 3600) / 60; val s = elapsed % 60
            binding.tvCallStatus.text =
                if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
            timerHandler.postDelayed(this, 1000)
        }
    }

    private val listener: (Call?, Int) -> Unit = { call, state ->
        runOnUiThread { updateUi(call, state) }
    }

    // Palette avatar – xoay theo hashCode tên/số
    private val avatarBgs  = intArrayOf(
        R.color.av0, R.color.av1, R.color.av2, R.color.av3, R.color.av4, R.color.av5)
    private val avatarTxts = intArrayOf(
        R.color.av0t, R.color.av1t, R.color.av2t, R.color.av3t, R.color.av4t, R.color.av5t)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAnswer.setOnClickListener  { CallManager.answer() }
        binding.btnDecline.setOnClickListener { CallManager.reject() }
        binding.btnEndCall.setOnClickListener { CallManager.hangup() }

        binding.btnMute.setOnClickListener {
            isMuted = !isMuted
            CallManager.toggleMute(isMuted)
            binding.btnMute.setBackgroundResource(
                if (isMuted) R.drawable.bg_action_circle_active else R.drawable.bg_action_circle)
        }
        binding.btnSpeaker.setOnClickListener {
            isSpeaker = !isSpeaker
            CallManager.setSpeaker(isSpeaker)
            binding.btnSpeaker.setBackgroundResource(
                if (isSpeaker) R.drawable.bg_action_circle_active else R.drawable.bg_action_circle)
        }

        CallManager.addListener(listener)
        updateUi(CallManager.currentCall, CallManager.currentCall?.state ?: Call.STATE_NEW)
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        CallManager.removeListener(listener)
        super.onDestroy()
    }

    private fun updateUi(call: Call?, state: Int) {
        if (call == null || state == Call.STATE_DISCONNECTED) {
            timerHandler.removeCallbacks(timerRunnable)
            finish(); return
        }

        val isOutgoing = state != Call.STATE_RINGING
        val display = if (isOutgoing && CallForwardManager.lastDisplayNumber.isNotEmpty())
            CallForwardManager.lastDisplayNumber
        else CallManager.callerNumber(call)

        binding.tvCallerName.text = display

        // Avatar: chữ cái + màu từ palette
        val idx = Math.abs(display.hashCode()) % avatarBgs.size
        binding.tvAvatarLetter.text = display.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
        binding.avatarView.setBackgroundResource(avatarBgs[idx])
        binding.tvAvatarLetter.setTextColor(getColor(avatarTxts[idx]))

        val isRinging = state == Call.STATE_RINGING
        binding.incomingControls.visibility = if (isRinging) View.VISIBLE else View.GONE
        binding.activeControls.visibility   = if (isRinging) View.GONE   else View.VISIBLE

        when (state) {
            Call.STATE_RINGING -> {
                timerHandler.removeCallbacks(timerRunnable)
                binding.tvCallStatus.text = getString(R.string.incoming_call)
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                timerHandler.removeCallbacks(timerRunnable)
                binding.tvCallStatus.text = "Đang gọi..."
            }
            Call.STATE_ACTIVE -> {
                if (callStartMs == 0L) {
                    callStartMs = System.currentTimeMillis()
                    timerHandler.post(timerRunnable)
                }
            }
            Call.STATE_HOLDING -> {
                timerHandler.removeCallbacks(timerRunnable)
                binding.tvCallStatus.text = "Đang giữ máy"
            }
            Call.STATE_DISCONNECTING -> {
                timerHandler.removeCallbacks(timerRunnable)
                binding.tvCallStatus.text = "Đang kết thúc..."
            }
        }
    }
}
