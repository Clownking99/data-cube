/**
 * DataCube 模块声明。
 *
 * <p>用于 Gradle + jlink/jpackage 的<b>模块化运行时</b>打包路径，消除 fat-jar 方式下的
 * {@code Unsupported JavaFX configuration: unnamed module} 警告。
 *
 * <p>注意：{@code build.sh} 的 javac 源清单<b>不含</b>本文件，故 classpath（fat-jar）
 * 构建方式不受影响，两种打包方式并存。
 *
 * <p>JDBC 驱动（Oracle ojdbc17 / PostgreSQL）通过 {@link java.sql.DriverManager} 的
 * ServiceLoader 机制加载，未在源码中直接 import，因此此处不 {@code requires} 其模块；
 * 它们作为自动模块由 jlink 的 {@code mergedModule} 合并处理。
 */
module com.datacube {
    requires javafx.controls;   // GUI（无 FXML/Preferences，声明最小）
    requires java.sql;          // JDBC / DriverManager
    requires java.logging;      // java.util.logging.Logger
    requires java.management;   // ManagementFactory（DataCube CLI 的 native-access 自检）
    requires java.net.http;     // 自动更新：调用 GitHub Releases API 查最新版
    requires org.fxmisc.richtext; // SQL 编辑器语法高亮（CodeArea，自动模块，由 jlink forceMerge 合并）
    requires org.fxmisc.flowless; // CodeArea 滚动容器 VirtualizedScrollPane（自动模块）

    // JavaFX 通过反射实例化 Application 子类（DataCubeFx），需向 javafx.graphics 开放入口包。
    opens com.datacube to javafx.graphics;
}
