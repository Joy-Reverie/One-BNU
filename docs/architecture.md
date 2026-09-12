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
的明文策略掐断，表现为登录最后一步失败、SSO 进教务读不到数据。校园网可直连教务时继续使用原地址；
蜂窝网络或直连 80 端口不可达时，`ZyfwApi` 将教务请求切换到学校 OneVPN 的 HTTPS 代理，代理会话和内层 CAS
票据都在应用侧完成。

解决办法不是把认证站点也加进明文白名单，而是 `core/net/Http.kt` 自己接管重定向，在跟随之前做**单向协议升级**：
能用 HTTPS 的北师大主机一律改走 HTTPS，只有 HTTP 的那几台保持原样（`BnuHosts`）。内嵌浏览器同样处理。

教务系统（KINGOSOFT）大量页面以 GBK 返回且不带 charset 头，`Http` 先按 UTF-8 试解，出现替换字符再按 GBK。

## 数据来源

| 功能 | 接口 | 说明 |
|---|---|---|
| 课表 | `wsxk/xkjg.ckdgxsxdkchj_data10319.jsp` | 课程类别列存在时保留官方原文；按课表学期缓存 |
| 选课结果 | 网上选课结果页面的候选入口 | 解析官方课程号 → 课程类别，作为学分核算的兜底来源 |
| 成绩 | `xscj.chkdgxscjyxxjd_data.jsp` | 按列名而非列序定位；含「课程性质」 |
| 考试 | `DataTable.jsp?tableId=2538` | |
| 空闲教室 | 教室课表取补集 | 北京、珠海均可用；只反映排课占用 |
| 学籍 | `STU_BaseInfoAction.do`（XML） | 身份证号、准考证号等敏感字段不展示 |
| 培养方案要求 | `DataTable.jsp?tableId=6033` | 学分核算里有数据时附带显示 |
| 培养方案课程模块 | `DataTable.jsp?tableId=5327008` | 有数据时优先作为学分归类依据 |
| 课程中心 | `kczx.bnu.edu.cn` `jw-pyfa` SPA | 官方实时页面；用于查看个人培养方案、教学手册、教学大纲，不把网页内容复制进应用 |
| 校历 | 无接口 | 手工录入，见 `data/model/OfficialCalendar.kt` |
| 作息时间 | 无接口 | 默认 `Settings.PERIOD_TIMES` 按学校统一作息生成，可在设置里逐节自定义 |

解析全部在 `data/parse/Parsers.kt`，用 Jsoup；单元测试的样本在 `app/src/test/resources/fixtures/`，已脱敏。

课程中心的入口是 `https://kczx.bnu.edu.cn/www/dd/vue/spa/jw-pyfa#/`。
它受北京 CAS 保护，且方案、手册、大纲会随学校发布和个人权限变化，因此 `CultivationPlanScreen` 只提供一个原生目录页，
由受限的 `WebScreen` 打开官方实时页面；不做 HTML 抓取、离线内置或导出。

北京校区已有 CAS 会话时，`OneVpnSso` 先访问课程中心的 `www/public/home/cas-bnu` 桥接页，
再从其中提取并校验课程中心自己的 CAS service，调用当前 CAS 的标准 `sso(service)` 建立会话，最后加载官方直连地址。
这样不依赖 OneVPN 网页端的 JavaScript Cookie 桥接，也不会把密码传给 WebView。旧版 OneVPN 代理地址仍保留严格白名单中转，
用于兼容历史调试入口；同步 Cookie 时，`CASTGC` 强制为 `cas.bnu.edu.cn` 的 host-only Cookie，并清除旧版可能遗留的
`.bnu.edu.cn` 跨子域副本，不能发送给 OneVPN 或门户。

教务系统等普通 CAS 入口不直接把 CAS 登录页交给 WebView：`WebScreen` 先用当前
`SessionAuthenticator` 在应用侧完成一次标准 SSO，取得目标站点的会话 Cookie 后再加载最终地址。数字京师与珠海门户
使用的是官方 OAuth CAS 流程。北京门户可由 `PortalSso` 从 CAS authorize 回调中取一次性 code，再调用门户自己的 token 接口换取
`accessToken`；珠海门户入口固定为 `/nup/`，因入口前有 aTrust challenge，改由 WebView 执行官方回调脚本。两者都只复用
当前 CAS 会话，不在网页中填写账号密码；门户 token 若存在，只以门户专属 Cookie 交给网页脚本。
整个过程复用应用已有的认证会话，不保存或向网页填写账号密码。

认证网络请求使用有限连接、读取和总超时；超时会回到可重试的登录提示，避免弱网或代理异常时界面永久停在加载状态。门户 WebView
在确认 accessToken 已同步后会检查页面主体是否为空，遇到脚本或 Cookie 瞬态失败最多自动重载一次。
校园服务里的北京数字京师入口把 WebView UA 设置为桌面浏览器，并先加载学校官方 OAuth 回调页；门户自己的 `cas.html`
在同一 WebView 上写入专属 accessToken 后再回到电脑端首页。后台已有 OAuth 预热只作加速，失败不会让 WebView 直接打开空壳。
若服务端仍返回跨设备引导页，应用只设置官方页面要求的本地访问偏好，再回到电脑端首页，不向页面注入账号或密码。

登录成功或应用启动时检测到已有会话后，`OneBnuRoot` 会在后台通过 `SsoWarmup` 依次预热北京门户、教务和课程中心
的服务会话；预热失败不会阻塞首页，点击入口时仍会按需重试。同步到 WebView 的只包含对应目标站点 Cookie，`CASTGC`
仍严格限制在 CAS 主机。

珠海当前使用独立的 `cas.bnuzh.edu.cn`，而课程中心的北京入口和旧 OneVPN 登录中转明确指向 `cas.bnu.edu.cn`。
在学校没有明确提供跨域委托前，应用不会把珠海凭据或 CAS 票据送往北京认证域；珠海门户则使用珠海自己的 `/nup/` OAuth 回调。

## 离线快照

`core/store/OfflineCache.kt` 按校区保存应用私有快照。登录成功后，`OneBnuRoot` 后台预热学期、各学期课表、成绩、考试轮次与安排、
教室索引、学籍、培养方案模块和毕业学分要求；每个请求成功才写入，失败时由 `AcademicRepository` 使用同一解析器回退最近快照。
学籍缓存只保存已经过字段白名单处理的展示模型，不保存接口原始 XML。退出登录或切换账号会清除对应校区快照，系统备份与设备迁移也不包含这些文件。

## 成绩与绩点

教务有时将缓考暂记为 `0` 分，并在备注、考核方式或单列的「成绩状态」中写出「缓考」。解析器将这些状态合并进
`Grade`；`Grade.isDeferredExam` 在所有绩点口径之前优先排除，因此即使教务同时返回 `0` 绩点也不会影响 GPA 或加权均分。

「成绩 → 计算范围」允许用户取消勾选当前口径下可计算的课程；选择只保存在本机的 `Settings.gpaExcludedCourseKeys`，
键为课程标识的 SHA-256，不含姓名、学号。缓考和无可用绩点记录不可手动重新纳入，新增成绩默认纳入。

## 课表缓存与桌面小组件

应用每次成功加载当前学期课表就写一份到本机（`core/store/ScheduleCache.kt`，只含课程、时间、地点、教师），
小组件只读缓存渲染。后台刷新走 JobScheduler（`widget/WidgetRefreshJob.kt`）：缓存超过 6 小时或手动点刷新时
用已保存的账号静默登录拉一次；没保存密码或需要短信验证时只提示打开应用。

后台任务不设「有网络」约束——国内网络常通不过系统的联网校验，设了条件任务永远等不到，改为没网时快速失败并在小组件上提示。
半小时一次的系统周期更新只重绘、不联网，用来跨零点翻页和更新「进行中 / 已结束」标记。

四种尺寸对应四个 provider（启动器要求每个默认尺寸是独立的 receiver 类）。MIUI 在未授权「桌面快捷方式」时会把
`requestPinAppWidget` 静默吞掉且没有公开的申请接口，`widget/MiuiShortcutPermission.kt` 通过 AppOps 10017 读状态并
跳到 MIUI 的权限编辑页；其他情况给手动添加步骤。

## 上课提醒与日程提醒

每次只向 AlarmManager 登记**一个**定时（下一次需要提醒的时刻，`core/notify/ClassReminder.kt`），到点送达后再登记下一个；
课表缓存或日程变动、开机、时区 / 时间变化、应用升级后重算。Android 12 上若「闹钟和提醒」权限未开则退回非精确定时。

课程与日程是两个平级开关（`Settings.remindClasses` / `Settings.remindEvents`），共用一个提前时间；
两个都关掉才会取消系统里的定时。1.9.3 及更早版本「日程也提醒」是挂在总开关下的子项，
`Settings.migrateReminderSwitches` 按「用户此前实际收到哪几类提醒」迁移一次。

日程随时能加，所以「下一次」不是简单的 `开始时刻 − 提前时间`（`core/notify/ReminderPlanner.next`）：

- **提醒点已过、但事项还没开始**就立刻提醒。否则在开始前 5 分钟添加的日程（提前时间 10 分钟）会被整条跳过。
- 立刻提醒会被反复算出来，所以送达前先把「已提醒到哪一刻」记进 `Settings.lastRemindedStart`（被提醒事项的开始时刻），
  重排时一律排除不晚于它的事项 —— 少了这一步就会一直响。
- 通知与闹钟的「N 分钟后开始」按**真实剩余时间**算（`ClassReminder.remainingLabel`），不照抄提前时间，
  不足一分钟写「即将开始」。

两种送达方式（`core/store/ReminderStyle.kt`，「我的 → 提醒 → 提醒方式」）：

- **通知提醒**（默认）：`setExactAndAllowWhileIdle` 登记，到点发一条高优先级通知，按通知音量响一声。
- **闹钟提醒**：改用 `setAlarmClock` 登记 —— 系统把它当作用户可见的闹钟，Doze 不延后，状态栏显示闹钟图标，
  国产 ROM 对这一类定时的拦截也最轻。到点由 `core/notify/AlarmService`（前台服务，`mediaPlayback` 类型）以
  `USAGE_ALARM` 循环播放系统闹铃并震动，通知带全屏意图：锁屏或灭屏时直接弹出 `ui/notify/AlarmActivity` 并点亮屏幕，
  亮屏时是横幅加「停止」。两分钟没人理会自动停，`MediaPlayer.setWakeMode` 与服务自持的唤醒锁保证灭屏期间不被 CPU 休眠掐断。

  停止有四条路，任何一条失效都还有别的：通知上的「停止」（走 `AlarmStopReceiver` 广播 + `stopService`，不受后台启动限制）、
  锁屏全屏页上的大按钮、「我的 → 提醒」卡片上的「停止」（响铃时「试一下」就地变成它）、两分钟自动停。
  亮屏且应用在前台时系统只会把全屏意图降级成横幅，若用户还关掉了通知权限就没有可点的「停止」，所以前台时直接把全屏页拉起来。
  重复拉起不会叠加：`onStartCommand` 先 `stopPlayback()` 再起新的，全程只有一个 `MediaPlayer`。
  勿扰模式（`currentInterruptionFilter != INTERRUPTION_FILTER_ALL`）下不出声，只震动，通知里写明原因。

定时始终由系统 AlarmManager 保管，应用进程被清理不影响到点；精确闹钟触发时系统会给应用一段临时白名单，后台也能拉起前台服务。
真正能挡住提醒的只有 ROM 级别的「强制停止」，所以卡片上保留了忽略电池优化的入口。前台服务万一起不来（系统拒绝后台启动），
`ClassReminder.deliver` 会退回普通通知，不让这一条整个丢掉。

## 课表网格

行高按屏幕档位取默认值，横屏改为「一天 12 节尽量落进一屏」并有下限，再乘用户双指缩放的倍数（`ui/schedule/ScheduleLayout`，
倍数跨启动记住）。左侧刻度每格是节次号 + 上课 + 下课时刻；三行放不下时（横屏压缩、缩到最小、系统字体调大）
按 `gutterDetail` 逐级降为「节次 + 上课」和「只有节次」，宽度按实际字号倍数放宽至多 1.5 倍 —— 宁可少显示一行，
也不把时刻裁掉半截。

## 个人日程

存在应用私有目录的 `personal_events.json`（`core/store/PersonalEventStore.kt`），不上传、不同步。重复规则以 `repeat`
（星期几数组）和 `until` 字段存储，旧数据没有这两个字段照常读。课表网格里日程不按整节占格，而是按起止时刻在行内线性定位、按时长取高（`data/model/PeriodMapper.position`：
第 k 节占 [k-1, k)，10 / 20 分钟的课间算进它前面那一节的行里，这样同样一小时的两段日程高度基本一致；
午休与晚上开课前这类超过 30 分钟的空档不并入，否则整个下午会被挤扁，落在里面的时刻贴到下一节上沿，作息之外贴两端）；重叠判定用真实时间区间，首尾相接的两条日程各自显示，
真正重叠的才归为一簇、一次显示一个（`ui/schedule/ScheduleLayout.groupColumn`）。课程仍按整节占格。

## 学分核算

归类依据按优先级：用户手动指定 > 课表自带的官方类别 > 各学期培养方案模块 > 课程中心 / 成绩单的
「课程性质」> 网上选课结果（兜底）> 推断（`data/model/CreditLedger.kt` 的 `CategoryRules`）。培养方案模块按每个
课表学期查询并合并，避免只用当前学期导致往年课程被粗略推断。公共课先按课程名称识别；这是为兼容珠海校区公共课
不统一使用 `GRA` 前缀的情况。官方「专业选修课」归入「专业拓展课」，「专业必修 / 学位必修」归入「学位专业课」。
「重修」显示但不计学分，手动归类存本机。
（`CreditCategoryStore`）。

## 校内联系方式

数据是 `app/src/main/res/raw/campus_contacts.json`：16 个分区、386 条、399 个号码，逐条抄自各单位官网「联系我们」页，
每个分区带来源链接和来源页面自己标注的发布日期；一个分区的数据抄自多张页面时（科研院的五个处室页各有各的发布日期），
来源标在小节上（`ContactSection.sourceUrl` / `sourceDate`）而不是分区上。`docs/bnu-directory.html` 是检索时的原始整理稿。校内 5880 号段用手机拨必须
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
