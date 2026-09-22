# DashScope 扩展支持 reasoning_effort 参数 — 设计文档

日期:2026-09-21
状态:已获用户批准(方案 A + 校验与 thinkingBudget 对齐)

## 背景

百炼(DashScope)原生 API 在 `parameters` 节点支持 `reasoning_effort` 参数,用于控制推理力度:

- 类型 `string`;仅在 `enable_thinking=true` 时生效
- 取值按模型系列不同:DeepSeek/GLM/Kimi 支持 `high`/`max`;deepseek-v4 系列支持 `max`/`high`/`low`;qwen3.8 系列支持 `xhigh`(默认)/`medium`/`low`,且 `reasoning_effort=none` 等价于 `enable_thinking=false`
- qwen3.8 系列不允许 `reasoning_effort` 与 `thinking_budget` 同时设置(服务端报错),两者支持自动互转

参考文档:
- https://docs.bailian.console.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions
- https://help.aliyun.com/zh/model-studio/qwen-api-via-dashscope

## 现状与问题

- core `GenerateOptions` 已有 `reasoningEffort` 字段(`agentscope-core/.../model/GenerateOptions.java:48`),含 builder 与 `mergeOptions` 合并逻辑
- OpenAI 扩展已将其映射为顶层 `reasoning_effort`(`OpenAIRequest.java:128`、`OpenAIChatFormatter.java:92-95`)
- **DashScope 扩展完全没有引用该字段**:用户设置 `GenerateOptions.reasoningEffort` 后被静默丢弃
- 目前唯一变通是 `additionalBodyParam("reasoning_effort", ...)`,HTTP 层 `DashScopeHttpClient.buildRequestBody`(:642-664)会把它 `putAll` 进 `parameters` 节点

## 目标

让 DashScope 扩展支持 `GenerateOptions.reasoningEffort`,行为与 OpenAI 扩展及本扩展既有参数(temperature、thinkingBudget 等)的传递路径完全一致。

## 非目标

- 不做 qwen3.8 的 `reasoning_effort`/`thinking_budget` 互斥客户端校验(模型特定规则,交给服务端报错)
- 不做按模型名过滤/映射取值(如 KimiFormatter 那种模型白名单),取值合法性交给服务端
- 不改动 core `GenerateOptions`(字段与合并逻辑已存在)

## 设计(方案 A:DTO 显式字段 + 统一映射)

### 1. DTO — `DashScopeParameters`

文件:`agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/main/java/io/agentscope/extensions/model/dashscope/dto/DashScopeParameters.java`

- 新增字段 `private String reasoningEffort`,`@JsonProperty("reasoning_effort")`,放在 `thinkingBudget` 字段附近(thinking 相关参数聚类)
- 新增 getter/setter 与 `Builder.reasoningEffort(String)`
- 类上已有 `@JsonInclude(NON_NULL)`,未设置时不序列化,零兼容影响

### 2. 映射 — `DashScopeToolsHelper.applyOptions`

文件:`.../dashscope/formatter/DashScopeToolsHelper.java`(:55)

按现有模式追加:

```java
String reasoningEffort =
        getOption(options, defaultOptions, GenerateOptions::getReasoningEffort);
if (reasoningEffort != null) {
    params.setReasoningEffort(reasoningEffort);
}
```

- `getOption` 已实现 options 优先、defaultOptions 兜底
- 两条 formatter 路径(`DashScopeChatFormatter.buildRequest`、`DashScopeMultiAgentFormatter.applyOptions`)都汇聚到此方法,自动覆盖
- **不**自动设置 `enableThinking(true)`:现有契约要求用户在 builder 上显式 `enableThinking(true)`,与 thinkingBudget 的校验语义保持一致

### 3. 校验 — `DashScopeChatModel.applyThinkingMode`

文件:`.../dashscope/DashScopeChatModel.java`(:379)

仿照 thinkingBudget 的校验(:381-388),在其后追加:

- `options.getReasoningEffort() != null && !Boolean.TRUE.equals(enableThinking)` → 抛 `IllegalStateException`,报错文案风格与现有 budget 提示一致,提示用户调用 `.enableThinking(true)` 并给出 builder 示例

注意:`applyThinkingMode` 收到的 `options` 是已合并的 `effectiveOptions`(:284、:328),默认值/请求级优先级已由 `mergeOptions` 处理。

### 4. 与 additionalBodyParams 的既有兼容

`DashScopeHttpClient.buildRequestBody` 在 DTO 序列化之后执行 `parametersMap.putAll(additionalBodyParams)`,因此显式 `additionalBodyParam("reasoning_effort", "low")` 仍可覆盖字段值——与 OpenAI 扩展 extra params 覆盖字段的语义一致。此行为无需改动,用测试锁定。

## 错误处理

- 唯一新增的错误路径:reasoningEffort 已设置但模型 builder 未开启 `enableThinking` → `IllegalStateException`(快速失败,不等服务端 400)
- 取值非法、模型不支持、qwen3.8 与 thinking_budget 互斥 → 沿用现有服务端错误透传(`ModelException`)

## 测试

参考 `OpenAIChatFormatterTest.java:387-435` 与 dashscope 现有测试:

1. **映射测试**(`DashScopeChatFormatterTest` 或 `DashScopeToolsHelper` 测试):
   - `GenerateOptions.builder().reasoningEffort("high")` → 请求 parameters 含 `reasoning_effort: "high"`
   - 仅 defaultOptions 设置 → 生效(兜底)
   - options 与 defaultOptions 同时设置 → options 优先
   - options 为 null 时从 defaultOptions 取值
2. **校验测试**(`DashScopeChatModelTest`):
   - reasoningEffort 已设、`enableThinking` 未开启 → 抛 `IllegalStateException`
   - `enableThinking(true)` + reasoningEffort → 请求体 `parameters.reasoning_effort` 正确
3. **序列化/覆盖测试**:
   - 未设置时请求体不含 `reasoning_effort` 键
   - `additionalBodyParam("reasoning_effort", "low")` 覆盖字段值

## 影响面

- 仅 dashscope 扩展 3 个主源码文件 + 测试,core 与其他扩展零改动
- 未使用该参数的用户无任何行为变化
