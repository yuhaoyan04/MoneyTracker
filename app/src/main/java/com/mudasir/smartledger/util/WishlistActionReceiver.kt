package com.mudasir.smartledger.util

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.WishlistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 24 小时回访通知上的快捷按钮：直接在通知栏完成「还想买 / 不要了」。 */
class WishlistActionReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ID = "item_id"
        const val EXTRA_STATUS = "item_status"

        fun actionIntent(context: Context, id: Long, status: String): Intent =
            Intent(context, WishlistActionReceiver::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_STATUS, status)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val status = intent.getStringExtra(EXTRA_STATUS) ?: return
        if (id <= 0) return

        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getDatabase(context).wishlistDao()
                val item = dao.getById(id)
                val changed = dao.resolve(id, status)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(WishlistNotifier.NOTIFICATION_ID)
                if (changed > 0 && item != null) {
                    val msg = if (status == WishlistItem.STATUS_DROPPED)
                        "为你骄傲 · 已省下 ¥${"%.2f".format(item.amount)}"
                    else "记得理性消费 · 买得开心"
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("WishlistAction", "resolve failed", e)
            } finally {
                result.finish()
            }
        }
    }
}
