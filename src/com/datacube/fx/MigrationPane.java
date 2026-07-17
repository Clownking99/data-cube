package com.datacube.fx;

import javafx.scene.Node;

/**
 * 迁移功能面板：包裹既有 {@link MainController} 的迁移 UI，作为一个内容标签嵌入 AppShell。
 *
 * <p>承接原 {@code isRunning()} / {@code shutdown()} 语义，供窗口关闭时聚合检查。
 * 迁移逻辑（Oracle→PG）保持原样，位于 {@code com.datacube.migration} 包。
 */
public final class MigrationPane {

    private final MainController controller = new MainController();
    private final Node content;

    public MigrationPane() {
        this.content = controller.createMigrationContent();
    }

    public Node getNode() {
        return content;
    }

    /** 是否有迁移任务在运行。 */
    public boolean isRunning() {
        return controller.isRunning();
    }

    /** 释放迁移相关资源（连接、日志）。 */
    public void shutdown() {
        controller.shutdown();
    }
}
