package com.h.simplecall.ui

import android.content.Context
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.data.local.AppDatabase
import com.h.simplecall.data.local.toCallLogEntry
import com.h.simplecall.databinding.FragmentCallLogBinding

class CallLogFragment : Fragment() {

    private var _b: FragmentCallLogBinding? = null
    private val b get() = _b!!
    private var allEntries: List<CallLogEntry> = emptyList()
    private var isDualSim: Boolean = false
    private var showMissedOnly = false

    // Truy vấn CallLog CHẠY NỀN: trước đây chạy thẳng trên main thread mỗi khi mở tab này, và
    // vì không còn giới hạn LIMIT (đọc TOÀN BỘ lịch sử) nên máy có lịch sử cuộc gọi dài (hàng
    // nghìn dòng) dễ bị đơ/ANR lúc mở tab. Cùng nhóm lỗi đã sửa ở DialerFragment.
    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentCallLogBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.btnRecentsSettings.setOnClickListener {
            (activity as? MainActivity)?.openSettings()
        }
        b.btnRecentsSearch.setOnClickListener {
            android.widget.Toast.makeText(requireContext(), "Tìm kiếm đang được phát triển", android.widget.Toast.LENGTH_SHORT).show()
        }
        b.tabAll.setOnClickListener { selectTab(missed = false) }
        b.tabMissed.setOnClickListener { selectTab(missed = true) }

        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        isDualSim = callCapableAccountCount() >= 2
        val appContext = requireContext().applicationContext
        bgExecutor.execute {
            val loaded = loadCallLog(appContext)
            mainHandler.post {
                if (_b == null) return@post // fragment đã bị huỷ trong lúc chờ
                allEntries = loaded
                renderList()
                markMissedAsRead()
            }
        }
    }

    private fun callCapableAccountCount(): Int {
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_PHONE_STATE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return 0
        return try {
            requireContext().getSystemService(TelecomManager::class.java)
                ?.callCapablePhoneAccounts?.size ?: 0
        } catch (_: SecurityException) { 0 }
    }

    private fun selectTab(missed: Boolean) {
        showMissedOnly = missed
        val accent = ContextCompat.getColor(requireContext(), R.color.accent_blue)
        val bright = ContextCompat.getColor(requireContext(), R.color.text_primary)
        val secondary = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        val transparent = ContextCompat.getColor(requireContext(), android.R.color.transparent)
        b.tvTabAll.setTextColor(if (missed) secondary else bright)
        b.tvTabAll.setTypeface(null, if (missed) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
        b.tvTabMissed.setTextColor(if (missed) bright else secondary)
        b.tvTabMissed.setTypeface(null, if (missed) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        // Thanh gạch chân xanh dương luôn đi theo tab đang chọn (chữ chỉ sáng hơn, không đổi màu theo gạch chân)
        b.tabAllUnderline.setBackgroundColor(if (missed) transparent else accent)
        b.tabMissedUnderline.setBackgroundColor(if (missed) accent else transparent)
        renderList()
    }

    private fun renderList() {
        val entries = if (showMissedOnly)
            allEntries.filter { it.type == CallLog.Calls.MISSED_TYPE }
        else allEntries

        if (entries.isEmpty()) {
            b.tvEmpty.text = if (showMissedOnly) "Không có cuộc gọi nhỡ" else "Chưa có nhật ký cuộc gọi"
            b.tvEmpty.visibility = View.VISIBLE
            b.recyclerView.visibility = View.GONE
        } else {
            b.tvEmpty.visibility = View.GONE
            b.recyclerView.visibility = View.VISIBLE
            b.recyclerView.adapter = CallLogAdapter(
                entries,
                isDualSim = isDualSim,
                onCall = { (activity as? MainActivity)?.placeCall(it) },
                onShowHistory = { number ->
                    val entry = entries.firstOrNull { it.number == number }
                    val name = entry?.name ?: number
                    requireActivity().supportFragmentManager.beginTransaction()
                        .replace(com.h.simplecall.R.id.fragmentContainer,
                            CallHistoryFragment.newInstance(number, name))
                        .addToBackStack("history")
                        .commit()
                    (activity as? MainActivity)?.hideNav()
                }
            )
        }
    }

    /** Đọc TOÀN BỘ lịch sử từ DB nội bộ của app (không đụng tới CallLog provider hệ thống nữa).
     *  Số của mỗi dòng chính là số ĐÃ ĐƯỢC HIỂN THỊ TRÊN MÀN HÌNH GỌI tại thời điểm gọi
     *  (do CallHistoryManager ghi lại), không phải số nguyên bản do hệ thống tự log. */
    private fun loadCallLog(ctx: Context): List<CallLogEntry> =
        AppDatabase.getInstance(ctx).callHistoryDao().getAll().map { it.toCallLogEntry() }

    /** Đánh dấu các cuộc gọi nhỡ là "đã xem" trong DB nội bộ - thay cho việc cập nhật cờ
     *  CallLog.Calls.NEW của hệ thống trước đây. */
    private fun markMissedAsRead() {
        val appContext = requireContext().applicationContext
        bgExecutor.execute {
            AppDatabase.getInstance(appContext).callHistoryDao()
                .markMissedAsRead(CallLog.Calls.MISSED_TYPE)
        }
    }

    /** MainActivity gọi khi DialerFragment gõ số – ẩn header "Gần đây" + tab */
    fun setHeaderVisible(visible: Boolean) {
        _b?.llCallLogHeader?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        bgExecutor.shutdownNow()
        super.onDestroyView(); _b = null
    }
}
