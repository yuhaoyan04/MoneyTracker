package com.mudasir.smartledger.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定「模型数字类型纠正」行为 —— 用户设备上曾因模型文件含 Double 值，
 * 导致批量学习时抛 ClassCastException: Double cannot be cast to Integer，
 * 1395 笔账单导入全部失败。本测试确保 coerce 修复该病根且不再复发。
 */
class PersonalTaggerCoerceTest {

    @Suppress("UNCHECKED_CAST")
    private fun poisonedModel(): PersonalTagger.Model {
        val m = PersonalTagger.Model()
        // 模拟 Gson 泛型擦除把 JSON 整数反序列化为 Double 存入 Int 结构
        (m.prior as MutableMap<Any?, Any?>)["外卖"] = 42.0
        (m.merchantCorrect as MutableMap<Any?, Any?>)["美团"] = 2.0
        m.feat.getOrPut("hour") { mutableMapOf() }
            .getOrPut("lunch") { mutableMapOf() }
            .also { (it as MutableMap<Any?, Any?>)["外卖"] = 7.0 }
        m.merchantMap.getOrPut("美团") { mutableMapOf() }
            .also { (it as MutableMap<Any?, Any?>)["外卖"] = 5.0 }
        m.merchantHourMap.getOrPut("美团") { mutableMapOf() }
            .getOrPut("lunch") { mutableMapOf() }
            .also { (it as MutableMap<Any?, Any?>)["外卖"] = 4.0 }
        return m
    }

    @Test
    fun `coerce converts doubles to ints in all int maps`() {
        val m = poisonedModel()
        PersonalTagger.coerceModelNumbers(m)
        assertEquals(42, m.prior["外卖"])
        assertEquals(2, m.merchantCorrect["美团"])
        // feat / merchantHourMap 为三层结构（特征→值→分类计数），断言取到计数层
        assertEquals(7, m.feat["hour"]!!["lunch"]!!["外卖"])
        assertEquals(5, m.merchantMap["美团"]!!["外卖"])
        assertEquals(4, m.merchantHourMap["美团"]!!["lunch"]!!["外卖"])
    }

    @Test
    fun `boxed int arithmetic works after coerce`() {
        // 修复前此处抛 ClassCastException: Double cannot be cast to Integer
        val m = poisonedModel()
        PersonalTagger.coerceModelNumbers(m)
        m.prior["外卖"] = (m.prior["外卖"] ?: 0) + 1
        m.merchantMap["美团"]?.let { cats ->
            cats["外卖"] = (cats["外卖"] ?: 0) + 1
        }
        assertEquals(43, m.prior["外卖"])
        assertEquals(6, m.merchantMap["美团"]!!["外卖"])
    }

    @Test
    fun `coerce keeps clean model untouched`() {
        val m = PersonalTagger.Model()
        m.prior["外卖"] = 10
        m.merchantMap.getOrPut("美团") { mutableMapOf() }["外卖"] = 3
        PersonalTagger.coerceModelNumbers(m)
        assertEquals(10, m.prior["外卖"])
        assertEquals(3, m.merchantMap["美团"]!!["外卖"])
        assertTrue(m.feat.isEmpty())
    }

    @Test
    fun `lenient gson parses decimal ints into model`() {
        // 模型文件里 Int 字段写成 42.0 时，旧版严格解析会抛异常并静默重置整个模型
        val json = """{"prior":{"外卖":42.0},"total":5.0,"lastUpdate":1700000000000}"""
        val model = PersonalTagger.lenientGson.fromJson(json, PersonalTagger.Model::class.java)
        assertEquals(42, model.prior["外卖"])
        assertEquals(5, model.total)
        assertEquals(1700000000000L, model.lastUpdate)
    }
}
