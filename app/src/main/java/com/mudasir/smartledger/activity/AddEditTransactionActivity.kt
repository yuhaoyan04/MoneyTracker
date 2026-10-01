package com.mudasir.smartledger.activity

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.mudasir.smartledger.R
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.Category
import com.mudasir.smartledger.data.PaymentChannel
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.ml.PersonalTagger
import com.mudasir.smartledger.util.AutoBackupManager
import com.mudasir.smartledger.util.CategoryStyle
import com.mudasir.smartledger.util.FormatUtil
import com.mudasir.smartledger.util.LocationHelper
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
    private var suggestedCategory: String? = null // 打标器给出的初始建议（用于修正学习）

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

        // 分类字段：禁用下拉列表，统一用「选择分类（多级）」按钮
        actvCategory.threshold = Int.MAX_VALUE

        val btnExpense: MaterialButton = findViewById(R.id.btnExpense)
        val btnIncome: MaterialButton = findViewById(R.id.btnIncome)
        btnExpense.setOnClickListener {
            expenseSelected = true
            actvCategory.setText("", false)
            maybePrefillSuggestion()
        }
        btnIncome.setOnClickListener {
            expenseSelected = false
            actvCategory.setText("", false)
            maybePrefillSuggestion()
        }
        btnExpense.isChecked = true

        tvDate.text = FormatUtil.day(selectedTs) + " " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(selectedTs))
        findViewById<View>(R.id.cardDate).setOnClickListener { pickDate() }
        findViewById<View>(R.id.btnPickCategory).setOnClickListener { pickCategory() }

        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        val btnDelete = findViewById<MaterialButton>(R.id.btnDelete)

        editingId = intent.getLongExtra(EXTRA_ID, 0)
        pendingId = intent.getLongExtra(EXTRA_PENDING_ID, 0)
        loadAutocomplete()
        maybePrefillSuggestion()

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
            val channels = db.channelDao().getAll()
            withContext(Dispatchers.Main) {
                actvChannel.setAdapter(ArrayAdapter(this@AddEditTransactionActivity, android.R.layout.simple_list_item_1, channels.map { it.name }))
                actvPaymentMethod.setAdapter(ArrayAdapter(this@AddEditTransactionActivity, android.R.layout.simple_list_item_1, listOf("余额", "零钱", "储蓄卡", "信用卡", "花呗", "余额宝", "微信零钱", "支付宝余额")))
            }
        }
    }

    /** 抓取条目无分类时，用打标器给个建议。 */
    private fun maybePrefillSuggestion() {
        if (editingId > 0 || pendingId > 0) return
        val current = actvCategory.text?.toString()?.trim().orEmpty()
        if (current.isNotBlank()) return
        val type = if (expenseSelected) TransactionRecord.TYPE_EXPENSE else TransactionRecord.TYPE_INCOME
        val amount = etAmount.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
        val channel = actvChannel.text?.toString()?.trim().orEmpty().ifEmpty { "其他" }
        val merchant = etMerchant.text?.toString()?.trim()
        val suggested = PersonalTagger.recommend(this, type, selectedTs, amount, channel, merchant, null)
        suggestedCategory = suggested
        actvCategory.setText(suggested, false)
    }

    private fun pickCategory() {
        val type = if (expenseSelected) TransactionRecord.TYPE_EXPENSE else TransactionRecord.TYPE_INCOME
        lifecycleScope.launch(Dispatchers.IO) {
            val roots = db.categoryDao().getRoots(type)
            if (roots.isEmpty()) {
                withContext(Dispatchers.Main) { Toast.makeText(this@AddEditTransactionActivity, "暂无分类", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            val childrenByRoot = mutableMapOf<String, List<Category>>()
            for (root in roots) {
                childrenByRoot[root.name] = db.categoryDao().getChildren(root.name, type)
            }
            withContext(Dispatchers.Main) { showCategorySheet(roots, childrenByRoot, type) }
        }
    }

    private fun showCategorySheet(
        roots: List<Category>,
        childrenByRoot: Map<String, List<Category>>,
        type: String
    ) {
        val sheet = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 32)
        }
        val title = TextView(this).apply {
            text = "选择分类"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(com.mudasir.smartledger.R.color.text_primary))
        }
        container.addView(title)

        val chipGroup = ChipGroup(this).apply {
            isSingleSelection = true
            chipSpacingHorizontal = 8
            chipSpacingVertical = 8
        }

        for (root in roots) {
            val rootColor = CategoryStyle.color(this, root.name, null)
            val rootChip = Chip(this).apply {
                text = root.name
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(rootColor)
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                isCheckable = true
                chipCornerRadius = 20f
                val children = childrenByRoot[root.name].orEmpty()
                if (children.isEmpty()) {
                    setOnClickListener {
                        actvCategory.setText(root.name, false)
                        sheet.dismiss()
                    }
                } else {
                    setOnClickListener {
                        chipGroup.removeAllViews()
                        val backChip = Chip(this@AddEditTransactionActivity).apply {
                            text = "‹ 返回"
                            setTextColor(getColor(com.mudasir.smartledger.R.color.teal_main))
                            isCheckable = false
                            chipBackgroundColor = android.content.res.ColorStateList.valueOf(getColor(com.mudasir.smartledger.R.color.surface_dim))
                            setOnClickListener { rebuildRootChips(chipGroup, roots, childrenByRoot, sheet) }
                        }
                        chipGroup.addView(backChip)
                        val useRootChip = Chip(this@AddEditTransactionActivity).apply {
                            text = "用「$root」"
                            chipBackgroundColor = android.content.res.ColorStateList.valueOf(rootColor)
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 13f
                            isCheckable = false
                            chipCornerRadius = 20f
                            setOnClickListener {
                                actvCategory.setText(root.name, false)
                                sheet.dismiss()
                            }
                        }
                        chipGroup.addView(useRootChip)
                        for (child in children) {
                            val childChip = Chip(this@AddEditTransactionActivity).apply {
                                text = child.name
                                chipBackgroundColor = android.content.res.ColorStateList.valueOf(rootColor)
                                setTextColor(android.graphics.Color.WHITE)
                                textSize = 13f
                                isCheckable = true
                                chipCornerRadius = 20f
                                setOnClickListener {
                                    actvCategory.setText(child.name, false)
                                    sheet.dismiss()
                                }
                            }
                            chipGroup.addView(childChip)
                        }
                    }
                }
            }
            chipGroup.addView(rootChip)
        }

        container.addView(chipGroup)

        val customBtn = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.style.Widget_Material3_Button_TextButton
        ).apply {
            text = "自定义输入"
            setOnClickListener {
                sheet.dismiss()
                actvCategory.requestFocus()
                actvCategory.showDropDown()
            }
        }
        val btnParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        btnParams.topMargin = 12
        container.addView(customBtn, btnParams)

        sheet.setContentView(container)
        sheet.show()
    }

    private fun rebuildRootChips(
        chipGroup: ChipGroup,
        roots: List<Category>,
        childrenByRoot: Map<String, List<Category>>,
        sheet: BottomSheetDialog
    ) {
        chipGroup.removeAllViews()
        for (root in roots) {
            val rootColor = CategoryStyle.color(this, root.name, null)
            val rootChip = Chip(this).apply {
                text = root.name
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(rootColor)
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                isCheckable = true
                chipCornerRadius = 20f
                val children = childrenByRoot[root.name].orEmpty()
                if (children.isEmpty()) {
                    setOnClickListener {
                        actvCategory.setText(root.name, false)
                        sheet.dismiss()
                    }
                } else {
                    setOnClickListener {
                        chipGroup.removeAllViews()
                        val backChip = Chip(this@AddEditTransactionActivity).apply {
                            text = "‹ 返回"
                            setTextColor(getColor(com.mudasir.smartledger.R.color.teal_main))
                            isCheckable = false
                            chipBackgroundColor = android.content.res.ColorStateList.valueOf(getColor(com.mudasir.smartledger.R.color.surface_dim))
                            setOnClickListener { rebuildRootChips(chipGroup, roots, childrenByRoot, sheet) }
                        }
                        chipGroup.addView(backChip)
                        val useRootChip = Chip(this@AddEditTransactionActivity).apply {
                            text = "用「$root」"
                            chipBackgroundColor = android.content.res.ColorStateList.valueOf(rootColor)
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 13f
                            isCheckable = false
                            chipCornerRadius = 20f
                            setOnClickListener {
                                actvCategory.setText(root.name, false)
                                sheet.dismiss()
                            }
                        }
                        chipGroup.addView(useRootChip)
                        for (child in children) {
                            val childChip = Chip(this@AddEditTransactionActivity).apply {
                                text = child.name
                                chipBackgroundColor = android.content.res.ColorStateList.valueOf(rootColor)
                                setTextColor(android.graphics.Color.WHITE)
                                textSize = 13f
                                isCheckable = true
                                chipCornerRadius = 20f
                                setOnClickListener {
                                    actvCategory.setText(child.name, false)
                                    sheet.dismiss()
                                }
                            }
                            chipGroup.addView(childChip)
                        }
                    }
                }
            }
            chipGroup.addView(rootChip)
        }
    }

    private fun loadForEdit(id: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val r = db.transactionDao().getById(id) ?: return@launch
            editingRecord = r
            selectedTs = r.timestamp
            expenseSelected = r.type == TransactionRecord.TYPE_EXPENSE
            suggestedCategory = r.categoryName // 原值，用于判断是否被修正
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
            ensureCategory(category, type)
            ensureChannel(channel)

            // 尽力获取地理位置（无权限则跳过，不阻塞）
            val place = runCatching { LocationHelper.lastPlace(this@AddEditTransactionActivity) }.getOrNull()

            val existing = editingRecord
            val saved: TransactionRecord
            if (existing != null) {
                saved = existing.copy(
                    type = type, amount = amount, categoryName = category, channelName = channel,
                    paymentMethod = pm, merchant = merchant, note = note, timestamp = selectedTs,
                    status = TransactionRecord.STATUS_CONFIRMED,
                    latitude = place?.latitude ?: existing.latitude,
                    longitude = place?.longitude ?: existing.longitude,
                    locationName = place?.name ?: existing.locationName
                )
                db.transactionDao().update(saved)
            } else {
                saved = TransactionRecord(
                    type = type, amount = amount, categoryName = category, channelName = channel,
                    paymentMethod = pm, merchant = merchant, note = note, timestamp = selectedTs,
                    source = TransactionRecord.SOURCE_MANUAL,
                    status = TransactionRecord.STATUS_CONFIRMED,
                    latitude = place?.latitude, longitude = place?.longitude, locationName = place?.name
                )
                db.transactionDao().insert(saved)
            }

            // 个性化打标学习：确认/修正即训练
            if (category.isNotBlank()) {
                val old = suggestedCategory
                if (old != null && old != category) {
                    PersonalTagger.correct(this@AddEditTransactionActivity, type, selectedTs, amount, channel, old, category, merchant)
                } else {
                    PersonalTagger.learn(this@AddEditTransactionActivity, type, selectedTs, amount, channel, category, merchant)
                }
            }

            // 自动备份（增量触发）
            runCatching { AutoBackupManager.backup(this@AddEditTransactionActivity) }

            if (place?.name != null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AddEditTransactionActivity, "已记录地点：${place.name}", Toast.LENGTH_SHORT).show()
                }
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
