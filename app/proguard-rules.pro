# ==============================================================================
# One BNU — R8 混淆 / 压缩规则（仅 release 生效）
#
# 目标：让反编译后的包尽量不可读 —— 类、方法、字段全部重命名，包层级打平，
# 未用到的代码与资源一并裁掉；同时不破坏运行时行为。
#
# 本项目没有 JNI、没有 Gson/Retrofit 这类靠反射读字段名的库、内嵌浏览器也不注入
# JS 桥，所以真正需要 keep 的东西很少。每条规则都写明理由，增删前先读懂原因。
# Compose / OkHttp / kotlinx-coroutines / DataStore / lifecycle-viewmodel 都自带
# consumer 规则（例如 ViewModel 的无参构造由 lifecycle 自己保住），不必在这里重复。
# ==============================================================================


# ------------------------------------------------------------------------------
# 一、混淆强度
# ------------------------------------------------------------------------------

# 把所有可重命名的类挪进无名根包，抹掉 io.github.joyreverie.onebnu.* 的目录结构，
# 反编译后看不出模块划分。
-repackageclasses ''

# 允许 R8 放宽访问修饰符，便于跨类内联与合并（默认的 proguard-android-optimize.txt
# 已含此项，这里显式重申，避免将来换默认文件时悄悄失效）。
-allowaccessmodification


# ------------------------------------------------------------------------------
# 二、崩溃栈可读性
# ------------------------------------------------------------------------------

# 保留行号、但把源文件名统一替换掉：崩溃栈里只剩「SourceFile:行号」，
# 配合 app/build/outputs/mapping/release/mapping.txt 可以完整还原。
# 每次发版务必把对应版本的 mapping.txt 归档，否则用户反馈的崩溃栈无法解读。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 本项目自定义异常只保留类名（成员照常混淆），让「网络诊断」页与日志里的
# 异常名可读。目前只有 ZyfwApi.SessionExpiredException 一处。
-keepnames class io.github.joyreverie.onebnu.** extends java.lang.Throwable


# ------------------------------------------------------------------------------
# 三、第三方库的缺类声明
# ------------------------------------------------------------------------------

# security-crypto 依赖的 Tink 在字节码里引用了 Error Prone 注解，这些类在
# Android 运行时并不存在；R8 默认把「缺类」当作错误终止构建，这里声明忽略。
# 注解缺失不影响运行。
-dontwarn com.google.errorprone.annotations.**
