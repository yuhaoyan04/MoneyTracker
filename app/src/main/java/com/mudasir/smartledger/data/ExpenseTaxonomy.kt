package com.mudasir.smartledger.data

/** Spending purpose is the primary dimension. Channel, timing and merchant are separate.
 * Names are persisted identifiers in existing ledgers: do not rename or delete them casually. */
object ExpenseTaxonomy {
    data class Group(val name: String, val color: String, val children: List<String>)

    val groups = listOf(
        Group("餐饮", "#C87558", listOf("正餐", "饮品", "零食甜点", "其他餐饮")),
        Group("交通", "#608CAC", listOf("公共交通", "出租与网约车", "城际交通", "车辆费用", "其他交通")),
        Group("居住", "#7D8EA0", listOf("房租", "水电燃气", "物业", "家政保洁", "装修维修", "搬家", "其他居住")),
        Group("日用", "#7D9D7A", listOf("生鲜食材", "清洁洗护", "家居用品", "其他日用")),
        Group("服饰穿搭", "#AA789A", listOf("服装鞋帽", "珠宝配饰", "其他服饰")),
        Group("个人护理", "#C18192", listOf("美妆护肤", "美容美发", "其他个护")),
        Group("数码设备", "#7589B4", listOf("电子设备", "数码配件", "其他数码")),
        Group("家庭照护", "#BA8FA2", listOf("母婴用品", "照护服务", "其他家庭照护")),
        Group("医疗", "#C97A7A", listOf("门诊治疗", "药品医疗用品", "检查预防", "健康保健", "其他医疗")),
        Group("教育", "#7985B8", listOf("课程培训", "教育用品", "考试费用", "其他教育")),
        Group("娱乐", "#C99B58", listOf("电影演出", "游戏充值", "旅行出游", "游乐社交", "运动健身", "兴趣爱好", "会员订阅", "其他娱乐")),
        Group("通讯", "#5C9B96", listOf("通信服务", "其他通讯")),
        Group("人情与公益", "#C99B78", listOf("礼金", "公益捐赠", "其他人情公益")),
        Group("金融保障", "#7695B2", listOf("保险费", "金融服务费", "其他金融")),
        Group("宠物", "#AD917B", listOf("宠物食品用品", "宠物医疗服务", "其他宠物")),
        Group("其他", "#96A2A7", listOf("未分类支出"))
    )

    val rootNames: Set<String> = groups.map { it.name }.toSet()
    val childNames: Set<String> = groups.flatMap { it.children }.toSet()

    /** Only used for old rule-generated suggestions, never to rewrite stored records
     * or a user's learned category. Ambiguous channel labels stay broad. */
    private val ruleAliases = mapOf(
        "食堂" to "正餐", "外卖" to "正餐", "下馆子" to "正餐", "早餐" to "正餐",
        "小吃快餐" to "正餐", "火锅烧烤" to "正餐", "夜宵" to "正餐",
        "零食饮料" to "餐饮", "咖啡茶饮" to "饮品", "甜品蛋糕" to "零食甜点",
        "地铁" to "公共交通", "公交" to "公共交通", "共享单车" to "公共交通",
        "打车" to "出租与网约车", "火车机票" to "城际交通", "加油停车" to "车辆费用",
        "电动车充电" to "车辆费用", "高速过路费" to "车辆费用", "代驾" to "车辆费用",
        "服饰" to "服装鞋帽", "数码" to "电子设备", "日用品" to "其他日用", "KTV酒吧" to "游乐社交",
        "家居家装" to "家居用品", "超市日用" to "日用", "生鲜果蔬" to "生鲜食材",
        "五金维修" to "其他日用", "挂号门诊" to "门诊治疗", "口腔眼科" to "门诊治疗",
        "药品" to "药品医疗用品", "体检疫苗" to "检查预防", "保健养生" to "健康保健",
        "物业宽带" to "居住", "话费" to "通信服务", "流量" to "通信服务",
        "书籍文具" to "教育用品", "考证考试" to "考试费用", "儿童教育" to "课程培训",
        "人情往来" to "礼金", "捐赠公益" to "公益捐赠", "宠物用品" to "宠物食品用品",
        "母婴玩具" to "未分类支出", "运动户外" to "未分类支出", "图书音像" to "未分类支出",
        "知识付费" to "教育", "网购" to "未分类支出", "购物" to "未分类支出", "海淘代购" to "未分类支出", "二手闲置" to "未分类支出"
    )

    fun canonicalRuleSuggestion(name: String): String = ruleAliases[name] ?: name

    fun isCommercePlatform(channel: String, merchant: String?): Boolean {
        val source = (channel + " " + merchant.orEmpty()).lowercase()
        return listOf("淘宝", "天猫", "京东", "拼多多", "苏宁", "得物", "小红书", "唯品会", "网易严选", "抖音", "快手", "微店", "转转", "闲鱼", "douyin", "kuaishou", "poizon").any(source::contains) ||
            channel.trim().equals("jd", ignoreCase = true) || merchant?.trim().equals("jd", ignoreCase = true)
    }

    /** A platform and an amount do not identify the product. Mixed evidence stays
     * uncategorized until the user can split or classify the transaction. */
    fun shoppingSuggestion(description: String): String {
        val text = description.lowercase()
        val deviceText = text.replace("手机壳", "")
        val evidence = listOf(
            "美妆护肤" to listOf("美妆", "护肤", "化妆", "面膜", "口红", "粉底", "乳液", "防晒", "香水"),
            "服装鞋帽" to listOf("服饰", "衣服", "鞋", "服装", "裙子", "外套", "裤子", "内衣", "羽绒服", "卫衣", "衬衫"),
            "电子设备" to listOf("手机", "电脑", "平板电脑", "显示器"),
            "数码配件" to listOf("耳机", "键盘", "鼠标", "手机壳", "数据线"),
            "生鲜食材" to listOf("生鲜", "水果", "蔬菜", "大米"),
            "饮品" to listOf("奶茶", "咖啡", "饮料"),
            "零食甜点" to listOf("零食", "蛋糕", "甜品"),
            "母婴用品" to listOf("尿不湿", "纸尿裤", "婴儿奶粉"),
            "宠物食品用品" to listOf("猫粮", "狗粮", "猫砂")
        ).filter { (category, words) ->
            val candidateText = if (category == "电子设备") deviceText else text
            words.any(candidateText::contains)
        }.map { it.first }.toSet()
        return evidence.singleOrNull() ?: "未分类支出"
    }
}

/** Receipt purpose remains separate from the account or transfer channel. */
object IncomeTaxonomy {
    val groups = listOf(
        ExpenseTaxonomy.Group("工资", "#72A176", listOf("基本工资", "奖金提成", "补贴津贴", "其他工资")),
        ExpenseTaxonomy.Group("经营收入", "#8A9F72", listOf("经营所得", "兼职劳务", "其他经营收入")),
        ExpenseTaxonomy.Group("理财", "#5B9A91", listOf("利息分红", "投资收益", "其他理财收入")),
        ExpenseTaxonomy.Group("资产处置", "#8A95AD", listOf("出售资产", "其他资产处置")),
        ExpenseTaxonomy.Group("赠与收入", "#C17D82", listOf("赠与红包", "其他赠与")),
        ExpenseTaxonomy.Group("退款", "#6D9CB5", listOf("消费退款", "其他退款")),
        ExpenseTaxonomy.Group("其他收入", "#96A2A7", listOf("未分类收入"))
    )
    val rootNames = groups.map { it.name }.toSet()
    fun canonicalRuleSuggestion(name: String): String = when (name) {
        "收发红包", "红包" -> "赠与红包"
        "基金股票" -> "投资收益"
        // The old label mixes unrelated transfers and refunds; do not guess.
        "转账退款" -> "未分类收入"
        else -> name
    }
}
