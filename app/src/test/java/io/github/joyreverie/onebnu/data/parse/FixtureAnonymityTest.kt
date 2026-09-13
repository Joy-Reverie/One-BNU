package io.github.joyreverie.onebnu.data.parse

import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 夹具脱敏守卫。
 *
 * 仓库是公开的，CONTRIBUTING 也声明了「学号、姓名、院系、课程与教师名都是虚构值」。
 * 直接把线上抓取的页面贴进 `fixtures/` 很容易把真实教师名和院系一并带进来
 * （1.9.x 就发生过），因此这里把这条规则钉成测试：夹具里出现的人名和院系
 * 必须来自下面这套固定的虚构数据集。新增夹具时按同一套改写，或把新的虚构名加进白名单。
 */
class FixtureAnonymityTest {

    /** 虚构数据集里的人名：教师用双名，学生固定张三。新增夹具请复用这一组。 */
    private val allowedNames = setOf(
        "张三",
        "王明", "李华", "张伟", "刘洋", "刘芳", "陈静", "赵磊", "周杰", "吴敏",
        "孙丽", "何强", "徐静", "林芳", "郑浩", "高峰",
    )

    /** 虚构数据集里的院系。 */
    private val allowedFaculties = setOf("人工智能学院")

    /** 单测的工作目录通常是模块目录，从仓库根跑时也能找到。 */
    private val fixtureDir: File
        get() = listOf(File("src/test/resources/fixtures"), File("app/src/test/resources/fixtures"))
            .first { it.isDirectory }

    private val fixtures: List<File>
        get() = fixtureDir.listFiles()?.sortedBy { it.name }.orEmpty()

    @Test
    fun `夹具目录存在且不为空`() {
        assertTrue("找不到 fixtures 目录", fixtures.isNotEmpty())
    }

    @Test
    fun `夹具里不出现真实院系`() {
        for (file in fixtures) {
            val text = file.readText()
            Regex("[\\u4e00-\\u9fa5]{2,8}(?:学院|学部)").findAll(text).forEach { m ->
                assertTrue(
                    "${file.name} 含疑似真实院系「${m.value}」，请改用虚构的人工智能学院",
                    m.value in allowedFaculties,
                )
            }
        }
    }

    @Test
    fun `教室课表的教师列全部是虚构姓名`() {
        val html = fixtureDir.resolve("classroom_list.html").readText()
        val teachers = teacherColumn(html)
        assertTrue("没有从 classroom_list.html 里读到教师列", teachers.isNotEmpty())
        teachers.forEach { name ->
            assertTrue("classroom_list.html 含疑似真实教师名「$name」", name in allowedNames)
        }
    }

    @Test
    fun `课表夹具的任课教师全部是虚构姓名`() {
        val html = fixtureDir.resolve("schedule_list.html").readText()
        val term = io.github.joyreverie.onebnu.data.model.Term("2026", "0", "2026-2027学年秋季学期")
        val teachers = Parsers.parseSchedule(html, term).courses.flatMap { it.teachers }.toSet()
        assertTrue("没有从 schedule_list.html 里读到任课教师", teachers.isNotEmpty())
        teachers.forEach { name ->
            assertTrue("schedule_list.html 含疑似真实教师名「$name」", name in allowedNames)
        }
    }

    /** 取出报表里「教师」那一列的全部取值。 */
    private fun teacherColumn(html: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (table in Jsoup.parse(html).select("table")) {
            val rows = table.select("tr")
            val header = rows.firstOrNull { row -> row.select("td,th").any { it.text().trim() == "教师" } } ?: continue
            val index = header.select("td,th").indexOfFirst { it.text().trim() == "教师" }
            rows.drop(rows.indexOf(header) + 1).forEach { row ->
                val cells = row.select("td,th")
                if (cells.size > index) {
                    cells[index].text().split('、', ';', '；', ',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach(out::add)
                }
            }
        }
        return out
    }
}
