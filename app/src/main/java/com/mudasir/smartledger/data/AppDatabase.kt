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
    version = 10,
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

            // ---- 二级小类（EXPENSE）----
            val expenseChildren = listOf(
                Sub("食堂", "#FF8A65", "餐饮", 0),
                Sub("外卖", "#FF7043", "餐饮", 1),
                Sub("下馆子", "#D84315", "餐饮", 2),
                Sub("零食饮料", "#FFAB91", "餐饮", 3),
                Sub("地铁", "#4FC3F7", "交通", 0),
                Sub("公交", "#29B6F6", "交通", 1),
                Sub("打车", "#0288D1", "交通", 2),
                Sub("火车机票", "#01579B", "交通", 3),
                Sub("加油停车", "#0277BD", "交通", 4),
                Sub("服饰", "#BA68C8", "网购", 0),
                Sub("数码", "#AB47BC", "网购", 1),
                Sub("日用品", "#8E24AA", "网购", 2),
                Sub("美妆护肤", "#CE93D8", "网购", 3),
                Sub("超市日用", "#A5D6A7", "日用", 0),
                Sub("电影演出", "#FFB300", "娱乐", 0),
                Sub("游戏充值", "#FFCA28", "娱乐", 1),
                Sub("旅行出游", "#FFA000", "娱乐", 2),
                Sub("挂号门诊", "#EF5350", "医疗", 0),
                Sub("药品", "#E53935", "医疗", 1),
                Sub("房租", "#90A4AE", "居住", 0),
                Sub("水电燃气", "#78909C", "居住", 1),
                Sub("物业宽带", "#607D8B", "居住", 2),
                Sub("话费", "#26A69A", "通讯", 0),
                Sub("流量", "#80CBC4", "通讯", 1),
                Sub("课程培训", "#5C6BC0", "教育", 0),
                Sub("书籍文具", "#3949AB", "教育", 1)
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
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
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
