package com.mudasir.smartledger.activity

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.mudasir.smartledger.R
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.Category
import com.mudasir.smartledger.data.PaymentChannel
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.FormatUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class AddEditTransactionActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }
    private var editingId: Long = 0
    private var pendingId: Long = 0
    private var editingRecord: TransactionRecord? = null
    private var selectedTs: Long = System.currentTimeMillis()

    private lateinit var etAmount: TextInputEditText
    private lateinit var actvCategory: AutoCompleteTextView
    private lateinit var actvChannel: AutoCompleteTextView
    private lateinit var actvPaymentMethod: AutoCompleteTextView
    private lateinit var etMerchant: TextInputEditText
    private lateinit var etNote: TextInputEditText
    private lateinit var tvDate: TextView
    private var expenseSelected = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_transaction)

        findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener { finish() }

        etAmount = findViewById(R.id.etAmount)
        actvCategory = findViewById(R.id.actvCategory)
        actvChannel = findViewById(R.id.actvChannel)
        actvPaymentMethod = findViewById(R.id.actvPaymentMethod)
        etMerchant = findViewById(R.id.etMerchant)
        etNote = findViewById(R.id.etNote)
        tvDate = findViewById(R.id.tvDate)

        val btnExpense: MaterialButton = findViewById(R.id.btnExpense)
        val btnIncome: MaterialButton = findViewById(R.id.btnIncome)
        btnExpense.setOnClickListener { expenseSelected = true }
        btnIncome.setOnClickListener { expenseSelected = false }
        btnExpense.isChecked = true

        tvDate.text = FormatUtil.day(selectedTs) + " " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(selectedTs))
        findViewById<View>(R.id.cardDate).setOnClickListener { pickDate() }

        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        val btnDelete = findViewById<MaterialButton>(R.id.btnDelete)

        editingId = intent.getLongExtra(EXTRA_ID, 0)
        pendingId = intent.getLongExtra(EXTRA_PENDING_ID, 0)
        loadAutocomplete()

        if (editingId > 0) {
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener { deleteCurrent() }
            loadForEdit(editingId)
        } else if (pendingId > 0) {
            loadForEdit(pendingId)
        }
    }

    private fun loadAutocomplete() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cats = db.categoryDao().getAll()
            val channels = db.channelDao().getAll()
            withContext(Dispatchers.Main) {
                actvCategory.setAdapter(ArrayAdapter(this@AddEditTransactionActivity, android.R.layout.simple_list_item_1, cats.map { it.name }))
                actvChannel.setAdapter(ArrayAdapter(this@AddEditTransactionActivity, android.R.layout.simple_list_item_1, channels.map { it.name }))
                actvPaymentMethod.setAdapter(ArrayAdapter(this@AddEditTransactionActivity, android.R.layout.simple_list_item_1, listOf("余额", "零钱", "储蓄卡", "信用卡", "花呗", "余额宝")))
            }
        }
    }

    private fun loadForEdit(id: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val r = db.transactionDao().getById(id) ?: return@launch
            editingRecord = r
            selectedTs = r.timestamp
            expenseSelected = r.type == TransactionRecord.TYPE_EXPENSE
            withContext(Dispatchers.Main) {
                findViewById<MaterialButton>(R.id.btnExpense).isChecked = expenseSelected
                findViewById<MaterialButton>(R.id.btnIncome).isChecked = !expenseSelected
                etAmount.setText(r.amount.toString())
                actvCategory.setText(r.categoryName, false)
                actvChannel.setText(r.channelName, false)
                actvPaymentMethod.setText(r.paymentMethod.orEmpty(), false)
                etMerchant.setText(r.merchant.orEmpty())
                etNote.setText(r.note.orEmpty())
                tvDate.text = FormatUtil.date(r.timestamp)
            }
        }
    }

    private fun pickDate() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedTs }
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            selectedTs = cal.timeInMillis
            tvDate.text = FormatUtil.date(selectedTs)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun save() {
        val amount = etAmount.text?.toString()?.trim()?.toDoubleOrNull()
        if (amount == null || amount <= 0.0) {
            Toast.makeText(this, "请输入金额", Toast.LENGTH_SHORT).show()
            return
        }
        val category = actvCategory.text?.toString()?.trim().orEmpty()
        val channel = actvChannel.text?.toString()?.trim().orEmpty().ifEmpty { "其他" }
        val pm = actvPaymentMethod.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val merchant = etMerchant.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val note = etNote.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val type = if (expenseSelected) TransactionRecord.TYPE_EXPENSE else TransactionRecord.TYPE_INCOME

        lifecycleScope.launch(Dispatchers.IO) {
            // 自动维护分类 / 渠道（自由新增）
            ensureCategory(category, type)
            ensureChannel(channel)

            val existing = editingRecord
            if (existing != null) {
                db.transactionDao().update(
                    existing.copy(
                        type = type, amount = amount, categoryName = category, channelName = channel,
                        paymentMethod = pm, merchant = merchant, note = note, timestamp = selectedTs,
                        status = TransactionRecord.STATUS_CONFIRMED
                    )
                )
            } else {
                db.transactionDao().insert(
                    TransactionRecord(
                        type = type, amount = amount, categoryName = category, channelName = channel,
                        paymentMethod = pm, merchant = merchant, note = note, timestamp = selectedTs,
                        source = TransactionRecord.SOURCE_MANUAL,
                        status = TransactionRecord.STATUS_CONFIRMED
                    )
                )
            }
            withContext(Dispatchers.Main) { finish() }
        }
    }

    private fun deleteCurrent() {
        val r = editingRecord ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            db.transactionDao().moveToTrash(r.id)
            withContext(Dispatchers.Main) { finish() }
        }
    }

    private suspend fun ensureCategory(name: String, type: String) {
        if (name.isBlank()) return
        val existing = db.categoryDao().getByType(type).any { it.name == name }
        if (!existing) db.categoryDao().insert(Category(name = name, type = type, isDefault = false))
    }

    private suspend fun ensureChannel(name: String) {
        if (name.isBlank()) return
        val existing = db.channelDao().getAll().any { it.name == name }
        if (!existing) db.channelDao().insert(PaymentChannel(name = name, isDefault = false))
    }

    companion object {
        const val EXTRA_ID = "extra_id"
        const val EXTRA_PREFILL_AMOUNT = "prefill_amount"
        const val EXTRA_PREFILL_TYPE = "prefill_type"
        const val EXTRA_PREFILL_CHANNEL = "prefill_channel"
        const val EXTRA_PREFILL_MERCHANT = "prefill_merchant"
        const val EXTRA_PREFILL_RAW = "prefill_raw"
        const val EXTRA_PENDING_ID = "pending_id"
    }
}
