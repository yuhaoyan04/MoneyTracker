package com.mudasir.smartledger.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mudasir.smartledger.R
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.FormatUtil

class CaptureAdapter(
    private val onConfirm: (TransactionRecord) -> Unit,
    private val onEdit: (TransactionRecord) -> Unit,
    private val onDismiss: (TransactionRecord) -> Unit
) : ListAdapter<TransactionRecord, CaptureAdapter.VH>(DIFF) {

    var parentMap: Map<String, String> = emptyMap()
        set(value) { field = value; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_capture, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvType: TextView = view.findViewById(R.id.tvCapType)
        private val tvCategory: TextView = view.findViewById(R.id.tvCapCategory)
        private val tvAmount: TextView = view.findViewById(R.id.tvCapAmount)
        private val tvMerchant: TextView = view.findViewById(R.id.tvCapMerchant)
        private val tvChannel: TextView = view.findViewById(R.id.tvCapChannel)
        private val tvRaw: TextView = view.findViewById(R.id.tvCapRaw)
        private val btnConfirm: MaterialButton = view.findViewById(R.id.btnConfirm)
        private val btnEdit: MaterialButton = view.findViewById(R.id.btnEdit)
        private val btnDismiss: MaterialButton = view.findViewById(R.id.btnDismiss)

        fun bind(r: TransactionRecord) {
            val isIncome = r.type == TransactionRecord.TYPE_INCOME
            tvType.text = if (isIncome) "收入" else "支出"
            tvAmount.text = FormatUtil.money(r.amount)
            tvAmount.setTextColor(ContextCompat.getColor(itemView.context, if (isIncome) R.color.color_income else R.color.color_expense))

            val parent = parentMap[r.categoryName]
            val catDisplay = if (!parent.isNullOrBlank() && parent != r.categoryName) {
                "$parent · ${r.categoryName}"
            } else {
                r.categoryName.takeIf { it.isNotBlank() } ?: "未分类"
            }
            tvCategory.text = catDisplay

            tvMerchant.text = r.merchant?.takeIf { it.isNotBlank() } ?: r.categoryName.ifBlank { "待确认交易" }
            tvChannel.text = listOfNotNull(r.channelName, r.paymentMethod)
                .joinToString(" · ").ifBlank { "未知渠道" }
            tvRaw.text = r.rawText ?: "无原文"
            tvRaw.visibility = if (r.rawText.isNullOrBlank()) View.GONE else View.VISIBLE

            btnConfirm.setOnClickListener { onConfirm(r) }
            btnEdit.setOnClickListener { onEdit(r) }
            btnDismiss.setOnClickListener { onDismiss(r) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TransactionRecord>() {
            override fun areItemsTheSame(a: TransactionRecord, b: TransactionRecord) = a.id == b.id
            override fun areContentsTheSame(a: TransactionRecord, b: TransactionRecord) = a == b
        }
    }
}
