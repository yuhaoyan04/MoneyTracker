package com.mudasir.smartledger.activity

import android.app.DatePickerDialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.ColorUtils
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
import com.mudasir.smartledger.ml.HabitLearning
import com.mudasir.smartledger.util.AutoBackupManager
import com.mudasir.smartledger.util.LocationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var categoryManuallySelected = false
    private var suggestionJob: Job? = null

    // 表单状态（卡片行式布局，无输入框）
    private var selectedCategory: String = ""
    private var selectedChannel: String = "微信支付"
    private var selectedPayment: String? = "余额"

    private lateinit var etAmount: TextInputEditText
    private lateinit var etNote: TextInputEditText
    private lateinit var tvDate: TextView
    private lateinit var tvCategoryValue: TextView
    private lateinit var tvChannelValue: TextView
    private lateinit var tvPaymentValue: TextView
    private var expenseSelected = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_transaction)

        findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener { finish() }

        etAmount = findViewById(R.id.etAmount)
        etNote = findViewById(R.id.etNote)
        tvDate = findViewById(R.id.tvDate)
        tvCategoryValue = findViewById(R.id.tvCategoryValue)
        tvChannelValue = findViewById(R.id.tvChannelValue)
        tvPaymentValue = findViewById(R.id.tvPaymentValue)

        val btnExpense: MaterialButton = findViewById(R.id.btnExpense)
        val btnIncome: MaterialButton = findViewById(R.id.btnIncome)
        btnExpense.setOnClickListener { selectTransactionType(true) }
        btnIncome.setOnClickListener { selectTransactionType(false) }
        btnExpense.isChecked = true

        tvDate.text = transactionDateLabel(selectedTs)

        findViewById<View>(R.id.cardDate).setOnClickListener { pickDate() }
        findViewById<View>(R.id.cardCategory).setOnClickListener { pickCategory() }
        findViewById<View>(R.id.cardChannel).setOnClickListener { showChannelSheet() }
        findViewById<View>(R.id.cardPayment).setOnClickListener { showPaymentSheet() }
        findViewById<View>(R.id.btnSave).setOnClickListener {
            lifecycleScope.launch { suggestionJob?.join(); save() }
        }
        val btnDelete = findViewById<MaterialButton>(R.id.btnDelete)

        editingId = intent.getLongExtra(EXTRA_ID, 0)
        pendingId = intent.getLongExtra(EXTRA_PENDING_ID, 0)
        etAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { maybePrefillSuggestion() }
        })
        maybePrefillSuggestion()

        if (editingId > 0) {
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener { deleteCurrent() }
            loadForEdit(editingId)
        } else if (pendingId > 0) {
            loadForEdit(pendingId)
        }
    }

    private fun selectTransactionType(expense: Boolean) {
        if (expenseSelected == expense) return
        expenseSelected = expense
        suggestionJob?.cancel()
        selectedCategory = ""
        categoryManuallySelected = false
        tvCategoryValue.text = "未选择"
        tvCategoryValue.setTextColor(getColor(R.color.text_secondary))
        maybePrefillSuggestion()
    }

    private fun transactionDateLabel(timestamp: Long): String =
        java.text.SimpleDateFormat("yyyy年M月d日 HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))

    /** 手动新记账时，用打标器给个默认分类建议。位置反查是网络请求，必须异步，绝不能阻塞主线程。 */
    private fun maybePrefillSuggestion() {
        if (editingId > 0 || pendingId > 0) return
        if (categoryManuallySelected) return
        val type = if (expenseSelected) TransactionRecord.TYPE_EXPENSE else TransactionRecord.TYPE_INCOME
        val amount = etAmount.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
        if (!amount.isFinite() || amount <= 0.0) {
            suggestionJob?.cancel()
            suggestedCategory = null
            selectedCategory = ""
            tvCategoryValue.text = "未选择"
            tvCategoryValue.setTextColor(getColor(R.color.text_secondary))
            return
        }
        val timestamp = selectedTs
        val channel = selectedChannel
        val note = etNote.text?.toString()
        suggestionJob?.cancel()
        suggestionJob = lifecycleScope.launch {
            delay(250)
            val suggested = withContext(Dispatchers.IO) {
                PersonalTagger.recommend(this@AddEditTransactionActivity, type, timestamp, amount, channel, null, note, null)
            }
            if (!categoryManuallySelected && editingId == 0L && pendingId == 0L) {
                suggestedCategory = suggested
                selectedCategory = suggested
                tvCategoryValue.text = suggested
                tvCategoryValue.setTextColor(getColor(R.color.text_primary))
            }
        }
    }

    // ===== 分类（多级面板 + 自定义小类） =====

    private fun pickCategory() {
        val type = if (expenseSelected) TransactionRecord.TYPE_EXPENSE else TransactionRecord.TYPE_INCOME
        lifecycleScope.launch(Dispatchers.IO) {
            val all = db.categoryDao().getByType(type)
            val roots = all.filter { it.level == 1 }
            if (roots.isEmpty()) {
                withContext(Dispatchers.Main) { Toast.makeText(this@AddEditTransactionActivity, "暂无分类", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            val childrenByRoot = mutableMapOf<String, List<Category>>()
            for (root in roots) childrenByRoot[root.name] = all.filter { it.parentName == root.name }
            withContext(Dispatchers.Main) { showCategorySheet(roots, childrenByRoot, type) }
        }
    }

    private fun applyCategory(name: String) {
        categoryManuallySelected = true
        suggestionJob?.cancel()
        selectedCategory = name
        tvCategoryValue.text = name
        tvCategoryValue.setTextColor(getColor(R.color.text_primary))
    }

    private fun showCategorySheet(
        roots: List<Category>,
        childrenByRoot: Map<String, List<Category>>,
        type: String,
        showLegacy: Boolean = false
    ) {
        val sheet = BottomSheetDialog(this)
        val ctx = sheet.context
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        container.addView(TextView(ctx).apply {
            text = if (showLegacy) "历史分类" else "选择分类"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
        })
        container.addView(TextView(ctx).apply {
            text = if (showLegacy) "历史分类仍可使用，已有记录保持不变"
                else if (type == TransactionRecord.TYPE_EXPENSE) "按消费用途选择；购买渠道可单独填写"
                else "按收入来源选择；收款渠道可单独填写"
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        })
        val scroll = android.widget.ScrollView(ctx).apply { isFillViewport = true }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val expense = type == TransactionRecord.TYPE_EXPENSE
        val groups = if (expense) com.mudasir.smartledger.data.ExpenseTaxonomy.groups else com.mudasir.smartledger.data.IncomeTaxonomy.groups
        val rootNames = groups.map { it.name }.toSet()
        fun legacyChildren(root: Category): List<Category> {
            val expected = groups.firstOrNull { it.name == root.name }?.children.orEmpty()
            return childrenByRoot[root.name].orEmpty().filter {
                (root.isDefault && root.name !in rootNames) || (it.isDefault && it.name !in expected)
            }
        }
        val visibleRoots = if (showLegacy) {
            roots.filter { root -> (root.isDefault && root.name !in rootNames) || legacyChildren(root).isNotEmpty() }
        } else {
            roots.filter { !it.isDefault || it.name in rootNames }
                .sortedBy { root -> groups.indexOfFirst { it.name == root.name }.let { if (it < 0) Int.MAX_VALUE else it } }
        }
        for (root in visibleRoots) {
            val expected = groups.firstOrNull { it.name == root.name }?.children.orEmpty()
            val children = if (showLegacy) legacyChildren(root)
                else childrenByRoot[root.name].orEmpty().filter { !it.isDefault || it.name in expected }
            val selected = root.name == selectedCategory || children.any { it.name == selectedCategory }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isClickable = true
                isFocusable = true
                contentDescription = "${root.name}，${children.size}个小类${if (selected) "，当前分类" else ""}"
                background = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).use { it.getDrawable(0) }
                setOnClickListener {
                    sheet.dismiss()
                    showChildCategorySheet(root, children, type) { showCategorySheet(roots, childrenByRoot, type, showLegacy) }
                }
            }
            val tint = runCatching { Color.parseColor(root.color) }.getOrDefault(getColor(R.color.teal_main))
            row.addView(TextView(ctx).apply {
                text = root.name.take(1)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.text_primary))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(ColorUtils.blendARGB(getColor(R.color.surface_card), tint, 0.17f))
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(4), 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(TextView(ctx).apply {
                    text = root.name
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(getColor(R.color.text_primary))
                })
                addView(TextView(ctx).apply {
                    text = if (selected) "当前：$selectedCategory" else "${children.size} 个小类"
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(ctx).apply {
                text = if (selected) "✓" else "›"
                textSize = 21f
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(if (selected) R.color.accent_text else R.color.text_secondary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(32), dp(44)))
            list.addView(row)
        }
        scroll.addView(list)
        container.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (!showLegacy && roots.any { root ->
                (root.isDefault && root.name !in rootNames) || legacyChildren(root).isNotEmpty()
            }) {
            container.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "查看历史分类"
                setTextColor(getColor(R.color.accent_text))
                strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.divider))
                setOnClickListener { sheet.dismiss(); showCategorySheet(roots, childrenByRoot, type, true) }
            })
        }
        presentCategorySheet(sheet, container)
    }

    private fun showChildCategorySheet(root: Category, children: List<Category>, type: String, onBack: () -> Unit) {
        val sheet = BottomSheetDialog(this)
        val ctx = sheet.context
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(32))
        }
        container.addView(TextView(ctx).apply {
            text = "‹  返回分类"
            textSize = 14f
            setTextColor(getColor(R.color.accent_text))
            setPadding(0, dp(4), 0, dp(12))
            minimumHeight = dp(48)
            gravity = android.view.Gravity.CENTER_VERTICAL
            isFocusable = true
            setOnClickListener { sheet.dismiss(); onBack() }
        })
        container.addView(TextView(ctx).apply {
            text = root.name
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        })
        container.addView(TextView(ctx).apply {
            text = "选择小类，或直接使用大类"
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(3), 0, dp(10))
        })
        val scroll = android.widget.ScrollView(ctx)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        list.addView(TextView(ctx).apply {
            text = "直接使用「${root.name}」"
            textSize = 15f
            setTextColor(getColor(R.color.accent_text))
            setPadding(dp(8), dp(13), dp(8), dp(13))
            background = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).use { it.getDrawable(0) }
            setOnClickListener { applyCategory(root.name); sheet.dismiss() }
        })
        children.forEach { child ->
            list.addView(TextView(ctx).apply {
                text = if (child.name == selectedCategory) "${child.name}  ✓" else child.name
                textSize = 15f
                setTextColor(if (child.name == selectedCategory) getColor(R.color.accent_text) else getColor(R.color.text_primary))
                setPadding(dp(8), dp(13), dp(8), dp(13))
                minimumHeight = dp(56)
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).use { it.getDrawable(0) }
                setOnClickListener { applyCategory(child.name); sheet.dismiss() }
            })
        }
        scroll.addView(list)
        container.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        container.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "＋ 新建「${root.name}」小类"
            setTextColor(getColor(R.color.accent_text))
            strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.divider))
            setOnClickListener { sheet.dismiss(); promptCustomChild(root, type) }
        })
        presentCategorySheet(sheet, container)
    }

    private fun presentCategorySheet(sheet: BottomSheetDialog, content: View) {
        sheet.setContentView(content)
        sheet.setOnShowListener {
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { panel ->
                panel.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.surface_card))
                panel.layoutParams = panel.layoutParams.apply { height = (resources.displayMetrics.heightPixels * 0.76f).toInt() }
            }
            sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        sheet.show()
    }

    /** 在指定大类下创建自定义小类：输入名称 → 归一化去重 → 存库 → 选中。 */
    private fun promptCustomChild(root: Category, type: String) {
        promptCustomText("在「${root.name}」下添加小类", "输入小类名称") { childName ->
            lifecycleScope.launch(Dispatchers.IO) {
                val existing = db.categoryDao().getByType(type).find { it.name.trim().equals(childName, ignoreCase = true) }
                if (existing != null && existing.parentName != root.name) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AddEditTransactionActivity, "该名称已存在于「${existing.parentName ?: existing.name}」，请使用其他名称", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val finalName = existing?.name ?: childName.also {
                    db.categoryDao().insert(Category(name = it, type = type, isDefault = false, parentName = root.name, level = 2, color = root.color))
                }
                withContext(Dispatchers.Main) {
                    applyCategory(finalName)
                    Toast.makeText(this@AddEditTransactionActivity, "已添加「${root.name}-${finalName}」", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ===== 渠道 / 支付方式（单选面板 + 自定义） =====

    private fun showChannelSheet() {
        lifecycleScope.launch(Dispatchers.IO) {
            val channels = db.channelDao().getAll().map { it.name }
            withContext(Dispatchers.Main) {
                showChoiceSheet("选择渠道", channels, selectedChannel) { pick ->
                    if (pick == null) {
                        promptCustomText("自定义渠道", "渠道名称（如 美团、抖音支付）") { custom ->
                            selectedChannel = custom
                            tvChannelValue.text = custom
                            tvChannelValue.setTextColor(getColor(R.color.text_primary))
                            maybePrefillSuggestion()
                        }
                    } else {
                        selectedChannel = pick
                        tvChannelValue.text = pick
                        tvChannelValue.setTextColor(getColor(R.color.text_primary))
                        maybePrefillSuggestion()
                    }
                }
            }
        }
    }

    private fun showPaymentSheet() {
        val options = listOf("余额", "零钱", "储蓄卡", "信用卡", "花呗", "余额宝", "微信零钱", "支付宝余额", "不填")
        showChoiceSheet("支付方式", options, selectedPayment ?: "不填") { pick ->
            if (pick == null) {
                promptCustomText("自定义支付方式", "如 云闪付、数字人民币") { custom ->
                    selectedPayment = custom
                    tvPaymentValue.text = custom
                    tvPaymentValue.setTextColor(getColor(R.color.text_primary))
                }
            } else if (pick == "不填") {
                selectedPayment = null
                tvPaymentValue.text = "不填"
                tvPaymentValue.setTextColor(getColor(R.color.text_secondary))
            } else {
                selectedPayment = pick
                tvPaymentValue.text = pick
                tvPaymentValue.setTextColor(getColor(R.color.text_primary))
            }
        }
    }

    /** 通用单选 BottomSheet：当前项高亮，底部「自定义输入」。onSelect(null) = 用户要自定义。 */
    private fun showChoiceSheet(title: String, options: List<String>, current: String?, onSelect: (String?) -> Unit) {
        val sheet = BottomSheetDialog(this)
        val ctx = sheet.context
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 48)
        }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        container.addView(TextView(ctx).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        })

        val scroll = android.widget.ScrollView(ctx).apply { isFillViewport = true }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        for (opt in options) {
            list.addView(TextView(ctx).apply {
                text = opt
                textSize = 15f
                setTextColor(if (opt == current) getColor(R.color.teal_main) else getColor(R.color.text_primary))
                setPadding(dp(4), dp(12), dp(4), dp(12))
                background = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).use { it.getDrawable(0) }
                setOnClickListener {
                    onSelect(opt)
                    sheet.dismiss()
                }
            })
        }

        scroll.addView(list)
        container.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val customBtn = com.google.android.material.button.MaterialButton(
            ctx, null, com.google.android.material.R.style.Widget_Material3_Button_TextButton
        ).apply {
            text = "自定义输入"
            setTextColor(getColor(R.color.teal_main))
            setOnClickListener {
                sheet.dismiss()
                onSelect(null)
            }
        }
        container.addView(customBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        sheet.setContentView(container)
        sheet.show()
    }

    /** 通用文本输入弹窗。 */
    private fun promptCustomText(title: String, hint: String, onOk: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            this.hint = hint
            setSingleLine()
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) onOk(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 编辑加载 / 日期 / 保存 =====

    private fun loadForEdit(id: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val r = db.transactionDao().getById(id)
            if (r == null) {
                // 记录已不存在（被清理）：不能落成"新建"，直接退出
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AddEditTransactionActivity, "该记录已被删除", Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@launch
            }
            editingRecord = r
            selectedTs = r.timestamp
            expenseSelected = r.type == TransactionRecord.TYPE_EXPENSE
            suggestedCategory = r.categoryName // 原值，用于判断是否被修正
            withContext(Dispatchers.Main) {
                findViewById<MaterialButton>(R.id.btnExpense).isChecked = expenseSelected
                findViewById<MaterialButton>(R.id.btnIncome).isChecked = !expenseSelected
                etAmount.setText(r.amount.toString())
                selectedCategory = r.categoryName
                tvCategoryValue.text = r.categoryName.ifBlank { "未选择" }
                tvCategoryValue.setTextColor(getColor(if (r.categoryName.isBlank()) R.color.text_secondary else R.color.text_primary))
                selectedChannel = r.channelName.ifBlank { "微信支付" }
                tvChannelValue.text = selectedChannel
                tvChannelValue.setTextColor(getColor(R.color.text_primary))
                selectedPayment = r.paymentMethod
                tvPaymentValue.text = r.paymentMethod?.takeIf { it.isNotBlank() } ?: "不填"
                tvPaymentValue.setTextColor(getColor(if (r.paymentMethod.isNullOrBlank()) R.color.text_secondary else R.color.text_primary))
                etNote.setText(r.note.orEmpty())
                tvDate.text = transactionDateLabel(r.timestamp)
                // 支付发生地（抓取时记录；无名时回退显示坐标）
                val locName = r.locationName?.takeIf { it.isNotBlank() }
                    ?: if (r.latitude != null && r.latitude != 0.0 && r.longitude != null && r.longitude != 0.0)
                        String.format(java.util.Locale.getDefault(), "%.4f, %.4f", r.latitude, r.longitude)
                    else null
                if (locName != null) {
                    findViewById<TextView>(R.id.tvLocationValue).text = locName
                    findViewById<View>(R.id.cardLocation).visibility = View.VISIBLE
                }
            }
        }
    }

    private fun pickDate() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedTs }
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            selectedTs = cal.timeInMillis
            tvDate.text = transactionDateLabel(selectedTs)
            maybePrefillSuggestion()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    /** 防重入：连点保存只生效一次，避免重复插入。 */
    private var saving = false

    private fun save() {
        if (saving) return
        val amount = etAmount.text?.toString()?.trim()?.toDoubleOrNull()
        if (amount == null || !amount.isFinite() || amount <= 0.0) {
            etAmount.error = "请输入大于 0 的金额"
            etAmount.requestFocus()
            return
        }
        if (selectedCategory.isBlank()) {
            Toast.makeText(this, "请选择分类", Toast.LENGTH_SHORT).show()
            return
        }
        // 从收件箱编辑进入但记录尚未加载完成（或已被删）→ 阻止保存成新记录
        if ((editingId > 0 || pendingId > 0) && editingRecord == null) {
            Toast.makeText(this, "记录加载中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        val explicitCategoryFeedback = categoryManuallySelected || pendingId > 0
        val channel = selectedChannel.ifBlank { "其他" }
        val pm = selectedPayment
        val note = etNote.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val type = if (expenseSelected) TransactionRecord.TYPE_EXPENSE else TransactionRecord.TYPE_INCOME

        lifecycleScope.launch(Dispatchers.IO) {
            var savedToDb = false
            try {
                val resolvedCategory = ensureCategory(selectedCategory, type)
                ensureChannel(channel)

                // 手动新记账：保存时记录当前位置（用户此刻就在消费地）
                val place = if (editingRecord == null) {
                    runCatching { LocationHelper.lastPlace(this@AddEditTransactionActivity) }.getOrNull()
                } else null

                val existing = editingRecord
                val saved: TransactionRecord
                if (existing != null) {
                    // 已有记录（自动抓取 → 收件箱确认/编辑）：
                    // - 保留抓取时记录的位置（支付发生地），绝不覆盖
                    // - 若抓取时未取到位置（如当时未授权），补记当前位置（对标「记一笔」）
                    val needsLoc = existing.locationName.isNullOrBlank() &&
                        (existing.latitude == null || existing.latitude == 0.0)
                    val fillPlace = if (needsLoc) {
                        runCatching { LocationHelper.lastPlace(this@AddEditTransactionActivity) }.getOrNull()
                    } else null
                    saved = existing.copy(
                        type = type, amount = amount, categoryName = resolvedCategory, channelName = channel,
                        paymentMethod = pm, note = note, timestamp = selectedTs,
                        status = TransactionRecord.STATUS_CONFIRMED,
                        latitude = if (needsLoc) fillPlace?.latitude else existing.latitude,
                        longitude = if (needsLoc) fillPlace?.longitude else existing.longitude,
                        locationName = if (needsLoc) fillPlace?.name else existing.locationName
                    )
                    db.transactionDao().update(saved)
                } else {
                    saved = TransactionRecord(
                        type = type, amount = amount, categoryName = resolvedCategory, channelName = channel,
                        paymentMethod = pm, merchant = null, note = note, timestamp = selectedTs,
                        source = TransactionRecord.SOURCE_MANUAL,
                        status = TransactionRecord.STATUS_CONFIRMED,
                        latitude = place?.latitude, longitude = place?.longitude, locationName = place?.name
                    )
                    db.transactionDao().insert(saved)
                }
                savedToDb = true
                HabitLearning.recordReviewed(this@AddEditTransactionActivity, saved,
                    requireExisting = !explicitCategoryFeedback)
                // 待确认数量可能变化（确认了一条 PENDING），刷新常驻通知
                com.mudasir.smartledger.util.PendingNotifier.update(this@AddEditTransactionActivity)

                // 自动备份（增量触发）
                runCatching { AutoBackupManager.backup(this@AddEditTransactionActivity) }

                if (place?.name != null || (saved.locationName?.isNotBlank() == true)) {
                    withContext(Dispatchers.Main) {
                        val shown = saved.locationName ?: place?.name
                        Toast.makeText(this@AddEditTransactionActivity, "已记录地点：$shown", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (t: Throwable) {
                // 后台异常不静默：提示用户并记录（数据可能未保存）
                android.util.Log.w("AddEdit", "save failed", t)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AddEditTransactionActivity,
                        if (savedToDb) "记录已保存，部分后续处理未完成" else "保存失败，请重试", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (savedToDb) finish() else saving = false
                }
            }
        }
    }

    private fun deleteCurrent() {
        val r = editingRecord ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            db.transactionDao().moveToTrash(r.id)
            HabitLearning.remove(this@AddEditTransactionActivity, r.stableId)
            com.mudasir.smartledger.util.PendingNotifier.update(this@AddEditTransactionActivity)
            withContext(Dispatchers.Main) { finish() }
        }
    }

    /**
     * 确保分类存在，并做防碎片化处理：
     * 1) 支持「父-子」格式（如 餐饮-寿司）：父类存在 → 子类挂到父类下（level=2）
     * 2) 归一化精确匹配（trim + 忽略大小写）→ 复用已有分类
     * 3) 唯一 contains 模糊匹配 → 复用已有分类
     * 目的：避免近似名称散落到不同类别，导致统计错分。
     */
    private suspend fun ensureCategory(name: String, type: String): String {
        if (name.isBlank()) return name
        val all = db.categoryDao().getByType(type)

        val dashIdx = name.indexOf('-', 1)
        if (dashIdx > 0 && dashIdx < name.length - 1) {
            val parent = name.substring(0, dashIdx).trim()
            val child = name.substring(dashIdx + 1).trim()
            val parentCat = all.find { it.name.equals(parent, ignoreCase = true) }
            if (parentCat != null && child.isNotBlank()) {
                val existingChild = all.find { it.name.equals(child, true) }
                if (existingChild != null) return existingChild.name
                db.categoryDao().insert(
                    Category(name = child, type = type, isDefault = false, parentName = parentCat.name, level = 2, color = parentCat.color)
                )
                return child
            }
        }

        val exact = all.find { it.name.trim().equals(name.trim(), ignoreCase = true) }
        if (exact != null) return exact.name

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
