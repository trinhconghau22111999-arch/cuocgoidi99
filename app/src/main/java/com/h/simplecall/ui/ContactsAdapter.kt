package com.h.simplecall.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.h.simplecall.R
import com.h.simplecall.data.Contact
import com.h.simplecall.databinding.ItemContactBinding
import com.h.simplecall.databinding.ItemContactHeaderBinding
import java.text.Normalizer

/** Hàng tĩnh hiển thị phía trên danh sách (vd. "Thông tin của tôi", "Nhóm của tôi"). */
data class ContactHeader(val iconRes: Int, val label: String, val onClick: () -> Unit)

/** Chuẩn hoá chữ cái đầu tên để nhóm A-Z (bỏ dấu, giữ riêng "Đ", số/ký hiệu -> "#"). */
fun firstLetterKey(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "#"
    val first = trimmed[0]
    if (!first.isLetter()) return "#"
    val upper = first.uppercaseChar()
    if (upper == 'Đ') return "Đ"
    val base = Normalizer.normalize(upper.toString(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}"), "")
    return if (base.isNotEmpty() && base[0] in 'A'..'Z') base[0].toString() else "#"
}

class ContactsAdapter(
    private var allContacts: List<Contact>,
    private val headers: List<ContactHeader>,
    private val onCall: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CONTACT = 1
    }

    private val avatarBgs  = intArrayOf(R.color.av0,R.color.av1,R.color.av2,R.color.av3,R.color.av4,R.color.av5)
    private val avatarTxts = intArrayOf(R.color.av0t,R.color.av1t,R.color.av2t,R.color.av3t,R.color.av4t,R.color.av5t)
    private var filtered = allContacts.toMutableList()
    private var query: String = ""

    /** Khi đang tìm kiếm thì ẩn 2 hàng tĩnh, chỉ hiện kết quả liên hệ. */
    private fun headerCount() = if (query.isEmpty()) headers.size else 0

    inner class HeaderVH(val b: ItemContactHeaderBinding) : RecyclerView.ViewHolder(b.root)
    inner class ContactVH(val b: ItemContactBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int): Int =
        if (position < headerCount()) TYPE_HEADER else TYPE_CONTACT

    override fun onCreateViewHolder(p: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER)
            HeaderVH(ItemContactHeaderBinding.inflate(LayoutInflater.from(p.context), p, false))
        else
            ContactVH(ItemContactBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = headerCount() + filtered.size

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        if (h is HeaderVH) {
            val header = headers[pos]
            h.b.ivIcon.setImageResource(header.iconRes)
            h.b.tvLabel.text = header.label
            h.b.root.setOnClickListener { header.onClick() }
            return
        }
        val vh = h as ContactVH
        val c = filtered[pos - headerCount()]; val ctx = vh.itemView.context
        val idx = Math.abs(c.name.hashCode()) % avatarBgs.size

        if (c.photoUri != null) {
            vh.b.ivContactPhoto.visibility = View.VISIBLE
            vh.b.avatarView.visibility = View.GONE
            vh.b.tvAvatar.visibility = View.GONE
            vh.b.ivContactPhoto.setImageURI(Uri.parse(c.photoUri))
        } else {
            vh.b.ivContactPhoto.visibility = View.GONE
            vh.b.avatarView.visibility = View.VISIBLE
            vh.b.tvAvatar.visibility = View.VISIBLE
            vh.b.avatarView.setBackgroundResource(R.drawable.bg_avatar)
            vh.b.avatarView.background.setTint(ctx.getColor(avatarBgs[idx]))
            vh.b.tvAvatar.text = c.name.take(1).uppercase()
            vh.b.tvAvatar.setTextColor(ctx.getColor(avatarTxts[idx]))
        }

        vh.b.tvName.text = c.name
        vh.b.btnCall.setOnClickListener { onCall(c.number) }
        vh.b.root.setOnClickListener { onCall(c.number) }
    }

    fun filter(q: String) {
        query = q
        filtered = if (q.isEmpty()) allContacts.toMutableList()
        else allContacts.filter { it.name.contains(q,true) || it.number.contains(q) }.toMutableList()
        notifyDataSetChanged()
    }

    /** Vị trí trong adapter của liên hệ đầu tiên có tên bắt đầu bằng [letter], hoặc null nếu không có. */
    fun positionForLetter(letter: String): Int? {
        val idx = filtered.indexOfFirst { firstLetterKey(it.name) == letter }
        return if (idx >= 0) idx + headerCount() else null
    }

    fun firstContactPosition(): Int = headerCount()
    fun lastPosition(): Int = (itemCount - 1).coerceAtLeast(0)
}
