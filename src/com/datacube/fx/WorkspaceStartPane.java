package com.datacube.fx;

import java.util.Objects;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Empty-workspace guidance; actions are explicit and have no database dependencies. */
final class WorkspaceStartPane extends VBox {
    WorkspaceStartPane(Runnable createConnection, Runnable focusConnections) {
        Objects.requireNonNull(createConnection, "createConnection");
        Objects.requireNonNull(focusConnections, "focusConnections");
        setId("workspace-start");
        setSpacing(12);
        setPadding(new Insets(24));
        setAlignment(Pos.CENTER_LEFT);
        setMaxSize(520, USE_PREF_SIZE);

        Label title = new Label("开始使用 DataCube");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        Label intro = new Label("新建连接，或在左侧选择已保存的连接后开始工作");
        intro.setWrapText(true);
        Button create = new Button("新建连接");
        create.setId("start-new-connection");
        create.setOnAction(event -> createConnection.run());
        Button focus = new Button("选择已有连接");
        focus.setId("start-select-connection");
        focus.setOnAction(event -> focusConnections.run());
        Label hint = new Label("选择 PostgreSQL / Oracle 连接后，点击顶部‘新建 SQL’。\n"
                + "Redis 请使用连接的控制台与键浏览功能。");
        hint.setWrapText(true);
        getChildren().addAll(title, intro, new HBox(8, create, focus), hint);
    }
}
