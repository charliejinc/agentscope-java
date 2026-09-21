# DashScope 扩展 reasoning_effort 支持 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 dashscope 模型扩展支持 `GenerateOptions.reasoningEffort`,映射为百炼原生 API `parameters.reasoning_effort`,并在未开启 thinking 时快速失败。

**Architecture:** 与现有 temperature/thinkingBudget 同路径:`DashScopeParameters` DTO 加字段 → `DashScopeToolsHelper.applyOptions` 统一映射(两条 formatter 路径自动覆盖)→ `DashScopeChatModel.applyThinkingMode` 校验(与 thinkingBudget 的校验语义对齐)。不做 qwen3.8 互斥校验(服务端报错透传)。

**Tech Stack:** Java 17、Jackson(`@JsonProperty`)、JUnit 5、MockWebServer、Maven(仓库外用 `D:\soft\apache-maven-3.9.9\bin\mvn.cmd` + `-s D:\soft\apache-maven-3.9.9\conf\settings-pip.xml`)。

**设计依据:** `docs/superpowers/specs/2026-09-21-dashscope-reasoning-effort-design.md`

**模块根目录(下文相对路径均基于此):**
`agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope`

**通用测试命令(Git Bash 中执行;`-am` 保证 core 从源码重编,上游模块无匹配测试会被 `-Dsurefire.failIfNoSpecifiedTests=false` 跳过):**

```bash
"D:\soft\apache-maven-3.9.9\bin\mvn.cmd" test \
  -s "D:\soft\apache-maven-3.9.9\conf\settings-pip.xml" \
  -pl :agentscope-extensions-model-dashscope -am \
  -Dtest=<TestClassName> -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

**提交前格式化(CI 跑 `spotless:check`):**

```bash
"D:\soft\apache-maven-3.9.9\bin\mvn.cmd" -s "D:\soft\apache-maven-3.9.9\conf\settings-pip.xml" \
  -pl :agentscope-extensions-model-dashscope spotless:apply
```

---

### Task 1: DashScopeParameters 增加 reasoning_effort 字段

**Files:**
- Modify: `src/main/java/io/agentscope/extensions/model/dashscope/dto/DashScopeParameters.java`
- Test: `src/test/java/io/agentscope/extensions/model/dashscope/dto/DashScopeDtoSerializationTest.java`

- [ ] **Step 1: 写失败测试**

在 `DashScopeDtoSerializationTest.java` 的 `testDashScopeParametersBuilder`(约 :336-361)之后追加。类中已有 `jsonCodec` 字段;若缺 `assertFalse` 静态导入则补上(`import static org.junit.jupiter.api.Assertions.assertFalse;`):

```java
    @Test
    void testDashScopeParametersReasoningEffort() {
        DashScopeParameters params =
                DashScopeParameters.builder().reasoningEffort("high").build();

        assertEquals("high", params.getReasoningEffort());

        String json = jsonCodec.toJson(params);
        assertTrue(json.contains("\"reasoning_effort\":\"high\""));

        DashScopeParameters deserialized = jsonCodec.fromJson(json, DashScopeParameters.class);
        assertEquals("high", deserialized.getReasoningEffort());

        String emptyJson = jsonCodec.toJson(DashScopeParameters.builder().build());
        assertFalse(emptyJson.contains("reasoning_effort"));
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: 通用测试命令,`-Dtest=DashScopeDtoSerializationTest`
Expected: **编译失败** — `reasoningEffort()` / `getReasoningEffort()` 不存在

- [ ] **Step 3: 实现 DTO 字段**

`DashScopeParameters.java` 三处改动:

(a) 字段,放在 `thinkingBudget`(:63-65)之后:

```java
    /** Reasoning effort level (e.g. "low", "medium", "high", "max", "xhigh"). */
    @JsonProperty("reasoning_effort")
    private String reasoningEffort;
```

(b) getter/setter,放在 `setThinkingBudget`(:184-186)之后:

```java
    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }
```

(c) Builder 方法,放在 `thinkingBudget(Integer)`(:324-327)之后:

```java
        public Builder reasoningEffort(String reasoningEffort) {
            params.setReasoningEffort(reasoningEffort);
            return this;
        }
```

- [ ] **Step 4: 跑测试确认通过**

Run: 通用测试命令,`-Dtest=DashScopeDtoSerializationTest`
Expected: PASS(`Tests run: ..., Failures: 0`)

- [ ] **Step 5: 格式化并提交**

```bash
# 先跑 spotless:apply(见文首命令),然后:
git add agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/main/java/io/agentscope/extensions/model/dashscope/dto/DashScopeParameters.java \
        agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/test/java/io/agentscope/extensions/model/dashscope/dto/DashScopeDtoSerializationTest.java
git commit -m "feat(dashscope): add reasoning_effort field to DashScopeParameters"
```

---

### Task 2: DashScopeToolsHelper.applyOptions 映射 reasoningEffort

**Files:**
- Modify: `src/main/java/io/agentscope/extensions/model/dashscope/formatter/DashScopeToolsHelper.java`(:55 applyOptions)
- Test: `src/test/java/io/agentscope/extensions/model/dashscope/formatter/DashScopeToolsHelperComprehensiveTest.java`

- [ ] **Step 1: 写失败测试**

在 `testApplyOptionsOptionsOverrideDefault`(约 :139)之后、`applyOptions Tests` 区块内追加(`helper` 字段与 `assertNull` 均已存在):

```java
    @Test
    void testApplyOptionsWithReasoningEffort() {
        DashScopeParameters params = DashScopeParameters.builder().build();
        GenerateOptions options = GenerateOptions.builder().reasoningEffort("high").build();

        helper.applyOptions(params, options, null);

        assertEquals("high", params.getReasoningEffort());
    }

    @Test
    void testApplyOptionsReasoningEffortFromDefault() {
        DashScopeParameters params = DashScopeParameters.builder().build();
        GenerateOptions defaultOptions =
                GenerateOptions.builder().reasoningEffort("medium").build();

        helper.applyOptions(params, null, defaultOptions);

        assertEquals("medium", params.getReasoningEffort());
    }

    @Test
    void testApplyOptionsReasoningEffortOptionsOverrideDefault() {
        DashScopeParameters params = DashScopeParameters.builder().build();
        GenerateOptions options = GenerateOptions.builder().reasoningEffort("high").build();
        GenerateOptions defaultOptions = GenerateOptions.builder().reasoningEffort("low").build();

        helper.applyOptions(params, options, defaultOptions);

        assertEquals("high", params.getReasoningEffort());
    }

    @Test
    void testApplyOptionsWithoutReasoningEffort() {
        DashScopeParameters params = DashScopeParameters.builder().build();

        helper.applyOptions(params, GenerateOptions.builder().build(), null);

        assertNull(params.getReasoningEffort());
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: 通用测试命令,`-Dtest=DashScopeToolsHelperComprehensiveTest`
Expected: FAIL — 前三个用例断言值实际为 `null`

- [ ] **Step 3: 实现映射**

`DashScopeToolsHelper.applyOptions` 中,在 responseFormat 块(:108-112)之后、方法结束前追加:

```java
        String reasoningEffort =
                getOption(options, defaultOptions, GenerateOptions::getReasoningEffort);
        if (reasoningEffort != null) {
            params.setReasoningEffort(reasoningEffort);
        }
```

注意:**不要**像 thinkingBudget 那样顺带 `setEnableThinking(true)` — 校验语义由 Task 3 的 applyThinkingMode 负责。

- [ ] **Step 4: 跑测试确认通过**

Run: 通用测试命令,`-Dtest=DashScopeToolsHelperComprehensiveTest`
Expected: PASS

- [ ] **Step 5: 格式化并提交**

```bash
git add agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/main/java/io/agentscope/extensions/model/dashscope/formatter/DashScopeToolsHelper.java \
        agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/test/java/io/agentscope/extensions/model/dashscope/formatter/DashScopeToolsHelperComprehensiveTest.java
git commit -m "feat(dashscope): map GenerateOptions.reasoningEffort in applyOptions"
```

---

### Task 3: applyThinkingMode 校验 + 端到端透传

**Files:**
- Modify: `src/main/java/io/agentscope/extensions/model/dashscope/DashScopeChatModel.java`(:379 applyThinkingMode)
- Test: `src/test/java/io/agentscope/extensions/model/dashscope/DashScopeChatModelTest.java`

- [ ] **Step 1: 写失败测试**

(a) 校验用例 — 放在 `testApplyThinkingModeValidation`(:884-903)之后,复用现有 `invokeApplyThinkingMode` 反射 helper:

```java
    @Test
    @DisplayName(
            "Should throw an IllegalStateException when setting reasoningEffort while thinking"
                    + " mode is disabled")
    void testApplyThinkingModeReasoningEffortValidation() {
        DashScopeChatModel chatModel =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .enableThinking(false)
                        .build();

        DashScopeRequest request =
                DashScopeRequest.builder()
                        .parameters(DashScopeParameters.builder().build())
                        .build();

        GenerateOptions options = GenerateOptions.builder().reasoningEffort("high").build();

        assertThrows(
                IllegalStateException.class,
                () -> invokeApplyThinkingMode(chatModel, request, options));
    }
```

(b) 端到端用例 — 放在 `testDoNonStreamWithAdditionHeadersAndParams`(:727-775)之后,确认 `enableThinking(true)` + reasoningEffort 时参数落到请求体:

```java
    @Test
    @DisplayName("DashScope chat model should send reasoning_effort in request body")
    void testDoNonStreamWithReasoningEffort() throws Exception {
        MockWebServer mockServer = new MockWebServer();
        mockServer.start();

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(
                                """
                                        {
                                            "request_id": "test",
                                            "output": {
                                                "choices": []
                                            }
                                        }
                                """)
                        .setHeader("Content-Type", "application/json"));

        DashScopeChatModel chatModel =
                DashScopeChatModel.builder()
                        .apiKey(mockApiKey)
                        .modelName("qwen-plus")
                        .stream(false)
                        .enableThinking(true)
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .httpTransport(OkHttpTransport.builder().build())
                        .build();

        chatModel
                .doStream(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(TextBlock.builder().text("test").build())
                                        .build()),
                        List.of(),
                        GenerateOptions.builder().reasoningEffort("high").build())
                .blockLast();

        RecordedRequest recorded = mockServer.takeRequest();
        assertTrue(recorded.getBody().readUtf8().contains("\"reasoning_effort\":\"high\""));

        mockServer.shutdown();
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: 通用测试命令,`-Dtest=DashScopeChatModelTest`
Expected: (a) FAIL — 未抛 `IllegalStateException`;(b) 此时已应 PASS(Task 2 的映射已生效)。若 (b) 也失败,停下来排查,不要继续。

- [ ] **Step 3: 实现校验**

`DashScopeChatModel.applyThinkingMode` 中,thinkingBudget 校验块(:381-388)之后追加:

```java
        if (options.getReasoningEffort() != null && !Boolean.TRUE.equals(enableThinking)) {
            throw new IllegalStateException(
                    "reasoningEffort is set but enableThinking is not enabled. To use reasoning"
                        + " effort control, you must explicitly enable thinking by calling"
                        + " .enableThinking(true) on the model builder. Example:"
                        + " DashScopeChatModel.builder().enableThinking(true)"
                        + ".defaultOptions(GenerateOptions.builder().reasoningEffort(\"high\")"
                        + ".build())");
        }
```

- [ ] **Step 4: 跑测试确认通过**

Run: 通用测试命令,`-Dtest=DashScopeChatModelTest`
Expected: PASS(全类)

- [ ] **Step 5: 格式化并提交**

```bash
git add agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/main/java/io/agentscope/extensions/model/dashscope/DashScopeChatModel.java \
        agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/test/java/io/agentscope/extensions/model/dashscope/DashScopeChatModelTest.java
git commit -m "feat(dashscope): validate reasoningEffort requires enableThinking"
```

---

### Task 4: 锁定 additionalBodyParams 覆盖语义(纯测试,无生产代码)

**Files:**
- Test: `src/test/java/io/agentscope/extensions/model/dashscope/DashScopeHttpClientTest.java`

- [ ] **Step 1: 写测试**

参照同文件 `createTestRequest`(:1758)与 :1580-1617 的 additionalBodyParams 用例(enqueue/字段 setup 完全照抄相邻用例),追加:

```java
    @Test
    void testAdditionalBodyParamsOverrideReasoningEffort() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeRequest request = createTestRequest("qwen-plus", "test");
        request.getParameters().setReasoningEffort("high");

        Map<String, Object> additionalBodyParams = new HashMap<>();
        additionalBodyParams.put("reasoning_effort", "low");

        client.call(request, null, additionalBodyParams, null);

        RecordedRequest recorded = mockServer.takeRequest();
        String body = recorded.getBody().readUtf8();

        DashScopeRequest dashScopeRequest =
                JsonUtils.getJsonCodec().fromJson(body, DashScopeRequest.class);
        assertNotNull(dashScopeRequest.getParameters());
        assertEquals("low", dashScopeRequest.getParameters().getReasoningEffort());
    }
```

注意:`client`、`mockServer` 字段与 enqueue/response body 格式以相邻 existing 用例(:1560-1617)为准;若相邻用例的 response body 结构不同,照抄它,不要自己发明。

- [ ] **Step 2: 跑测试**

Run: 通用测试命令,`-Dtest=DashScopeHttpClientTest`
Expected: PASS — 这是锁定既有行为的测试,不需要改生产代码。若 FAIL,说明 `buildRequestBody` 的 `putAll` 顺序与预期不符,停下来排查,不要用改代码硬凑通过。

- [ ] **Step 3: 格式化并提交**

```bash
git add agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/test/java/io/agentscope/extensions/model/dashscope/DashScopeHttpClientTest.java
git commit -m "test(dashscope): lock in additionalBodyParams override for reasoning_effort"
```

---

### Task 5: 全模块回归 + 格式检查

- [ ] **Step 1: 跑 dashscope 模块全部测试**

```bash
"D:\soft\apache-maven-3.9.9\bin\mvn.cmd" test \
  -s "D:\soft\apache-maven-3.9.9\conf\settings-pip.xml" \
  -pl :agentscope-extensions-model-dashscope -am \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `BUILD SUCCESS`,dashscope 模块 `Tests run: ..., Failures: 0, Errors: 0, Skipped: 0`(Skipped 非 0 也可接受,只确认无 Failure/Error)。控制台 GBK 乱码是既有噪音,grep `Tests run:` 与 `BUILD`。

- [ ] **Step 2: spotless 检查**

```bash
"D:\soft\apache-maven-3.9.9\bin\mvn.cmd" -s "D:\soft\apache-maven-3.9.9\conf\settings-pip.xml" \
  -pl :agentscope-extensions-model-dashscope spotless:check
```

Expected: `BUILD SUCCESS`。若失败,跑 `spotless:apply` 后把改动并入对应 Task 的提交之后的单独 `style:` 提交。
