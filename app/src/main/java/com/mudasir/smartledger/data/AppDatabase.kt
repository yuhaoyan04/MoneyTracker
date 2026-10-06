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
    version = 12,
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
                "居住" to "#78909C", "通讯" to "#26A69A", "教育" to "#5C6BC0", "其他" to "#90A4AE"
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
                "退款" to "#29B6F6", "其他收入" to "#90A4AE"
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
                Sub("服饰", "#BA68C8", "网购", 0),
                Sub("数码", "#AB47BC", "网购", 1),
                Sub("日用品", "#8E24AA", "网购", 2),
                Sub("美妆护肤", "#CE93D8", "网购", 3),
                Sub("家居家装", "#7E57C2", "网购", 4),
                Sub("母婴玩具", "#F48FB1", "网购", 5),
                Sub("运动户外", "#26C6DA", "网购", 6),
                Sub("图书音像", "#5C6BC0", "网购", 7),
                Sub("二手闲置", "#9FA8DA", "网购", 8),
                Sub("海淘代购", "#B39DDB", "网购", 9),
                // 日用
                Sub("超市日用", "#A5D6A7", "日用", 0),
                Sub("生鲜果蔬", "#81C784", "日用", 1),
                Sub("清洁洗护", "#66BB6A", "日用", 2),
                Sub("五金维修", "#4DB6AC", "日用", 3),
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
                // 通讯
                Sub("话费", "#26A69A", "通讯", 0),
                Sub("流量", "#80CBC4", "通讯", 1),
                // 教育
                Sub("课程培训", "#5C6BC0", "教育", 0),
                Sub("书籍文具", "#3949AB", "教育", 1),
                Sub("知识付费", "#7986CB", "教育", 2),
                Sub("考证考试", "#5E35B1", "教育", 3),
                Sub("儿童教育", "#9575CD", "教育", 4),
                // 其他
                Sub("人情往来", "#FFD180", "其他", 0),
                Sub("宠物用品", "#A1887F", "其他", 1),
                Sub("保险费", "#90CAF9", "其他", 2),
                Sub("捐赠公益", "#81C784", "其他", 3)
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
                Sub("收发红包", "#EF5350", "红包", 0),
                Sub("转账退款", "#29B6F6", "退款", 0)
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
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
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
