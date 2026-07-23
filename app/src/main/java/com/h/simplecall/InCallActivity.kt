package com.h.simplecall

import android.content.ContentUris
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.telecom.Call
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import androidx.appcompat.app.AppCompatActivity
import com.h.simplecall.call.CallForwardManager
import com.h.simplecall.call.CallManager
import com.h.simplecall.databinding.ActivityInCallBinding

class InCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInCallBinding
    private var isMuted   = false
    private var isSpeaker = false
    private var dtmfVisible = false

    private val timerHandler = Handler(Looper.getMainLooper())
    private var callStartMs = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            val e = (System.currentTimeMillis() - callStartMs) / 1000
            val h = e / 3600; val m = (e % 3600) / 60; val s = e % 60
            binding.tvCallStatus.text =
                if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
            timerHandler.postDelayed(this, 1000)
        }
    }

    private val listener: (Call?, Int) -> Unit = { call, state ->
        runOnUiThread { updateUi(call, state) }
    }

    private val avatarBgs  = intArrayOf(R.color.av0,R.color.av1,R.color.av2,R.color.av3,R.color.av4,R.color.av5)
    private val avatarTxts = intArrayOf(R.color.av0t,R.color.av1t,R.color.av2t,R.color.av3t,R.color.av4t,R.color.av5t)

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
            binding.btnMute.setImageResource(
                if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
            binding.tvMuteLabel.text = if (isMuted) "Bật mic" else "Tắt mic"
        }

        binding.btnSpeaker.setOnClickListener {
            isSpeaker = !isSpeaker
            CallManager.setSpeaker(isSpeaker)
            binding.btnSpeaker.setBackgroundResource(
                if (isSpeaker) R.drawable.bg_action_circle_active else R.drawable.bg_action_circle)
        }

        // DTMF toggle
        binding.btnDtmf.setOnClickListener {
            dtmfVisible = !dtmfVisible
            binding.dtmfPanel.visibility = if (dtmfVisible) View.VISIBLE else View.GONE
            binding.btnDtmf.setBackgroundResource(
                if (dtmfVisible) R.drawable.bg_action_circle_active else R.drawable.bg_action_circle)
        }

        // DTMF keys
        val dtmfGrid = binding.dtmfPanel
        for (i in 0 until dtmfGrid.childCount) {
            val btn = dtmfGrid.getChildAt(i) as? Button ?: continue
            val tag = (btn.tag as? String)?.firstOrNull() ?: continue
            btn.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        CallManager.playDtmf(tag)
                        haptic()
                    }
                    android.view.MotionEvent.ACTION_UP -> CallManager.stopDtmf()
                }
                false
            }
        }

        CallManager.addListener(listener)
        updateUi(CallManager.currentCall, CallManager.currentCall?.state ?: Call.STATE_NEW)
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        CallManager.removeListener(listener)
        super.onDestroy()
    }

    private var trackedCall: Call? = null
    private var isOutgoingCall = false

    private fun updateUi(call: Call?, state: Int) {
        if (call == null || state == Call.STATE_DISCONNECTED) {
            timerHandler.removeCallbacks(timerRunnable)
            finish(); return
        }

        // Xác định gọi đi/gọi đến MỘT LẦN DUY NHẤT khi nhận call, không tính lại theo state.
        // Trước đây dùng "state != STATE_RINGING" mỗi lần cập nhật UI: sau khi TRẢ LỜI một
        // cuộc gọi ĐẾN, trạng thái chuyển ACTIVE khiến điều kiện này hiểu nhầm thành gọi đi,
        // có thể hiển thị nhầm số của lần chuyển hướng trước đó thay vì số người gọi đến.
        if (call !== trackedCall) {
            trackedCall = call
            isOutgoingCall =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    call.details?.callDirection == Call.Details.DIRECTION_OUTGOING
                else
                    state != Call.STATE_RINGING
        }

        val number = if (isOutgoingCall && CallForwardManager.lastDisplayNumber.isNotEmpty())
            CallForwardManager.lastDisplayNumber
        else CallManager.callerNumber(call)

        // Tra cứu tên + ảnh trong danh bạ
        val contactInfo = lookupContact(number)
        val displayName = when {
            contactInfo != null -> contactInfo.first
            number.isNotEmpty() -> formatNumberForDisplay(number)
            else -> "Không xác định"
        }
        val photoUri = contactInfo?.second

        binding.tvCallerName.text = displayName
        if (contactInfo != null && number.isNotEmpty()) {
            binding.tvCallerNumber.text = formatNumberForDisplay(number)
            binding.tvCallerNumber.visibility = View.VISIBLE
        } else {
            binding.tvCallerNumber.visibility = View.GONE
        }

        // Avatar
        if (photoUri != null) {
            binding.ivContactPhoto.visibility = View.VISIBLE
            binding.ivContactPhoto.setImageURI(photoUri)
        } else {
            binding.ivContactPhoto.visibility = View.GONE
            val idx = Math.abs(displayName.hashCode()) % avatarBgs.size
            binding.avatarView.setBackgroundResource(R.drawable.bg_avatar)
            binding.avatarView.background.setTint(getColor(avatarBgs[idx]))
            binding.tvAvatarLetter.text = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
            binding.tvAvatarLetter.setTextColor(getColor(avatarTxts[idx]))
        }

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

    private fun formatNumberForDisplay(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return raw
        return if (raw.startsWith("+")) {
            when {
                digits.length <= 2 -> "+$digits"
                digits.length <= 5 -> "+${digits.take(2)} ${digits.drop(2)}"
                digits.length <= 8 -> "+${digits.take(2)} ${digits.drop(2).take(3)} ${digits.drop(5)}"
                else -> "+${digits.take(2)} ${digits.drop(2).take(3)} ${digits.drop(5).take(3)} ${digits.drop(8)}"
            }
        } else {
            when {
                digits.length <= 4 -> digits
                digits.length <= 7 -> "${digits.take(4)} ${digits.drop(4)}"
                else -> "${digits.take(4)} ${digits.drop(4).take(3)} ${digits.drop(7)}"
            }
        }
    }

    private fun lookupContact(number: String): Pair<String, android.net.Uri?>? {
        if (number.isEmpty()) return null
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(number)
        )
        val cursor = contentResolver.query(uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
                ContactsContract.PhoneLookup._ID),
            null, null, null) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return null
            val name = it.getString(0) ?: return null
            val photoUriStr = it.getString(1)
            val photoUri = if (photoUriStr != null) android.net.Uri.parse(photoUriStr) else null
            Pair(name, photoUri)
        }
    }

    private fun haptic() {
        val v = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(30)
    }
}
