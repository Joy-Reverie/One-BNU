package io.github.joyreverie.onebnu.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 内嵌页下载的文件名、上传选择器的类型限定；地址里的会话参数都是编的。 */
class WebFilesTest {

    private val panUrl =
        "https://pan.bnu.edu.cn/v2/dl_router/databox/%2F%E6%96%87%E4%BB%B6%2Freport.pdf?S=sig0001&X-LENOVO-SESS-ID=tok0001"
    private val noNameUrl = "https://pan.bnu.edu.cn/v2/dl_router/databox/%2Freadme?S=sig0001"

    private val mimes = mapOf(
        "pdf" to "application/pdf",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )

    private fun accepted(vararg accept: String) = acceptedMimeTypes(accept.toList(), mimes::get)

    @Test
    fun `文件名：filename* 按声明的字符集解码，且优先于 filename`() {
        assertEquals(
            "文件.pdf",
            downloadFileName(noNameUrl, "attachment; filename*=UTF-8''%E6%96%87%E4%BB%B6.pdf"),
        )
        assertEquals(
            "报告.pdf",
            downloadFileName(
                noNameUrl,
                "attachment; filename=\"fallback.pdf\"; filename*=utf-8''%E6%8A%A5%E5%91%8A.pdf",
            ),
        )
        assertEquals("£ rates.pdf", downloadFileName(noNameUrl, "attachment; filename*=iso-8859-1'en'%A3%20rates.pdf"))
    }

    @Test
    fun `文件名：filename* 解不开时退回 filename`() {
        assertEquals(
            "ok.pdf",
            downloadFileName(noNameUrl, "attachment; filename*=UTF-8''%E6%96; filename=\"ok.pdf\""),
        )
    }

    @Test
    fun `文件名：普通 filename 带不带引号都认，塞在里面的百分号编码能解就解`() {
        assertEquals("report 2026.pdf", downloadFileName(noNameUrl, "attachment; filename=\"report 2026.pdf\""))
        assertEquals("plain.docx", downloadFileName(noNameUrl, "attachment;filename=plain.docx"))
        assertEquals("报告.docx", downloadFileName(noNameUrl, "attachment; filename=\"%E6%8A%A5%E5%91%8A.docx\""))
        // 解不开的百分号原样保留；+ 不是空格
        assertEquals("100%.pdf", downloadFileName(noNameUrl, "attachment; filename=\"100%.pdf\""))
        assertEquals("a+b.pdf", downloadFileName(noNameUrl, "attachment; filename=a+b.pdf"))
        // 引号里的转义
        assertEquals("say _hi_.pdf", downloadFileName(noNameUrl, "attachment; filename=\"say \\\"hi\\\".pdf\""))
    }

    @Test
    fun `文件名：响应头没给就取云盘下载地址解码后的最后一截`() {
        assertEquals("report.pdf", downloadFileName(panUrl, null))
        assertEquals("report.pdf", downloadFileName(panUrl, "attachment"))
        assertEquals("report.pdf", downloadFileName(panUrl, "attachment; filename=\"\""))
        assertEquals(
            "报告.pdf",
            downloadFileName("https://pan.bnu.edu.cn/v2/dl_router/databox/%2F%E6%8A%A5%E5%91%8A.pdf?S=sig0001", null),
        )
    }

    @Test
    fun `文件名：地址最后一截不带扩展名时取不到`() {
        assertNull(downloadFileName(noNameUrl, null))
        assertNull(downloadFileName("https://pan.bnu.edu.cn/", null))
        assertNull(downloadFileName("https://pan.bnu.edu.cn/v2/dl_router/databox/.pdf", null))
        assertNull(downloadFileName("not a url", null))
    }

    @Test
    fun `文件名：文件系统不接受的字符、控制字符换成下划线，开头的点去掉`() {
        assertEquals("a_b_c_d_.pdf", downloadFileName(noNameUrl, "attachment; filename=\"a/b:c*d?.pdf\""))
        assertEquals("a_b.pdf", downloadFileName(noNameUrl, "attachment; filename=\"a\\\\b.pdf\""))
        assertEquals("line_break.txt", downloadFileName(noNameUrl, "attachment; filename*=UTF-8''line%0Abreak.txt"))
        assertEquals("hidden.pdf", downloadFileName(noNameUrl, "attachment; filename=\"..hidden.pdf\""))
        // 清理完什么都不剩，就当响应头没给名字
        assertEquals("report.pdf", downloadFileName(panUrl, "attachment; filename=\"...\""))
    }

    @Test
    fun `文件名：过长时截短但保住扩展名`() {
        val long = downloadFileName(noNameUrl, "attachment; filename=\"${"文".repeat(200)}.pdf\"")!!
        assertEquals(120, long.length)
        assertEquals("文".repeat(116) + ".pdf", long)
        // 扩展名本身太长（或者没有）就直接截
        assertEquals("a".repeat(120), downloadFileName(noNameUrl, "attachment; filename=\"${"a".repeat(200)}\""))
    }

    @Test
    fun `上传类型：没写 accept 就什么都能选`() {
        assertEquals(emptyList<String>(), accepted())
        assertEquals(emptyList<String>(), accepted(""))
        assertEquals(emptyList<String>(), accepted(" , "))
    }

    @Test
    fun `上传类型：MIME 原样保留，扩展名查表，逗号分隔的也拆开`() {
        assertEquals(listOf("image/*"), accepted("image/*"))
        assertEquals(listOf("application/pdf"), accepted(".PDF"))
        assertEquals(listOf("image/*", "application/pdf"), accepted("image/*,.pdf"))
        assertEquals(listOf("image/*", "application/pdf"), accepted("image/*", ".pdf"))
        assertEquals(listOf("image/png"), accepted("image/png", " IMAGE/PNG "))
    }

    @Test
    fun `上传类型：有一项是任意类型或认不出来，就不加限制`() {
        assertEquals(emptyList<String>(), accepted(".xyz"))
        assertEquals(emptyList<String>(), accepted("image/*", ".xyz"))
        assertEquals(emptyList<String>(), accepted("*/*"))
        assertEquals(emptyList<String>(), accepted("image/*,*/*"))
        assertEquals(emptyList<String>(), accepted("image"))
    }
}
