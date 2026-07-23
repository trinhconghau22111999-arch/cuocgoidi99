package com.h.simplecall.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.CallLog
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.h.simplecall.R
import com.h.simplecall.call.BlockedNumbersManager
import com.h.simplecall.data.CallLogEntry
import com.h.simplecall.databinding.ItemCallLogBinding
import java.text.SimpleDateFormat
import java.util.*

class CallLogAdapter(
    private val items: List<CallLogEntry>,
    private val onCall: (String) -> Unit,
    private val onShowHistory: (String) -> Unit
) : RecyclerView.Adapter<CallLogAdapter.VH>() {

    private val avatarBgs  = intArrayOf(R.color.av0,R.color.av1,R.color.av2,R.color.av3,R.color.av4,R.color.av5)
    private val avatarTxts = intArrayOf(R.color.av0t,R.color.av1t,R.color.av2t,R.color.av3t,R.color.av4t,R.color.av5t)

    inner class VH(val b: ItemCallLogBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(ItemCallLogBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]; val ctx = h.itemView.context
        val display = item.name.ifEmpty { item.number }
        val isBlocked = BlockedNumbersManager.isBlocked(item.number)

        val idx = Math.abs(display.hashCode()) % avatarBgs.size
        h.b.avatarView.setBackgroundResource(R.drawable.bg_avatar)
        h.b.avatarView.background.setTint(ctx.getColor(avatarBgs[idx]))
        h.b.tvInitial.text = display.take(1).uppercase()
        h.b.tvInitial.setTextColor(ctx.getColor(avatarTxts[idx]))

        h.b.tvName.text = if (isBlocked) "🚫 $display" else display
        h.b.tvName.setTextColor(ctx.getColor(
            if (item.type == CallLog.Calls.MISSED_TYPE) R.color.missed_red
            else R.color.text_primary
        ))

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0)
        }
        val cal = Calendar.getInstance().apply { timeInMillis = item.date }
        val fmt = if (cal.after(today)) SimpleDateFormat("HH:mm", Locale.getDefault())
                  else SimpleDateFormat("dd/MM  HH:mm", Locale.getDefault())
        h.b.tvDate.text = fmt.format(Date(item.date))

        when (item.type) {
            CallLog.Calls.MISSED_TYPE   -> h.b.ivType.setImageResource(R.drawable.ic_call_missed)
            CallLog.Calls.OUTGOING_TYPE -> h.b.ivType.setImageResource(R.drawable.ic_call_outgoing)
            else -> h.b.ivType.setImageResource(R.drawable.ic_call_incoming)
        }
        h.b.ivType.clearColorFilter()

        h.b.btnCallBack.setOnClickListener { onCall(item.number) }
        h.b.root.setOnClickListener { onShowHistory(item.number) }

        // Long press: menu copy / block
        h.b.root.setOnLongClickListener {
            showContextMenu(ctx, item.number, display)
            true
        }
    }

    private fun showContextMenu(ctx: Context, number: String, display: String) {
        val isBlocked = BlockedNumbersManager.isBlocked(number)
        val blockLabel = if (isBlocked)
            ctx.getString(R.string.unblock_number) else ctx.getString(R.string.block_number)

        val options = arrayOf(ctx.getString(R.string.number_copied), blockLabel)
        AlertDialog.Builder(ctx)
            .setTitle(display)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("phone", number))
                        Toast.makeText(ctx, ctx.getString(R.string.number_copied), Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        if (isBlocked) {
                            BlockedNumbersManager.unblock(number)
                            Toast.makeText(ctx, "Đã bỏ chặn $display", Toast.LENGTH_SHORT).show()
                        } else {
                            AlertDialog.Builder(ctx)
                                .setTitle(ctx.getString(R.string.block_confirm, display))
                                .setMessage(ctx.getString(R.string.block_confirm_msg))
                                .setPositiveButton(ctx.getString(R.string.yes_block)) { _, _ ->
                                    BlockedNumbersManager.block(number)
                                    Toast.makeText(ctx, "Đã chặn $display", Toast.LENGTH_SHORT).show()
                                    notifyDataSetChanged()
                                }
                                .setNegativeButton(ctx.getString(R.string.cancel), null)
                                .show()
                        }
                    }
                }
            }.show()
    }
}
