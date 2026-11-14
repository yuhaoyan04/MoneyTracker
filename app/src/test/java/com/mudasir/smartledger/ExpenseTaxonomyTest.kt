package com.mudasir.smartledger

import com.mudasir.smartledger.data.ExpenseTaxonomy
import com.mudasir.smartledger.data.IncomeTaxonomy
import org.junit.Assert.*
import org.junit.Test

class ExpenseTaxonomyTest {
    @Test fun `default names have one owner and every group offers a fallback`() {
        val groups = ExpenseTaxonomy.groups
        val roots = groups.map { it.name }
        val children = groups.flatMap { it.children }
        assertEquals(roots.size, roots.toSet().size)
        assertEquals(children.size, children.toSet().size)
        assertTrue(roots.intersect(children.toSet()).isEmpty())
        assertTrue(groups.all { group -> group.children.any { it.startsWith("其他") || it == "未分类支出" } })
    }

    @Test fun `personal care and medical goods do not share a parent`() {
        fun owner(name: String) = ExpenseTaxonomy.groups.single { name in it.children }.name
        assertEquals("个人护理", owner("美妆护肤"))
        assertEquals("个人护理", owner("美容美发"))
        assertEquals("医疗", owner("药品医疗用品"))
        assertFalse("网购" in ExpenseTaxonomy.rootNames)
        assertEquals("未分类支出", ExpenseTaxonomy.canonicalRuleSuggestion("网购"))
        assertEquals("公共交通", ExpenseTaxonomy.canonicalRuleSuggestion("地铁"))
    }

    @Test fun `income names are unique and include an uncategorized fallback`() {
        val groups = IncomeTaxonomy.groups
        val roots = groups.map { it.name }
        val children = groups.flatMap { it.children }
        assertEquals(roots.size, roots.toSet().size)
        assertEquals(children.size, children.toSet().size)
        assertTrue(roots.intersect(children.toSet()).isEmpty())
        assertTrue(groups.all { it.children.any { child -> child.startsWith("其他") || child == "未分类收入" } })
        assertEquals("未分类收入", IncomeTaxonomy.canonicalRuleSuggestion("转账退款"))
    }

    @Test fun `platform or ambiguous mixed purchases never imply a product category`() {
        assertTrue(ExpenseTaxonomy.isCommercePlatform("微信支付", "京东"))
        assertEquals("未分类支出", ExpenseTaxonomy.shoppingSuggestion("淘宝 支付成功 398.06元"))
        assertEquals("美妆护肤", ExpenseTaxonomy.shoppingSuggestion("淘宝 面膜"))
        assertEquals("数码配件", ExpenseTaxonomy.shoppingSuggestion("淘宝 手机壳"))
        assertEquals("未分类支出", ExpenseTaxonomy.shoppingSuggestion("淘宝 手机和手机壳"))
        assertEquals("未分类支出", ExpenseTaxonomy.shoppingSuggestion("京东 面膜和猫粮"))
    }
}
