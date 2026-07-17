package com.datacube.fx;

import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * 内容标签容器：承载 SQL 编辑器 / 数据浏览 / DDL 查看 / 迁移等功能面板。
 *
 * <p>本类只负责标签管理，具体面板由 {@link AppShell} 依据用户操作构建后注入，
 * 保持 UI 组件间低耦合。
 */
public final class ContentTabPane {

    private final TabPane tabPane = new TabPane();

    public ContentTabPane() {
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
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

    /** 打开一个可关闭标签并选中它。 */
    public void openTab(String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(true);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
    }
}
