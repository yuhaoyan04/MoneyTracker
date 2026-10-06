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
import com.google.android.material.textfield.TextInputEditText
import com.mudasir.smartledger.R
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.Category
import com.mudasir.smartledger.data.PaymentChannel
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.ml.PersonalTagger
import com.mudasir.smartledger.util.AutoBackupManager
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
        val place = runCatching { LocationHelper.lastPlace(this) }.getOrNull()
        val suggested = PersonalTagger.recommend(this, type, selectedTs, amount, channel, merchant, null, place?.name)
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
        val ctx = sheet.context
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 48)
        }

        // 标题
        container.addView(TextView(ctx).apply {
            text = "选择分类"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16
            }
        })

        // 用 ScrollView 包裹列表
        val scroll = android.widget.ScrollView(ctx).apply {
            isFillViewport = true
        }
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        for (root in roots) {
            val children = childrenByRoot[root.name].orEmpty()

            // 分类标题
            list.addView(TextView(ctx).apply {
                text = root.name
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.teal_main))
                letterSpacing = 0.08f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(16)
                    bottomMargin = dp(4)
                }
            })

            // 如果有子分类，逐行展示
            if (children.isEmpty()) {
                list.addView(TextView(ctx).apply {
                    text = root.name
                    textSize = 15f
                    setTextColor(getColor(R.color.text_primary))
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    background = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).use { it.getDrawable(0) }
                    setOnClickListener {
                        actvCategory.setText(root.name, false)
                        sheet.dismiss()
                    }
                })
            } else {
                for (child in children) {
                    list.addView(TextView(ctx).apply {
                        text = child.name
                        textSize = 15f
                        setTextColor(getColor(R.color.text_primary))
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                        background = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).use { it.getDrawable(0) }
                        setOnClickListener {
                            actvCategory.setText(child.name, false)
                            sheet.dismiss()
                        }
                    })
                }
                // 也可以直接用父分类
                list.addView(TextView(ctx).apply {
                    text = "用「${root.name}」"
                    textSize = 13f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener {
                        actvCategory.setText(root.name, false)
                        sheet.dismiss()
                    }
                })
                // ＋ 自定义小类：挂到当前大类下（如 餐饮-寿司）
                list.addView(TextView(ctx).apply {
                    text = "＋ 自定义小类"
                    textSize = 13f
                    setTextColor(getColor(R.color.teal_main))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).use { it.getDrawable(0) }
                    setOnClickListener {
                        sheet.dismiss()
                        promptCustomChild(root, type)
                    }
                })
            }
        }

        scroll.addView(list)
        container.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // 自定义输入按钮
        val customBtn = com.google.android.material.button.MaterialButton(
            ctx, null, com.google.android.material.R.style.Widget_Material3_Button_TextButton
        ).apply {
            text = "自定义输入"
            setTextColor(getColor(R.color.teal_main))
            setOnClickListener {
                sheet.dismiss()
                actvCategory.requestFocus()
                actvCategory.showDropDown()
            }
        }
        container.addView(customBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        sheet.setContentView(container)
        sheet.show()
    }

    /** 在指定大类下创建自定义小类：输入名称 → 归一化去重 → 存库 → 选中。 */
    private fun promptCustomChild(root: Category, type: String) {
        val input = android.widget.EditText(this).apply {
            hint = "小类名称（如 寿司、小龙虾）"
            setSingleLine()
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("在「${root.name}」下添加小类")
            .setView(wrap)
            .setPositiveButton("添加") { _, _ ->
                val childName = input.text?.toString()?.trim().orEmpty()
                if (childName.isBlank()) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    // 归一化去重：同大类下已有同名（忽略大小写）小类 → 直接复用
                    val siblings = db.categoryDao().getChildren(root.name, type)
                    val existing = siblings.find { it.name.trim().equals(childName, ignoreCase = true) }
                    val finalName = if (existing != null) {
                        existing.name
                    } else {
                        db.categoryDao().insert(
                            Category(name = childName, type = type, isDefault = false, parentName = root.name, level = 2, color = root.color)
                        )
                        childName
                    }
                    withContext(Dispatchers.Main) {
                        actvCategory.setText(finalName, false)
                        Toast.makeText(this@AddEditTransactionActivity, "已添加「${root.name}-${finalName}」", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
            val resolvedCategory = ensureCategory(category, type)
            ensureChannel(channel)

            // 尽力获取地理位置（无权限则跳过，不阻塞）
            val place = runCatching { LocationHelper.lastPlace(this@AddEditTransactionActivity) }.getOrNull()

            val existing = editingRecord
            val saved: TransactionRecord
            if (existing != null) {
                // 已有记录（自动抓取 → 收件箱确认/编辑）：保留抓取时记录的位置（支付发生地），
                // 不因用户几小时后在别处确认而覆盖成错误位置
                saved = existing.copy(
                    type = type, amount = amount, categoryName = resolvedCategory, channelName = channel,
                    paymentMethod = pm, merchant = merchant, note = note, timestamp = selectedTs,
                    status = TransactionRecord.STATUS_CONFIRMED
                )
                db.transactionDao().update(saved)
            } else {
                // 手动记账：保存时记录当前位置（用户此刻就在消费地）
                saved = TransactionRecord(
                    type = type, amount = amount, categoryName = resolvedCategory, channelName = channel,
                    paymentMethod = pm, merchant = merchant, note = note, timestamp = selectedTs,
                    source = TransactionRecord.SOURCE_MANUAL,
                    status = TransactionRecord.STATUS_CONFIRMED,
                    latitude = place?.latitude, longitude = place?.longitude, locationName = place?.name
                )
                db.transactionDao().insert(saved)
            }
            // 待确认数量可能变化（确认了一条 PENDING），刷新常驻通知
            com.mudasir.smartledger.util.PendingNotifier.update(this@AddEditTransactionActivity)

            // 个性化打标学习：确认/修正即训练
            if (resolvedCategory.isNotBlank()) {
                val old = suggestedCategory
                if (old != null && old != resolvedCategory) {
                    PersonalTagger.correct(this@AddEditTransactionActivity, type, selectedTs, amount, channel, old, resolvedCategory, merchant)
                } else {
                    PersonalTagger.learn(this@AddEditTransactionActivity, type, selectedTs, amount, channel, resolvedCategory, merchant)
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

    /**
     * 确保分类存在，并做防碎片化处理：
     * 1) 支持「父-子」格式（如 餐饮-寿司）：父类存在 → 子类挂到父类下（level=2）
     * 2) 归一化精确匹配（trim + 忽略大小写）→ 复用已有分类
     * 3) 唯一 contains 模糊匹配（如「寿司（外卖）」→「寿司」）→ 复用已有分类
     * 目的：避免近似名称散落到不同类别，导致统计错分。
     */
    private suspend fun ensureCategory(name: String, type: String): String {
        if (name.isBlank()) return name
        val all = db.categoryDao().getByType(type)

        // 「父-子」格式解析
        val dashIdx = name.indexOf('-', 1)
        if (dashIdx > 0 && dashIdx < name.length - 1) {
            val parent = name.substring(0, dashIdx).trim()
            val child = name.substring(dashIdx + 1).trim()
            val parentCat = all.find { it.name.equals(parent, ignoreCase = true) }
            if (parentCat != null && child.isNotBlank()) {
                val existingChild = all.find { it.name.equals(child, true) && it.parentName == parentCat.name }
                if (existingChild != null) return existingChild.name
                db.categoryDao().insert(
                    Category(name = child, type = type, isDefault = false, parentName = parentCat.name, level = 2, color = parentCat.color)
                )
                return child
            }
        }

        // 归一化精确匹配
        val exact = all.find { it.name.trim().equals(name.trim(), ignoreCase = true) }
        if (exact != null) return exact.name

        // 唯一 contains 模糊匹配（用户输入包含已有名，或已有名包含输入）
        val contains = all.filter {
            val a = it.name.trim(); val b = name.trim()
            a.length > 1 && b.length > 1 && (a.contains(b, true) || b.contains(a, true))
        }
        if (contains.size == 1) return contains.first().name

        // 新建根分类
        db.categoryDao().insert(Category(name = name.trim(), type = type, isDefault = false))
        return name.trim()
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
