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
    fun `内置通讯录完整：16 个分区 386 条，号码都是 8 位并带 010 拨号`() {
        assertEquals(16, directory.groups.size)
        assertEquals(386, directory.rowCount)
        assertEquals(4, directory.emergency.size)
        val tels = directory.groups.flatMap { g -> g.sections.flatMap { s -> s.rows.flatMap { it.tels } } }
        assertEquals(399, tels.size)
        assertTrue(tels.all { it.number.matches(Regex("\\d{8}")) && it.dial == "010" + it.number })
        // 每个分区要么自己有来源页，要么每个小节各自标出来源（科研院一个分区抄自五张页面）
        assertTrue(
            directory.groups.all { g ->
                g.sourceUrl.startsWith("http") || g.sections.all { it.sourceUrl.startsWith("http") }
            },
        )
        // 来源页面的发布日期：有标注的写 ISO 日期，没标注的留空并说明
        assertEquals("2012-12-29", directory.groups.first { it.id == "safety" }.sourceDate)
        assertEquals("页面发布于 2024年4月2日", directory.groups.first { it.id == "money" }.sourceDateLabel)
        assertEquals("页面未标注发布日期", directory.groups.first { it.id == "academic" }.sourceDateLabel)
        assertTrue(
            (directory.groups.map { it.sourceDate } + directory.groups.flatMap { g -> g.sections.map { it.sourceDate } })
                .all { it.isEmpty() || it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) },
        )
    }

    @Test
    fun `科研院按处室分小节，每个小节标出自己那张页面的发布日期`() {
        val ky = directory.groups.first { it.id == "research" }
        assertEquals(39, ky.rowCount)
        assertEquals(
            listOf("服务大厅", "院领导", "综合处", "科学技术处 · 理工科", "社会科学处 · 人文社科", "知识产权管理与科研成果转化办公室"),
            ky.sections.map { it.title },
        )
        assertEquals("页面发布于 2023年10月13日", ky.sections.first { it.title.startsWith("科学技术处") }.sourceDateLabel)
        assertEquals("页面发布于 2023年12月4日", ky.sections.first { it.title.startsWith("社会科学处") }.sourceDateLabel)
        assertEquals("页面未标注发布日期", ky.sections.first { it.title == "服务大厅" }.sourceDateLabel)
        assertTrue(ky.sections.drop(1).all { it.sourceUrl.startsWith("https://keyanyuan.bnu.edu.cn/") })
        // 具体职责能直接搜到对应的人与电话
        assertTrue(directory.search("国家自然科学基金").any { it.row.tels.single().number == "58800226" })
        assertTrue(directory.search("成果转化").any { it.row.who.startsWith("张文舒") })
    }

    @Test
    fun `财经处按来源表逐窗口收录，职责写全`() {
        val money = directory.groups.first { it.id == "money" }
        assertEquals(39, money.rowCount)
        // 同一号码服务多个窗口时逐个列出，不再合并成一行
        assertEquals(2, money.sections.flatMap { it.rows }.count { r -> r.tels.any { it.number == "58808114" } })
        assertTrue(directory.search("博士后进站导师配套").any { it.row.who.startsWith("06 号窗口") })
        assertTrue(directory.search("学宿费").any { it.row.tels.single().number == "58807714" })
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
