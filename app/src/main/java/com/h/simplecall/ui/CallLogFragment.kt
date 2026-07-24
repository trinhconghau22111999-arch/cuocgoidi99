package com.h.simplecall.ui

import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.data.CallLogEntry
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

        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_CALL_LOG)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            b.tvEmpty.text = "Chưa cấp quyền đọc nhật ký"
            b.tvEmpty.visibility = View.VISIBLE; return
        }

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

    private fun loadCallLog(ctx: Context): List<CallLogEntry> {
        val list = mutableListOf<CallLogEntry>()
        try {
            val cursor = ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.PHONE_ACCOUNT_ID),
                null, null, "${CallLog.Calls.DATE} DESC"
            ) ?: return list
            cursor.use {
                val iName = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iNum  = it.getColumnIndex(CallLog.Calls.NUMBER)
                val iDate = it.getColumnIndex(CallLog.Calls.DATE)
                val iType = it.getColumnIndex(CallLog.Calls.TYPE)
                val iAcct = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                while (it.moveToNext()) {
                    val name = it.getString(iName) ?: ""
                    // Số đã lưu trong danh bạ (có CACHED_NAME) hiện "Di động";
                    // số lạ chưa lưu hiện quốc gia (giống danh bạ điện thoại thật).
                    val numberType = if (name.isNotEmpty()) "Di động" else "Việt Nam"
                    // Xác định SIM slot qua SubscriptionManager, giống DialerFragment, để khung
                    // số SIM cũng hiện đúng ở tab Gần đây chính (trước đây luôn bị bỏ trống).
                    val acctId = if (iAcct >= 0) it.getString(iAcct) ?: "" else ""
                    val simSlot: Int? = try {
                        val subId = acctId.toIntOrNull()
                        if (subId != null) {
                            val sm = ctx.getSystemService(SubscriptionManager::class.java)
                            sm?.getActiveSubscriptionInfo(subId)?.simSlotIndex?.takeIf { idx -> idx >= 0 }
                        } else null
                    } catch (_: Exception) { null }
                    list.add(CallLogEntry(
                        name = name,
                        number = it.getString(iNum) ?: "",
                        date = it.getLong(iDate),
                        type = it.getInt(iType),
                        simSlot = simSlot,
                        numberType = numberType
                    ))
                }
            }
        } catch (_: SecurityException) { }
        return list
    }

    private fun markMissedAsRead() {
        // WRITE_CALL_LOG là quyền bắt buộc riêng cho update(); nếu thiếu (hoặc bị hệ thống
        // thu hồi) mà không kiểm tra trước, contentResolver.update() sẽ ném SecurityException
        // và làm app crash ngay khi mở tab Nhật ký. Kiểm tra trước + try/catch phòng hờ.
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.WRITE_CALL_LOG)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            val cv = ContentValues().apply { put(CallLog.Calls.NEW, 0) }
            requireContext().contentResolver.update(
                CallLog.Calls.CONTENT_URI, cv,
                "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE} AND ${CallLog.Calls.NEW} = 1",
                null
            )
        } catch (_: SecurityException) {
            // Một số ROM (Xiaomi/Huawei...) vẫn từ chối dù đã cấp quyền; bỏ qua an toàn.
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
