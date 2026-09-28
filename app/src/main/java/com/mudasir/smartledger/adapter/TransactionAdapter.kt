package com.mudasir.smartledger.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mudasir.smartledger.R
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.ChannelStyle
import com.mudasir.smartledger.util.FormatUtil

class TransactionAdapter(
    private val onClick: (TransactionRecord) -> Unit
) : ListAdapter<TransactionRecord, TransactionAdapter.VH>(DIFF) {

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_transaction, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        private val tvSubtitle: TextView = view.findViewById(R.id.tvSubtitle)
        private val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        private val tvDate: TextView = view.findViewById(R.id.tvDate)
        private val vChannelDot: View = view.findViewById(R.id.vChannelDot)
        private val tvChannelInitial: TextView = view.findViewById(R.id.tvChannelInitial)

        fun bind(r: TransactionRecord) {
            val title = listOfNotNull(r.merchant, r.categoryName.takeIf { it.isNotBlank() }, r.note)
                .firstOrNull()?.takeIf { it.isNotBlank() } ?: "交易"
            tvTitle.text = title
            val parts = listOfNotNull(
                r.channelName.takeIf { it.isNotBlank() },
                r.categoryName.takeIf { it.isNotBlank() },
                r.paymentMethod
            )
            tvSubtitle.text = if (parts.isEmpty()) "—" else parts.joinToString(" · ")
            tvAmount.text = FormatUtil.moneySigned(r.amount, r.type)
            tvAmount.setTextColor(
                ContextCompat.getColor(itemView.context, if (r.type == TransactionRecord.TYPE_INCOME) R.color.color_income else R.color.color_expense)
            )
            tvDate.text = FormatUtil.date(r.timestamp)

            val dotColor = ChannelStyle.color(itemView.context, r.channelName)
            vChannelDot.background?.mutate()?.setTint(dotColor)
            tvChannelInitial.text = ChannelStyle.initial(r.channelName)

            itemView.setOnClickListener { onClick(r) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TransactionRecord>() {
            override fun areItemsTheSame(a: TransactionRecord, b: TransactionRecord) = a.id == b.id
            override fun areContentsTheSame(a: TransactionRecord, b: TransactionRecord) = a == b
        }
    }
}
