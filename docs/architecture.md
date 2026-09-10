# 技术说明

本文记录 One BNU 与学校系统对接的关键细节，以及几处不那么显然的实现选择。目录结构见 README。

## 登录：统一身份认证（CAS）

与网页端同一套流程，全部在应用内完成，没有中间服务器：

```
GET  /cas/login?service=…            取 lt / execution / 表单 action
rsa = strEnc(用户名 + 密码 + lt, "1", "2", "3")
POST /cas/secondAuth  method=check   探测是否需要二次认证
       ├─ info == "noAuth"           直接提交登录表单
       └─ 否则 info 为打码的手机号
            POST … method=send       下发短信验证码
            POST … method=login&code 校验验证码
POST /cas/login?service=…            CASTGC 落地，之后凭票据 SSO 进各子系统
```

`strEnc` 是服务端指定的非标准三重 DES（`/cas/comm/js/des.js`），逐位移植在 `core/crypto/KingoDes.kt`，
用线上 JS 实跑得到的向量做差分测试。**不能替换成标准 DES**，两者结果不同。

二次认证只实现了短信方式；网页端另有企业微信扫码，应用内未做。

## 设备标识

认证服务靠 `devInfo` Cookie 认设备，不认识的设备要求短信验证。这份记号必须跨进程留存
（`core/store/DeviceIdentity.kt`），否则每次启动都是「新设备」。登录表单里的 `device` 字段网页端用的是
浏览器指纹，这里换成**安装时生成的随机值**：同一台设备保持一致，又不采集任何硬件信息，清除应用数据即可切断关联。
「我的 → 设置 → 网络诊断」可以查看并重置。

## 明文与重定向

`http://zyfw.bnu.edu.cn/` 会 302 到 **明文的** `http://cas.bnu.edu.cn/cas/login?…`。应用默认禁止明文流量，
只对确实没有 HTTPS 的教务与图书馆主机放行（`res/xml/network_security_config.xml`），跟随这一跳会被 Android
的明文策略掐断，表现为登录最后一步失败、SSO 进教务读不到数据。

解决办法不是把认证站点也加进明文白名单，而是 `core/net/Http.kt` 自己接管重定向，在跟随之前做**单向协议升级**：
能用 HTTPS 的北师大主机一律改走 HTTPS，只有 HTTP 的那几台保持原样（`BnuHosts`）。内嵌浏览器同样处理。

教务系统（KINGOSOFT）大量页面以 GBK 返回且不带 charset 头，`Http` 先按 UTF-8 试解，出现替换字符再按 GBK。

## 数据来源

| 功能 | 接口 | 说明 |
|---|---|---|
| 课表 | `wsxk/xkjg.ckdgxsxdkchj_data10319.jsp` | 表格无课程类别列 |
| 成绩 | `xscj.chkdgxscjyxxjd_data.jsp` | 按列名而非列序定位；含「课程性质」 |
| 考试 | `DataTable.jsp?tableId=2538` | |
| 空闲教室 | 教室课表取补集 | 只反映排课占用 |
| 学籍 | `STU_BaseInfoAction.do`（XML） | 身份证号、准考证号等敏感字段不展示 |
| 培养方案要求 | `DataTable.jsp?tableId=6033` | 学分核算里有数据时附带显示 |
| 校历 | 无接口 | 手工录入，见 `data/model/OfficialCalendar.kt` |
| 作息时间 | 无接口 | `Settings.PERIOD_TIMES` 按学校统一作息生成 |

解析全部在 `data/parse/Parsers.kt`，用 Jsoup；单元测试的样本在 `app/src/test/resources/fixtures/`，已脱敏。

## 课表缓存与桌面小组件

应用每次成功加载当前学期课表就写一份到本机（`core/store/ScheduleCache.kt`，只含课程、时间、地点、教师），
小组件只读缓存渲染。后台刷新走 JobScheduler（`widget/WidgetRefreshJob.kt`）：缓存超过 6 小时或手动点刷新时
用已保存的账号静默登录拉一次；没保存密码或需要短信验证时只提示打开应用。

后台任务不设「有网络」约束——国内网络常通不过系统的联网校验，设了条件任务永远等不到，改为没网时快速失败并在小组件上提示。
半小时一次的系统周期更新只重绘、不联网，用来跨零点翻页和更新「进行中 / 已结束」标记。

四种尺寸对应四个 provider（启动器要求每个默认尺寸是独立的 receiver 类）。MIUI 在未授权「桌面快捷方式」时会把
`requestPinAppWidget` 静默吞掉且没有公开的申请接口，`widget/MiuiShortcutPermission.kt` 通过 AppOps 10017 读状态并
跳到 MIUI 的权限编辑页；其他情况给手动添加步骤。

## 上课提醒

每次只向 AlarmManager 登记**一个**定时（下一次需要提醒的时刻，`core/notify/ClassReminder.kt`），到点发通知后再登记下一个；
课表缓存或日程变动、开机、时区 / 时间变化、应用升级后重算。用 `setExactAndAllowWhileIdle`，Android 12 上若「闹钟和提醒」
权限未开则退回非精确定时。提醒是普通通知，不响铃。

## 个人日程

存在应用私有目录的 `personal_events.json`（`core/store/PersonalEventStore.kt`），不上传、不同步。重复规则以 `repeat`
（星期几数组）和 `until` 字段存储，旧数据没有这两个字段照常读。课表网格按作息时间把日程时刻映射到节次
（`data/model/PeriodMapper.kt`）。

## 学分核算

教务的选课课程表没有课程类别列，归类依据按优先级：用户手动指定 > 成绩单的「课程性质」> 推断
（`data/model/CreditLedger.kt` 的 `CategoryRules`：GRA 开头是研究生院公共课，按课名分必修 / 选修；院系开课 3 学分及以上算
学位基础课，其余算学位专业课）。「重修」显示但不计学分。手动归类存本机（`CreditCategoryStore`）。

## 校内联系方式

数据是 `app/src/main/res/raw/campus_contacts.json`：15 个分区、337 条、349 个号码，逐条抄自各单位官网「联系我们」页，
每个分区带来源链接和来源页面自己标注的发布日期。`docs/bnu-directory.html` 是检索时的原始整理稿。校内 5880 号段用手机拨必须
加区号，界面上一律显示完整的「010 5880 xxxx」。`CampusContactsTest` 校验分区数、条数与号码格式。

## 应用内更新

`core/update/UpdateChecker.kt` 请求 GitHub 公开接口 `GET /repos/{owner}/{repo}/releases/latest`（仓库名来自
`BuildConfig.GITHUB_REPO`，fork 后在 `app/build.gradle.kts` 改一处即可），按点分数字段比较版本号。下载交给系统
DownloadManager，文件落在应用私有外部目录，经 FileProvider 授权给系统安装器；系统安装时校验签名与已装版本一致。
应用在后台时下载完成改为发通知，回到设置页也能继续安装。

启动时的自动检查（`core/update/AutoUpdate.kt`，挂在界面根部的 `AutoUpdatePrompt`）：有网络、用户没关掉、进程内没查过、
距上次成功检查不少于 6 小时才发请求；查到的新版本若用户点过「以后再说」，同一版本不再弹，正在下载或已下载的版本也不再弹。
对话框与下载提示都注明安装包由 GitHub 提供、请注意网络环境。判断「有网络」只看 `NET_CAPABILITY_INTERNET`，不要求系统的联网校验通过。

## 混淆与崩溃栈

release 开启 R8 混淆、包层级打平与资源收缩，规则见 `app/proguard-rules.pro`。源文件名统一替换、只保留行号，
每次发版归档 `app/build/outputs/mapping/release/mapping.txt`，否则用户反馈的崩溃栈无法还原。
