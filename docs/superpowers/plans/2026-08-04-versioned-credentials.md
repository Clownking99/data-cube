# Versioned Cross-Platform Credentials Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Protect newly saved passwords with Windows DPAPI while retaining versioned AES-GCM on other platforms and transparent access to legacy ciphertext.

**Architecture:** Keep `CredentialCipher` as the public facade. Delegate payload protection to small `CredentialProtector` implementations, choose DPAPI on Windows with AES-GCM fallback, and migrate valid legacy entries only when the connection snapshot is next saved.

**Tech Stack:** Java 25 FFM API, Windows Crypt32/Kernel32, AES-GCM, JUnit 5, Gradle/jlink.

## Global Constraints

- Windows is the primary release target while all non-native functionality remains cross-platform.
- Do not add JNA or another runtime dependency.
- Empty passwords remain empty strings.
- Errors and logs must not contain plaintext passwords or complete ciphertext.
- Existing unprefixed AES-GCM ciphertext remains readable.
- Work directly on `main`; do not push.

---

### Task 1: Versioned cipher facade and legacy compatibility

**Files:**
- Create: `src/com/datacube/config/CredentialProtector.java`
- Create: `src/com/datacube/config/AesGcmCredentialProtector.java`
- Modify: `src/com/datacube/config/CredentialCipher.java`
- Create: `test/com/datacube/config/CredentialCipherTest.java`

**Interfaces:**
- Produces: `CredentialProtector.scheme()`, `protect(String)`, `unprotect(String)`.
- Produces: `CredentialCipher.encrypt(String)`, `decrypt(String)`, `needsUpgrade(String)`, and `upgrade(String)`.

- [x] **Step 1: Write failing facade tests**

Add tests proving that AES output starts with `v2:aesgcm:`, legacy unprefixed payloads still decrypt, empty values stay empty, unknown/damaged formats fail without echoing input, and `upgrade` converts a readable legacy payload but retains an unreadable one.

- [x] **Step 2: Verify RED**

Run: `./gradlew.bat test --tests com.datacube.config.CredentialCipherTest`

Expected: compilation fails because the versioned facade and protector seam do not exist.

- [x] **Step 3: Implement the minimal facade and AES protector**

Use the existing v1 key derivation and payload algorithm for legacy reads. Prefix new AES payloads as `v2:aesgcm:<payload>`, dispatch by prefix, and return the original ciphertext from `upgrade` if migration fails.

- [x] **Step 4: Verify GREEN and review assertions**

Run: `./gradlew.bat test --tests com.datacube.config.CredentialCipherTest`

Expected: all focused tests pass; assertions verify values and sanitization rather than test-double calls.

- [x] **Step 5: Commit**

Commit: `feat: 引入版本化凭据格式并兼容旧密文`

### Task 2: Windows DPAPI through JDK 25 FFM

**Files:**
- Create: `src/com/datacube/config/DpapiCredentialProtector.java`
- Modify: `src/com/datacube/config/CredentialCipher.java`
- Modify: `build.gradle`
- Create: `test/com/datacube/config/DpapiCredentialProtectorTest.java`
- Modify: `test/com/datacube/config/CredentialCipherTest.java`

**Interfaces:**
- Produces: DPAPI protector returning raw Base64 payloads to the facade.
- Consumes: `CryptProtectData`, `CryptUnprotectData`, `LocalFree`, and `GetLastError` via FFM.

- [x] **Step 1: Write failing DPAPI selection/fallback tests**

Test that a DPAPI primary produces `v2:dpapi:`, that primary failure falls back to `v2:aesgcm:`, and that a Windows native round trip restores Unicode without returning plaintext bytes.

- [x] **Step 2: Verify RED**

Run: `./gradlew.bat test --tests com.datacube.config.CredentialCipherTest --tests com.datacube.config.DpapiCredentialProtectorTest`

Expected: compilation fails because the DPAPI protector and primary/fallback constructor are missing.

- [x] **Step 3: Implement the native bridge**

Model `DATA_BLOB` using ABI-aligned FFM layouts, pass `CRYPTPROTECT_UI_FORBIDDEN`, copy native output before leaving the confined arena, and always release `pbData` with `LocalFree`. Convert native failures to sanitized `IllegalStateException` messages containing only the Win32 error code.

- [x] **Step 4: Enable native access for tests and verify GREEN**

Set `--enable-native-access=ALL-UNNAMED` on the Gradle test JVM. Run the two focused test classes and confirm all tests pass on Windows; gate the native round trip with an OS assumption so Linux CI remains valid.

- [x] **Step 5: Commit**

Commit: `feat: Windows 使用 DPAPI 保护连接密码`

### Task 3: Save-time legacy migration and documentation

**Files:**
- Create: `src/com/datacube/config/CredentialMigration.java`
- Create: `test/com/datacube/config/CredentialMigrationTest.java`
- Modify: `src/com/datacube/fx/ConnectionTreePane.java`
- Modify: `src/com/datacube/spi/model/ConnConfig.java`
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-08-04-versioned-credentials.md`

**Interfaces:**
- Produces: `CredentialMigration.upgradeAll(List<ConnConfig>, CredentialCipher)`.
- Consumes: `ConnConfig.withEncryptedPassword(String)` and `CredentialCipher.upgrade(String)`.

- [ ] **Step 1: Write failing migration tests**

Test a mixed snapshot where a valid v1 entry becomes v2, an existing v2 entry is unchanged, an empty password remains empty, and a damaged v1 entry is preserved without blocking valid siblings.

- [ ] **Step 2: Verify RED**

Run: `./gradlew.bat test --tests com.datacube.config.CredentialMigrationTest`

Expected: compilation fails because `CredentialMigration` is missing.

- [ ] **Step 3: Implement migration and wire every snapshot save**

Add the pure migration helper and call it before all connection-tree add/edit/delete `saveAll` operations. Do not migrate during `loadAll`, and never overwrite an entry whose legacy decryption fails.

- [ ] **Step 4: Update docs and verify the project**

Document version prefixes, DPAPI user/machine binding, fallback behavior, and save-time migration. Run `./gradlew.bat clean test`, `./gradlew.bat jlink`, `git diff --check`, and `codegraph sync`.

- [ ] **Step 5: Commit**

Commit: `feat: 保存连接时迁移旧版凭据`
