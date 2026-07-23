package com.h.simplecall.ui

import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.h.simplecall.MainActivity
import com.h.simplecall.R
import com.h.simplecall.data.Contact
import com.h.simplecall.databinding.FragmentContactsBinding

/** Các chữ cái trên thanh chỉ mục bên phải, theo đúng thứ tự bảng chữ cái tiếng Việt
 *  dùng trong danh bạ điện thoại (bỏ E,F,I,W...). */
private val INDEX_LETTERS = listOf(
    "★", "…", "A", "Â", "B", "C", "D", "Đ", "G", "H", "J", "K", "L", "M", "N",
    "O", "Ô", "P", "Q", "R", "S", "T", "U", "V", "X", "Y", "Z", "#"
)

class ContactsFragment : Fragment() {

    private var _b: FragmentContactsBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: ContactsAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentContactsBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val headers = listOf(
            ContactHeader(R.drawable.ic_person, getString(R.string.my_info)) { openMyProfile() },
            ContactHeader(R.drawable.ic_tab_contacts, getString(R.string.my_groups)) { openMyGroups() }
        )

        val contacts = loadContacts()
        adapter = ContactsAdapter(contacts, headers) { (activity as? MainActivity)?.placeCall(it) }
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerView.adapter = adapter

        if (contacts.isNotEmpty()) {
            b.tvContactsCount.text = getString(R.string.contacts_count, contacts.size)
            b.tvContactsCount.visibility = View.VISIBLE
        }

        b.btnContactsSettings.setOnClickListener {
            Toast.makeText(requireContext(), "Cài đặt danh bạ đang được phát triển", Toast.LENGTH_SHORT).show()
        }

        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { adapter.filter(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b2: Int, c: Int) {}
        })

        setupAlphabetIndex()
        b.fabAddContact.setOnClickListener { openCreateContact() }
    }

    private fun setupAlphabetIndex() {
        b.llAlphabetIndex.removeAllViews()
        INDEX_LETTERS.forEach { letter ->
            val tv = TextView(requireContext()).apply {
                text = letter
                textSize = 9.5f
                gravity = android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 1, 0, 1)
                setOnClickListener { jumpTo(letter) }
            }
            b.llAlphabetIndex.addView(tv)
        }
    }

    private fun jumpTo(letter: String) {
        val lm = b.recyclerView.layoutManager as? LinearLayoutManager ?: return
        val pos = when (letter) {
            "★", "…" -> adapter.firstContactPosition()
            "#" -> adapter.positionForLetter("#") ?: adapter.lastPosition()
            else -> adapter.positionForLetter(letter)
        }
        if (pos != null && pos >= 0) lm.scrollToPositionWithOffset(pos, 0)
    }

    private fun openMyProfile() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Profile.CONTENT_URI))
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "Không thể mở thông tin của bạn", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMyGroups() {
        Toast.makeText(requireContext(), "Tính năng nhóm liên hệ đang được phát triển", Toast.LENGTH_SHORT).show()
    }

    private fun openCreateContact() {
        try {
            startActivity(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI))
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "Không tìm thấy ứng dụng để tạo liên hệ", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadContacts(): List<Contact> {
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        val list = mutableListOf<Contact>()
        val cur = requireContext().contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
            ), null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        ) ?: return list
        cur.use {
            val iName  = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val iNum   = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val iPhoto = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
            while (it.moveToNext()) {
                list.add(Contact(
                    name     = it.getString(iName) ?: "",
                    number   = it.getString(iNum) ?: "",
                    photoUri = it.getString(iPhoto)
                ))
            }
        }
        return list
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
