package com.datacube.fx;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 内容标签容器：承载 SQL 编辑器 / 数据浏览 / DDL 查看 / 迁移等功能面板。
 *
 * <p>本类只负责标签管理，具体面板由 {@link AppShell} 依据用户操作构建后注入，
 * 保持 UI 组件间低耦合。
 */
public final class ContentTabPane {

    private final TabPane tabPane = new TabPane();
    private final AsyncManagedTabRegistry<Tab> guardedTabs = new AsyncManagedTabRegistry<>();

    public ContentTabPane() {
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
        tabPane.getTabs().addListener((ListChangeListener<Tab>) change -> {
            while (change.next()) {
                int restoreIndex = change.getFrom();
                for (Tab removed : change.getRemoved()) {
                    int index = restoreIndex++;
                    CompletionStage<TabCloseOutcome> close = guardedTabs.requestClose(removed);
                    AsyncTabRemovalRecovery.restoreOnIncomplete(
                            close,
                            ContentTabPane::dispatchFx,
                            disabled -> restoreRemovedTab(removed, index, disabled),
                            ContentTabPane::reportCloseFailure);
                }
            }
        });
    }

    public Node getNode() {
        return tabPane;
    }

    /** 添加不可关闭的常驻标签（如迁移功能）。 */
    public void addPermanentTab(String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        tabPane.getTabs().add(tab);
    }

    /** 打开一个可关闭标签并选中它，返回该标签（便于调用方挂 onClosed 等）。 */
    public Tab openTab(String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(true);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        return tab;
    }

    /**
     * 旧式单一 disposer API。disposer 现在在一次性虚拟线程 guard 中运行，调用方应迁移到
     * 明确拆分 blocking cleanup 与 FX finalizer 的四参数重载。
     */
    @Deprecated(forRemoval = false)
    public Tab openManagedTab(String title, Node content, Runnable disposer) {
        return openManagedTab(title, content, AsyncTabCloseGuards.blocking(disposer), () -> {});
    }

    /**
     * 打开带异步关闭守卫的受管标签。
     *
     * <p>{@code guard} 承担虚拟线程上的阻塞清理；{@code uiFinalizer} 只在 FX 线程调用，
     * 必须轻量、非阻塞。清理批准前标签不会由正常关闭请求移除。
     */
    public Tab openManagedTab(
            String title,
            Node content,
            AsyncTabCloseGuard guard,
            Runnable uiFinalizer) {
        Tab tab = new Tab(title, content);
        tab.setClosable(true);
        AsyncTabCloseCoordinator coordinator = new AsyncTabCloseCoordinator(
                guard,
                AsyncTabCloseCoordinator.DEFAULT_TIMEOUT,
                AsyncTabCloseCoordinator::scheduleTimeout,
                ContentTabPane::dispatchFx,
                () -> tab.setDisable(true),
                () -> tab.setDisable(false),
                () -> tabPane.getTabs().remove(tab),
                () -> {
                    guardedTabs.unregister(tab);
                    uiFinalizer.run();
                },
                ContentTabPane::reportCloseFailure);
        if (!guardedTabs.register(tab, coordinator)) {
            reportCloseFailure(new IllegalStateException("managed tab rejected while registry is closing"));
            coordinator.requestClose().whenComplete((outcome, failure) -> {
                if (failure != null) reportCloseFailure(failure);
                else if (outcome != TabCloseOutcome.COMPLETED) {
                    reportCloseFailure(new IllegalStateException(
                            "rejected managed tab cleanup ended as " + outcome));
                }
            });
            return null;
        }
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        tab.setOnCloseRequest(event -> {
            event.consume();
            coordinator.requestClose();
        });
        tab.setOnClosed(event -> coordinator.requestClose());
        return tab;
    }

    /**
     * 异步关闭所有受管标签；受守卫标签先完成阻塞清理，再在 FX 线程执行 UI finalizer。
     * 返回的 stage 在所有守卫到达批准、拒绝、失败或超时终态后完成。
     */
    public CompletionStage<TabCloseOutcome> closeAllManagedTabs() {
        if (!Platform.isFxApplicationThread()) {
            CompletableFuture<TabCloseOutcome> dispatched = new CompletableFuture<>();
            try {
                Platform.runLater(() -> closeAllManagedTabs().whenComplete((outcome, failure) -> {
                    if (failure == null) dispatched.complete(outcome);
                    else dispatched.completeExceptionally(failure);
                }));
            } catch (Throwable failure) {
                dispatched.completeExceptionally(failure);
            }
            return dispatched;
        }
        return guardedTabs.closeAll();
    }

    /** @deprecated 使用并等待 {@link #closeAllManagedTabs()} 的显式结果。 */
    @Deprecated(forRemoval = false)
    public void disposeAll() {
        closeAllManagedTabs();
    }

    private void restoreRemovedTab(Tab tab, int requestedIndex, boolean disabled) {
        tab.setDisable(disabled);
        if (tabPane.getTabs().contains(tab)) {
            tabPane.getSelectionModel().select(tab);
            return;
        }
        int index = Math.max(0, Math.min(requestedIndex, tabPane.getTabs().size()));
        tabPane.getTabs().add(index, tab);
        tabPane.getSelectionModel().select(tab);
    }

    private static void dispatchFx(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }

    private static void reportCloseFailure(Throwable failure) {
        System.err.println("[DataCube] tab close failure: " + failure);
        failure.printStackTrace(System.err);
    }

    /**
     * 打开单例标签：若已存在承载同一 {@code content} 的标签则直接选中，
     * 否则新建一个可关闭标签。适用于迁移页等单实例面板（避免重复标签与节点被转移）。
     */
    public void openSingletonTab(String title, Node content) {
        for (Tab t : tabPane.getTabs()) {
            if (t.getContent() == content) {
                tabPane.getSelectionModel().select(t);
                return;
            }
        }
        openTab(title, content);
    }
}
