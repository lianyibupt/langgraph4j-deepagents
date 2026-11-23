# 第七课实施计划

按用户已同意的路线，在当前会话实施并验证。

- [x] 扩展检查点 Binding 的可选配置，保持原四参数构造和旧 JSON 可读。
- [x] SourceWorkspace 增加白名单完整内容指纹，SourceResearch 从成功读取消息恢复 evidence。
- [x] LongTaskContext 从历史恢复计划及归档索引，AgentRuntime 恢复后继续下一轮。
- [x] Main 根据研究和上下文选项区分通用恢复与第六课委派，绑定输入版本并在恢复、发布前核对。
- [x] NoteOutput 支持恢复时复用完全一致的既有文件，保留默认独占发布。
- [x] Web 保存并恢复运行记录，保持单任务执行，增加恢复详情和页面入口。
- [x] 核心回归和 HTTP 测试通过，执行跨 Web JVM 恢复，浏览器核对界面和下载。
- [x] 增加 STAGE7 学习文档与断点入口，记录实际验证边界。
