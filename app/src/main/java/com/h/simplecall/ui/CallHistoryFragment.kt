package com.h.simplecall.ui

import android.os.Bundle
import android.provider.CallLog
import android.telephony.SubscriptionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.databinding.FragmentCallHistoryBinding
import com.h.simplecall.databinding.ItemCallHistoryEntryBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * Màn "chi tiết liên hệ / chi tiết số điện thoại", mở ra khi bấm icon info trên 1 dòng
 * ở Gần đây. Dựng lại 100% theo ảnh mẫu người dùng gửi: avatar tròn, tên lớn, dòng SIM
 * mặc định, thẻ số điện thoại + Zalo + Xem thêm, thẻ Meet, thẻ Tóm tắt/Bản ghi âm cuộc gọi,
 * và danh sách Nhật ký cuộc gọi của riêng số này.
 */
class CallHistoryFragment : Fragment() {

    companion object {
        fun newInstance(number: String, name: String) = CallHistoryFragment().also {
            it.arguments = Bundle().apply {
                putString("number", number)
                putString("name", name)
            }
        }
    }

    private var _b: FragmentCallHistoryBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentCallHistoryBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val number = arguments?.getString("number") ?: ""
        val name   = arguments?.getString("name") ?: number
        val display = name.ifBlank { number }

        // ── Header: avatar tròn (chữ cái đầu) + tên + số ──
        b.tvAvatar.text = display.take(1).uppercase()
        // Nếu không có tên liên hệ (chỉ là 1 số lạ) thì số lớn phía trên PHẢI cách nhóm 3-3-2-2
        // giống số trong thẻ phía dưới, ví dụ "090 130 08 36" - đúng ảnh mẫu người dùng gửi.
        b.tvTitle.text = if (name.isBlank()) formatNumberGrouped(number) else display
        // Đọc SIM mặc định cho cuộc gọi từ hệ thống
        val defaultSimSlot: Int = try {
            if (android.content.pm.PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(
                    requireContext(), android.Manifest.permission.READ_PHONE_STATE)) {
                val sm = requireContext().getSystemService(SubscriptionManager::class.java)
                val defaultSubId = SubscriptionManager.getDefaultVoiceSubscriptionId()
                val info = sm?.getActiveSubscriptionInfo(defaultSubId)
                (info?.simSlotIndex ?: 0) + 1
            } else 1
        } catch (_: Exception) { 1 }

        b.tvSubtitle.text = getString(R.string.default_sim_call, defaultSimSlot)
        b.tvSimBadge.text = defaultSimSlot.toString()
        // Số SIM trên icon gọi
        b.tvCallSimNum.text = defaultSimSlot.toString()

        b.tvNumber.text = formatNumberGrouped(number)
        val digitsOnly = number.filter { it.isDigit() }
        val nationalNumber = if (digitsOnly.startsWith("0")) digitsOnly.drop(1) else digitsOnly
        b.tvZalo.text = getString(R.string.zalo_call_with_number, nationalNumber)

        // ── Nút back / edit / thẻ liên hệ / thêm ──
        b.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        b.btnEdit.setOnClickListener { /* TODO: mở màn sửa liên hệ khi có */ }
        b.btnContactCard.setOnClickListener { /* TODO: mở thẻ liên hệ đầy đủ khi có */ }
        b.btnMore.setOnClickListener { /* TODO: menu 3 chấm (thêm vào danh bạ, chặn, v.v.) */ }

        // ── Hàng hành động trên thẻ số: gọi / nhắn tin / video ──
        b.btnCallRow.setOnClickListener { (activity as? MainActivity)?.placeCall(number) }
        b.btnMessageRow.setOnClickListener { openSms(number) }
        b.btnVideoRow.setOnClickListener { (activity as? MainActivity)?.placeCall(number) }
        b.rowZalo.setOnClickListener { /* TODO: mở Zalo nếu app cài trên máy */ }
        b.rowSeeMore.setOnClickListener { /* TODO: mở rộng thêm thông tin liên hệ */ }
        b.rowMeet.setOnClickListener { /* TODO: tích hợp Meet khi có */ }
        b.rowCallSummary.setOnClickListener { /* TODO: tóm tắt cuộc gọi (AI) khi có */ }
        b.rowCallRecording.setOnClickListener { /* TODO: bản ghi âm cuộc gọi khi có */ }

        val entries = loadHistory(number)
        b.btnClearLog.setOnClickListener { clearHistory(number, entries) }
        renderEntries(entries)
    }

    private fun openSms(number: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO)
        intent.data = android.net.Uri.parse("smsto:$number")
        runCatching { startActivity(intent) }
    }

    /** Dựng danh sách "Nhật ký cuộc gọi" bằng tay (không RecyclerView) vì đã nằm trong 1
     *  ScrollView chung của toàn màn hình - tránh xung đột cuộn lồng nhau. */
    private fun renderEntries(entries: List<CallLogEntry>) {
        val container = b.llHistoryEntries
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        entries.forEachIndexed { index, item ->
            val rowBinding = ItemCallHistoryEntryBinding.inflate(inflater, container, false)
            bindEntry(rowBinding, item)
            container.addView(rowBinding.root)

            if (index != entries.lastIndex) {
                val divider = View(requireContext())
                val dividerHeightPx = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                divider.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dividerHeightPx
                )
                divider.setBackgroundColor(resources.getColor(R.color.divider, requireContext().theme))
                container.addView(divider)
            }
        }
    }

    private fun bindEntry(rb: ItemCallHistoryEntryBinding, item: CallLogEntry) {
        val ctx = requireContext()
        val isMissed = item.type == CallLog.Calls.MISSED_TYPE
        val isOutgoing = item.type == CallLog.Calls.OUTGOING_TYPE

        val (label, iconRes) = when {
            isMissed -> getString(R.string.call_type_missed) to R.drawable.ic_call_missed
            isOutgoing -> getString(R.string.call_type_outgoing) to R.drawable.ic_call_outgoing
            else -> getString(R.string.call_type_incoming) to R.drawable.ic_call_incoming
        }
        rb.tvEntryLabel.text = label
        rb.tvEntryLabel.setTextColor(
            ctx.getColor(if (isMissed) R.color.missed_red else R.color.text_primary)
        )
        rb.ivEntryType.setImageResource(iconRes)

        // Giờ:phút bắt đầu cuộc gọi
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFmt.format(Date(item.date))

        // Format status theo hình mẫu
        rb.tvEntryStatus.text = when {
            isMissed -> {
                // Nhỡ: đổ chuông = duration nếu > 0, không thì 0
                val ring = item.duration
                if (ring > 0) "$timeStr  (Đổ chuông trong ${ring}giây)"
                else "$timeStr  (Đổ chuông trong 1 giây)"
            }
            item.duration <= 0 -> "$timeStr  Chưa được kết nối"
            else -> "$timeStr  (${formatDurationVi(item.duration)})"
        }

        // Màu đỏ cho nhỡ
        rb.tvEntryStatus.setTextColor(
            requireContext().getColor(if (isMissed) R.color.missed_red else R.color.text_secondary)
        )

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val cal = Calendar.getInstance().apply { timeInMillis = item.date }
        rb.tvEntryDate.text = if (cal.after(today))
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.date))
        else
            SimpleDateFormat("d/M", Locale.getDefault()).format(Date(item.date))

        rb.root.setOnClickListener { (activity as? MainActivity)?.placeCall(item.number) }
    }

    /** Cách nhóm số điện thoại theo 3-3-2-2, ví dụ "0901300836" -> "090 130 08 36",
     *  đúng định dạng hiển thị trong ảnh mẫu. Giữ dấu "+" đầu số (nếu có) đứng riêng,
     *  không tính vào phần chia nhóm. Số dài hơn 10 chữ số thì phần dư được gộp vào nhóm cuối. */
    private fun formatNumberGrouped(raw: String): String {
        val hasPlus = raw.trimStart().startsWith("+")
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return raw
        val groups = mutableListOf<String>()
        var i = 0
        for (size in intArrayOf(3, 3, 2, 2)) {
            if (i >= digits.length) break
            val end = (i + size).coerceAtMost(digits.length)
            groups.add(digits.substring(i, end))
            i = end
        }
        if (i < digits.length) groups.add(digits.substring(i))
        return (if (hasPlus) "+" else "") + groups.joinToString(" ")
    }

    private fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", m, s)
    }

    private fun formatDurationVi(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m > 0 && s > 0 -> "${m}phút ${s}giây"
            m > 0 -> "${m}phút"
            else -> "${s}giây"
        }
    }

    private fun clearHistory(number: String, entries: List<CallLogEntry>) {
        // Xóa nhật ký cuộc gọi của riêng số này khỏi CallLog hệ thống.
        val clean = number.filter { it.isDigit() }
        runCatching {
            requireContext().contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls.NUMBER} LIKE ?",
                arrayOf("%$clean%")
            )
        }
        b.llHistoryEntries.removeAllViews()
    }

    private fun loadHistory(number: String): List<CallLogEntry> {
        val list = mutableListOf<CallLogEntry>()
        val clean = number.filter { it.isDigit() }
        val cursor = requireContext().contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER,
                CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
            "${CallLog.Calls.NUMBER} LIKE ?",
            arrayOf("%$clean%"),
            "${CallLog.Calls.DATE} DESC"
        ) ?: return list
        cursor.use {
            val iName = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val iNum  = it.getColumnIndex(CallLog.Calls.NUMBER)
            val iDate = it.getColumnIndex(CallLog.Calls.DATE)
            val iType = it.getColumnIndex(CallLog.Calls.TYPE)
            val iDur  = it.getColumnIndex(CallLog.Calls.DURATION)
            while (it.moveToNext()) {
                list.add(CallLogEntry(
                    name = it.getString(iName) ?: "",
                    number = it.getString(iNum) ?: "",
                    date = it.getLong(iDate),
                    type = it.getInt(iType),
                    duration = if (iDur >= 0) it.getLong(iDur) else 0
                ))
            }
        }
        return list
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
