package com.h.simplecall.ui

import android.provider.CallLog
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.h.simplecall.R
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.databinding.ItemCallLogBinding
import java.text.SimpleDateFormat
import java.util.*

class CallLogAdapter(
    private val items: List<CallLogEntry>,
    private val onCall: (String) -> Unit
) : RecyclerView.Adapter<CallLogAdapter.VH>() {

    private val avatarBgs  = intArrayOf(R.color.av0,R.color.av1,R.color.av2,R.color.av3,R.color.av4,R.color.av5)
    private val avatarTxts = intArrayOf(R.color.av0t,R.color.av1t,R.color.av2t,R.color.av3t,R.color.av4t,R.color.av5t)

    inner class VH(val b: ItemCallLogBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(ItemCallLogBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        val ctx  = h.itemView.context
        val display = item.name.ifEmpty { item.number }

        // Avatar
        val idx = Math.abs(display.hashCode()) % avatarBgs.size
        h.b.avatarView.setBackgroundResource(avatarBgs[idx])
        h.b.tvInitial.text = display.take(1).uppercase()
        h.b.tvInitial.setTextColor(ctx.getColor(avatarTxts[idx]))

        // Name (đỏ nếu missed)
        h.b.tvName.text = display
        h.b.tvName.setTextColor(ctx.getColor(
            if (item.type == CallLog.Calls.MISSED_TYPE) R.color.missed_red else R.color.text_primary
        ))

        // Date - hôm nay chỉ hiện giờ, ngày khác hiện ngày
        val cal = Calendar.getInstance()
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0) }
        cal.timeInMillis = item.date
        val fmt = if (cal.after(today)) SimpleDateFormat("HH:mm", Locale.getDefault())
                  else SimpleDateFormat("dd/MM  HH:mm", Locale.getDefault())
        h.b.tvDate.text = fmt.format(Date(item.date))

        // Type icon
        when (item.type) {
            CallLog.Calls.MISSED_TYPE   -> h.b.ivType.setImageResource(R.drawable.ic_call_missed)
            CallLog.Calls.OUTGOING_TYPE -> h.b.ivType.setImageResource(R.drawable.ic_call_outgoing)
            else                        -> h.b.ivType.setImageResource(R.drawable.ic_call_incoming)
        }
        h.b.ivType.clearColorFilter()

        h.b.btnCallBack.setOnClickListener { onCall(item.number) }
        h.b.root.setOnClickListener { onCall(item.number) }
    }
}
