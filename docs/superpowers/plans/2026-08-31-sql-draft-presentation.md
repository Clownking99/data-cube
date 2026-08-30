# SQL Draft Presentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct nullable draft metadata labels and readable empty-preview prompts discovered in actual desktop acceptance.

**Architecture:** Keep all changes in manager presentation. Reuse its existing ID and theme lookup color; real ListCell and JavaFX CSS tests exercise output without adding production test APIs.

**Tech Stack:** Java 25, JavaFX 25, JUnit 5, existing Gradle wrapper.

## Global Constraints

- Work only in `D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery`, branch `codex/sql-draft-recovery`.
- Never access, modify, stage or remove `.testagent/` contents; name-only git status is allowed.
- No real connections, credentials, DB execution, external requests, privacy/history changes, push, tag, install or release.
- Preserve checkpoint values, identity semantics, bounded/control-sanitized metadata previews and normalized display-only SQL.
- No new dependencies, public API, global prompt rule or ThemeManager changes.
- Row copy: `未绑定连接`; typed unnamed connections `未命名连接 · POSTGRESQL` or `未命名连接 · ORACLE`; missing Schema `Schema: 未指定`.
- Preview ID `draft-manager-sql` uses existing `-brand-fg-dim`; ordinary and focused CSS states must be opaque/readable in both themes, contrast at least 4.5:1.
- One implementer/Gradle owner. Root owns acceptance docs and later integration; do not stage root edits.

---

### Task 1: Manager labels and preview contrast

**Files:**
- Modify: `src/com/datacube/fx/SqlDraftManagerPane.java`
- Modify: `resources/com/datacube/fx/theme-base.css`
- Test: `test/com/datacube/fx/SqlDraftManagerTest.java`

**Interfaces:**
- Consumes existing `SqlDraft` record, `SqlDraftManagerPane.preview(String,int)`, existing test `Fixture` (`probe`, `pane`, `fx`, `list`, `sql`, `settle`).
- Produces corrected actual cell text and scoped prompt styling; no new public interfaces.

- [ ] **Step 1: Add regression tests before production changes.** Add imports for `java.util.stream.Stream`, `javafx.css.PseudoClass`, `javafx.scene.control.ListCell`, `javafx.scene.layout.Region`, `javafx.scene.paint.Color`, `javafx.scene.text.Text`, `org.junit.jupiter.params.provider.Arguments`, `org.junit.jupiter.params.provider.MethodSource`.

Add inside `SqlDraftManagerTest`:

```java
static Stream<Arguments> metadataRows() {
    return Stream.of(
            Arguments.of(null, null, null, null, "未绑定连接", "未指定"),
            Arguments.of(null, null, "", "  ", "未绑定连接", "未指定"),
            Arguments.of("pg", DbType.POSTGRESQL, null, "", "未命名连接 · POSTGRESQL", "未指定"),
            Arguments.of("ora", DbType.ORACLE, " \t", null, "未命名连接 · ORACLE", "未指定"),
            Arguments.of("pg", DbType.POSTGRESQL, "开发\nPG", "  public  ", "开发 PG · POSTGRESQL", "  public  "),
            Arguments.of("ora", DbType.ORACLE, "Oracle", "APP", "Oracle · ORACLE", "APP"));
}

@ParameterizedTest @MethodSource("metadataRows")
void metadataRowsDescribeMissingValuesWithoutChangingCheckpoint(
        String connectionId, DbType type, String name, String schema,
        String expectedConnection, String expectedSchema) throws Exception {
    try (Fixture f = new Fixture(true, true, true)) {
        SqlDraft raw = new SqlDraft(UUID.randomUUID(), 100_001L, connectionId, type, name,
                schema, "select null;\r\n-- raw");
        f.probe.records.clear();
        f.probe.records.add(raw);
        f.fx(() -> f.button("refresh").fire());
        f.settle();
        f.fx(() -> {
            ListCell<SqlDraft> cell = f.list().getCellFactory().call(f.list());
            cell.updateListView(f.list());
            cell.updateIndex(0);
            String[] lines = cell.getText().split("\n", -1);
            assertTrue(lines[0].endsWith("  " + expectedConnection), lines[0]);
            assertEquals("Schema: " + expectedSchema, lines[1]);
            assertEquals("select null;  -- raw", lines[2]);
            f.list().getSelectionModel().selectFirst();
            SqlDraft selected = f.list().getSelectionModel().getSelectedItem();
            assertSame(raw, selected);
            assertEquals(name, selected.connectionName());
            assertEquals(schema, selected.schema());
            assertEquals("select null;\r\n-- raw", selected.sql());
            assertEquals("select null;\n-- raw", f.sql().getText());
            cell.updateIndex(-1);
            assertNull(cell.getText());
            assertNull(cell.getGraphic());
        });
    }
}

@ParameterizedTest @ValueSource(strings = {"dark", "light"})
void previewPromptHasReadableThemeContrastAcrossSelectionAndFocusStyles(String theme) throws Exception {
    try (Fixture f = new Fixture(true, true, true)) {
        f.fx(() -> {
            Scene scene = f.pane.getNode().getScene();
            scene.getStylesheets().setAll(
                    SqlDraftManagerTest.class.getResource("theme-base.css").toExternalForm(),
                    SqlDraftManagerTest.class.getResource("theme-" + theme + ".css").toExternalForm());
            for (boolean focusedStyle : new boolean[] {false, true}) {
                f.sql().pseudoClassStateChanged(PseudoClass.getPseudoClass("focused"), focusedStyle);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                assertReadablePreviewText(f.sql(), f.sql().getPromptText());
            }
            f.list().getSelectionModel().select(f.newer);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            assertFalse(f.sql().isEditable());
            assertReadablePreviewText(f.sql(), "select 1;\n-- raw\n");
            assertEquals("select 1;\r\n-- raw\n", f.newer.sql());
            f.list().getSelectionModel().clearSelection();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            assertReadablePreviewText(f.sql(), f.sql().getPromptText());
        });
    }
}

private static void assertReadablePreviewText(TextArea area, String expected) {
    Text rendered = area.lookupAll(".text").stream().filter(Text.class::isInstance)
            .map(Text.class::cast).filter(text -> expected.equals(text.getText()))
            .findFirst().orElseThrow(() -> new AssertionError("Missing rendered preview: " + expected));
    assertTrue(rendered.isVisible());
    assertEquals(1.0, rendered.getOpacity(), 0.0001);
    Color foreground = assertInstanceOf(Color.class, rendered.getFill());
    assertEquals(1.0, foreground.getOpacity(), 0.0001);
    Region content = assertInstanceOf(Region.class, area.lookup(".content"));
    javafx.scene.paint.Paint background = content.getBackground().getFills().getLast().getFill();
    List<Color> backgroundColors = background instanceof Color color ? List.of(color)
            : assertInstanceOf(javafx.scene.paint.LinearGradient.class, background).getStops().stream()
                    .map(javafx.scene.paint.Stop::getColor).toList();
    assertFalse(backgroundColors.isEmpty());
    for (Color color : backgroundColors) {
        assertEquals(1.0, color.getOpacity(), 0.0001);
        double a = luminance(foreground), b = luminance(color);
        double contrast = (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
        assertTrue(contrast >= 4.5, "Preview contrast=" + contrast + ", text=" + expected);
    }
}

private static double luminance(Color color) {
    return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen())
            + 0.0722 * linear(color.getBlue());
}

private static double linear(double channel) {
    return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
}
```

These tests deliberately use actual CSS computation with a focused pseudo-class, not OS focus acquisition. Root will separately verify actual focus in desktop acceptance. If a JavaFX skin fixture assumption fails, correct the fixture against actual node behavior before counting RED; never weaken contrast assertions or introduce production seams.

- [ ] **Step 2: Run focused RED and report before GREEN.**

PowerShell (preserve process environment):

```powershell
$draftPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=false'
    .\gradlew.bat test --tests com.datacube.fx.SqlDraftManagerTest --rerun-tasks --no-daemon --console=plain
} finally { $env:JAVA_TOOL_OPTIONS = $draftPreviousJavaOptions }
```

Expected nonzero with missing metadata fallback and insufficient/transparent prompt-color assertions. Compile errors or fixture lookups are not behavioral RED. Send root the actual XML names/count and keep production unchanged until root acknowledges.

- [ ] **Step 3: Implement minimal presentation correction.** Replace the existing cell `setText` construction with:

```java
setText(empty || draft == null ? null : TIME.format(Instant.ofEpochMilli(draft.modifiedAt()))
        + "  " + (draft.connectionType() == null ? "未绑定连接"
                : displayMetadata(draft.connectionName(), "未命名连接") + " · " + draft.connectionType())
        + "\nSchema: " + displayMetadata(draft.schema(), "未指定") + "\n"
        + (draft.sql().isEmpty() ? "空草稿" : preview(draft.sql(), 120)));
```

Add this private method to the same class:

```java
private static String displayMetadata(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : preview(value, 80);
}
```

Append to `theme-base.css`:

```css
/* Keep the read-only draft preview guidance visible, including focused state. */
#draft-manager-sql {
    -fx-prompt-text-fill: -brand-fg-dim;
}
```

- [ ] **Step 4: Verify focused GREEN then full regression once.** Repeat Step 2 command, expected all manager tests pass without skips; then same environment-preserving wrapper with `test --rerun-tasks --no-daemon --console=plain` (no tests filter). Expected exit0; record actual suites/tests/failures/errors/skips and any existing compiler notes. Root owns later process/package and desktop checks; do not run them.
- [ ] **Step 5: Self-review, commit only the three task files, write report.**

```powershell
git diff --check
git add src/com/datacube/fx/SqlDraftManagerPane.java resources/com/datacube/fx/theme-base.css test/com/datacube/fx/SqlDraftManagerTest.java
git commit -m "fix: clarify SQL draft metadata and preview guidance"
```

Report must contain actual RED/GREEN command/output and XML counts, changed files, concerns and a `Requirement | Evidence` table mapping metadata and both-theme/focus-style/selection behavior to exact test names. No claim of desktop focus from pseudo-class tests. Root performs independent review and final integration.
