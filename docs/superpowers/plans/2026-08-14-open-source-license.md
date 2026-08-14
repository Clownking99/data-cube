# DataCube Open Source License Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** License DataCube-owned source code under Apache License 2.0 and document the boundary between DataCube code and bundled third-party components.

**Architecture:** This is a repository metadata and documentation change only. Add the canonical Apache License 2.0 text at the repository root and add one README section that states ownership, SPDX identity, copyright name, and third-party-license boundaries without modifying source or build behavior.

**Tech Stack:** Markdown, Apache License 2.0 canonical text, Git static checks, PowerShell verification.

## Global Constraints

- Use the standard Apache License 2.0 text without modification.
- Use `Copyright 2026 Clownking99` as the public copyright identity.
- License only DataCube-owned source code; JavaFX, Oracle/PostgreSQL JDBC, RichTextFX, and all other third-party components remain under their own terms.
- Do not add source-file headers or modify Java, Gradle, packaging, or workflow behavior.
- Do not read, modify, stage, or commit the existing untracked `.testagent/` directory.

---

### Task 1: Add and document Apache License 2.0

**Files:**
- Create: `LICENSE`
- Modify: `README.md`

**Interfaces:**
- Consumes: Apache License 2.0 canonical text from `https://www.apache.org/licenses/LICENSE-2.0.txt`
- Produces: GitHub-detectable root license metadata and a human-readable README license statement

- [ ] **Step 1: Verify the precondition**

Run:

```powershell
Test-Path -LiteralPath LICENSE
Select-String -LiteralPath README.md -Pattern '^## 开源许可$'
```

Expected: `Test-Path` prints `False`; `Select-String` returns no match.

- [ ] **Step 2: Add the canonical license text**

Use `apply_patch` to create `LICENSE` with the complete, unmodified text whose first lines are:

```text
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/
```

The file must include the complete `END OF TERMS AND CONDITIONS` section and the standard application appendix through its final `under the License.` line. Do not add a project-specific clause to the license body.

- [ ] **Step 3: Add the README license section**

Append this exact section to `README.md`:

```markdown
## 开源许可

DataCube 自有源码采用 [Apache License 2.0](LICENSE) 开源许可。

Copyright 2026 Clownking99

本仓库包含的 JavaFX、Oracle/PostgreSQL JDBC、RichTextFX 及其他第三方组件不因
DataCube 的许可证而重新授权；它们继续遵循各自的许可证和分发条款。
```

- [ ] **Step 4: Verify canonical text and repository scope**

Download the official reference to a temporary file outside the repository, normalize only line endings, and compare the full text:

```powershell
$reference = Join-Path ([System.IO.Path]::GetTempPath()) 'apache-license-2.0-reference.txt'
Invoke-WebRequest -Uri 'https://www.apache.org/licenses/LICENSE-2.0.txt' -OutFile $reference
$localText = (Get-Content -Raw -LiteralPath LICENSE) -replace "`r`n", "`n"
$referenceText = (Get-Content -Raw -LiteralPath $reference) -replace "`r`n", "`n"
if ($localText.TrimEnd() -ne $referenceText.TrimEnd()) { throw 'LICENSE differs from Apache canonical text' }
git diff --check
git status --short
```

Expected: full-text comparison succeeds; `git diff --check` exits 0; status lists only `LICENSE`, `README.md`, and the pre-existing untracked `.testagent/` directory.

- [ ] **Step 5: Review and commit the license change**

Run:

```powershell
git diff -- LICENSE README.md
git add -- LICENSE README.md
git diff --cached --check
git commit -m "docs: 添加 Apache 2.0 开源许可"
```

Expected: the commit contains exactly `LICENSE` and `README.md`; no source, dependency, build, workflow, or `.testagent/` file is included.
