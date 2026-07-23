package com.h.simplecall.ui

import android.content.ContentValues
import android.os.Bundle
import android.provider.CallLog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.h.simplecall.MainActivity
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.databinding.FragmentCallLogBinding

class CallLogFragment : Fragment() {

    private var _b: FragmentCallLogBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentCallLogBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_CALL_LOG)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            b.tvEmpty.text = "Chưa cấp quyền đọc nhật ký"
            b.tvEmpty.visibility = View.VISIBLE; return
        }

        val entries = loadCallLog()
        if (entries.isEmpty()) {
            b.tvEmpty.visibility = View.VISIBLE
            b.recyclerView.visibility = View.GONE
        } else {
            b.tvEmpty.visibility = View.GONE
            b.recyclerView.visibility = View.VISIBLE
            b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
            b.recyclerView.adapter = CallLogAdapter(
                entries,
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
            markMissedAsRead()
        }
    }

    private fun loadCallLog(): List<CallLogEntry> {
        val list = mutableListOf<CallLogEntry>()
        try {
            val cursor = requireContext().contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE, CallLog.Calls.TYPE),
                null, null, "${CallLog.Calls.DATE} DESC"
            ) ?: return list
            cursor.use {
                val iName = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iNum  = it.getColumnIndex(CallLog.Calls.NUMBER)
                val iDate = it.getColumnIndex(CallLog.Calls.DATE)
                val iType = it.getColumnIndex(CallLog.Calls.TYPE)
                while (it.moveToNext()) {
                    list.add(CallLogEntry(
                        name = it.getString(iName) ?: "",
                        number = it.getString(iNum) ?: "",
                        date = it.getLong(iDate),
                        type = it.getInt(iType)
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

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
