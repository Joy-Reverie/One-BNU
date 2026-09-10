# 参与贡献

## 反馈问题

用 Issue 模板提交，写清应用版本、设备与系统版本、复现步骤。截图或日志请先遮掉学号、姓名等个人信息。
涉及账号安全的问题按 [SECURITY.md](SECURITY.md) 私下报告。

## 开发环境

- JDK 17，Android SDK 34（Android Studio 自带即可）
- 构建与测试：

  ```bash
  ./gradlew :app:testDebugUnitTest   # 单元测试
  ./gradlew :app:assembleDebug        # 调试包 → app/build/outputs/apk/debug/
  ```

- `local.properties` 需指向本机 SDK；该文件与 `keystore.properties` 都不入库。
- 没有可用账号时，debug 包里有一组不登录就能打开的预览页面，见 README「调试预览」。

## 提交代码

1. 从 `main` 新建分支，改动尽量聚焦一件事。
2. 解析学校页面的改动请附上脱敏后的页面样本放进 `app/src/test/resources/fixtures/`，并补对应的单元测试。
   样本里的学号、姓名、院系、课程、教师一律替换成虚构值，只保留页面结构。
3. 提交前跑一遍 `./gradlew :app:testDebugUnitTest`，CI 也会跑同样的命令。
4. 提交信息用一句话说明「改了什么、为什么」，中英文均可。

## 数据维护

- **校历**：每学期学校公布新校历后，把图放到 `app/src/main/res/drawable-nodpi/calendar_<学年起始年>_<autumn|spring>.jpg`，
  在 `data/model/OfficialCalendar.kt` 里照现有条目补一条并加入 `ALL`。`AcademicCalendarTest` 会检查起点是否周一、周数与日期是否合理。
- **校内联系方式**：数据在 `app/src/main/res/raw/campus_contacts.json`，每条带来源页面地址与该页面标注的发布日期。
  改完后同步 `CampusContactsTest` 里的分区数、条数与号码数。
- **作息时间**：`core/store/Settings.kt` 的 `PERIOD_TIMES`。

## 代码风格

Kotlin 官方风格（`kotlin.code.style=official`），四空格缩进，行宽 120。界面文案用简体中文，
不写多余的说明性小字；注释解释「为什么这么做」，而不是复述代码。
