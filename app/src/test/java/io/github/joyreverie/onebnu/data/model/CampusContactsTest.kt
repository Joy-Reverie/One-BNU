package io.github.joyreverie.onebnu.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CampusContactsTest {

    private val directory by lazy {
        // 单元测试的工作目录是 app 模块目录
        CampusContactsJson.parse(File("src/main/res/raw/campus_contacts.json").readText())
    }

    @Test
    fun `内置通讯录完整：15 个分区 337 条，号码都是 8 位并带 010 拨号`() {
        assertEquals(15, directory.groups.size)
        assertEquals(337, directory.rowCount)
        assertEquals(4, directory.emergency.size)
        val tels = directory.groups.flatMap { g -> g.sections.flatMap { s -> s.rows.flatMap { it.tels } } }
        assertEquals(349, tels.size)
        assertTrue(tels.all { it.number.matches(Regex("\\d{8}")) && it.dial == "010" + it.number })
        assertTrue(directory.groups.all { it.sourceUrl.startsWith("http") })
        // 来源页面的发布日期：有标注的写 ISO 日期，没标注的留空并说明
        assertEquals("2012-12-29", directory.groups.first { it.id == "safety" }.sourceDate)
        assertEquals("页面发布于 2024年4月2日", directory.groups.first { it.id == "money" }.sourceDateLabel)
        assertEquals("页面未标注发布日期", directory.groups.first { it.id == "academic" }.sourceDateLabel)
        assertTrue(directory.groups.all { it.sourceDate.isEmpty() || it.sourceDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) })
    }

    @Test
    fun `搜索覆盖业务、老师、楼号、号码与分区名`() {
        val byWhat = directory.search("学籍")
        assertTrue(byWhat.any { it.row.what == "本科生学籍" })
        assertTrue(directory.search("58806110").any { it.group.id == "safety" })
        assertTrue(directory.search("主楼 A207").isNotEmpty())
        assertTrue(directory.search("图书馆").size >= 28)
        assertTrue(directory.search("   ").isEmpty())
    }

    @Test
    fun `补充信息里的邮箱单独取出，剩余说明去掉分隔符`() {
        val row = ContactRow("科研院", "服务大厅", emptyList(), "京师大厦 1103 室", "kyy@bnu.edu.cn")
        assertEquals("kyy@bnu.edu.cn", row.email)
        assertEquals("", row.extraText)
        val two = ContactRow("实验室", "", emptyList(), "", "ssc@bnu.edu.cn · 设备平台 yqys@bnu.edu.cn")
        assertEquals("ssc@bnu.edu.cn", two.email)
        assertEquals("设备平台", two.extraText)
        assertEquals("010 5880 6110", ContactTel("58806110", "01058806110").pretty)
        assertEquals("010 8258 8100", ContactTel("82588100", "01082588100").pretty)
    }
}
