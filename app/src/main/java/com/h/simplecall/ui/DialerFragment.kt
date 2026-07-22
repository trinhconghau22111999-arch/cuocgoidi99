package com.h.simplecall.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import androidx.fragment.app.Fragment
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.databinding.FragmentDialerBinding

class DialerFragment : Fragment() {

    companion object {
        private val SUB_LABELS = mapOf(
            "2" to "ABC", "3" to "DEF", "4" to "GHI",
            "5" to "JKL", "6" to "MNO", "7" to "PQRS",
            "8" to "TUV", "9" to "WXYZ", "0" to "+"
        )

        fun newInstanceWithNumber(number: String?): DialerFragment {
            return DialerFragment().also {
                it.arguments = Bundle().apply { putString("number", number) }
            }
        }
    }

    private var _b: FragmentDialerBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentDialerBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.getString("number")?.let { b.etNumber.setText(it) }

        // Sub-labels trên mỗi phím
        val grid = view.findViewById<GridLayout>(R.id.keypad)
        for (i in 0 until grid.childCount) {
            val btn = grid.getChildAt(i) as? Button ?: continue
            val tag = btn.tag as? String ?: continue
            val sub = SUB_LABELS[tag]
            if (sub != null) {
                // Hiển thị số lớn + sub nhỏ hơn bằng cách set text 2 dòng
                // Dùng SpannableString để cỡ chữ khác nhau
                val ss = android.text.SpannableStringBuilder()
                ss.append(tag)
                ss.append("\n")
                val subStart = ss.length
                ss.append(sub)
                ss.setSpan(
                    android.text.style.RelativeSizeSpan(0.38f),
                    subStart, ss.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                ss.setSpan(
                    android.text.style.ForegroundColorSpan(
                        requireContext().getColor(R.color.text_secondary)
                    ),
                    subStart, ss.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                btn.text = ss
                btn.setLines(2)
                btn.textSize = 24f
            }

            btn.setOnClickListener {
                appendDigit(tag)
            }

            // Long press 0 → +
            if (tag == "0") {
                btn.setOnLongClickListener {
                    val cur = b.etNumber.text.toString()
                    if (cur.endsWith("0")) b.etNumber.setText(cur.dropLast(1) + "+")
                    else appendDigit("+")
                    syncBackspace()
                    true
                }
            }
        }

        // Backspace
        b.btnBackspace.setOnClickListener {
            val t = b.etNumber.text.toString()
            if (t.isNotEmpty()) b.etNumber.setText(t.dropLast(1))
            syncBackspace()
        }
        b.btnBackspace.setOnLongClickListener {
            b.etNumber.setText("")
            syncBackspace()
            true
        }

        // Format real-time (VN phone number)
        b.etNumber.addTextChangedListener(object : TextWatcher {
            private var editing = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (editing) return
                editing = true
                val raw = s.toString().filter { it.isDigit() || it == '+' }
                val fmt = formatVN(raw)
                if (fmt != s.toString()) {
                    b.etNumber.setText(fmt)
                    b.etNumber.setSelection(fmt.length)
                }
                syncBackspace()
                editing = false
            }
        })

        b.btnCall.setOnClickListener {
            val number = b.etNumber.text.toString().filter { it.isDigit() || it == '+' }
            if (number.isNotEmpty()) (activity as? MainActivity)?.placeCall(number)
        }

        syncBackspace()
    }

    private fun appendDigit(d: String) {
        val cur = b.etNumber.text.toString()
        val raw = cur.filter { it.isDigit() || it == '+' } + d
        b.etNumber.setText(formatVN(raw))
        b.etNumber.setSelection(b.etNumber.text.length)
        syncBackspace()
    }

    /** Format số VN: 0xxx xxx xxx hoặc +84 xxx xxx xxx */
    private fun formatVN(raw: String): String {
        if (raw.isEmpty()) return raw
        val digits = raw.filter { it.isDigit() }
        return when {
            raw.startsWith("+") -> {
                // +84 xxx xxx xxx
                when {
                    digits.length <= 2  -> "+$digits"
                    digits.length <= 5  -> "+${digits.take(2)} ${digits.drop(2)}"
                    digits.length <= 8  -> "+${digits.take(2)} ${digits.drop(2).take(3)} ${digits.drop(5)}"
                    else -> "+${digits.take(2)} ${digits.drop(2).take(3)} ${digits.drop(5).take(3)} ${digits.drop(8)}"
                }
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

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
