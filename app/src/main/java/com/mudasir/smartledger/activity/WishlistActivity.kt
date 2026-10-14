package com.mudasir.smartledger.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.mudasir.smartledger.R
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.WishlistItem
import com.mudasir.smartledger.util.FormatUtil
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 想好再买 · 愿望清单：24 小时冷静回访 + 已省下统计。 */
class WishlistActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: WishlistAdapter
    private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wishlist)

        findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener { finish() }

        adapter = WishlistAdapter(
            onBought = { item -> lifecycleScope.launch { db.wishlistDao().resolve(item.id, WishlistItem.STATUS_BOUGHT) } },
            onDropped = { item -> lifecycleScope.launch { db.wishlistDao().resolve(item.id, WishlistItem.STATUS_DROPPED) } },
            onDelete = { item -> lifecycleScope.launch { db.wishlistDao().delete(item) } }
        )
        findViewById<RecyclerView>(R.id.rvWishlist).apply {
            layoutManager = LinearLayoutManager(this@WishlistActivity)
            adapter = this@WishlistActivity.adapter
        }

        findViewById<View>(R.id.fabAdd).setOnClickListener {
            startActivity(android.content.Intent(this, WishlistAddActivity::class.java))
        }

        lifecycleScope.launch {
            db.wishlistDao().observeAll().collectLatest { list ->
                adapter.submitList(list)
                findViewById<View>(R.id.tvEmpty).visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
                val dropped = list.filter { it.status == WishlistItem.STATUS_DROPPED }
                findViewById<TextView>(R.id.tvSavedTotal).text =
                    "¥" + FormatUtil.money(dropped.sumOf { it.amount })
                findViewById<TextView>(R.id.tvSavedHint).text =
                    "${dropped.size} 个冲动被冷静期拦下"
            }
        }
    }

    private class WishlistAdapter(
        val onBought: (WishlistItem) -> Unit,
        val onDropped: (WishlistItem) -> Unit,
        val onDelete: (WishlistItem) -> Unit
    ) : RecyclerView.Adapter<WishlistAdapter.VH>() {

        private val items = mutableListOf<WishlistItem>()
        private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun submitList(list: List<WishlistItem>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_wishlist, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = items[position]
            h.tvName.text = item.name
            h.tvAmount.text = "¥" + FormatUtil.money(item.amount)
            h.tvMeta.text = "记录于 ${sdf.format(Date(item.createdAt))}"

            val waiting = item.status == WishlistItem.STATUS_WAITING
            h.tvStatus.text = when {
                waiting -> "⏳ 冷静中"
                item.status == WishlistItem.STATUS_BOUGHT -> "已购买"
                else -> "✓ 已省下"
            }
            h.tvStatus.setTextColor(
                h.itemView.context.getColor(
                    when {
                        waiting -> R.color.teal_main
                        item.status == WishlistItem.STATUS_BOUGHT -> R.color.text_secondary
                        else -> R.color.color_income
                    }
                )
            )
            h.actionsWaiting.visibility = if (waiting) View.VISIBLE else View.GONE
            h.actionsResolved.visibility = if (waiting) View.GONE else View.VISIBLE
            h.itemView.alpha = if (waiting) 1f else 0.75f

            h.btnBought.setOnClickListener { onBought(item) }
            h.btnDropped.setOnClickListener { onDropped(item) }
            h.btnDelete.setOnClickListener { onDelete(item) }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvAmount: TextView = v.findViewById(R.id.tvAmount)
            val tvMeta: TextView = v.findViewById(R.id.tvMeta)
            val tvStatus: TextView = v.findViewById(R.id.tvStatus)
            val actionsWaiting: View = v.findViewById(R.id.actionsWaiting)
            val actionsResolved: View = v.findViewById(R.id.actionsResolved)
            val btnBought: MaterialButton = v.findViewById(R.id.btnBought)
            val btnDropped: MaterialButton = v.findViewById(R.id.btnDropped)
            val btnDelete: MaterialButton = v.findViewById(R.id.btnDelete)
        }
    }
}
