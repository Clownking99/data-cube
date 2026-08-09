package com.datacube.fx;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 内容标签容器：承载 SQL 编辑器 / 数据浏览 / DDL 查看 / 迁移等功能面板。
 *
 * <p>本类只负责标签管理，具体面板由 {@link AppShell} 依据用户操作构建后注入，
 * 保持 UI 组件间低耦合。
 */
public final class ContentTabPane {

    private final TabPane tabPane = new TabPane();
    private final AsyncManagedTabRegistry<Tab> guardedTabs = new AsyncManagedTabRegistry<>();
    private MandatoryAbortTracker mandatoryAborts = new MandatoryAbortTracker();
    private final Object ownershipLock = new Object();
    private boolean internalTabMutation;
    private final ManagedSelectionTracker<Tab> selectionTracker = new ManagedSelectionTracker<>();

    public ContentTabPane() {
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
        tabPane.getSelectionModel().selectedItemProperty().addListener(
                (ignored, before, selected) -> selectionTracker.changed(before, selected));
        tabPane.getTabs().addListener((ListChangeListener<Tab>) change -> {
            if (internalTabMutation) return;
            while (change.next()) {
                var managed = change.getRemoved().stream()
                        .filter(guardedTabs::isManaged)
                        .filter(tab -> !guardedTabs.isRemovalAuthorized(tab))
                        .toList();
                if (managed.isEmpty()) continue;
                Tab originalSelection = selectionTracker.originalSelection(
                        managed, tabPane.getSelectionModel().getSelectedItem());
                ManagedTabRemovalBatch<Tab> batch = ManagedTabRemovalBatch.capture(
                        change.getFrom(), managed, originalSelection);
                guardedTabs.requestExternalClose(managed, () -> {
                    internalTabMutation = true;
                    try {
                        batch.restoreInto(tabPane.getTabs(), tab -> tab.setDisable(true),
                                tab -> tabPane.getSelectionModel().select(tab));
                    } finally {
                        internalTabMutation = false;
                    }
                });
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
     * reservation factory，并明确拆分 blocking cleanup 与 FX finalizer。
     */
    @Deprecated(forRemoval = false)
    public Tab openManagedTab(String title, Node content, Runnable disposer) {
        return openManagedTab(title, content, AsyncTabCloseGuards.blocking(
                        disposer, ContentTabPane::reportCloseFailure),
                () -> {}, disposer);
    }

    /**
     * 打开带异步关闭守卫的受管标签。
     *
     * <p>{@code guard} 承担虚拟线程上的阻塞清理；{@code uiFinalizer} 只在 FX 线程调用，
     * 必须轻量、非阻塞。清理批准前标签不会由正常关闭请求移除。
     * @deprecated 资源已在 reservation 之前构造；新代码使用 factory 重载并提供强制 abort。
     */
    @Deprecated(forRemoval = false)
    public Tab openManagedTab(
            String title,
            Node content,
            AsyncTabCloseGuard guard,
            Runnable uiFinalizer) {
        return openManagedTab(title, content, guard, uiFinalizer, () -> {
            throw new PartialCloseException(new IllegalStateException(
                    "managed content was constructed without abort cleanup"));
        });
    }

    /**
     * Constructs managed content only after acquiring registry ownership. Factory code must use
     * try/finally internally for partially constructed resources and provide mandatory abort cleanup.
     */
    public Tab openManagedTab(String title, Supplier<ManagedTabSpec> factory) {
        return openManagedTab(title, binding -> {
            ManagedTabSpec spec = factory.get();
            binding.bind(spec.mandatoryAbortCleanup());
            return spec;
        });
    }

    /** Opens only after both registry and abort-accounting leases are acquired. */
    public Tab openManagedTab(String title, ManagedTabFactory factory) {
        AsyncManagedTabRegistry<Tab>.Reservation reservation;
        ManagedOpenLease lease;
        synchronized (ownershipLock) {
            reservation = guardedTabs.reserve();
            lease = ManagedOpenLease.acquire(
                    reservation.acquired(), reservation, mandatoryAborts);
        }
        if (!lease.acquired()) return null;
        AbortBinding binding = new AbortBinding();
        try {
            ManagedTabSpec spec = factory.create(binding);
            if (!binding.isBound()) binding.bind(spec.mandatoryAbortCleanup());
            Tab tab = openReservedManagedTab(title, spec, reservation);
            if (tab == null) {
                lease.abort(binding.guard());
                return null;
            }
            lease.installed();
            return tab;
        } catch (Throwable failure) {
            lease.abort(binding.guard());
            reportCloseFailure(failure);
            return null;
        }
    }

    private Tab openManagedTab(
            String title,
            Node content,
            AsyncTabCloseGuard guard,
            Runnable uiFinalizer,
            Runnable mandatoryAbortCleanup) {
        AsyncManagedTabRegistry<Tab>.Reservation reservation;
        ManagedOpenLease lease;
        synchronized (ownershipLock) {
            reservation = guardedTabs.reserve();
            lease = ManagedOpenLease.acquire(
                    reservation.acquired(), reservation, mandatoryAborts);
        }
        if (!lease.acquired()) {
            AsyncTabCloseGuards.mandatoryAbort(
                    mandatoryAbortCleanup, ContentTabPane::reportCloseFailure).requestClose();
            reportCloseFailure(new IllegalStateException("managed tab rejected while registry is closing"));
            return null;
        }
        try {
            Tab tab = openReservedManagedTab(title,
                    new ManagedTabSpec(content, guard, uiFinalizer, mandatoryAbortCleanup), reservation);
            if (tab == null) lease.abort(AsyncTabCloseGuards.mandatoryAbort(
                    mandatoryAbortCleanup, ContentTabPane::reportCloseFailure));
            else lease.installed();
            return tab;
        } catch (Throwable failure) {
            lease.abort(AsyncTabCloseGuards.mandatoryAbort(
                    mandatoryAbortCleanup, ContentTabPane::reportCloseFailure));
            reportCloseFailure(failure);
            return null;
        }
    }

    private Tab openReservedManagedTab(
            String title,
            ManagedTabSpec spec,
            AsyncManagedTabRegistry<Tab>.Reservation reservation) {
        Node content = spec.content();
        AsyncTabCloseGuard guard = spec.guard();
        Runnable uiFinalizer = spec.uiFinalizer();
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
                () -> guardedTabs.unregister(tab),
                uiFinalizer,
                ContentTabPane::reportCloseFailure);
        if (!reservation.register(tab, coordinator)) {
            reportCloseFailure(new IllegalStateException("managed tab reservation was released before register"));
            return null;
        }
        try {
            tabPane.getTabs().add(tab);
            tabPane.getSelectionModel().select(tab);
            tab.setOnCloseRequest(event -> {
                event.consume();
                coordinator.requestClose();
            });
            tab.setOnClosed(event -> coordinator.requestClose());
            return tab;
        } catch (Throwable failure) {
            guardedTabs.unregister(tab);
            throw failure;
        }
    }

    /**
     * 异步关闭所有受管标签；受守卫标签先完成阻塞清理，再在 FX 线程执行 UI finalizer。
     * 返回的 stage 只在所有守卫最终结算后完成；超时仅标记仍在关闭，不释放所有权。
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
        MandatoryAbortTracker tracker;
        CompletionStage<TabCloseOutcome> tabs;
        CompletionStage<TabCloseOutcome> aborts;
        synchronized (ownershipLock) {
            tracker = mandatoryAborts;
            tabs = guardedTabs.closeAll();
            aborts = tracker.seal();
        }
        return tabs.thenCombine(aborts, ContentTabPane::aggregate).thenApply(outcome -> {
            if (outcome == TabCloseOutcome.CANCELLED) {
                synchronized (ownershipLock) {
                    if (mandatoryAborts == tracker) mandatoryAborts = new MandatoryAbortTracker();
                }
            }
            return outcome;
        });
    }

    /** @deprecated 使用并等待 {@link #closeAllManagedTabs()} 的显式结果。 */
    @Deprecated(forRemoval = false)
    public void disposeAll() {
        closeAllManagedTabs();
    }

    private static TabCloseOutcome aggregate(TabCloseOutcome left, TabCloseOutcome right) {
        if (left == TabCloseOutcome.FAILED_PARTIAL || right == TabCloseOutcome.FAILED_PARTIAL) {
            return TabCloseOutcome.FAILED_PARTIAL;
        }
        if (left == TabCloseOutcome.CANCELLED || right == TabCloseOutcome.CANCELLED) {
            return TabCloseOutcome.CANCELLED;
        }
        return TabCloseOutcome.COMPLETED;
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

    /** Immutable ownership bundle created only after a registry reservation is acquired. */
    public record ManagedTabSpec(
            Node content,
            AsyncTabCloseGuard guard,
            Runnable uiFinalizer,
            Runnable mandatoryAbortCleanup) {
        public ManagedTabSpec {
            if (content == null || guard == null || uiFinalizer == null
                    || mandatoryAbortCleanup == null) {
                throw new NullPointerException("managed tab spec fields");
            }
        }
    }

    @FunctionalInterface
    public interface ManagedTabFactory {
        ManagedTabSpec create(AbortBinding abortBinding);
    }

    public static final class AbortBinding {
        private Runnable cleanup;

        public void bind(Runnable mandatoryAbortCleanup) {
            if (cleanup != null) throw new IllegalStateException("mandatory abort already bound");
            cleanup = java.util.Objects.requireNonNull(mandatoryAbortCleanup, "mandatoryAbortCleanup");
        }

        boolean isBound() { return cleanup != null; }

        AsyncTabCloseGuard guard() {
            if (cleanup == null) {
                return () -> CompletableFuture.completedFuture(CloseGuardOutcome.FAILED_PARTIAL);
            }
            return AsyncTabCloseGuards.mandatoryAbort(
                    cleanup, ContentTabPane::reportCloseFailure);
        }
    }
}
