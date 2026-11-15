package com.mudasir.smartledger.activity

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.mudasir.smartledger.R
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.WishlistItem
import com.mudasir.smartledger.util.WishlistRevisitWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记录「我想买」→ 24 小时后自动回访。 */
class WishlistAddActivity : AppCompatActivity() {

    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wishlist_add)

        findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun save() {
        if (saving) return
        val name = findViewById<TextInputEditText>(R.id.etName).text?.toString()?.trim().orEmpty()
        val amount = findViewById<TextInputEditText>(R.id.etAmount).text?.toString()?.trim()?.toDoubleOrNull()
        if (name.isBlank()) { Toast.makeText(this, "写点什么想买", Toast.LENGTH_SHORT).show(); return }
        if (amount == null || !amount.isFinite() || amount <= 0.0) { Toast.makeText(this, "请输入有效的预计花费", Toast.LENGTH_SHORT).show(); return }

        saving = true
        findViewById<MaterialButton>(R.id.btnSave).isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val item = WishlistItem(name = name, amount = amount)
                val id = AppDatabase.getDatabase(applicationContext).wishlistDao().insert(item)
                val scheduled = runCatching { WishlistRevisitWorker.schedule(applicationContext, id, item.createdAt) }.isSuccess
                runCatching { com.mudasir.smartledger.util.AutoBackupManager.backup(applicationContext) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, if (scheduled) "已记下 · 24 小时后问你" else "已记下，回访提醒未能设置", Toast.LENGTH_LONG).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    saving = false
                    findViewById<MaterialButton>(R.id.btnSave).isEnabled = true
                    Toast.makeText(applicationContext, "保存失败，请重试", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
