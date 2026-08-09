# Build Warning Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 JDK 25/JavaFX 25 主源码编译中的弃用和未检查警告，并让同类警告在后续构建中直接失败。

**Architecture:** 保留现有 JavaFX 行为，只把已弃用的表格列宽常量换成其官方等价替代项，并把单元素 `setAll` 调用切换到集合重载。`compileJava` 显式启用两类 lint 并使用 `-Werror`，测试源码和第三方 jlink 生成步骤不纳入这条项目源码门禁。

**Tech Stack:** JDK 25, JavaFX 25, Gradle 9.2, JUnit 5, org.beryx.jlink 4.1.0.

## Global Constraints

- 直接在 `main` 分支推进。
- Windows 为主但保留跨平台运行。
- 保留 G1 256MB 平衡模式。
- 不隐藏 beryx/JDK 25 的 JEP 493 提示，通过实际 jlink 兼容验证处理。
- 不修改或提交用户持有的 `.testagent/`。

---

### Task 1: 主源码警告门禁与等价 API 替换

**Files:**
- Modify: `build.gradle`
- Modify: `src/com/datacube/fx/TableDesignerPane.java`
- Modify: `src/com/datacube/fx/ConnectionTreePane.java`

**Interfaces:**
- Consumes: JavaFX 25 `TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN` 与 `ObservableList.setAll(Collection)`。
- Produces: `compileJava` 对 `deprecation`、`unchecked` 警告执行 `-Werror` 的构建门禁。

- [ ] **Step 1: 用临时编译门禁验证 RED**

创建临时 Gradle init script，为所有 `JavaCompile` 注入：

```groovy
options.compilerArgs += ['-Xlint:deprecation', '-Xlint:unchecked', '-Werror']
```

Run: `./gradlew.bat clean compileJava --rerun-tasks -I .codex-lint.init.gradle`

Expected: FAIL，输出定位 `TableDesignerPane.java:173`、`:227` 和 `ConnectionTreePane.java:391` 的三条警告。

- [ ] **Step 2: 添加项目源码编译门禁**

在 `build.gradle` 中加入：

```groovy
tasks.named('compileJava') {
    options.compilerArgs += ['-Xlint:deprecation', '-Xlint:unchecked', '-Werror']
}
```

- [ ] **Step 3: 使用 JavaFX 25 官方等价列宽策略**

将 `TableDesignerPane` 两处：

```java
TableView.CONSTRAINED_RESIZE_POLICY
```

替换为：

```java
TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
```

- [ ] **Step 4: 使用类型安全的集合重载**

将连接树失败回调改为：

```java
failure -> item.getChildren().setAll(List.of(new TreeItem<>(
        new NodeData(item.getValue().kind, "错误: " + message(failure),
                null, null, null, null))))
```

- [ ] **Step 5: 验证 GREEN**

Run: `./gradlew.bat clean compileJava --warning-mode all --rerun-tasks`

Expected: BUILD SUCCESSFUL，且不再输出项目源码弃用或未检查警告。

- [ ] **Step 6: 回归与打包验证**

Run: `./gradlew.bat clean test jlink --warning-mode all --rerun-tasks`

Expected: BUILD SUCCESSFUL；全部测试通过；jlink 镜像生成成功。第三方插件/JDK 提示如仍存在，保留原文并记录，不用参数压制。

- [ ] **Step 7: 同步索引并提交**

Run: `codegraph sync`

Run: `git diff --check`

```powershell
git add -- build.gradle src/com/datacube/fx/TableDesignerPane.java src/com/datacube/fx/ConnectionTreePane.java docs/superpowers/plans/2026-08-09-build-warning-cleanup.md
git commit -m "build: 清理项目编译兼容警告"
```

### Task 2: Gradle 10 执行期 Project 访问

**Files:**
- Modify: `build.gradle`

**Interfaces:**
- Consumes: 配置阶段已确定的 `project.version` 字符串。
- Produces: 内容不变的 `build/generated/version/com/datacube/version.properties`，执行阶段不再访问 `Task.project`。

- [ ] **Step 1: 验证 Gradle 10 兼容门禁 RED**

Run: `./gradlew.bat generateVersionProperties --warning-mode fail --rerun-tasks`

Expected: FAIL，提示 `build.gradle:67` 的 `Invocation of Task.project at execution time has been deprecated`。

- [ ] **Step 2: 在配置阶段冻结版本值**

把注册任务前的版本字符串保存为局部变量，并同时用于输入声明与输出内容：

```groovy
def generatedAppVersion = project.version.toString()
tasks.register('generateVersionProperties') {
    def outFile = file('build/generated/version/com/datacube/version.properties')
    inputs.property('version', generatedAppVersion)
    outputs.file(outFile)
    doLast {
        outFile.parentFile.mkdirs()
        outFile.text = "version=${generatedAppVersion}\n"
    }
}
```

- [ ] **Step 3: 验证 Gradle 10 兼容门禁 GREEN**

Run: `./gradlew.bat generateVersionProperties --warning-mode fail --rerun-tasks`

Expected: BUILD SUCCESSFUL，且生成的 `version.properties` 仍为当前项目版本。
