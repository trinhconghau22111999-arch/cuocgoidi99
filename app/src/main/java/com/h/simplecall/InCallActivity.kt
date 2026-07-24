package com.h.simplecall

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.telecom.Call
import android.telephony.SubscriptionManager
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.h.simplecall.call.CallForwardManager
import com.h.simplecall.call.CallManager
import com.h.simplecall.databinding.ActivityInCallBinding

class InCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInCallBinding
    private var isMuted   = false
    private var isSpeaker = false
    private var dtmfVisible = false
    private var isHeld = false
    private var isRecording = false
    private var isClarityOn = false
    private var recorder: MediaRecorder? = null
    private var recordingFile: java.io.File? = null
    private var noiseSuppressor: NoiseSuppressor? = null

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

        // Giữ máy: dùng đúng API Telecom (call.hold()/unhold()), không phải giả lập UI
        binding.btnHold.setOnClickListener {
            isHeld = !isHeld
            if (isHeld) CallManager.currentCall?.hold() else CallManager.currentCall?.unhold()
            binding.btnHold.setBackgroundResource(
                if (isHeld) R.drawable.bg_action_circle_active else R.drawable.bg_action_circle)
            binding.tvHoldLabel.text = getString(if (isHeld) R.string.unhold_call else R.string.hold_call)
        }

        binding.btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        // "Gọi rõ ràng": khử tiếng ồn bằng AudioEffect chuẩn của Android (NoiseSuppressor).
        // LƯU Ý: đây không phải công nghệ tăng cường giọng nói độc quyền như máy Samsung thật -
        // hiệu quả tuỳ thuộc chip xử lý âm thanh của từng máy, và trên nhiều thiết bị hầu như
        // không có tác dụng rõ rệt với đường tiếng của cuộc gọi (đường tiếng cuộc gọi thường đi
        // qua phần cứng modem, ứng dụng thường không can thiệp trực tiếp được).
        binding.btnClarity.setOnClickListener {
            isClarityOn = !isClarityOn
            try {
                if (isClarityOn) {
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(0)
                        noiseSuppressor?.enabled = true
                    } else {
                        Toast.makeText(this, "Máy không hỗ trợ khử tiếng ồn", Toast.LENGTH_SHORT).show()
                        isClarityOn = false
                    }
                } else {
                    noiseSuppressor?.release(); noiseSuppressor = null
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Máy không hỗ trợ khử tiếng ồn", Toast.LENGTH_SHORT).show()
                isClarityOn = false
            }
            binding.btnClarity.setBackgroundResource(
                if (isClarityOn) R.drawable.bg_action_circle_active else R.drawable.bg_action_circle)
        }

        // "Thêm cuộc gọi" (ghép cuộc gọi thứ 2/hội nghị) và "Thêm" (tuỳ chọn khác) chưa được
        // xây dựng đầy đủ - app hiện chỉ quản lý 1 cuộc gọi tại một thời điểm (CallManager chỉ
        // giữ currentCall duy nhất), nên chưa thể ghép/giữ nhiều cuộc gọi cùng lúc một cách an
        // toàn. Thông báo rõ cho người dùng thay vì giả vờ hoạt động.
        binding.btnAddCall.setOnClickListener {
            Toast.makeText(this, getString(R.string.feature_coming_soon), Toast.LENGTH_SHORT).show()
        }
        binding.btnMore.setOnClickListener {
            Toast.makeText(this, getString(R.string.feature_coming_soon), Toast.LENGTH_SHORT).show()
        }

        CallManager.addListener(listener)
        updateUi(CallManager.currentCall, CallManager.currentCall?.state ?: Call.STATE_NEW)
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        CallManager.removeListener(listener)
        contactLookupExecutor.shutdownNow()
        if (isRecording) stopRecording()
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.recording_failed), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = java.io.File(getExternalFilesDir(null), "CallRecordings").apply { mkdirs() }
            val file = java.io.File(dir, "call_${System.currentTimeMillis()}.m4a")
            val rec = MediaRecorder()
            // VOICE_CALL ghi được cả 2 chiều tiếng trên một số máy, nhưng nhiều hãng/ROM
            // (đặc biệt Android 10 trở lên) CHẶN nguồn ghi âm này vì lý do riêng tư của người
            // gọi tới. Nếu không dùng được, thử lại bằng MIC (chỉ ghi được giọng người dùng).
            try {
                rec.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
            } catch (_: Exception) {
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordingFile = file
            isRecording = true
            binding.btnRecord.setBackgroundResource(R.drawable.bg_action_circle_active)
            binding.tvRecordLabel.text = getString(R.string.stop_recording)
        } catch (e: Exception) {
            // Rất nhiều máy (đặc biệt Samsung/Xiaomi các đời mới) chặn hẳn việc ghi âm cuộc
            // gọi ở tầng hệ thống bất kể quyền đã cấp - báo rõ cho người dùng thay vì im lặng.
            Toast.makeText(this, getString(R.string.recording_failed), Toast.LENGTH_LONG).show()
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null; isRecording = false
        }
    }

    private fun stopRecording() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        isRecording = false
        binding.btnRecord.setBackgroundResource(R.drawable.bg_action_circle)
        binding.tvRecordLabel.text = getString(R.string.start_recording)
        recordingFile?.let {
            Toast.makeText(this, "${getString(R.string.recording_saved)}: ${it.name}", Toast.LENGTH_LONG).show()
        }
    }

    private var trackedCall: Call? = null
    private var isOutgoingCall = false

    // Truy vấn danh bạ chạy nền: tránh block main thread (nguyên nhân gây ANR "Gọi Điện tiếp tục dừng")
    private val contactLookupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastLookedUpNumber: String? = null
    private var lastContactInfo: Pair<String, android.net.Uri?>? = null

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

        // Tra cứu tên + ảnh trong danh bạ CHẠY NỀN (contentResolver.query có thể chậm,
        // không được gọi trên main thread vì sẽ gây treo/ANR). Kết quả được cache theo số
        // để tránh truy vấn lại mỗi lần trạng thái cuộc gọi đổi (ringing -> active -> ...).
        if (number != lastLookedUpNumber) {
            lastLookedUpNumber = number
            lastContactInfo = null
            renderCallerInfo(number, null)
            contactLookupExecutor.execute {
                val info = lookupContact(number)
                mainHandler.post {
                    // Bỏ qua nếu số đã thay đổi trong lúc chờ (ví dụ chuyển sang cuộc gọi khác)
                    if (number == lastLookedUpNumber) {
                        lastContactInfo = info
                        renderCallerInfo(number, info)
                    }
                }
            }
        } else {
            renderCallerInfo(number, lastContactInfo)
        }

        val isRinging = state == Call.STATE_RINGING
        binding.incomingControls.visibility = if (isRinging) View.VISIBLE else View.GONE
        binding.activeControls.visibility   = if (isRinging) View.GONE   else View.VISIBLE

        if (call !== trackedCall || binding.llSimLine.tag != call) {
            binding.llSimLine.tag = call
            renderSimLine(call)
        }

        when (state) {
            Call.STATE_RINGING -> {
                timerHandler.removeCallbacks(timerRunnable)
                binding.tvCallStatus.text = getString(R.string.incoming_call)
                binding.tvHdBadge.visibility = View.GONE
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                timerHandler.removeCallbacks(timerRunnable)
                binding.tvCallStatus.text = "Đang gọi..."
                binding.tvHdBadge.visibility = View.GONE
            }
            Call.STATE_ACTIVE -> {
                isHeld = false
                binding.btnHold.setBackgroundResource(R.drawable.bg_action_circle)
                binding.tvHoldLabel.text = getString(R.string.hold_call)
                binding.tvHdBadge.visibility = View.VISIBLE
                if (callStartMs == 0L) {
                    callStartMs = System.currentTimeMillis()
                    timerHandler.post(timerRunnable)
                }
            }
            Call.STATE_HOLDING -> {
                isHeld = true
                binding.btnHold.setBackgroundResource(R.drawable.bg_action_circle_active)
                binding.tvHoldLabel.text = getString(R.string.unhold_call)
                timerHandler.removeCallbacks(timerRunnable)
                binding.tvCallStatus.text = "Đang giữ máy"
                binding.tvHdBadge.visibility = View.GONE
            }
            Call.STATE_DISCONNECTING -> {
                timerHandler.removeCallbacks(timerRunnable)
                binding.tvCallStatus.text = "Đang kết thúc..."
            }
        }
    }

    /** Hiện dòng "[số SIM] Tên nhà mạng/quốc gia" giống trình quay số hệ thống, dựa vào
     *  PhoneAccountHandle của cuộc gọi. Máy 1 SIM hoặc không tra được thì ẩn dòng này đi. */
    private fun renderSimLine(call: Call) {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) { binding.llSimLine.visibility = View.GONE; return }
            val subId = call.details?.accountHandle?.id?.toIntOrNull()
            val info = if (subId != null)
                getSystemService(SubscriptionManager::class.java)?.getActiveSubscriptionInfo(subId)
            else null
            if (info != null) {
                binding.llSimLine.visibility = View.VISIBLE
                binding.tvSimBadge.text = (info.simSlotIndex + 1).toString()
                binding.tvCarrier.text = info.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "Việt Nam"
            } else {
                binding.llSimLine.visibility = View.GONE
            }
        } catch (_: Exception) {
            binding.llSimLine.visibility = View.GONE
        }
    }

    private fun renderCallerInfo(number: String, contactInfo: Pair<String, android.net.Uri?>?) {
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
