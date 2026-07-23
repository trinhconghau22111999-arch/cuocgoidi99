package com.h.simplecall.ui

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CallLog
import android.provider.ContactsContract
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.data.Contact
import com.h.simplecall.databinding.FragmentDialerBinding

class DialerFragment : Fragment() {

    companion object {
        private val SUB_LABELS = mapOf(
            "2" to "ABC", "3" to "DEF", "4" to "GHI",
            "5" to "JKL", "6" to "MNO", "7" to "PQRS",
            "8" to "TUV", "9" to "WXYZ", "0" to "+"
        )
        private val DTMF_MAP = mapOf(
            "0" to ToneGenerator.TONE_DTMF_0, "1" to ToneGenerator.TONE_DTMF_1,
            "2" to ToneGenerator.TONE_DTMF_2, "3" to ToneGenerator.TONE_DTMF_3,
            "4" to ToneGenerator.TONE_DTMF_4, "5" to ToneGenerator.TONE_DTMF_5,
            "6" to ToneGenerator.TONE_DTMF_6, "7" to ToneGenerator.TONE_DTMF_7,
            "8" to ToneGenerator.TONE_DTMF_8, "9" to ToneGenerator.TONE_DTMF_9,
            "*" to ToneGenerator.TONE_DTMF_S, "#" to ToneGenerator.TONE_DTMF_P
        )

        fun newInstanceWithNumber(number: String?): DialerFragment {
            return DialerFragment().also {
                it.arguments = Bundle().apply { putString("number", number) }
            }
        }
    }

    private var _b: FragmentDialerBinding? = null
    private val b get() = _b!!
    private var toneGen: ToneGenerator? = null
    private lateinit var suggestAdapter: ContactSuggestAdapter
    private var keypadVisible = true
    private var pendingNumberToAdd: String = ""

    // "Thêm vào liên hệ hiện có": cho người dùng chọn 1 liên hệ có sẵn, sau đó mở màn
    // hình sửa liên hệ đó với số điện thoại đang gõ được điền sẵn để họ lưu thêm số này.
    private val pickContactLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickContact()
    ) { contactUri ->
        if (contactUri == null) return@registerForActivityResult
        try {
            startActivity(Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(contactUri, android.provider.ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, pendingNumberToAdd)
                putExtra("finishActivityOnSaveCompleted", true)
            })
        } catch (_: Exception) {
            android.widget.Toast.makeText(requireContext(), "Không thể mở màn hình sửa liên hệ", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentDialerBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try { toneGen = ToneGenerator(AudioManager.STREAM_DTMF, 80) } catch (_: Exception) {}

        // Suggestions RecyclerView (hiện khi đang gõ số để tìm liên hệ khớp)
        suggestAdapter = ContactSuggestAdapter { number ->
            (activity as? MainActivity)?.placeCall(number)
        }
        b.rvSuggestions.layoutManager = LinearLayoutManager(requireContext())
        b.rvSuggestions.adapter = suggestAdapter

        // Danh sách "Gần đây" mặc định, dữ liệu thật đầy đủ từ Nhật ký cuộc gọi trên máy
        b.rvRecents.layoutManager = LinearLayoutManager(requireContext())
        loadRecents()

        arguments?.getString("number")?.let { b.etNumber.setText(it) }

        setupKeypad(view)

        // Backspace
        b.btnBackspace.setOnClickListener {
            val t = b.etNumber.text.toString()
            if (t.isNotEmpty()) b.etNumber.setText(t.dropLast(1))
            syncBackspace()
        }
        b.btnBackspace.setOnLongClickListener {
            b.etNumber.setText(""); syncBackspace(); true
        }

        // Format + suggestions
        b.etNumber.addTextChangedListener(object : TextWatcher {
            private var editing = false
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (editing) return; editing = true
                val raw = s.toString().filter { it.isDigit() || it == '+' }
                val fmt = formatVN(raw)
                if (fmt != s.toString()) {
                    b.etNumber.setText(fmt)
                    b.etNumber.setSelection(fmt.length)
                }
                syncBackspace()
                searchSuggestions(raw)
                editing = false
            }
        })

        // Nút gọi: gọi lại nếu rỗng
        b.btnCall.setOnClickListener {
            val raw = b.etNumber.text.toString().filter { it.isDigit() || it == '+' }
            if (raw.isNotEmpty()) {
                (activity as? MainActivity)?.placeCall(raw)
            } else {
                // Gọi lại số cuối cùng
                val last = getLastCalledNumber()
                if (last != null) {
                    b.etNumber.setText(formatVN(last))
                    b.etNumber.setSelection(b.etNumber.text.length)
                    syncBackspace()
                }
            }
        }

        // Gọi video: tính năng chưa được hỗ trợ, thông báo cho người dùng biết thay vì im lặng.
        b.btnVideoCall.setOnClickListener {
            android.widget.Toast.makeText(requireContext(),
                getString(R.string.video_call_unsupported), android.widget.Toast.LENGTH_SHORT).show()
        }

        // Menu hành động khi số đang gõ không khớp liên hệ nào
        b.rowCreateContact.setOnClickListener {
            val raw = b.etNumber.text.toString().filter { it.isDigit() || it == '+' }
            try {
                startActivity(Intent(Intent.ACTION_INSERT, android.provider.ContactsContract.Contacts.CONTENT_URI)
                    .putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, raw))
            } catch (_: Exception) {
                android.widget.Toast.makeText(requireContext(), "Không thể mở màn hình tạo liên hệ", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        b.rowAddToExisting.setOnClickListener {
            pendingNumberToAdd = b.etNumber.text.toString().filter { it.isDigit() || it == '+' }
            try {
                pickContactLauncher.launch(null)
            } catch (_: Exception) {
                android.widget.Toast.makeText(requireContext(), "Không thể chọn liên hệ", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        b.rowSendSms.setOnClickListener {
            val raw = b.etNumber.text.toString().filter { it.isDigit() || it == '+' }
            try {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$raw")))
            } catch (_: Exception) {
                android.widget.Toast.makeText(requireContext(), "Không tìm thấy ứng dụng nhắn tin", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        b.rowVideoMeet.setOnClickListener {
            // Chưa tích hợp Google Meet thật; thông báo rõ cho người dùng thay vì im lặng.
            android.widget.Toast.makeText(requireContext(),
                getString(R.string.video_call_unsupported), android.widget.Toast.LENGTH_SHORT).show()
        }

        // Ẩn/hiện bàn phím số để xem trọn danh sách Gần đây / gợi ý liên hệ khi cần.
        // Dùng GONE (không phải INVISIBLE) để danh sách phía trên thực sự giãn ra
        // chiếm khoảng trống đó, thay vì để lại một vùng trống vô ích.
        b.btnKeypadToggle.setOnClickListener {
            keypadVisible = !keypadVisible
            b.keypad.visibility = if (keypadVisible) View.VISIBLE else View.GONE
        }

        syncBackspace()
    }

    private fun setupKeypad(view: View) {
        val grid = view.findViewById<GridLayout>(R.id.keypad)
        for (i in 0 until grid.childCount) {
            val btn = grid.getChildAt(i) as? Button ?: continue
            val tag = btn.tag as? String ?: continue

            // Sub-labels
            val sub = SUB_LABELS[tag]
            if (sub != null) {
                val ss = SpannableStringBuilder()
                ss.append(tag); ss.append("\n")
                val subStart = ss.length; ss.append(sub)
                ss.setSpan(RelativeSizeSpan(0.38f), subStart, ss.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                ss.setSpan(ForegroundColorSpan(requireContext().getColor(R.color.text_secondary)),
                    subStart, ss.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                btn.text = ss; btn.setLines(2); btn.textSize = 27f
            }

            // Phím 1: voicemail icon text nhỏ
            if (tag == "1") {
                val ss = SpannableStringBuilder()
                ss.append("1"); ss.append("\n")
                val sub2Start = ss.length; ss.append("☎")
                ss.setSpan(RelativeSizeSpan(0.38f), sub2Start, ss.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                ss.setSpan(ForegroundColorSpan(requireContext().getColor(R.color.text_secondary)),
                    sub2Start, ss.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                btn.text = ss; btn.setLines(2); btn.textSize = 27f
            }

            btn.setOnClickListener {
                appendDigit(tag)
                haptic()
                toneGen?.startTone(DTMF_MAP[tag] ?: ToneGenerator.TONE_DTMF_0, 120)
            }

            if (tag == "0") {
                btn.setOnLongClickListener {
                    val cur = b.etNumber.text.toString()
                    val raw = cur.filter { it.isDigit() || it == '+' }
                    val newRaw = if (raw.endsWith("0")) raw.dropLast(1) + "+" else raw + "+"
                    b.etNumber.setText(formatVN(newRaw))
                    b.etNumber.setSelection(b.etNumber.text.length)
                    syncBackspace(); true
                }
            }
        }
    }

    private fun appendDigit(d: String) {
        val cur = b.etNumber.text.toString()
        val raw = cur.filter { it.isDigit() || it == '+' } + d
        b.etNumber.setText(formatVN(raw))
        b.etNumber.setSelection(b.etNumber.text.length)
        syncBackspace()
    }

    private fun formatVN(raw: String): String {
        if (raw.isEmpty()) return raw
        val digits = raw.filter { it.isDigit() }
        return when {
            raw.startsWith("+") -> when {
                digits.length <= 2  -> "+$digits"
                digits.length <= 5  -> "+${digits.take(2)} ${digits.drop(2)}"
                digits.length <= 8  -> "+${digits.take(2)} ${digits.drop(2).take(3)} ${digits.drop(5)}"
                else -> "+${digits.take(2)} ${digits.drop(2).take(3)} ${digits.drop(5).take(3)} ${digits.drop(8)}"
            }
            digits.length <= 4  -> digits
            digits.length <= 7  -> "${digits.take(4)} ${digits.drop(4)}"
            else                -> "${digits.take(4)} ${digits.drop(4).take(3)} ${digits.drop(7)}"
        }
    }

    private fun syncBackspace() {
        _b?.btnBackspace?.visibility =
            if (b.etNumber.text.isNotEmpty()) View.VISIBLE else View.INVISIBLE
    }

    /** Nạp đầy đủ Nhật ký cuộc gọi thật trên máy để hiển thị mặc định phía trên bàn phím,
     *  giống màn hình Bàn phím của trình quay số hệ thống. Không giới hạn số lượng bản ghi. */
    private fun loadRecents() {
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_CALL_LOG)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            b.rvRecents.visibility = View.GONE
            return
        }
        val entries = mutableListOf<CallLogEntry>()
        try {
            val cur = requireContext().contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE, CallLog.Calls.TYPE),
                null, null, "${CallLog.Calls.DATE} DESC"
            )
            cur?.use {
                val iName = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iNum  = it.getColumnIndex(CallLog.Calls.NUMBER)
                val iDate = it.getColumnIndex(CallLog.Calls.DATE)
                val iType = it.getColumnIndex(CallLog.Calls.TYPE)
                while (it.moveToNext()) {
                    entries.add(CallLogEntry(
                        name = it.getString(iName) ?: "",
                        number = it.getString(iNum) ?: "",
                        date = it.getLong(iDate),
                        type = it.getInt(iType)
                    ))
                }
            }
        } catch (_: SecurityException) {}

        b.rvRecents.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        b.rvRecents.adapter = CallLogAdapter(
            entries,
            onCall = { (activity as? MainActivity)?.placeCall(it) },
            onShowHistory = { number ->
                val entry = entries.firstOrNull { it.number == number }
                val name = entry?.name ?: number
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, CallHistoryFragment.newInstance(number, name))
                    .addToBackStack("history")
                    .commit()
                (activity as? MainActivity)?.hideNav()
            }
        )
    }

    private fun searchSuggestions(raw: String) {
        if (raw.length < 2) {
            b.rvSuggestions.visibility = View.GONE
            b.llNoMatchActions.visibility = View.GONE
            b.rvRecents.visibility = if ((b.rvRecents.adapter?.itemCount ?: 0) > 0) View.VISIBLE else View.GONE
            return
        }
        b.rvRecents.visibility = View.GONE
        val list = mutableListOf<Contact>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val cur: Cursor = requireContext().contentResolver.query(uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                arrayOf("%$raw%"), null)
            cur?.use {
                val iName = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val iNum  = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                // Không còn giới hạn 5 kết quả: trả về toàn bộ liên hệ khớp trên máy.
                while (it.moveToNext()) {
                    list.add(Contact(it.getString(iName) ?: "", it.getString(iNum) ?: ""))
                }
            }
        } catch (_: Exception) {}

        if (list.isEmpty()) {
            // Không tìm thấy liên hệ nào khớp: hiện menu tạo liên hệ / thêm vào liên hệ có
            // sẵn / gửi SMS / gọi video, giống trình quay số hệ thống.
            b.rvSuggestions.visibility = View.GONE
            b.llNoMatchActions.visibility = View.VISIBLE
        } else {
            suggestAdapter.update(list, raw)
            b.rvSuggestions.visibility = View.VISIBLE
            b.llNoMatchActions.visibility = View.GONE
        }
    }

    private fun getLastCalledNumber(): String? {
        return try {
            val cur = requireContext().contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                null, null,
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            ) ?: return null
            cur.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null }
    }

    private fun haptic() {
        val v = requireContext().getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(25)
    }

    override fun onDestroyView() {
        toneGen?.release(); toneGen = null
        super.onDestroyView(); _b = null
    }
}
