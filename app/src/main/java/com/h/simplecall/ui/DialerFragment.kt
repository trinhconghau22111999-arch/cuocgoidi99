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
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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
    private var allRecentEntries: List<CallLogEntry> = emptyList()
    private var showMissedOnly = false
    // Truy vấn CallLog/Contacts CHẠY NỀN: trước đây chạy thẳng trên main thread mỗi khi mở màn
    // hình này (onViewCreated + onResume) và mỗi lần gõ số (searchSuggestions), gây lag/giật khi
    // bật bàn phím lên và trong lúc gõ — cùng nhóm lỗi ANR đã sửa ở các màn hình khác.
    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // "Phiên bản" mỗi lần gõ số, dùng để huỷ kết quả tra cứu cũ trả về trễ (gõ nhanh nhiều ký tự)
    private var searchGeneration = 0

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

        suggestAdapter = ContactSuggestAdapter { number ->
            (activity as? MainActivity)?.placeCall(number)
        }
        b.rvSuggestions.layoutManager = LinearLayoutManager(requireContext())
        b.rvSuggestions.adapter = suggestAdapter

        b.rvRecents.layoutManager = LinearLayoutManager(requireContext())
        loadRecents()

        b.btnDialerSettings.setOnClickListener { (activity as? MainActivity)?.openSettings() }
        b.btnDialerSearch.setOnClickListener {
            android.widget.Toast.makeText(requireContext(), "Tìm kiếm đang được phát triển", android.widget.Toast.LENGTH_SHORT).show()
        }
        b.tabAll.setOnClickListener { selectTab(missed = false) }
        b.tabMissed.setOnClickListener { selectTab(missed = true) }

        arguments?.getString("number")?.let { b.etNumber.setText(it) }

        setupKeypad(view)

        b.btnBackspace.setOnClickListener {
            val t = b.etNumber.text.toString()
            if (t.isNotEmpty()) b.etNumber.setText(t.dropLast(1))
            syncBackspace()
        }
        b.btnBackspace.setOnLongClickListener {
            b.etNumber.setText(""); syncBackspace(); true
        }

        b.btnDialMenu.setOnClickListener { showDialMenu(it) }

        b.etNumber.addTextChangedListener(object : TextWatcher {
            private var editing = false
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (editing) return; editing = true
                val raw = dialableFilter(s.toString())
                val fmt = formatVN(raw)
                if (fmt != s.toString()) {
                    b.etNumber.setText(fmt)
                    b.etNumber.setSelection(fmt.length)
                }
                syncBackspace()
                searchSuggestions(raw.filter { it.isDigit() || it == '+' })
                editing = false
            }
        })

        setupCallButtons()

        // Nút video dùng FrameLayout có id btnVideoCall
        view.findViewById<View>(R.id.btnVideoCall)?.setOnClickListener {
            android.widget.Toast.makeText(requireContext(),
                getString(R.string.video_call_unsupported), android.widget.Toast.LENGTH_SHORT).show()
        }

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
            try { pickContactLauncher.launch(null) } catch (_: Exception) {
                android.widget.Toast.makeText(requireContext(), "Không thể chọn liên hệ", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        b.rowSendSms.setOnClickListener {
            val raw = b.etNumber.text.toString().filter { it.isDigit() || it == '+' }
            try { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$raw"))) } catch (_: Exception) {
                android.widget.Toast.makeText(requireContext(), "Không tìm thấy ứng dụng nhắn tin", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        b.rowVideoMeet.setOnClickListener {
            android.widget.Toast.makeText(requireContext(),
                getString(R.string.video_call_unsupported), android.widget.Toast.LENGTH_SHORT).show()
        }

        b.btnKeypadToggle.setOnClickListener {
            setKeypadVisible(!keypadVisible)
        }

        syncBackspace()

        // Bàn phím số luôn bật sẵn khi vào app/tab Gần đây
        setKeypadVisible(true)

        // KHÔNG bật bàn phím hệ thống của máy ở đây nữa. etNumber chỉ dùng để HIỂN THỊ số đang
        // gõ, việc nhập số chỉ đến từ các phím bấm 0-9 * # trong bàn phím số riêng của app (xem
        // setupKeypad bên dưới). "android:showSoftInputOnFocus" không phải attribute XML công
        // khai (aapt2 từ chối biên dịch) nên phải set qua code bằng method tương ứng.
        b.etNumber.showSoftInputOnFocus = false
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(b.etNumber.windowToken, 0)
    }

    private fun setupKeypad(view: View) {
        val grid = view.findViewById<GridLayout>(R.id.keypad)
        for (i in 0 until grid.childCount) {
            val btn = grid.getChildAt(i) as? Button ?: continue
            val tag = btn.tag as? String ?: continue

            val sub = SUB_LABELS[tag]
            if (sub != null) {
                val ss = SpannableStringBuilder()
                ss.append(tag); ss.append("\n")
                val subStart = ss.length; ss.append(sub)
                ss.setSpan(RelativeSizeSpan(0.35f), subStart, ss.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                ss.setSpan(ForegroundColorSpan(requireContext().getColor(R.color.text_secondary)),
                    subStart, ss.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                btn.text = ss; btn.setLines(2); btn.textSize = 30f
            }

            if (tag == "1") {
                val ss = SpannableStringBuilder()
                ss.append("1"); ss.append("\n")
                val sub2Start = ss.length; ss.append("QO")
                ss.setSpan(RelativeSizeSpan(0.35f), sub2Start, ss.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                ss.setSpan(ForegroundColorSpan(requireContext().getColor(R.color.text_secondary)),
                    sub2Start, ss.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                btn.text = ss; btn.setLines(2); btn.textSize = 30f
            }

            btn.setOnClickListener {
                appendDigit(tag)
                haptic()
                toneGen?.startTone(DTMF_MAP[tag] ?: ToneGenerator.TONE_DTMF_0, 120)
            }

            if (tag == "0") {
                btn.setOnLongClickListener {
                    val cur = b.etNumber.text.toString()
                    val raw = dialableFilter(cur)
                    val newRaw = if (raw.endsWith("0")) raw.dropLast(1) + "+" else raw + "+"
                    b.etNumber.setText(formatVN(newRaw))
                    b.etNumber.setSelection(b.etNumber.text.length)
                    syncBackspace(); true
                }
            }
        }
    }

    private fun callCapableAccounts(): List<PhoneAccountHandle> {
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_PHONE_STATE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        return try {
            val tm = requireContext().getSystemService(TelecomManager::class.java) ?: return emptyList()
            tm.callCapablePhoneAccounts ?: emptyList()
        } catch (_: SecurityException) { emptyList() }
    }

    private fun callWith(handle: PhoneAccountHandle?) {
        val raw = dialableFilter(b.etNumber.text.toString())
        if (raw.isNotEmpty()) {
            (activity as? MainActivity)?.placeCall(raw, handle)
        } else {
            val last = getLastCalledNumber()
            if (last != null) {
                b.etNumber.setText(formatVN(last))
                b.etNumber.setSelection(b.etNumber.text.length)
                syncBackspace()
            }
        }
    }

    private fun setupCallButtons() {
        val accounts = callCapableAccounts()
        if (accounts.size >= 2) {
            b.btnCall.visibility = View.GONE
            b.llCallDual.visibility = View.VISIBLE
            b.btnCallSim1.setOnClickListener { callWith(accounts[0]) }
            b.btnCallSim2.setOnClickListener { callWith(accounts[1]) }
        } else {
            b.llCallDual.visibility = View.GONE
            b.btnCall.visibility = View.VISIBLE
            b.btnCall.setOnClickListener { callWith(accounts.firstOrNull()) }
        }
    }

    // Giữ lại chữ số, "+" và các ký hiệu dừng/chờ (","=2 giây dừng, ";"=chờ) khi lọc nội dung
    // ô nhập số. Dùng chung cho cả gõ phím lẫn thêm dấu dừng/chờ từ menu 3 chấm, để 2 luồng
    // nhập không xoá mất ký hiệu của nhau.
    private fun dialableFilter(s: String) = s.filter { it.isDigit() || it == '+' || it == ',' || it == ';' }

    private fun appendDigit(d: String) {
        val cur = b.etNumber.text.toString()
        val raw = dialableFilter(cur) + d
        b.etNumber.setText(formatVN(raw))
        b.etNumber.setSelection(b.etNumber.text.length)
        syncBackspace()
    }

    private fun formatVN(raw: String): String {
        if (raw.isEmpty()) return raw
        // Có dấu dừng/chờ: không áp dụng định dạng nhóm số VN, giữ nguyên chuỗi người dùng gõ.
        if (raw.contains(',') || raw.contains(';')) return raw
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

    // Icon nút bật/tắt bàn phím phải phản ánh đúng trạng thái hiện tại: đang MỞ bàn phím thì
    // hiện mũi tên xuống (báo bấm để ẨN), đang ẨN thì hiện icon lưới chấm (báo bấm để MỞ).
    private fun updateKeypadToggleIcon() {
        _b?.btnKeypadToggle?.setImageResource(
            if (keypadVisible) R.drawable.ic_keyboard_hide else R.drawable.ic_dialpad
        )
    }

    // Ẩn/hiện lưới số VÀ hàng nút video/gọi/toggle CÙNG LÚC - trước đây chỉ ẩn mỗi lưới số nên
    // hàng nút gọi bị "chừa lại" một mình phía dưới. Khi ẩn, FAB bàn phím ở MainActivity (đặt
    // cạnh thanh tab Gần đây/Danh bạ) sẽ hiện lên thay thế, dùng để mở lại bàn phím.
    private fun setKeypadVisible(visible: Boolean) {
        keypadVisible = visible
        // panelKeypad bao gồm etNumber + keypad + rowDialControls, nổi overlay gravity=bottom
        _b?.panelKeypad?.visibility = if (visible) View.VISIBLE else View.GONE
        updateKeypadToggleIcon()
        (activity as? MainActivity)?.setDialpadFabVisible(!visible)
    }

    /** Gọi từ MainActivity khi người dùng bấm FAB bàn phím lúc đang ở màn này với bàn phím đã ẩn. */
    fun showKeypad() = setKeypadVisible(true)

    private fun syncBackspace() {
        val hasNumber = b.etNumber.text.isNotEmpty()
        _b?.btnBackspace?.visibility = if (hasNumber) View.VISIBLE else View.INVISIBLE
        _b?.btnDialMenu?.visibility = if (hasNumber) View.VISIBLE else View.INVISIBLE
        // Ẩn ô nhập số khi chưa gõ gì, hiện lên khi có số
        _b?.frameNumber?.visibility = if (hasNumber) View.VISIBLE else View.GONE
    }

    // Menu 3 chấm cạnh ô nhập số: chèn ký tự dừng (,) hoặc chờ (;) vào cuối số đang gõ,
    // giống hành vi bàn phím quay số chuẩn của Android khi gọi vào hệ thống IVR/tổng đài.
    private fun showDialMenu(anchor: View) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, getString(R.string.add_2s_pause))
        popup.menu.add(0, 2, 1, getString(R.string.add_wait))
        popup.setOnMenuItemClickListener { item ->
            val symbol = when (item.itemId) { 1 -> ","; 2 -> ";"; else -> "" }
            if (symbol.isNotEmpty()) {
                val raw = dialableFilter(b.etNumber.text.toString()) + symbol
                b.etNumber.setText(formatVN(raw))
                b.etNumber.setSelection(b.etNumber.text.length)
                syncBackspace()
            }
            true
        }
        popup.show()
    }

    private fun loadRecents() {
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_CALL_LOG)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            b.rvRecents.visibility = View.GONE
            return
        }
        val appContext = requireContext().applicationContext
        val isDualSim = callCapableAccounts().size >= 2
        bgExecutor.execute {
            val entries = queryRecents(appContext)
            mainHandler.post {
                if (_b == null) return@post // fragment đã bị huỷ trong lúc chờ
                allRecentEntries = entries
                renderRecents(isDualSim)
            }
        }
    }

    /** Áp bộ lọc tab (Tất cả/Cuộc gọi nhỡ) đang chọn lên allRecentEntries rồi bơm vào adapter.
     *  Dùng chung cho lần tải đầu tiên VÀ mỗi khi người dùng đổi tab. */
    private fun renderRecents(isDualSim: Boolean) {
        val entries = if (showMissedOnly)
            allRecentEntries.filter { it.type == CallLog.Calls.MISSED_TYPE }
        else allRecentEntries
        b.rvRecents.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        b.rvRecents.adapter = CallLogAdapter(
            entries,
            isDualSim = isDualSim,
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
        b.tabAllUnderline.setBackgroundColor(if (missed) transparent else accent)
        b.tabMissedUnderline.setBackgroundColor(if (missed) accent else transparent)
        renderRecents(callCapableAccounts().size >= 2)
    }

    private fun queryRecents(ctx: Context): List<CallLogEntry> {
        val entries = mutableListOf<CallLogEntry>()
        try {
            val projection = arrayOf(
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE,
                CallLog.Calls.TYPE,
                CallLog.Calls.PHONE_ACCOUNT_ID,
                CallLog.Calls.CACHED_NUMBER_TYPE,
                CallLog.Calls.CACHED_NUMBER_LABEL
            )
            // Chỉ hiển thị gợi ý "gần đây" trong màn hình quay số nên KHÔNG cần tải toàn bộ lịch sử
            // (có máy hàng nghìn cuộc gọi) — giới hạn 50 dòng mới nhất là đủ và tránh lag khi mở màn.
            val cur = ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null, null, "${CallLog.Calls.DATE} DESC LIMIT 50"
            )
            cur?.use {
                val iName   = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iNum    = it.getColumnIndex(CallLog.Calls.NUMBER)
                val iDate   = it.getColumnIndex(CallLog.Calls.DATE)
                val iType   = it.getColumnIndex(CallLog.Calls.TYPE)
                val iAcct   = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                val iNumType = it.getColumnIndex(CallLog.Calls.CACHED_NUMBER_TYPE)
                val iLabel  = it.getColumnIndex(CallLog.Calls.CACHED_NUMBER_LABEL)
                while (it.moveToNext()) {
                    // Xác định SIM slot qua SubscriptionManager.getActiveSubscriptionInfo
                    val acctId = if (iAcct >= 0) it.getString(iAcct) ?: "" else ""
                    val simSlot: Int? = try {
                        val subId = acctId.toIntOrNull()
                        if (subId != null) {
                            val sm = ctx.getSystemService(SubscriptionManager::class.java)
                            sm?.getActiveSubscriptionInfo(subId)?.simSlotIndex?.takeIf { idx -> idx >= 0 }
                        } else null
                    } catch (_: Exception) { null }
                    // Loại đường dây
                    val numType = if (iNumType >= 0) it.getInt(iNumType) else 0
                    val label   = if (iLabel >= 0) it.getString(iLabel) ?: "" else ""
                    val typeLabel = when (numType) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Di động"
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME   -> "Nhà riêng"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK   -> "Cơ quan"
                        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> label.ifEmpty { "Di động" }
                        else -> "Di động"
                    }
                    entries.add(CallLogEntry(
                        name = it.getString(iName) ?: "",
                        number = it.getString(iNum) ?: "",
                        date = it.getLong(iDate),
                        type = it.getInt(iType),
                        simSlot = simSlot,
                        numberType = typeLabel
                    ))
                }
            }
        } catch (_: SecurityException) {}
        return entries
    }

    private fun searchSuggestions(raw: String) {
        // Header "Gần đây" (tiêu đề + tab) LUÔN nằm cố định trên cùng, KHÔNG bị bàn phím che -
        // chỉ ẩn hẳn khi người dùng bắt đầu gõ số, nhường chỗ cho "Tất cả liên hệ" bên dưới.
        b.llDialerHeader.visibility = if (raw.isEmpty()) View.VISIBLE else View.GONE
        if (raw.length < 2) {
            searchGeneration++
            b.llSuggestionsWrap.visibility = View.GONE
            b.llNoMatchActions.visibility = View.GONE
            b.rvRecents.visibility = if ((b.rvRecents.adapter?.itemCount ?: 0) > 0) View.VISIBLE else View.GONE
            return
        }
        b.rvRecents.visibility = View.GONE
        val myGeneration = ++searchGeneration
        val appContext = requireContext().applicationContext
        bgExecutor.execute {
            val list = queryContactSuggestions(appContext, raw)
            mainHandler.post {
                // Người dùng đã gõ thêm/xoá ký tự khác trong lúc chờ: bỏ qua kết quả trễ này
                if (_b == null || myGeneration != searchGeneration) return@post
                if (list.isEmpty()) {
                    b.llSuggestionsWrap.visibility = View.GONE
                    b.llNoMatchActions.visibility = View.VISIBLE
                } else {
                    suggestAdapter.update(list, raw)
                    b.llSuggestionsWrap.visibility = View.VISIBLE
                    b.llNoMatchActions.visibility = View.GONE
                }
            }
        }
    }

    private fun queryContactSuggestions(ctx: Context, raw: String): List<Contact> {
        val list = mutableListOf<Contact>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val cur: Cursor? = ctx.contentResolver.query(uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                arrayOf("%$raw%"), null)
            cur?.use {
                val iName = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val iNum  = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    list.add(Contact(it.getString(iName) ?: "", it.getString(iNum) ?: ""))
                }
            }
        } catch (_: Exception) {}
        return list
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

    // Khi mở màn hình này lần đầu, quyền READ_PHONE_STATE có thể CHƯA được cấp (hộp thoại xin
    // quyền của MainActivity chạy bất đồng bộ). Nếu không làm mới lại ở đây, nút gọi sẽ bị kẹt
    // vĩnh viễn ở chế độ 1 SIM ngay cả sau khi người dùng đã cấp quyền / cắm thêm SIM.
    override fun onResume() {
        super.onResume()
        // Bàn phím số luôn hiện khi quay lại tab Gần đây
        setKeypadVisible(true)
        setupCallButtons()
        loadRecents()
    }

    override fun onDestroyView() {
        toneGen?.release(); toneGen = null
        bgExecutor.shutdownNow()
        super.onDestroyView(); _b = null
    }
}
