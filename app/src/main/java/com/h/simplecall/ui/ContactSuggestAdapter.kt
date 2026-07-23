package com.h.simplecall.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.h.simplecall.R
import com.h.simplecall.data.Contact
import com.h.simplecall.databinding.ItemContactSuggestBinding

class ContactSuggestAdapter(
    private val onCall: (String) -> Unit
) : RecyclerView.Adapter<ContactSuggestAdapter.VH>() {

    private val avatarBgs  = intArrayOf(R.color.av0,R.color.av1,R.color.av2,R.color.av3,R.color.av4,R.color.av5)
    private val avatarTxts = intArrayOf(R.color.av0t,R.color.av1t,R.color.av2t,R.color.av3t,R.color.av4t,R.color.av5t)

    private var items = listOf<Contact>()

    fun update(list: List<Contact>) { items = list; notifyDataSetChanged() }

    inner class VH(val b: ItemContactSuggestBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(ItemContactSuggestBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = items[pos]; val ctx = h.itemView.context
        val idx = Math.abs(c.name.hashCode()) % avatarBgs.size
        h.b.avatarView.setBackgroundResource(R.drawable.bg_avatar)
        h.b.avatarView.background.setTint(ctx.getColor(avatarBgs[idx]))
        h.b.tvInitial.text = c.name.take(1).uppercase()
        h.b.tvInitial.setTextColor(ctx.getColor(avatarTxts[idx]))
        h.b.tvName.text = c.name
        h.b.tvNumber.text = c.number
        h.b.root.setOnClickListener { onCall(c.number.filter { it.isDigit() || it == '+' }) }
    }
}
