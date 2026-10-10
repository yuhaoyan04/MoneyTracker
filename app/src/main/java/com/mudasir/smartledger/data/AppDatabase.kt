package com.mudasir.smartledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 二级分类种子：(名称, 颜色, 父分类, 排序)。 */
private data class Sub(val name: String, val color: String, val parent: String, val so: Int)

@Database(
    entities = [
        CalcHistory::class, Expense::class, Electricity::class, MilkRecord::class,
        CustomLedger::class, CustomEntry::class, CustomDailyRecord::class,
        TransactionRecord::class, Category::class, PaymentChannel::class
    ],
    version = 13,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun calcDao(): CalcDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun electricityDao(): ElectricityDao
    abstract fun milkDao(): MilkDao
    abstract fun customLedgerDao(): CustomLedgerDao
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun channelDao(): ChannelDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // Note: SQLite technically supports DROP COLUMN in newer versions,
        // but creating a migration to explicitly ignore them is safer for Room integrity.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
            CREATE TABLE IF NOT EXISTS custom_ledgers (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL, iconName TEXT NOT NULL, fields TEXT NOT NULL,
                hasPhotos INTEGER NOT NULL, createdAt INTEGER NOT NULL
            )
        """.trimIndent())
                database.execSQL("""
            CREATE TABLE IF NOT EXISTS custom_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                ledgerId INTEGER NOT NULL, date INTEGER NOT NULL, amount REAL,
                dataJson TEXT NOT NULL, imagePaths TEXT NOT NULL,
                isDeleted INTEGER NOT NULL, deletedAt INTEGER
            )
        """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_custom_entries_ledgerId ON custom_entries (ledgerId)")
            }
        }

        // v8: 统一交易记录体系 —— 自动抓取 + 自定义分类/渠道
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
            CREATE TABLE IF NOT EXISTS transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                type TEXT NOT NULL, amount REAL NOT NULL, categoryId INTEGER,
                categoryName TEXT NOT NULL, channelName TEXT NOT NULL, paymentMethod TEXT,
                merchant TEXT, note TEXT, timestamp INTEGER NOT NULL, source TEXT NOT NULL,
                rawText TEXT, packageName TEXT, status TEXT NOT NULL,
                isDeleted INTEGER NOT NULL, deletedAt INTEGER, createdAt INTEGER NOT NULL
            )
        """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_timestamp ON transactions (timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_status ON transactions (status)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_type ON transactions (type)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_categoryName ON transactions (categoryName)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_channelName ON transactions (channelName)")

                database.execSQL("""
            CREATE TABLE IF NOT EXISTS categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL, type TEXT NOT NULL, color TEXT NOT NULL, iconName TEXT,
                sortOrder INTEGER NOT NULL, isDefault INTEGER NOT NULL, createdAt INTEGER NOT NULL
            )
        """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_name_type ON categories (name, type)")

                database.execSQL("""
            CREATE TABLE IF NOT EXISTS payment_channels (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL, iconName TEXT, isDefault INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL
            )
        """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_payment_channels_name ON payment_channels (name)")

                seedTaxonomy(database)
            }
        }

        // v9: 地理信息 + 多级分类 + 更全支付方式
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transactions ADD COLUMN latitude REAL")
                database.execSQL("ALTER TABLE transactions ADD COLUMN longitude REAL")
                database.execSQL("ALTER TABLE transactions ADD COLUMN locationName TEXT")
                database.execSQL("ALTER TABLE categories ADD COLUMN parentName TEXT")
                database.execSQL("ALTER TABLE categories ADD COLUMN level INTEGER NOT NULL DEFAULT 1")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_categories_parentName ON categories (parentName)")
                seedTaxonomy(database)
            }
        }

        // v10: 修复分类加载——补齐一级大类（之前迁移只播了二级）、改名（外出用餐→下馆子、购物→网购、话费流量→话费/流量），并把改名同步到交易记录
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 改名：分类 + 子分类 parentName + 交易记录
                database.execSQL("UPDATE categories SET name='下馆子' WHERE name='外出用餐' AND type='EXPENSE'")
                database.execSQL("UPDATE transactions SET categoryName='下馆子' WHERE categoryName='外出用餐'")
                database.execSQL("UPDATE categories SET name='网购' WHERE name='购物' AND type='EXPENSE'")
                database.execSQL("UPDATE categories SET parentName='网购' WHERE parentName='购物'")
                database.execSQL("UPDATE transactions SET categoryName='网购' WHERE categoryName='购物'")
                database.execSQL("UPDATE categories SET name='话费' WHERE name='话费流量' AND type='EXPENSE'")
                database.execSQL("UPDATE transactions SET categoryName='话费' WHERE categoryName='话费流量'")
                // 幂等补齐：一级大类 + 二级 + 渠道（缺什么补什么，已有的不动）
                seedTaxonomy(database)
            }
        }

        // v11: AI 打标签名度（收件箱展示「AI 信心 92%」）
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transactions ADD COLUMN aiConfidence REAL")
            }
        }

        // v12: 丰富二级子类 —— 覆盖日常生活全场景（幂等，已有的不动）
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                seedTaxonomy(database)
            }
        }

        // v13: 补齐日常分类，并把少数旧子类归入更准确的新大类。
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                seedTaxonomy(database)
                database.execSQL("UPDATE categories SET parentName='服饰美容' WHERE type='EXPENSE' AND name IN ('服饰','美妆护肤')")
                database.execSQL("UPDATE categories SET parentName='家庭育儿' WHERE type='EXPENSE' AND name='母婴玩具'")
                database.execSQL("UPDATE categories SET parentName='宠物' WHERE type='EXPENSE' AND name='宠物用品'")
                database.execSQL("UPDATE categories SET parentName='金融保险' WHERE type='EXPENSE' AND name='保险费'")
                database.execSQL("UPDATE categories SET sortOrder=15 WHERE type='EXPENSE' AND level=1 AND name='其他'")
                database.execSQL("UPDATE categories SET sortOrder=8 WHERE type='INCOME' AND level=1 AND name='其他收入'")
            }
        }

        /**
         * 幂等播种全量分类与渠道（一级大类 + 二级小类 + 支付方式）。
         * 同时用于：v7→v8、v8→v9、v9→v10 迁移，以及 onCreate（全新安装）。
         * 这样无论全新安装还是各升级路径，分类都齐全。
         */
        private fun seedTaxonomy(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()

            // ---- 一级大类（EXPENSE）----
            val expenseRoots = listOf(
                "餐饮" to "#FF7043", "交通" to "#29B6F6", "网购" to "#AB47BC",
                "日用" to "#66BB6A", "娱乐" to "#FFCA28", "医疗" to "#EF5350",
                "居住" to "#78909C", "通讯" to "#26A69A", "教育" to "#5C6BC0",
                "服饰美容" to "#EC407A", "家庭育儿" to "#AB47BC", "宠物" to "#8D6E63",
                "人情社交" to "#FF8A65", "金融保险" to "#42A5F5", "工作商务" to "#5C6BC0",
                "其他" to "#90A4AE"
            )
            expenseRoots.forEachIndexed { i, (n, c) ->
                db.execSQL(
                    "INSERT OR IGNORE INTO categories(name,type,color,iconName,sortOrder,isDefault,parentName,level,createdAt) " +
                        "VALUES('$n','EXPENSE','$c',NULL,$i,1,NULL,1,$now)"
                )
            }
            // ---- 一级大类（INCOME）----
            val incomeRoots = listOf(
                "工资" to "#66BB6A", "理财" to "#26A69A", "红包" to "#EF5350",
                "退款" to "#29B6F6", "兼职" to "#7E57C2", "经营" to "#FF7043",
                "报销" to "#42A5F5", "资产处置" to "#78909C", "其他收入" to "#90A4AE"
            )
            incomeRoots.forEachIndexed { i, (n, c) ->
                db.execSQL(
                    "INSERT OR IGNORE INTO categories(name,type,color,iconName,sortOrder,isDefault,parentName,level,createdAt) " +
                        "VALUES('$n','INCOME','$c',NULL,$i,1,NULL,1,$now)"
                )
            }

            // ---- 二级小类（EXPENSE）—— 覆盖日常生活全场景 ----
            val expenseChildren = listOf(
                // 餐饮
                Sub("食堂", "#FF8A65", "餐饮", 0),
                Sub("外卖", "#FF7043", "餐饮", 1),
                Sub("下馆子", "#D84315", "餐饮", 2),
                Sub("零食饮料", "#FFAB91", "餐饮", 3),
                Sub("早餐", "#FFB74D", "餐饮", 4),
                Sub("小吃快餐", "#FFA726", "餐饮", 5),
                Sub("火锅烧烤", "#F4511E", "餐饮", 6),
                Sub("咖啡茶饮", "#BF360C", "餐饮", 7),
                Sub("甜品蛋糕", "#FFCC80", "餐饮", 8),
                Sub("夜宵", "#FF8A80", "餐饮", 9),
                // 交通
                Sub("地铁", "#4FC3F7", "交通", 0),
                Sub("公交", "#29B6F6", "交通", 1),
                Sub("打车", "#0288D1", "交通", 2),
                Sub("火车机票", "#01579B", "交通", 3),
                Sub("加油停车", "#0277BD", "交通", 4),
                Sub("共享单车", "#81D4FA", "交通", 5),
                Sub("电动车充电", "#4DD0E1", "交通", 6),
                Sub("高速过路费", "#039BE5", "交通", 7),
                Sub("代驾", "#0288D1", "交通", 8),
                // 网购
                Sub("数码", "#AB47BC", "网购", 1),
                Sub("日用品", "#8E24AA", "网购", 2),
                Sub("家居家装", "#7E57C2", "网购", 4),
                Sub("运动户外", "#26C6DA", "网购", 6),
                Sub("图书音像", "#5C6BC0", "网购", 7),
                Sub("二手闲置", "#9FA8DA", "网购", 8),
                Sub("海淘代购", "#B39DDB", "网购", 9),
                Sub("综合购物", "#AB47BC", "网购", 10),
                // 日用
                Sub("超市日用", "#A5D6A7", "日用", 0),
                Sub("生鲜果蔬", "#81C784", "日用", 1),
                Sub("清洁洗护", "#66BB6A", "日用", 2),
                Sub("五金维修", "#4DB6AC", "日用", 3),
                Sub("厨房用品", "#81C784", "日用", 4),
                Sub("纸品耗材", "#A5D6A7", "日用", 5),
                Sub("洗衣洗鞋", "#4DB6AC", "日用", 6),
                // 娱乐
                Sub("电影演出", "#FFB300", "娱乐", 0),
                Sub("游戏充值", "#FFCA28", "娱乐", 1),
                Sub("旅行出游", "#FFA000", "娱乐", 2),
                Sub("KTV酒吧", "#FFD54F", "娱乐", 3),
                Sub("运动健身", "#4DB6AC", "娱乐", 4),
                Sub("兴趣爱好", "#AED581", "娱乐", 5),
                Sub("会员订阅", "#FFB74D", "娱乐", 6),
                Sub("直播打赏", "#FFE082", "娱乐", 7),
                // 医疗
                Sub("挂号门诊", "#EF5350", "医疗", 0),
                Sub("药品", "#E53935", "医疗", 1),
                Sub("体检疫苗", "#EF9A9A", "医疗", 2),
                Sub("口腔眼科", "#C62828", "医疗", 3),
                Sub("保健养生", "#FF8A65", "医疗", 4),
                Sub("美容美发", "#AD1457", "医疗", 5),
                // 居住
                Sub("房租", "#90A4AE", "居住", 0),
                Sub("水电燃气", "#78909C", "居住", 1),
                Sub("物业宽带", "#607D8B", "居住", 2),
                Sub("家政保洁", "#B0BEC5", "居住", 3),
                Sub("装修维修", "#8D6E63", "居住", 4),
                Sub("搬家", "#A1887F", "居住", 5),
                Sub("房贷", "#78909C", "居住", 6),
                Sub("家具家电", "#8D6E63", "居住", 7),
                Sub("酒店住宿", "#90A4AE", "居住", 8),
                // 通讯
                Sub("话费", "#26A69A", "通讯", 0),
                Sub("流量", "#80CBC4", "通讯", 1),
                Sub("宽带", "#4DB6AC", "通讯", 2),
                Sub("手机配件", "#26A69A", "通讯", 3),
                // 教育
                Sub("课程培训", "#5C6BC0", "教育", 0),
                Sub("书籍文具", "#3949AB", "教育", 1),
                Sub("知识付费", "#7986CB", "教育", 2),
                Sub("考证考试", "#5E35B1", "教育", 3),
                Sub("儿童教育", "#9575CD", "教育", 4),
                Sub("学费", "#5C6BC0", "教育", 5),
                Sub("留学游学", "#7986CB", "教育", 6),
                // 服饰美容
                Sub("服饰", "#EC407A", "服饰美容", 0),
                Sub("鞋帽箱包", "#D81B60", "服饰美容", 1),
                Sub("美妆护肤", "#F06292", "服饰美容", 2),
                Sub("理发造型", "#AD1457", "服饰美容", 3),
                Sub("美容美甲", "#C2185B", "服饰美容", 4),
                Sub("洗衣护理", "#F48FB1", "服饰美容", 5),
                // 家庭育儿
                Sub("母婴玩具", "#AB47BC", "家庭育儿", 0),
                Sub("奶粉尿裤", "#BA68C8", "家庭育儿", 1),
                Sub("儿童医疗", "#9575CD", "家庭育儿", 2),
                Sub("老人赡养", "#7E57C2", "家庭育儿", 3),
                Sub("家庭共同支出", "#CE93D8", "家庭育儿", 4),
                // 宠物
                Sub("宠物用品", "#8D6E63", "宠物", 0),
                Sub("宠物食品", "#A1887F", "宠物", 1),
                Sub("宠物医疗", "#795548", "宠物", 2),
                Sub("洗护寄养", "#BCAAA4", "宠物", 3),
                // 人情社交
                Sub("红包礼金", "#FF7043", "人情社交", 0),
                Sub("请客聚会", "#FF8A65", "人情社交", 1),
                Sub("礼物", "#FFAB91", "人情社交", 2),
                Sub("孝敬长辈", "#FFB74D", "人情社交", 3),
                // 金融保险
                Sub("保险费", "#42A5F5", "金融保险", 0),
                Sub("贷款利息", "#1E88E5", "金融保险", 1),
                Sub("手续费", "#64B5F6", "金融保险", 2),
                Sub("税费", "#1976D2", "金融保险", 3),
                Sub("投资亏损", "#EF5350", "金融保险", 4),
                // 工作商务
                Sub("办公用品", "#5C6BC0", "工作商务", 0),
                Sub("商务差旅", "#3949AB", "工作商务", 1),
                Sub("客户招待", "#7986CB", "工作商务", 2),
                Sub("软件服务", "#3F51B5", "工作商务", 3),
                // 其他
                Sub("捐赠公益", "#81C784", "其他", 0),
                Sub("丢失赔偿", "#90A4AE", "其他", 1),
                Sub("罚款违约", "#78909C", "其他", 2),
                Sub("无法归类", "#B0BEC5", "其他", 3)
            )
            expenseChildren.forEach { (n, c, p, so) ->
                db.execSQL(
                    "INSERT OR IGNORE INTO categories(name,type,color,iconName,sortOrder,isDefault,parentName,level,createdAt) " +
                        "VALUES('$n','EXPENSE','$c',NULL,$so,1,'$p',2,$now)"
                )
            }

            // ---- 二级小类（INCOME）----
            val incomeChildren = listOf(
                Sub("基本工资", "#66BB6A", "工资", 0),
                Sub("奖金提成", "#43A047", "工资", 1),
                Sub("利息分红", "#26A69A", "理财", 0),
                Sub("基金股票", "#00897B", "理财", 1),
                Sub("房租收入", "#26A69A", "理财", 2),
                Sub("收发红包", "#EF5350", "红包", 0),
                Sub("转账退款", "#29B6F6", "退款", 0),
                Sub("购物退款", "#4FC3F7", "退款", 1),
                Sub("兼职劳务", "#7E57C2", "兼职", 0),
                Sub("稿费佣金", "#9575CD", "兼职", 1),
                Sub("经营收入", "#FF7043", "经营", 0),
                Sub("销售货款", "#FF8A65", "经营", 1),
                Sub("差旅报销", "#42A5F5", "报销", 0),
                Sub("费用报销", "#64B5F6", "报销", 1),
                Sub("二手出售", "#78909C", "资产处置", 0),
                Sub("资产转让", "#90A4AE", "资产处置", 1),
                Sub("其他进账", "#B0BEC5", "其他收入", 0)
            )
            incomeChildren.forEach { (n, c, p, so) ->
                db.execSQL(
                    "INSERT OR IGNORE INTO categories(name,type,color,iconName,sortOrder,isDefault,parentName,level,createdAt) " +
                        "VALUES('$n','INCOME','$c',NULL,$so,1,'$p',2,$now)"
                )
            }

            // ---- 支付方式 / 渠道 ----
            val channels = listOf(
                "微信支付", "支付宝", "京东", "淘宝", "银行卡", "现金",
                "信用卡", "花呗", "借呗", "Apple Pay", "云闪付", "数字人民币", "其他"
            )
            channels.forEachIndexed { i, name ->
                db.execSQL(
                    "INSERT OR IGNORE INTO payment_channels(name,iconName,isDefault,sortOrder,createdAt) " +
                        "VALUES('$name',NULL,1,$i,$now)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_ledger_db"
                )
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                    // 全新安装时 onCreate 播种全量分类（迁移不会在全新库上执行）
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            seedTaxonomy(db)
                        }
                    })
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
