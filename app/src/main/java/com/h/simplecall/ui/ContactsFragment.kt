package com.h.simplecall.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.h.simplecall.MainActivity
import com.h.simplecall.data.Contact
import com.h.simplecall.databinding.FragmentContactsBinding

class ContactsFragment : Fragment() {

    private var _b: FragmentContactsBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: ContactsAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentContactsBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val contacts = loadContacts()
        adapter = ContactsAdapter(contacts) { number ->
            (activity as? MainActivity)?.placeCall(number)
        }
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerView.adapter = adapter

        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { adapter.filter(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
    }

    private fun loadContacts(): List<Contact> {
        if (ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()

        val list = mutableListOf<Contact>()
        val cur = requireContext().contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            ), null, null,
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        ) ?: return list
        cur.use {
            val iName = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val iNum  = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                list.add(Contact(it.getString(iName) ?: "", it.getString(iNum) ?: ""))
            }
        }
        return list
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
