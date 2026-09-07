# chDB Java Binding V1 工作计划

> 状态：Draft  
> 更新时间：2026-09-08  
> 目标仓库：`chdb-io/chdb-java`

## 1. 项目目标

V1 的目标是在 Java 11 及以上版本中，以进程内动态链接的方式使用 chDB，并提供一个可以被常见 Java 应用和 JDBC 框架采用的最小生产级驱动。

V1 的一句话定义：

> 在 Linux x86_64/aarch64 和 macOS x86_64/arm64 上，通过小型 JNI shim 动态加载固定版本的 `libchdb`，提供安全、流式、类型正确、可取消、可明确释放的 JDBC 查询能力，并以 Maven 平台包发布。

V1 不追求一次性覆盖完整 JDBC 规范，也不追求覆盖 chDB 的全部高级能力。首要目标是把 native 生命周期、信号处理、堆外内存、平台打包和查询结果类型做正确。

## 2. V1 范围边界

### 2.1 V1 必须交付

- Java 11 为最低运行版本。
- 验证 Java 11、17、21、25，并将 Java 26 作为前向兼容测试版本。
- 支持以下四个平台：
  - Linux x86_64 glibc
  - Linux aarch64 glibc
  - macOS x86_64
  - macOS arm64
- 使用固定版本的 chDB 稳定 C ABI；V1 初始基线为 `chdb-core v26.7.0`，发布前可以升级，但必须重新锁定并完成全量测试。
- 小型 JNI shim 与 `libchdb` 保持为两个独立动态库，不在 Java JNI 库中静态链接完整 chDB。
- Maven 产物采用“纯 Java 主包 + 平台 native 包”的模式。
- 提供 JDBC Driver、Connection、Statement、PreparedStatement 和流式 ResultSet。
- 支持 `DriverManager` 和 `META-INF/services/java.sql.Driver` 自动发现。
- 支持进程内 chDB session 和同一 storage path 的多个 Connection。
- 对不同 storage path 同时连接的请求给出清晰、可诊断的错误。
- 查询结果按有界 batch 流式读取，不允许默认全量物化为 CSV、JSON 或 Java byte array。
- 支持 JDBC 常用标量类型、NULL、ResultSetMetaData 和必要的 DatabaseMetaData。
- PreparedStatement 使用 chDB server-side typed parameters，不允许通过 SQL 字符串插值实现。
- 支持 `Statement.cancel()`、ResultSet 提前关闭和确定性 native 资源释放。
- 所有 C++ 异常必须在 JNI 边界内转换为 Java 异常，不允许穿过 JNI 边界。
- 处理 JVM signal handler 与 chDB signal handler 的冲突。
- 对 native 版本不匹配、符号缺失、平台不支持、动态库加载失败进行 fail-fast。
- 建立 native 内存、句柄泄漏、use-after-free、并发和生命周期测试。
- 发布用户文档、平台选择说明、限制说明和最小示例。

### 2.2 V1 可以实验性提供，但不阻塞正式发布

- `chdb-adbc` convenience module：封装 Apache Arrow ADBC JNI Driver Manager 对 `chdb_adbc_init` 的加载。
- 少量框架兼容性示例，例如 Spring JDBC、HikariCP 和 ShardingSphere。
- 复杂 ClickHouse 类型通过 `getObject()` 或稳定文本形式读取。
- macOS universal binary。

实验性能力必须清楚标为 experimental，不纳入 V1 的稳定兼容承诺。

### 2.3 明确不属于 V1

- Java 8 支持。
- Windows native 包。
- Linux musl/Alpine native 包。
- 单个包含全部平台引擎的 `chdb-jdbc-all` 巨型 JAR。
- 运行时联网下载 `libchdb`。
- GraalVM Native Image。
- Android。
- OSGi 的完整支持。
- 在多个相互隔离的 ClassLoader 中各自加载一份 chDB native library。
- 完整 JDBC TCK 兼容。
- 可滚动 ResultSet、可更新 ResultSet 和 scrollable cursor。
- JDBC transaction、savepoint、XA transaction。
- CallableStatement、stored procedure。
- JDBC batch update。
- Arrow scan、Arrow table 注册和零复制写入。
- streaming insert 和高吞吐批量 ingest。
- Java UDF。
- 完整支持 Array、Map、Tuple、Nested、Variant、Dynamic 等全部 ClickHouse 复杂类型。
- 自动跨版本兼容任意用户提供的 `libchdb`。
- native 崩溃隔离；V1 是进程内 binding，native 崩溃可能终止 JVM。

## 3. V1 架构决策

### 3.1 调用链

```text
Java 应用 / Spring / JDBC 框架
                |
            chdb-jdbc
   JDBC API、loader、生命周期、类型映射
                |
        libchdb_java_jni
   JNI 句柄、异常、信号保护、batch 访问
                |
             libchdb
        chDB 稳定 C ABI
```

### 3.2 主实现路线

V1 正式 JDBC 驱动采用直接 JNI 路线：

```text
JDBC -> JNI shim -> chDB C API
```

原因：

- 不让 V1 稳定性依赖 chDB 当前标为 experimental 的 ADBC 实现。
- 避免在基础 JDBC 包中强制引入完整 Arrow Java 依赖。
- 可以直接控制 signal handler、句柄生命周期和错误映射。
- 后续仍可让 JDBC 和 ADBC 共享相同的平台 `libchdb` 包。

### 3.3 数据通路

- 优先使用 chDB Arrow streaming C API 获取有类型的有界批次。
- JNI 层拥有当前 stream、Arrow schema、Arrow batch 和底层 native buffer。
- Java ResultSet 只持有 opaque native handle，不持有已经被释放的裸指针。
- 只有在 ResultSet 前进到下一批或者关闭时，才能释放当前 batch。
- Java 基础 JDBC 模块不直接向用户暴露 Arrow Java 类型。
- 在正式选定具体 batch 访问方式前，必须基准比较：
  - 每个单元格一次 JNI getter；
  - 每列 DirectByteBuffer；
  - native 端转换成紧凑的 Java-owned batch。
- 最终方案必须同时满足正确生命周期、有界内存和可接受的 JNI 调用开销。

### 3.4 JDBC 能力约束

- ResultSet 固定为 `TYPE_FORWARD_ONLY`。
- ResultSet 固定为 `CONCUR_READ_ONLY`。
- `supportsTransactions()` 返回 `false`。
- `setAutoCommit(false)`、`commit()`、`rollback()` 和 savepoint API 抛出 `SQLFeatureNotSupportedException`。
- 未实现的 JDBC API 必须抛出 `SQLFeatureNotSupportedException`，不能返回虚假的 `null`、`0` 或成功结果。
- 一个 Connection 同时只执行一条语句；并发调用必须拒绝或串行，行为要写入文档。

### 3.5 ClassLoader 和单 native 实例模型

V1 的强制不变量：

> 一个 JVM 进程只能有一个 native owner ClassLoader，并且只加载一份 JNI shim 和一份 `libchdb`。V1 不通过复制动态库来让多个隔离 ClassLoader 各自加载 chDB。

支持的部署模型：

```text
普通 Java 应用
Application/System ClassLoader
└── chdb-jdbc + chdb-native-<platform> + 唯一 native runtime

应用服务器或插件容器
System/Common/Shared Parent ClassLoader
├── chdb-jdbc + chdb-native-<platform> + 唯一 native runtime
├── Child ClassLoader A（通过 parent delegation 使用 chDB）
└── Child ClassLoader B（通过 parent delegation 使用 chDB）
```

实现要求：

- 所有声明 JNI native method 的类、native loader 和 runtime singleton 必须由同一个 owner ClassLoader 加载。
- 普通单应用 JVM 中，owner 就是 application/system ClassLoader。
- Tomcat、Spark、Flink 和插件容器中，用户必须把 `chdb-jdbc` 与平台 native 包放到共同父 ClassLoader；子应用不能再次打包一份驱动。
- native 文件使用 content-addressed 固定路径解压，同一版本和 checksum 始终解析到同一绝对路径，不使用每个 ClassLoader 一个随机副本。
- 首次加载时写入只包含字符串信息的 JVM 级 owner marker，至少记录 owner、native 路径、engine version 和 binding version，用于在第二次加载前给出可诊断错误；JNI/OS loader 仍是最终安全边界。
- 第二个隔离 ClassLoader 如果不能通过 parent delegation 访问已有 runtime，必须 fail-fast，提示将驱动移到共享父 ClassLoader。
- V1 不尝试把第一个 child ClassLoader 中已经加载的 JNI 对象、函数或 handle 跨 ClassLoader 转交给第二个 child ClassLoader。

V1 对“多个 ClassLoader”的支持含义是：多个 child ClassLoader 可以共享父层的一份 chDB；不代表多个相互隔离的 ClassLoader 能分别拥有 chDB。

## 4. Maven 产物设计

### 4.1 V1 产物

```text
org.chdb:chdb-jdbc:<version>
org.chdb:chdb-native-linux-x86_64-gnu:<version>
org.chdb:chdb-native-linux-aarch64-gnu:<version>
org.chdb:chdb-native-macos-x86_64:<version>
org.chdb:chdb-native-macos-aarch64:<version>
org.chdb:chdb-bom:<version>
```

- `chdb-jdbc`：纯 Java API、JDBC 实现和 native loader，不包含完整 chDB 引擎。
- `chdb-native-*`：包含对应平台的 JNI shim、`libchdb`、manifest、checksum、许可证和 SBOM。
- `chdb-bom`：只负责让 Java/JNI/engine 包保持相同版本，不包含可执行代码。
- 平台 native 包可以传递依赖 `chdb-jdbc`，使应用只声明一个平台包也能获得完整运行时。

### 4.2 Native JAR 目录布局

```text
META-INF/chdb/native/<os>/<arch>/<libchdb>
META-INF/chdb/native/<os>/<arch>/<jni-shim>
META-INF/chdb/native/<os>/<arch>/manifest.properties
META-INF/chdb/native/<os>/<arch>/sha256sums.txt
META-INF/licenses/
META-INF/sbom/
```

`manifest.properties` 至少记录：

- Java binding 版本；
- 预期 chDB engine 版本；
- JNI ABI 版本；
- OS、CPU、libc；
- 构建 commit；
- 构建工具链；
- 动态库 checksum。

### 4.3 版本规则

采用“完整 engine 版本 + binding 修订号”：

```text
<engine-version>.<binding-revision>
```

- `engine-version` 必须原样保留 chDB Core release 版本，包括 `rc` qualifier。
- `binding-revision` 是最后一个正整数，表示 Java/JNI/loader/平台打包修订；每个新的 engine version 从 `1` 开始。
- 这是一套 engine-aligned 版本规则，不按 Java SemVer 解释。

示例：

| 场景 | Maven 版本 | 含义 |
|---|---|---|
| 首次绑定稳定引擎 26.7.0 | `26.7.0.1` | engine=`26.7.0`，binding revision=`1` |
| 同一稳定引擎只修 Java/JNI/loader | `26.7.0.2` | engine 不变，binding revision 加一 |
| 首次绑定引擎 26.7.2-rc.2 | `26.7.2-rc.2.1` | engine=`26.7.2-rc.2`，binding revision=`1` |
| 同一 rc.2 只修改 addon 后重发 | `26.7.2-rc.2.2` | engine 不变，binding revision 加一 |
| 引擎升级到 rc.3 | `26.7.2-rc.3.1` | 新 engine，binding revision 重置为 `1` |
| 引擎升级为正式 26.7.2 | `26.7.2.1` | 稳定 engine 的首次 binding release |

发布规则：

- Maven repository 中的 release artifact 不可变，绝不覆盖 `26.7.2-rc.2.1`；任何 addon、JNI、Java、POM、loader、checksum 或单平台修复都发布 `.2`。
- 一次 binding release 中，`chdb-jdbc`、四个平台包和 `chdb-bom` 使用完全相同的版本，即使某些平台内容没有变化也一起发布，避免 BOM 和平台包形成混合版本。
- engine 从一个 RC 升到另一个 RC，或者从 RC 升到稳定版，都视为新的 engine version，binding revision 重新从 `1` 开始。
- 基于 engine RC 的 Java artifact 也属于 preview，不可以作为 V1 GA 的引擎依赖；V1 GA 必须绑定稳定的 chDB Core release。
- 开发构建可使用 `26.7.2-rc.2.2-SNAPSHOT`，但 `SNAPSHOT` 不进入 Maven Central 正式 release。
- 如果稳定 engine 上的 Java binding 自身需要发布候选版，可使用 `26.7.0.1-rc.1`、`26.7.0.1-rc.2`，最终 GA 为 `26.7.0.1`；候选版和 GA 不能复用同一个不可变 artifact。
- engine 发生变化必须产生新的 Maven 版本并完成全平台测试。

为了消除字符串解析歧义，所有 artifact 的 manifest 必须分别记录：

```properties
engine.version=26.7.2-rc.2
binding.revision=2
binding.version=26.7.2-rc.2.2
java.api.version=1
jni.abi.version=1
```

运行时兼容性判断使用独立的 `engine.version`、`java.api.version`、`jni.abi.version` 和 symbol set，不依赖拆分 Maven 版本字符串来猜测。

### 4.4 发布前置调研

- [ ] 确认 Maven Central 对 100–180 MB 单文件 artifact 的当前限制。
- [ ] 确认 chDB 和所包含第三方动态库的再分发许可证要求。
- [ ] 确认是否需要发布到 ClickHouse/chDB 自有 Maven repository 作为备用。
- [ ] 确认 Maven Central 的签名、SBOM、sources 和 javadoc 要求。
- [ ] 验证 JAR/ZIP 压缩后的实际大小和安装磁盘占用。

## 5. 工作分解

### 5.1 阶段 0：冻结 V1 决策和上游依赖

- [ ] 将本计划提交到 `chdb-io/chdb-java` 并由 chDB Core、Java 和发布负责人评审。
- [ ] 确认 V1 最低 Java 版本为 11。
- [ ] 确认 V1 只支持四个已有 glibc/macOS 平台。
- [ ] 确认直接 JNI 是正式 JDBC 主线，ADBC 是实验支线。
- [ ] 固定 chDB Core release、header、动态库 checksum 和必需符号集合。
- [ ] 对 `chdb_version()`、release tag 和 header version 的一致性建立检查。
- [ ] 列出 V1 使用的 C API，并区分必需符号和可选符号。
- [ ] 与 chDB Core 维护者确认 C ABI 的版本和兼容承诺。
- [ ] 为 Java signal handler 需求向 chDB Core 提交或确认上游方案。

阶段完成条件：所有影响公共 API、Java 最低版本、平台范围和 native ABI 的决定均已书面确认。

### 5.2 阶段 1：重建仓库和构建骨架

- [ ] 保留现有仓库历史、许可证和必要包名，删除或隔离不安全的旧 JNI 实现。
- [ ] 建立 Maven multi-module 根项目。
- [ ] 建立 `chdb-jdbc` 模块。
- [ ] 建立 JNI CMake 构建目录。
- [ ] 建立四个平台 native artifact 的构建定义。
- [ ] 建立 `chdb-bom` 模块。
- [ ] 建立 integration test 和 example 模块。
- [ ] 使用 `javac -h` 生成 JNI header。
- [ ] 使用 CMake `find_package(JNI)`，不再提交系统 `jni.h`/`jni_md.h`。
- [ ] 启用 C++17、严格编译警告和符号可见性控制。
- [ ] CI 中加入 Java 格式化、静态检查和 native 格式化。

阶段完成条件：空实现能够在四个平台构建 Java JAR、JNI shim 和 native JAR。

### 5.3 阶段 2：平台引擎获取和动态链接

- [ ] 编写构建期下载脚本，只下载固定版本的官方 chDB Core release asset。
- [ ] 下载后强制校验 checksum。
- [ ] 禁止 CI 使用 `latest` URL。
- [ ] Linux JNI shim 动态链接同目录 `libchdb.so`。
- [ ] Linux 设置 `RUNPATH=$ORIGIN`。
- [ ] 检查 Linux 动态依赖中不存在构建机绝对路径。
- [ ] 检查 glibc 和 libstdc++ 最低版本。
- [ ] macOS 使用 `@loader_path` 引用同目录 `libchdb`。
- [ ] 检查 macOS 动态依赖中不存在构建机绝对路径。
- [ ] 确认 macOS codesign/notarization 是否影响从 JAR 解压加载。
- [ ] 从 runtime JAR 中排除 debug symbols，将符号单独保存为 CI artifact。
- [ ] 生成 licenses、checksum 和 SBOM。

阶段完成条件：四个平台上可以从 native JAR 解压两个动态库并完成 `System.load`。

### 5.4 阶段 3：Native loader

- [ ] 识别 OS、CPU 和 libc；V1 对 musl 明确报不支持。
- [ ] 规范化 `amd64/x86_64`、`aarch64/arm64` 等名称。
- [ ] 定义加载优先级：
  1. `-Dchdb.library.path=<path>`；
  2. 平台 native JAR；
  3. `java.library.path`。
- [ ] 支持用户指定同时包含 JNI shim 和 `libchdb` 的外部目录。
- [ ] 不允许运行时联网下载。
- [ ] 按 checksum 建立 content-addressed 解压目录。
- [ ] 采用临时文件加 atomic rename，避免半写入动态库。
- [ ] 使用跨进程文件锁，避免多个 JVM 同时解压损坏文件。
- [ ] 校验解压后 checksum。
- [ ] 设置安全文件权限和可执行权限。
- [ ] 支持 `-Dchdb.tmpdir` 或 `-Dchdb.cache.dir`。
- [ ] 对只读目录和 `noexec /tmp` 给出明确错误及解决方法。
- [ ] 先加载 `libchdb`，再加载 JNI shim。
- [ ] 运行时校验 JNI ABI、engine version 和必需 symbols。
- [ ] 捕获“已被另一个 ClassLoader 加载”的错误并给出共享父 ClassLoader 的解决说明。
- [ ] 不通过随机复制 native library 绕过 ClassLoader 限制。
- [ ] 建立 JVM 级 native owner marker，在加载第二份动态库之前检测 owner、路径和版本冲突。
- [ ] 同一个 engine/checksum 在所有 ClassLoader 中解析到相同的 content-addressed 解压路径。
- [ ] 确保 native-facing class、loader 和 runtime singleton 由同一个 owner ClassLoader 定义。

阶段完成条件：正常加载、外部路径覆盖、checksum 失败、平台不支持、noexec 和 ClassLoader 冲突都有自动化测试。

### 5.5 阶段 4：JNI 核心和句柄生命周期

- [ ] 定义 connection、query stream、batch、result 等 opaque handle 类型。
- [ ] 每个 handle 包含 magic、ABI version、状态和 owner 关系，拒绝无效或已关闭 handle。
- [ ] 所有 close/destroy 操作具备幂等性。
- [ ] 所有 C++ exception 在 JNI 层转换为 Java exception。
- [ ] native error 保留 chDB error code、message 和必要 query context。
- [ ] Java 字符串转为标准 UTF-8 bytes，并调用带长度的 `_n` API。
- [ ] 不使用 `GetStringUTFChars` 处理可能含非 ASCII 或 NUL 的参数。
- [ ] 实现连接创建和关闭。
- [ ] 实现 query/stream open、fetch、cancel 和 destroy。
- [ ] 实现 batch 生命周期，不允许释放后仍暴露 DirectByteBuffer。
- [ ] 为 debug/test build 增加开放句柄计数器。
- [ ] 增加 `Cleaner` 作为泄漏保险，但所有正常路径仍要求显式 close。
- [ ] 确保 Java callback 如从 native thread 发起，线程已正确 attach/detach JVM；V1 尽量不从 chDB worker thread 反调 Java。

阶段完成条件：JNI 单元测试覆盖正常、异常、重复关闭、提前关闭、取消和错误 handle，ASan 下无 use-after-free。

### 5.6 阶段 5：JVM signal handler 安全

- [ ] 明确记录 `chdb_set_signal_handlers_enabled(0)` 当前会将部分已有 signal handler 重置为 `SIG_DFL` 的副作用。
- [ ] 优先推动 chDB Core 增加“只禁止未来安装，不重置宿主 handler”的 API。
- [ ] 在上游 API 不可用时，在 JNI shim 中实现临时保护：
  - 保存 chDB 涉及信号的当前 `sigaction`；
  - 调用 `chdb_set_signal_handlers_enabled(0)`；
  - 恢复 JVM 原来的 `sigaction`；
  - 对首次 connect 的潜在重置路径做同样保护；
  - 使用进程级锁串行执行。
- [ ] 加载前后比较 SIGSEGV、SIGBUS、SIGILL、SIGFPE、SIGABRT 等 handler。
- [ ] connect/query/close 前后再次比较 handler。
- [ ] 在 Linux 和 macOS、x86_64 和 arm64 上执行 signal 回归测试。
- [ ] 测试 Java 自己的 SIGTERM/SIGINT graceful shutdown 不被 chDB 接管。
- [ ] 测试 native crash 时 JVM/OS 仍能生成预期诊断文件。
- [ ] 文档说明关闭 chDB handler 后将失去 ClickHouse 格式的 native crash stack trace。

阶段完成条件：chDB 加载和连接前后的 JVM signal handler 保持不变；不能满足时不得发布 V1。

### 5.7 阶段 6：Connection 和进程级 storage path

- [ ] 定义并实现 JDBC URL：`jdbc:chdb:<path-or-memory>`。
- [ ] 定义 `:memory:` 的共享范围和生命周期。
- [ ] 将文件 storage path 规范化为 absolute/real path。
- [ ] 建立 JVM 进程级 storage path registry。
- [ ] 允许同一路径创建多个独立 chDB Connection。
- [ ] 在仍有连接时拒绝不同路径，并返回包含当前路径、请求路径和解决方法的错误。
- [ ] 最后一个 Connection 关闭后允许绑定新路径。
- [ ] Connection close 时处理仍打开的 Statement/ResultSet。
- [ ] Connection methods 中未支持的事务和高级能力明确抛异常。
- [ ] 实现 `isValid()`、`isClosed()`、client info 和必要 read-only 语义。
- [ ] 对 Connection 并发使用给出确定行为。

阶段完成条件：同路径多连接、不同路径冲突、最后连接释放和 memory 模式均有集成测试。

### 5.8 阶段 7：Statement、PreparedStatement 和取消

- [ ] 实现 `executeQuery()`。
- [ ] 实现 `execute()`。
- [ ] 实现 DDL/DML 所需的 `executeUpdate()` 和 update count；无法可靠计算时必须文档化并返回 JDBC 允许的值。
- [ ] 实现 query timeout，并将其映射到显式 cancel，而不是只在 Java 侧停止等待。
- [ ] 实现 `Statement.cancel()`。
- [ ] 定义 cancel、fetch、close 的锁和状态机。
- [ ] 禁止同一 Connection 同时运行两条 statement，或以明确规则串行。
- [ ] 为 PreparedStatement 编写 SQL parameter lexer。
- [ ] lexer 必须跳过字符串、quoted identifier、转义内容和 SQL 注释中的 `?`。
- [ ] 将 JDBC `?` 转换为 chDB typed parameter。
- [ ] 支持常用 `setBoolean/setInt/setLong/setDouble/setBigDecimal/setString/setBytes/setDate/setTimestamp/setNull/setObject`。
- [ ] 使用 `chdb_query_with_params_n`，禁止参数字符串拼接。
- [ ] 检查未绑定参数、重复执行、NULL 类型和不支持类型。
- [ ] PreparedStatement 参数只对当前执行生效，执行后清理状态。

阶段完成条件：参数注入测试、特殊字符/NUL/Unicode 测试、取消竞态和 timeout 测试全部通过。

### 5.9 阶段 8：流式 ResultSet 和类型映射

- [ ] 实现 forward-only batch cursor。
- [ ] 实现 ResultSet 提前 close 并立即释放 stream/batch。
- [ ] 实现 `wasNull()`。
- [ ] 实现列索引和列名查找，严格遵守 JDBC 1-based column index。
- [ ] 实现 ResultSetMetaData。
- [ ] V1 对以下类型提供稳定映射：
  - Bool -> `boolean/Boolean`
  - Int8/16/32/64 -> 对应 Java integer 类型
  - UInt8/16/32 -> 可容纳的 Java integer 类型
  - UInt64 -> `BigInteger`；超出范围时 `getLong()` 报错
  - Float32/64 -> `float/double`
  - Decimal -> `BigDecimal`
  - String/FixedString -> `String`
  - Binary -> `byte[]`
  - Date/Date32 -> `LocalDate` 和 `java.sql.Date`
  - DateTime/DateTime64 -> 明确定义时区后的 `Instant`/`Timestamp`
  - UUID -> `UUID`/String
  - Nullable -> JDBC NULL 和 `wasNull()`
- [ ] 为时区、Decimal 精度、NaN/Infinity 和无符号溢出建立测试。
- [ ] 复杂类型必须至少能够安全读取或给出明确 unsupported error，不能导致数据错位或 JVM 崩溃。
- [ ] 任何文本 fallback 都必须在文档和 metadata 中保持一致。
- [ ] 避免每一行生成大量临时 Java 对象，进行 allocation profiling。

阶段完成条件：类型矩阵 round-trip、NULL、列索引边界、大 batch 和提前关闭测试通过。

### 5.10 阶段 9：JDBC 生态兼容

- [ ] 添加 `META-INF/services/java.sql.Driver`。
- [ ] 实现 JDBC URL property 解析和 `DriverPropertyInfo`。
- [ ] 实现框架常用的最小 DatabaseMetaData。
- [ ] 确认 shade/fat-JAR 后 service 文件合并方式并写入文档。
- [ ] 增加 Spring `JdbcTemplate` smoke test。
- [ ] 增加 HikariCP smoke test，并明确连接池大小和 Connection 并发限制。
- [ ] 增加 ShardingSphere URL/dialect smoke test。
- [ ] 测试普通 classpath、JPMS module path 和 named/unnamed module 的 native access 提示。
- [ ] 添加 `Automatic-Module-Name`。
- [ ] 测试同一 JVM 中两个 child ClassLoader 的失败诊断。
- [ ] 测试两个 child ClassLoader 通过共同父 ClassLoader 成功共享同一份 chDB runtime。
- [ ] 测试两个完全隔离的 ClassLoader 在第二次加载前 fail-fast，且磁盘和进程中都没有生成第二份 native 副本。
- [ ] 编写 Tomcat/Spark/Flink 中使用共享父 ClassLoader 的部署说明。

阶段完成条件：三种常见框架能完成连接和基础查询，ClassLoader 错误具有可操作的解决信息。

### 5.11 阶段 10：堆外内存和稳定性测试

- [ ] debug build 暴露 connection/result/stream/batch 等句柄计数。
- [ ] 每个单元和集成测试结束时断言句柄回到零。
- [ ] 对 JNI shim 和完整 chDB sanitizer build 运行 ASan、LSan 和 UBSan。
- [ ] 创建独立 JVM 进程的 RSS/PSS 采集工具。
- [ ] 预热后连续执行至少 1,000 次查询，检查 RSS/PSS 不呈线性增长。
- [ ] 使用逻辑上远大于内存的结果验证流式读取，峰值内存不能随完整结果大小增长。
- [ ] 测试慢消费者和 backpressure。
- [ ] 测试读取一行即关闭。
- [ ] 测试长查询取消后 native 内存回到稳定平台。
- [ ] 测试 SQL 错误、类型错误和 JNI exception 路径无泄漏。
- [ ] 测试 Connection 关闭时仍有开放 ResultSet 的清理行为。
- [ ] 测试 Cleaner 泄漏保险，但不将 GC 时机作为正常释放机制。
- [ ] 在容器内组合 `-Xmx`、cgroup memory limit 和 chDB `max_memory_usage`。
- [ ] 高内存查询应返回 Java exception，而不是导致进程被 OOM Kill。
- [ ] 运行一到六小时的并发 soak test。
- [ ] 区分 allocator 缓存与真正泄漏，以“稳定平台和增长斜率”而不是“必须返回初始 RSS”作为判据。
- [ ] 记录 `jcmd VM.native_memory` 只覆盖 JVM 自身、不能覆盖全部 libchdb 内存的限制。

阶段完成条件：句柄计数归零、sanitizer 无错误、RSS/PSS 形成稳定平台、内存上限错误可恢复。

### 5.12 阶段 11：平台和 JDK 测试矩阵

- [ ] Linux x86_64 glibc + Java 11/17/21/25/26。
- [ ] Linux aarch64 glibc + Java 11/17/21/25/26。
- [ ] macOS x86_64 + Java 11/17/21/25/26。
- [ ] macOS arm64 + Java 11/17/21/25/26。
- [ ] Temurin/HotSpot 作为主 JVM。
- [ ] 至少一个 OpenJ9/Semeru smoke test。
- [ ] 测试路径包含空格、Unicode 和超长文件名。
- [ ] 测试只读目录、无执行权限临时目录和磁盘空间不足。
- [ ] 测试动态库损坏、checksum 不匹配、架构不匹配和缺少依赖。
- [ ] 测试从 IDE、Maven Surefire、Gradle Test 和普通 `java -jar` 加载。
- [ ] 新 JDK CI 使用严格 native-access 模式，提前发现未来默认拒绝行为。

阶段完成条件：所有支持平台和正式支持 JDK 通过；Java 26 的失败必须分类并在发布前决定阻断或记录。

### 5.13 阶段 12：ADBC 实验支线

- [ ] 使用 Apache Arrow `adbc-driver-jni` 加载平台 `libchdb`。
- [ ] 显式设置 entrypoint 为 `chdb_adbc_init`。
- [ ] 验证 query、Arrow batch、metadata、parameter bind、cancel 和 close。
- [ ] 验证 Java ADBC 的 BufferAllocator 最终归零。
- [ ] 处理 ADBC 路径中的 JVM signal handler 问题。
- [ ] 判断是否可以在后续版本用 ADBC 复用或替换 JDBC 数据通路。
- [ ] 如发布 `chdb-adbc`，必须标为 experimental，并与 JDBC GA 的稳定承诺分开。

阶段完成条件：形成一份书面结论，说明 ADBC 哪些能力可用、哪些缺失以及是否适合作为 V2 基础。此阶段不阻塞 JDBC V1。

### 5.14 阶段 13：文档和示例

- [ ] README：项目定位、支持矩阵和 Maven 安装。
- [ ] 每个平台的 dependency 示例。
- [ ] 外部 `libchdb` 路径覆盖示例。
- [ ] JDBC URL 和 session/path 语义。
- [ ] Statement、PreparedStatement、ResultSet 示例。
- [ ] unsupported JDBC 能力列表。
- [ ] 类型映射表。
- [ ] signal handler 行为和诊断差异。
- [ ] 堆外内存和 `-Xmx` 不覆盖 chDB 内存的说明。
- [ ] ClassLoader 的通俗说明和 Tomcat/Spark/Flink 部署方式。
- [ ] native-access JVM 参数说明。
- [ ] 平台不支持错误的解决路径。
- [ ] 安全报告和崩溃报告模板，要求附上 JVM、OS、arch、libc、engine、binding 版本和实际加载路径。

阶段完成条件：一个不了解实现细节的 Java 用户可以只根据 README 安装、连接、执行参数化查询并正确关闭资源。

### 5.15 阶段 14：发布准备

- [ ] 发布 Maven snapshot。
- [ ] 让至少两个外部 Java 项目验证 snapshot。
- [ ] 验证 POM 传递依赖和 BOM。
- [ ] 验证 sources、javadoc、签名、许可证和 SBOM。
- [ ] 验证 Maven Central 下载后 checksum 与 CI 产物一致。
- [ ] 验证四个平台的实际下载和首次加载。
- [ ] 冻结公共 Java API 和 JNI ABI。
- [ ] 编写 release notes 和已知限制。
- [ ] 发布 RC，完成至少一周 soak/外部验证窗口。
- [ ] 所有 V1 release gate 通过后发布与稳定 engine 对齐的首个正式版本，例如 `26.7.0.1`。

## 6. 里程碑

| 里程碑 | 可演示结果 | 退出条件 |
|---|---|---|
| M0：范围冻结 | 评审通过的 V1 API、平台和 ABI 决策 | 阶段 0 完成 |
| M1：Native smoke | 四个平台运行 `SELECT 1` | 阶段 1–5 核心项完成 |
| M2：JDBC Alpha | Driver/Connection/Statement/流式 ResultSet 可用 | 基础类型、close、错误路径通过 |
| M3：JDBC Beta | PreparedStatement、cancel、metadata、框架 smoke 可用 | 阶段 6–9 完成 |
| M4：Release Candidate | Maven 平台包、完整文档和稳定性数据 | 阶段 10–14 的 RC 条件完成 |
| M5：V1 GA | 可公开支持的四平台 JDBC binding | 所有 release gate 通过 |

## 7. V1 Release Gate

以下任何一项未满足都不得发布正式 V1：

- [ ] 四个支持平台均可从 Maven artifact 完成首次加载和 `SELECT 1`。
- [ ] Java 11、17、21、25 全部通过必需测试。
- [ ] chDB/JNI 版本和符号不匹配能够在第一次使用前失败。
- [ ] signal handler 在 load/connect/query/close 前后保持宿主 JVM 状态。
- [ ] ASan、LSan、UBSan 没有未处理错误。
- [ ] 所有正常和异常测试结束后 native handle 计数为零。
- [ ] 大结果流式读取内存有界，不随完整结果大小增长。
- [ ] 1,000 次查询及长时间 soak 后 RSS/PSS 不呈线性增长。
- [ ] cancel、timeout、提前 close 和 Connection 级联关闭无泄漏、无死锁。
- [ ] PreparedStatement 不通过 SQL 拼接绑定参数。
- [ ] UTF-8、NUL、引号、注释和恶意参数测试通过。
- [ ] 所有未支持 JDBC 方法明确抛出 `SQLFeatureNotSupportedException`。
- [ ] storage path 进程级限制行为正确且错误信息清晰。
- [ ] 第二个 ClassLoader 加载失败时给出可操作诊断，不导致 JVM 崩溃。
- [ ] 两个 child ClassLoader 通过共同父层共享时，进程中只有一个 native owner、一份 JNI shim 和一份 `libchdb` 映射。
- [ ] 两个隔离 ClassLoader 的测试不会通过随机文件名或第二个解压目录复制加载 native library。
- [ ] Maven artifact 不在运行时访问网络。
- [ ] Maven Central 大文件、许可证、签名和 SBOM 要求已经验证。
- [ ] README 与实际支持范围一致，没有宣称未测试的能力。

## 8. 主要风险和缓解措施

| 风险 | 影响 | 缓解措施 |
|---|---|---|
| chDB C ABI 仍在快速变化 | Java/native 版本错配或崩溃 | 固定 engine、JNI ABI、symbol probe、fail-fast |
| chDB 会修改进程 signal handler | JVM 随机崩溃或诊断失效 | 上游新 API；JNI 保存/恢复；四平台回归测试 |
| native 包过大 | Maven Central 限制、下载和缓存成本 | 每平台独立包；不发布默认 all-in-one；先验证仓库限制 |
| DirectByteBuffer 生命周期错误 | use-after-free 和 JVM 崩溃 | native owner handle；batch 生命周期状态机；ASan |
| chDB 堆外内存不受 `-Xmx` 控制 | OOM Kill、内存不可预测 | streaming、句柄计数、RSS/PSS、cgroup 和 query memory limit |
| 多 ClassLoader | `UnsatisfiedLinkError`、ClassLoader 泄漏或多份巨大引擎 | JVM 只允许一个 native owner；共享父 ClassLoader；owner marker；隔离加载 fail-fast；不随机复制 native |
| 每进程只能使用一个 storage path | 连接池或多租户行为意外 | JVM 级 path registry；明确错误和文档 |
| ClickHouse 类型远多于 JDBC 类型 | 数据丢失或错误映射 | 明确 V1 类型矩阵；复杂类型安全 fallback/unsupported |
| native crash 会终止 JVM | 影响宿主服务 | 文档化边界；V2 再评估 subprocess 隔离模式 |
| ADBC 仍为实验性 | 兼容性和依赖风险 | 不作为 JDBC V1 的发布阻塞项 |

## 9. V1 完成后的候选工作

以下能力进入 V1 之后的独立规划，不自动承诺进入 V1.1：

- Java 8 兼容层。
- Windows 和 Linux musl 平台包。
- `chdb-adbc` 稳定化。
- 直接 Arrow Java API。
- streaming insert 和批量 ingest。
- Arrow scan 和 Java 内存表注册。
- 完整复杂类型映射。
- JDBC batch update。
- GraalVM Native Image。
- Gradle variant 自动选择平台包。
- 可选全平台聚合包。
- subprocess 隔离模式。
- 更完整的 JDBC compliance suite。

## 10. 调研依据

- chDB Java driver 需求：[chdb-io/chdb#243](https://github.com/chdb-io/chdb/issues/243)
- 现有官方原型：[chdb-io/chdb-java](https://github.com/chdb-io/chdb-java)
- 社区 FFM 原型：[linux-china/chdb-java-ffm](https://github.com/linux-china/chdb-java-ffm)
- chDB C API：[programs/local/chdb.h](https://github.com/chdb-io/chdb-core/blob/main/programs/local/chdb.h)
- chDB streaming API：[streaming.rst](https://github.com/chdb-io/chdb-core/blob/main/docs/streaming.rst)
- chDB ADBC：[adbc.rst](https://github.com/chdb-io/chdb-core/blob/main/docs/adbc.rst)
- chDB signal handler 控制：[chdb-core PR #11](https://github.com/chdb-io/chdb-core/pull/11)
- Go signal handler 实际故障：[chdb-go Issue #30](https://github.com/chdb-io/chdb-go/issues/30)
- chDB Node 平台包设计：[chdb-node package.json](https://github.com/chdb-io/chdb-node/blob/main/package.json)
- Apache Arrow ADBC Java JNI：[jni.rst](https://github.com/apache/arrow-adbc/blob/main/docs/source/java/jni.rst)
- DuckDB Java：[duckdb/duckdb-java](https://github.com/duckdb/duckdb-java)
- SQLite JDBC：[xerial/sqlite-jdbc](https://github.com/xerial/sqlite-jdbc)
- JNI ClassLoader 限制：[JNI Invocation API](https://docs.oracle.com/en/java/javase/26/docs/specs/jni/invocation.html)
- JVM signal handling：[Oracle Handle Signals and Exceptions](https://docs.oracle.com/en/java/javase/17/troubleshoot/handle-signals-and-exceptions.html)
- Native Memory Tracking 限制：[Oracle NMT](https://docs.oracle.com/en/java/javase/13/vm/native-memory-tracking.html)
