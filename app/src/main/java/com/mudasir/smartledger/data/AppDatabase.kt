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
    version = 9,
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
        // Assuming you have removed 'firebaseId' from your Entity classes,
        // we run SQL to clean the database structure.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
            CREATE TABLE IF NOT EXISTS custom_ledgers (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                name TEXT NOT NULL, 
                iconName TEXT NOT NULL, 
                fields TEXT NOT NULL, 
                hasPhotos INTEGER NOT NULL, 
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())

                database.execSQL("""
            CREATE TABLE IF NOT EXISTS custom_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                ledgerId INTEGER NOT NULL, 
                date INTEGER NOT NULL, 
                amount REAL, 
                dataJson TEXT NOT NULL, 
                imagePaths TEXT NOT NULL, 
                isDeleted INTEGER NOT NULL, 
                deletedAt INTEGER
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
                type TEXT NOT NULL,
                amount REAL NOT NULL,
                categoryId INTEGER,
                categoryName TEXT NOT NULL,
                channelName TEXT NOT NULL,
                paymentMethod TEXT,
                merchant TEXT,
                note TEXT,
                timestamp INTEGER NOT NULL,
                source TEXT NOT NULL,
                rawText TEXT,
                packageName TEXT,
                status TEXT NOT NULL,
                isDeleted INTEGER NOT NULL,
                deletedAt INTEGER,
                createdAt INTEGER NOT NULL
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
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                color TEXT NOT NULL,
                iconName TEXT,
                sortOrder INTEGER NOT NULL,
                isDefault INTEGER NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_name_type ON categories (name, type)")

                database.execSQL("""
            CREATE TABLE IF NOT EXISTS payment_channels (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                iconName TEXT,
                isDefault INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_payment_channels_name ON payment_channels (name)")

                seedDefaults(database)
            }

            private fun seedDefaults(database: SupportSQLiteDatabase) {
                val expenseCats = listOf(
                    "餐饮" to "#FF7043", "交通" to "#29B6F6", "购物" to "#AB47BC",
                    "日用" to "#66BB6A", "娱乐" to "#FFCA28", "医疗" to "#EF5350",
                    "居住" to "#78909C", "通讯" to "#26A69A", "教育" to "#5C6BC0", "其他" to "#BDBDBD"
                )
                expenseCats.forEachIndexed { i, (name, color) ->
                    database.execSQL(
                        "INSERT OR IGNORE INTO categories(name,type,color,iconName,sortOrder,isDefault,createdAt) " +
                            "VALUES('$name','EXPENSE','$color',NULL,$i,1,${System.currentTimeMillis()})"
                    )
                }
                val incomeCats = listOf("工资" to "#66BB6A", "理财" to "#26A69A", "红包" to "#EF5350", "退款" to "#29B6F6", "其他收入" to "#BDBDBD")
                incomeCats.forEachIndexed { i, (name, color) ->
                    database.execSQL(
                        "INSERT OR IGNORE INTO categories(name,type,color,iconName,sortOrder,isDefault,createdAt) " +
                            "VALUES('$name','INCOME','$color',NULL,$i,1,${System.currentTimeMillis()})"
                    )
                }
                val channels = listOf("微信支付", "支付宝", "京东", "淘宝", "银行卡", "现金", "其他")
                channels.forEachIndexed { i, name ->
                    database.execSQL(
                        "INSERT OR IGNORE INTO payment_channels(name,iconName,isDefault,sortOrder,createdAt) " +
                            "VALUES('$name',NULL,1,$i,${System.currentTimeMillis()})"
                    )
                }
            }
        }

        // v9: 地理信息 + 多级分类 + 更全支付方式
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // transactions 增列（地理）
                database.execSQL("ALTER TABLE transactions ADD COLUMN latitude REAL")
                database.execSQL("ALTER TABLE transactions ADD COLUMN longitude REAL")
                database.execSQL("ALTER TABLE transactions ADD COLUMN locationName TEXT")
                // categories 增列（多级）
                database.execSQL("ALTER TABLE categories ADD COLUMN parentName TEXT")
                database.execSQL("ALTER TABLE categories ADD COLUMN level INTEGER NOT NULL DEFAULT 1")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_categories_parentName ON categories (parentName)")
                seedSubcategories(database)
                seedMoreChannels(database)
            }

            private fun seedSubcategories(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                // (name, color, parent, sortOrder)
                val expenseChildren = listOf(
                    Sub("食堂", "#FF8A65", "餐饮", 0),
                    Sub("外卖", "#FF7043", "餐饮", 1),
                    Sub("外出用餐", "#D84315", "餐饮", 2),
                    Sub("零食饮料", "#FFAB91", "餐饮", 3),
                    Sub("地铁", "#4FC3F7", "交通", 0),
                    Sub("公交", "#29B6F6", "交通", 1),
                    Sub("打车", "#0288D1", "交通", 2),
                    Sub("火车机票", "#01579B", "交通", 3),
                    Sub("加油停车", "#0277BD", "交通", 4),
                    Sub("服饰", "#BA68C8", "购物", 0),
                    Sub("数码", "#AB47BC", "购物", 1),
                    Sub("日用品", "#8E24AA", "购物", 2),
                    Sub("美妆护肤", "#CE93D8", "购物", 3),
                    Sub("电影演出", "#FFB300", "娱乐", 0),
                    Sub("游戏充值", "#FFCA28", "娱乐", 1),
                    Sub("旅行出游", "#FFA000", "娱乐", 2),
                    Sub("挂号门诊", "#EF5350", "医疗", 0),
                    Sub("药品", "#E53935", "医疗", 1),
                    Sub("房租", "#90A4AE", "居住", 0),
                    Sub("水电燃气", "#78909C", "居住", 1),
                    Sub("物业宽带", "#607D8B", "居住", 2),
                    Sub("话费流量", "#26A69A", "通讯", 0),
                    Sub("课程培训", "#5C6BC0", "教育", 0),
                    Sub("书籍文具", "#3949AB", "教育", 1)
                )
                expenseChildren.forEachIndexed { _, (name, color, parent, so) ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO categories(name,type,color,iconName,sortOrder,isDefault,parentName,level,createdAt) " +
                            "VALUES('$name','EXPENSE','$color',NULL,$so,1,'$parent',2,$now)"
                    )
                }
                val incomeChildren = listOf(
                    Sub("工资", "#66BB6A", "工资", 0),
                    Sub("奖金提成", "#43A047", "工资", 1),
                    Sub("利息分红", "#26A69A", "理财", 0),
                    Sub("基金股票", "#00897B", "理财", 1),
                    Sub("转账退款", "#29B6F6", "退款", 0),
                    Sub("红包礼金", "#EF5350", "红包", 0)
                )
                incomeChildren.forEach { (name, color, parent, so) ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO categories(name,type,color,iconName,sortOrder,isDefault,parentName,level,createdAt) " +
                            "VALUES('$name','INCOME','$color',NULL,$so,1,'$parent',2,$now)"
                    )
                }
            }

            private fun seedMoreChannels(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                val extra = listOf("信用卡", "花呗", "借呗", "Apple Pay", "云闪付", "数字人民币")
                extra.forEachIndexed { i, name ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO payment_channels(name,iconName,isDefault,sortOrder,createdAt) " +
                            "VALUES('$name',NULL,0,${10 + i},$now)"
                    )
                }
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_ledger_db"
                )
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}