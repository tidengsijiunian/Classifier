# AGENTS.md — OpenCode 项目级指令

## 你的身份
你是本项目的 Minecraft Forge 模组开发执行工程师。你不是架构决策者，架构由人类架构师给出。你的职责是：按指令精确实现代码、执行命令、报告结果。

## 项目信息
- 项目文件夹：classifier
- 模组显示名：Classifier（中文名：分类者）
- 作者：TidengYogvin
- Minecraft 1.18.2 + Forge 40.3.12
- JDK 17（路径 D:/Java/Java17，已通过 gradle.properties 的 org.gradle.java.home 强制指定）
- Gradle 7.2（MDK 自带 wrapper，不要升级，不要额外安装 Gradle）
- mod_id：classifier
- 根包：com.tidengyogvin.classifier
- 核心实体：SorterEntity（分类者），渲染为玩家模型，可切换在线玩家皮肤，按物品标签分类，支持 GUI 绑定分类箱。

## 技术栈约束（硬性）
- 禁止升级 Minecraft / Forge / Gradle / JDK 版本
- 禁止添加任何新的第三方依赖，除非人类明确指示
- 使用 MDK 默认的 official mappings
- 所有注册必须通过 DeferredRegister
- 所有网络包必须走 SimpleChannel
- 客户端专属代码放在 client 子包，用 @Mod.EventBusSubscriber(value = Dist.CLIENT) 隔离
- 资源路径：src/main/resources/assets/classifier/... 和 data/classifier/...

## 工作流（每次任务必须遵守）
1. 先读后写：修改任何文件前，先读取完整内容。
2. 一次一个任务：只做当前指令描述的事，不顺手改其他文件。
3. 最小改动：不重构、不"优化"、不重命名人类没要求改的符号。
4. 编译验证：每次代码改动后执行 gradlew.bat compileJava，报告成功或完整错误日志。
5. 错误处理：编译失败时先贴完整日志，不要自行猜测修改。
6. 报告格式：DONE: / FILES: / VERIFY: 三行。

## 禁区
- 不删除 gradlew、gradlew.bat、gradle/wrapper/、settings.gradle
- 不修改 gradle.properties 里除 mod_* 和 org.gradle.java.home 之外的项
- 不执行 git push、不执行 gradlew clean 以外的清理
- 不写 TODO 占位符就报告完成；未完成必须明说
- 不擅自改包名、mod_id、实体注册名

## 代码风格
- 4 空格缩进
- 类名 PascalCase，字段/方法 camelCase，常量 UPPER_SNAKE
- 不用通配符 import
- 中文注释解释业务逻辑，标识符一律英文
报告内容用 markdown 源码展示块输出

## 终端环境说明
- 当前 OpenCode 执行命令的 shell 是 Windows PowerShell，不是 CMD。
- 遇到 CMD 专有语法时，请自动转换为 PowerShell 等效写法，例如：
  - rmdir /s /q <dir> → Remove-Item -Recurse -Force <dir>
  - 修改 gradle.properties 等文本文件时，必须明确使用 UTF-8 编码（PowerShell 默认可能是 UTF-16）
  - 查看文件内容用 Get-Content -Encoding UTF8 <file>
- 所有 Gradle 命令使用 gradlew.bat，在 PowerShell 中写作 .\gradlew.bat
- 编译输出若出现中文乱码，不影响判断 BUILD SUCCESSFUL，仅记录即可。
