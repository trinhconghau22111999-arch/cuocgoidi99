package com.h.simplecall.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.h.simplecall.R
import com.h.simplecall.data.Contact
import com.h.simplecall.databinding.ItemContactBinding

class ContactsAdapter(
    private var allContacts: List<Contact>,
    private val onCall: (String) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.VH>() {

    private val avatarBgs  = intArrayOf(R.color.av0,R.color.av1,R.color.av2,R.color.av3,R.color.av4,R.color.av5)
    private val avatarTxts = intArrayOf(R.color.av0t,R.color.av1t,R.color.av2t,R.color.av3t,R.color.av4t,R.color.av5t)

    private var filtered = allContacts.toMutableList()

    inner class VH(val b: ItemContactBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(ItemContactBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = filtered.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val contact = filtered[pos]
        val ctx = h.itemView.context
        val idx = Math.abs(contact.name.hashCode()) % avatarBgs.size
        h.b.avatarView.setBackgroundResource(avatarBgs[idx])
        h.b.tvAvatar.text = contact.name.take(1).uppercase()
        h.b.tvAvatar.setTextColor(ctx.getColor(avatarTxts[idx]))
        h.b.tvName.text = contact.name
        h.b.btnCall.setOnClickListener { onCall(contact.number) }
        h.b.root.setOnClickListener { onCall(contact.number) }
    }

    fun filter(query: String) {
        filtered = if (query.isEmpty()) allContacts.toMutableList()
        else allContacts.filter {
            it.name.contains(query, true) || it.number.contains(query)
        }.toMutableList()
        notifyDataSetChanged()
    }
}
