# DataCube Redis Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add first-class standalone Redis management to DataCube: RESP2 connectivity, connection lifecycle, key browsing/editing for all five Redis value types, and a safe command console.

**Architecture:** Keep the JDBC provider/service contracts unchanged and add a parallel `com.datacube.redis` subsystem. `ConnectionManager` performs only top-level type dispatch; each Redis UI tab owns an isolated session so `SELECT` and command sequencing cannot leak between tabs.

**Tech Stack:** Java 25, JavaFX 25, JUnit 5.11, Gradle 9, JDK `Socket`; no new runtime dependency.

## Global Constraints

- Implement only standalone Redis with optional password and optional ACL username.
- Use RESP2 over a plain TCP socket; TLS, cluster, Sentinel, Pub/Sub, SLOWLOG, and server dashboards remain out of scope.
- Never use `KEYS`; key and collection discovery must use cursor-based `SCAN`, `HSCAN`, `SSCAN`, or `ZSCAN`.
- All network I/O must run off the JavaFX application thread.
- Values remain binary-safe as `byte[]`; text conversion is an explicit UI concern.
- Preserve all existing JDBC provider interfaces and relational behavior.
- Run `codegraph sync .` after source changes so impact checks use the current graph.

---

## File Map

- `src/com/datacube/redis/RedisException.java`: Redis protocol/server exception.
- `src/com/datacube/redis/RespCodec.java`: RESP2 command encoding and response decoding.
- `src/com/datacube/redis/RespClient.java`: socket transport, authentication, DB selection, and serialized calls.
- `src/com/datacube/redis/RedisSession.java`: typed Redis command facade and scan result records.
- `src/com/datacube/redis/RedisSessionManager.java`: configured/cached Redis session lifecycle plus independent tab sessions.
- `src/com/datacube/redis/KeyTreeBuilder.java`: pure key-prefix tree builder.
- `src/com/datacube/redis/RedisConsoleSupport.java`: pure shell-like tokenizer, command policy, and RESP formatting.
- `src/com/datacube/fx/RedisKeyBrowserPane.java`: paged key browser and five value editors.
- `src/com/datacube/fx/RedisConsolePane.java`: non-blocking Redis command console.
- Existing model, service, dialog, tree, and shell files: minimal Redis dispatch/wiring only.
- `test/com/datacube/redis/*Test.java`: protocol, session, tree, and console behavior tests.

---

### Task 1: RESP2 codec

**Files:**
- Create: `src/com/datacube/redis/RedisException.java`
- Create: `src/com/datacube/redis/RespCodec.java`
- Create: `test/com/datacube/redis/RespCodecTest.java`

**Interfaces:**
- Produces: `static byte[] RespCodec.encode(String... args)`
- Produces: `static Object RespCodec.decode(InputStream input) throws IOException`
- Produces: `RedisException(String message)`

- [ ] **Step 1: Write failing codec tests**

Cover an exact UTF-8 command frame, simple strings as `byte[]`, bulk strings, binary bulk payloads, integers as `Long`, nested arrays, null bulk/array values, truncated frames, invalid markers, and server errors preserving the original message.

```java
assertArrayEquals("*2\r\n$3\r\nGET\r\n$6\r\n你好\r\n".getBytes(UTF_8),
        RespCodec.encode("GET", "你好"));
assertEquals(42L, RespCodec.decode(stream(":42\r\n")));
assertThrows(RedisException.class, () -> RespCodec.decode(stream("-WRONGTYPE bad\r\n")));
```

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat test --tests com.datacube.redis.RespCodecTest`

Expected: compilation fails because `RespCodec` and `RedisException` do not exist.

- [ ] **Step 3: Implement the minimal codec**

Decode line lengths as strict base-10 integers, read bulk bytes with an exact-length loop, require the trailing CRLF, recurse for arrays, and throw `IOException` for malformed/truncated transport data.

- [ ] **Step 4: Verify GREEN**

Run: `.\gradlew.bat test --tests com.datacube.redis.RespCodecTest`

Expected: all codec tests pass.

### Task 2: Socket client and typed session

**Files:**
- Create: `src/com/datacube/redis/RespClient.java`
- Create: `src/com/datacube/redis/RedisSession.java`
- Create: `test/com/datacube/redis/RespClientTest.java`
- Create: `test/com/datacube/redis/RedisSessionTest.java`

**Interfaces:**
- Produces: `RespClient(String host, int port, String username, String password, int database)`
- Produces: `synchronized Object RespClient.call(String... args)` and `void close()`
- Produces: `RedisSession.ScanPage(long cursor, List<byte[]> values)`
- Produces: `RedisSession.ScoredValue(byte[] member, double score)`
- Produces: typed key, String, Hash, List, Set, ZSet, INFO, and raw command methods from the approved design.

- [ ] **Step 1: Write failing transport tests around a loopback fake RESP server**

The fake server records command arrays and returns scripted RESP frames. Assert handshake order for no-auth, password-only AUTH, ACL AUTH, and nonzero SELECT; assert socket close, serialized calls, timeouts, and propagation of server errors.

```java
assertEquals(List.of(List.of("AUTH", "alice", "secret"), List.of("SELECT", "3"), List.of("PING")),
        server.receivedUtf8Commands());
```

- [ ] **Step 2: Verify client RED**

Run: `.\gradlew.bat test --tests com.datacube.redis.RespClientTest`

Expected: compilation fails because `RespClient` does not exist.

- [ ] **Step 3: Implement `RespClient`**

Use a 5,000 ms connect timeout and 10,000 ms read timeout. Connect lazily on the first command, perform AUTH/SELECT once per socket, synchronize complete request/response pairs, and close/reset the socket after any `IOException`.

- [ ] **Step 4: Verify client GREEN**

Run: `.\gradlew.bat test --tests com.datacube.redis.RespClientTest`

Expected: all client tests pass.

- [ ] **Step 5: Write failing typed-session tests**

Inject a package-private command transport into `RedisSession`; assert exact argument order and binary response conversion for SCAN, TYPE/TTL, SET/GET, HSCAN/HSET, LRANGE/LSET/LREM, SSCAN/SADD, ZSCAN/ZADD, INFO, and raw commands.

- [ ] **Step 6: Implement the typed facade and verify GREEN**

Run: `.\gradlew.bat test --tests com.datacube.redis.RedisSessionTest`

Expected: all session tests pass.

### Task 3: Pure key-tree and console behavior

**Files:**
- Create: `src/com/datacube/redis/KeyTreeBuilder.java`
- Create: `src/com/datacube/redis/RedisConsoleSupport.java`
- Create: `test/com/datacube/redis/KeyTreeBuilderTest.java`
- Create: `test/com/datacube/redis/RedisConsoleSupportTest.java`

**Interfaces:**
- Produces: immutable `KeyTreeBuilder.Node` with `segment`, optional full key, descendants, and recursive key count.
- Produces: `List<String> RedisConsoleSupport.tokenize(String line)`
- Produces: `CommandPolicy RedisConsoleSupport.policy(List<String> args)` with `NORMAL`, `CONFIRM`, and `BLOCKED`.
- Produces: `String RedisConsoleSupport.format(Object response)`.

- [ ] **Step 1: Write failing tree tests**

Cover nested `:` segments, a configurable separator, keys without separators, stable lexical ordering, duplicates, empty segments, and the `user`/`user:name` key-folder collision without dropping either key.

- [ ] **Step 2: Verify tree RED, implement minimally, and verify GREEN**

Run: `.\gradlew.bat test --tests com.datacube.redis.KeyTreeBuilderTest`

Expected first run: missing class; expected final run: all tests pass.

- [ ] **Step 3: Write failing console tests**

Cover single/double quotes, escaped quotes/backslashes, empty quoted arguments, unterminated quote errors, case-insensitive dangerous and blocking command classification, CONFIG SET two-word matching, and nested RESP formatting.

- [ ] **Step 4: Implement console support and verify GREEN**

Run: `.\gradlew.bat test --tests com.datacube.redis.RedisConsoleSupportTest`

Expected: all console tests pass.

### Task 4: Redis lifecycle and existing connection model integration

**Files:**
- Create: `src/com/datacube/redis/RedisSessionManager.java`
- Create: `test/com/datacube/redis/RedisSessionManagerTest.java`
- Modify: `src/com/datacube/spi/model/DbType.java`
- Modify: `src/com/datacube/spi/model/ConnConfig.java`
- Modify: `src/com/datacube/service/ConnectionManager.java`

**Interfaces:**
- Produces: `RedisSession acquire(String connId)`, `RedisSession openSession(String connId, int database)`, `String test(ConnConfig cfg)`, `boolean isConnected(String connId)`, `release`, `unregister`, and `closeAll`.
- Produces: `RedisSession ConnectionManager.acquireRedis(String connId)` and `openRedisSession(String connId, int database)`.
- Preserves: `Connection ConnectionManager.acquire(String connId)` for JDBC callers; Redis input throws a descriptive `IllegalStateException`.

- [ ] **Step 1: Write failing manager/model tests**

Use an injected Redis client factory to assert decrypted credentials, cached PING validation, one reconnect after a failed PING/call, isolated sessions at the requested DB, release/close behavior, `redis://host:port/db`, and unchanged PostgreSQL/Oracle URLs.

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat test --tests com.datacube.redis.RedisSessionManagerTest`

Expected: compilation fails on the new Redis lifecycle API.

- [ ] **Step 3: Add `DbType.REDIS` and Redis URL construction**

```java
REDIS("Redis", "redis://", 6379)
// ConnConfig.jdbcUrl(): redis://<host>:<port>/<database>
```

- [ ] **Step 4: Implement Redis lifecycle and thin `ConnectionManager` dispatch**

Registering an updated config must release any old Redis/JDBC live connection for that ID. Password plaintext stays only in a temporary config/client constructor and is never stored in `configs`.

- [ ] **Step 5: Verify GREEN and relational regression safety**

Run: `.\gradlew.bat test`

Expected: Redis manager tests and all existing tests pass.

### Task 5: Redis connection dialog and connection tree

**Files:**
- Modify: `src/com/datacube/fx/ConnectionDialog.java`
- Modify: `src/com/datacube/fx/ConnectionTreePane.java`
- Modify: `src/com/datacube/fx/AppShell.java`

**Interfaces:**
- Adds: `ConnectionTreePane.Actions.openRedisKeys(ConnConfig conn, int database)`
- Adds: `ConnectionTreePane.Actions.openRedisConsole(ConnConfig conn)`
- Adds: `Kind.REDIS_DB` with DB index stored in `NodeData.name`.

- [ ] **Step 1: Add Redis-aware form state and validation**

Populate the type box from `DbType.values()`. For Redis, label the database field `DB 索引:`, default it to `0`, show optional ACL/password hints, permit an empty username/password, normalize an empty DB to `0`, and require an integer from 0 through 15.

- [ ] **Step 2: Build to catch enum exhaustiveness/UI wiring errors**

Run: `.\gradlew.bat compileJava`

Expected: successful compilation.

- [ ] **Step 3: Add Redis DB nodes and actions**

For a Redis connection, parse `INFO keyspace` into db0-db15 labels without invoking relational services. Double-clicking a DB opens its key browser; the Redis connection menu opens the console; SQL/table/DDL actions remain relational-only.

- [ ] **Step 4: Block SQL entry for Redis and wire placeholder pane actions**

Both toolbar and tree SQL entry paths must show a concise information alert for Redis. Add action implementations that construct the Redis panes in Tasks 6 and 7, attach tab-close cleanup, and keep session selection stable.

- [ ] **Step 5: Verify integration compilation**

Run: `.\gradlew.bat compileJava`

Expected: successful compilation without changing relational provider code.

### Task 6: Redis key browser and five value editors

**Files:**
- Create: `src/com/datacube/fx/RedisKeyBrowserPane.java`

**Interfaces:**
- Produces: `RedisKeyBrowserPane(ConnectionManager manager, ConnConfig config, int database)`
- Produces: `Node getNode()` and `void close()`.

- [ ] **Step 1: Build the isolated session and paged key list**

Create an independent session at the selected DB. Implement glob search, DB switching, refresh, separator selection, `SCAN COUNT 500`, accumulated de-duplicated keys, and a load-more control that disappears when cursor returns to zero.

- [ ] **Step 2: Implement common key actions**

Load TYPE and TTL in the background; support rename, expire, persist, delete, copy key name, and create-key dialogs. Refresh only affected UI state after successful mutations.

- [ ] **Step 3: Implement String editor with binary/large-value safety**

Use text, JSON-pretty, and hexadecimal views. Values over 1 MiB initially show only a 4 KiB preview and require explicit confirmation before complete rendering; save via SET without implicit charset conversion when hex mode is active.

- [ ] **Step 4: Implement Hash/List/Set/ZSet editors**

Use paged tables: HSCAN, LRANGE windows, SSCAN, and ZSCAN. Support the exact add/update/delete operations in the approved design and keep score/member pairing intact.

- [ ] **Step 5: Verify build after each editor and run all unit tests**

Run: `.\gradlew.bat clean test`

Expected: build and all tests pass.

### Task 7: Redis command console

**Files:**
- Create: `src/com/datacube/fx/RedisConsolePane.java`
- Modify: `src/com/datacube/fx/AppShell.java`

**Interfaces:**
- Produces: `RedisConsolePane(ConnectionManager manager, ConnConfig config)`
- Produces: `Node getNode()` and `void close()`.

- [ ] **Step 1: Build a console with an independent session**

Use a read-only output area plus one-line input, execute off the FX thread, format results through `RedisConsoleSupport`, and retain in-memory Up/Down history.

- [ ] **Step 2: Enforce command policy before transport**

Block SUBSCRIBE, PSUBSCRIBE, MONITOR, BLPOP, BRPOP, BRPOPLPUSH, BLMOVE, BZPOPMIN, BZPOPMAX, XREAD BLOCK, and WAIT. Confirm FLUSHALL, FLUSHDB, SHUTDOWN, DEBUG, and CONFIG SET.

- [ ] **Step 3: Wire tab lifecycle**

Opening a console creates a fresh tab/session; closing the tab invokes `close()`. Network errors append an error-styled entry without freezing or closing the console.

- [ ] **Step 4: Verify compilation and tests**

Run: `.\gradlew.bat test`

Expected: all tests pass.

### Task 8: Full verification, graph impact review, and documentation alignment

**Files:**
- Modify if needed: `README.md`
- Modify if needed: `docs/superpowers/specs/2026-07-08-redis-support-design.md`

- [ ] **Step 1: Rebuild the graph and inspect affected callers/tests**

Run: `codegraph sync .`

Run: `codegraph affected src/com/datacube/service/ConnectionManager.java src/com/datacube/fx/AppShell.java src/com/datacube/fx/ConnectionTreePane.java`

- [ ] **Step 2: Run clean verification**

Run: `.\gradlew.bat clean test`

Run: `.\gradlew.bat jlink`

Expected: both commands exit 0, with zero failed tests and a generated modular runtime image.

- [ ] **Step 3: Perform a focused manual smoke test when Redis is locally available**

Verify passwordless and ACL connections, DB switching, paged SCAN, CRUD for String/Hash/List/Set/ZSet, TTL/persist, console quoting/history, dangerous-command confirmation, blocked commands, disconnect/reconnect, and tab-close/application-close resource cleanup. If no Redis server is available, report this manual verification as not run rather than claiming it passed.

- [ ] **Step 4: Review requirements and report project-wide recommendations**

Compare every approved design section to the implementation and classify remaining gaps as blocker, follow-up, or intentionally deferred. Base project recommendations on concrete graph hotspots, file sizes, test coverage, lifecycle/threading risks, packaging, and documentation—not generic style advice.
