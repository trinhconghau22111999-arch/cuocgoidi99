package com.h.simplecall.ui

import android.os.Bundle
import android.provider.CallLog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.h.simplecall.MainActivity
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.databinding.FragmentCallHistoryBinding

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

        b.tvTitle.text = name
        b.tvSubtitle.text = number

        b.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        b.btnCallTop.setOnClickListener {
            (activity as? MainActivity)?.placeCall(number)
        }

        val entries = loadHistory(number)
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerView.adapter = CallLogAdapter(entries,
            onCall = { (activity as? MainActivity)?.placeCall(it) },
            onShowHistory = {}
        )
    }

    private fun loadHistory(number: String): List<CallLogEntry> {
        val list = mutableListOf<CallLogEntry>()
        val clean = number.filter { it.isDigit() }
        val cursor = requireContext().contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER,
                CallLog.Calls.DATE, CallLog.Calls.TYPE),
            "${CallLog.Calls.NUMBER} LIKE ?",
            arrayOf("%$clean%"),
            "${CallLog.Calls.DATE} DESC"
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
        return list
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
