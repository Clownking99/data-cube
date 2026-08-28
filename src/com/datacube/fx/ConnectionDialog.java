package com.datacube.fx;

import com.datacube.config.CredentialCipher;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.ConnectionEnvironment;
import com.datacube.spi.model.ConnectionSafetyOptions;
import com.datacube.spi.model.DbType;

import javafx.geometry.Insets;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 连接编辑对话框：新建 / 编辑连接表单。
 *
 * <p>密码经 {@link CredentialCipher} 加密后存入 {@link ConnConfig#encryptedPassword()}；
 * 编辑时密码留空表示沿用原密文。内置"测试连接"按钮，经 {@link ConnectionManager} 校验。
 */
public final class ConnectionDialog {

    private ConnectionDialog() {}

    /**
     * 弹出对话框。
     *
     * @param existing 编辑时传入既有配置；新建传 {@code null}
     * @return 用户确认返回新的 {@link ConnConfig}，取消返回空
     */
    public static Optional<ConnConfig> show(ConnConfig existing, CredentialCipher cipher,
                                            ConnectionManager connMgr, FxTaskRunner runner) {
        FxTaskScope scope = runner.scope();
        try (ConnectionTestController tester = new ConnectionTestController(scope, connMgr::test)) {
            return create(existing, cipher, tester).showAndWait();
        } finally {
            scope.close();
        }
    }

    static Dialog<ConnConfig> create(ConnConfig existing, CredentialCipher cipher,
                                     ConnectionTestController tester) {
        Dialog<ConnConfig> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? "新建连接" : "编辑连接");
        dialog.setHeaderText(null);

        ButtonType saveType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        ButtonType testType = new ButtonType("测试连接", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(testType, saveType, ButtonType.CANCEL);

        ComboBox<DbType> typeBox = new ComboBox<>();
        typeBox.getItems().addAll(DbType.values());
        typeBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(DbType t) { return t == null ? "" : t.displayName(); }
            @Override public DbType fromString(String s) { return null; }
        });
        typeBox.setMaxWidth(Double.MAX_VALUE);

        TextField nameField = new TextField();
        TextField hostField = new TextField("127.0.0.1");
        TextField portField = new TextField();
        TextField dbField = new TextField();
        TextField userField = new TextField();
        PasswordField passField = new PasswordField();
        ConnectionSafetyOptions safety = ConnectionSafetyOptions.from(existing);
        ComboBox<ConnectionEnvironment> environmentBox = new ComboBox<>();
        environmentBox.getItems().addAll(ConnectionEnvironment.values());
        environmentBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ConnectionEnvironment value) {
                return value == null ? "" : value.label();
            }

            @Override public ConnectionEnvironment fromString(String value) {
                return ConnectionEnvironment.parse(value);
            }
        });
        environmentBox.setValue(safety.environment());
        environmentBox.setMaxWidth(Double.MAX_VALUE);
        CheckBox readOnlyCheck = new CheckBox("只读连接");
        readOnlyCheck.setSelected(safety.readOnly());
        TextField timeoutField = new TextField(Integer.toString(safety.queryTimeoutSeconds()));
        Label dbLabel = new Label("数据库:");
        Label userLabel = new Label("用户名:");
        Label passLabel = new Label("密码:");
        Label environmentLabel = new Label("环境:");
        Label readOnlyLabel = new Label("访问:");
        Label timeoutLabel = new Label("查询超时(秒):");

        var relational = typeBox.valueProperty().isNotEqualTo(DbType.REDIS);
        for (Control control : new Control[]{environmentBox, readOnlyCheck, timeoutField}) {
            control.visibleProperty().bind(relational);
            control.managedProperty().bind(relational);
        }
        for (Label label : new Label[]{environmentLabel, readOnlyLabel, timeoutLabel}) {
            label.visibleProperty().bind(relational);
            label.managedProperty().bind(relational);
        }

        // 类型切换：联动默认端口、“数据库/服务名”标签与提示、标题
        typeBox.valueProperty().addListener((obs, old, nv) -> {
            if (nv == null) return;
            dialog.setHeaderText(nv.displayName() + " 连接");
            portField.setText(String.valueOf(nv.defaultPort()));
            userField.setPromptText("");
            passField.setPromptText(existing == null ? "" : "（留空沿用原密码）");
            if (nv == DbType.REDIS) {
                dbLabel.setText("DB 索引:");
                dbField.setText("0");
                dbField.setPromptText("0-15");
                userField.setText("");
                userField.setPromptText("可选，Redis ACL 用户名");
                passField.setPromptText(existing == null ? "可选" : "可选；留空沿用原密码");
            } else if (nv == DbType.ORACLE) {
                dbLabel.setText("服务名:");
                dbField.setText("");
                dbField.setPromptText("Service Name");
                userField.setText("");
            } else {
                dbLabel.setText("数据库:");
                dbField.setText("postgres");
                dbField.setPromptText("");
                userField.setText("postgres");
            }
        });

        typeBox.setValue(existing != null ? existing.type() : DbType.POSTGRESQL);

        if (existing != null) {
            nameField.setText(existing.name());
            hostField.setText(existing.host());
            portField.setText(String.valueOf(existing.port()));
            dbField.setText(existing.database());
            userField.setText(existing.username());
            passField.setPromptText("（留空沿用原密码）");
        }

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(15));
        grid.addRow(0, new Label("类型:"), typeBox);
        grid.addRow(1, new Label("名称:"), nameField);
        grid.addRow(2, new Label("主机:"), hostField);
        grid.addRow(3, new Label("端口:"), portField);
        grid.addRow(4, dbLabel, dbField);
        grid.addRow(5, userLabel, userField);
        grid.addRow(6, passLabel, passField);
        grid.addRow(7, environmentLabel, environmentBox);
        grid.addRow(8, readOnlyLabel, readOnlyCheck);
        grid.addRow(9, timeoutLabel, timeoutField);
        typeBox.setId("connection-type");
        nameField.setId("connection-name");
        hostField.setId("connection-host");
        portField.setId("connection-port");
        dbField.setId("connection-database");
        userField.setId("connection-user");
        passField.setId("connection-password");
        environmentBox.setId("connection-environment");
        readOnlyCheck.setId("connection-read-only");
        timeoutField.setId("connection-timeout");
        linkFormLabels(grid);

        Button testBtn = (Button) dialog.getDialogPane().lookupButton(testType);
        Button saveBtn = (Button) dialog.getDialogPane().lookupButton(saveType);
        testBtn.setId("connection-test");
        saveBtn.setId("connection-save");
        Label testStatus = new Label();
        testStatus.setId("connection-test-status");
        testStatus.setWrapText(true);
        testStatus.setMaxWidth(360);
        testStatus.setMinHeight(Region.USE_PREF_SIZE);
        testStatus.textProperty().bind(Bindings.createStringBinding(
                () -> tester.phase().text(), tester.phaseProperty()));
        var testing = tester.phaseProperty().isEqualTo(ConnectionTestController.Phase.TESTING);
        grid.disableProperty().bind(testing);
        testBtn.disableProperty().bind(testing);
        saveBtn.disableProperty().bind(testing);
        ProgressIndicator progress = new ProgressIndicator();
        progress.setId("connection-test-progress");
        progress.setMaxSize(18, 18);
        progress.visibleProperty().bind(testing);
        progress.managedProperty().bind(testing);
        // Reserve the wrapped failure message's space before the window is shown.
        // A later result must not push the dialog's action buttons out of view.
        Label failureSpace = new Label(ConnectionTestController.Phase.FAILED.text());
        failureSpace.setWrapText(true);
        failureSpace.setVisible(false);
        var statusArea = new javafx.scene.layout.StackPane(failureSpace, testStatus);
        statusArea.setMaxWidth(360);
        statusArea.setMinHeight(Region.USE_PREF_SIZE);
        javafx.scene.layout.StackPane.setAlignment(testStatus, javafx.geometry.Pos.CENTER_LEFT);
        HBox feedback = new HBox(8, progress, statusArea);
        feedback.setPadding(new Insets(0, 15, 12, 15));
        dialog.getDialogPane().setContent(new VBox(grid, feedback));

        for (Observable value : List.<Observable>of(typeBox.valueProperty(), nameField.textProperty(),
                hostField.textProperty(), portField.textProperty(), dbField.textProperty(),
                userField.textProperty(), passField.textProperty(), environmentBox.valueProperty(),
                readOnlyCheck.selectedProperty(), timeoutField.textProperty())) {
            value.addListener(ignored -> tester.edited());
        }
        testBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            if (tester.phase() == ConnectionTestController.Phase.TESTING) return;
            ConnConfig snapshot = build(existing, cipher, typeBox.getValue(), nameField, hostField, portField,
                    dbField, userField, passField, environmentBox, readOnlyCheck, timeoutField);
            if (snapshot != null) tester.start(snapshot);
        });

        dialog.setOnHidden(event -> tester.close());

        // Returning null from a result converter still closes a Dialog. Validate before closing.
        saveBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            if (tester.phase() == ConnectionTestController.Phase.TESTING) return;
            ConnConfig result = build(existing, cipher, typeBox.getValue(), nameField, hostField, portField,
                    dbField, userField, passField, environmentBox, readOnlyCheck, timeoutField);
            if (result != null) {
                dialog.setResult(result);
                dialog.close();
            }
        });
        dialog.setResultConverter(button -> null); // Cancel and window close never produce a configuration.

        return dialog;
    }

    private static void linkFormLabels(GridPane grid) {
        for (Node node : grid.getChildren()) {
            if (!(node instanceof Label label)) continue;
            for (Node candidate : grid.getChildren()) {
                if (!(candidate instanceof Control control) || candidate == node) continue;
                if (Objects.equals(GridPane.getRowIndex(node), GridPane.getRowIndex(candidate))
                        && Integer.valueOf(1).equals(GridPane.getColumnIndex(candidate))) {
                    label.setLabelFor(control);
                    control.accessibleTextProperty().bind(label.textProperty());
                }
            }
        }
    }

    private static ConnConfig build(
            ConnConfig existing,
            CredentialCipher cipher,
            DbType type,
            TextField nameField,
            TextField hostField,
            TextField portField,
            TextField dbField,
            TextField userField,
            PasswordField passField,
            ComboBox<ConnectionEnvironment> environmentBox,
            CheckBox readOnlyCheck,
            TextField timeoutField) {
        String name = nameField.getText().trim();
        String host = hostField.getText().trim();
        String db = dbField.getText().trim();
        String user = userField.getText().trim();
        if (type == DbType.REDIS && db.isEmpty()) db = "0";
        if (name.isEmpty() || host.isEmpty()
                || (type != DbType.REDIS && (db.isEmpty() || user.isEmpty()))) {
            warn(type == DbType.REDIS ? "名称和主机不能为空" : "名称/主机/数据库/用户名均不能为空",
                    name.isEmpty() ? nameField : host.isEmpty() ? hostField : db.isEmpty() ? dbField : userField);
            return null;
        }
        if (type == DbType.REDIS) {
            try {
                int index = Integer.parseInt(db);
                if (index < 0 || index > 15) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                warn("Redis DB 索引必须是 0-15 的整数", dbField);
                return null;
            }
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            warn("端口必须为数字", portField);
            return null;
        }

        int timeoutSeconds = ConnectionSafetyOptions.DEFAULT_QUERY_TIMEOUT_SECONDS;
        if (type != DbType.REDIS) {
            try {
                timeoutSeconds = Integer.parseInt(timeoutField.getText().trim());
                if (timeoutSeconds < 0
                        || timeoutSeconds > ConnectionSafetyOptions.MAX_QUERY_TIMEOUT_SECONDS) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                warn("查询超时必须是 0-3600 的整数秒", timeoutField);
                return null;
            }
        }

        String enc;
        String plain = passField.getText();
        if (existing != null && plain.isEmpty()) {
            enc = existing.encryptedPassword();   // 沿用原密文
        } else {
            enc = cipher.encrypt(plain);
        }

        String id = existing != null ? existing.id() : UUID.randomUUID().toString();
        Map<String, String> props = type == DbType.REDIS ? Map.of() : new ConnectionSafetyOptions(
                environmentBox.getValue(),
                readOnlyCheck.isSelected(),
                timeoutSeconds).toPersistentProps();
        return new ConnConfig(id, name, type, host, port, db, user, enc, props);
    }

    private static void warn(String msg, Control field) {
        Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
        field.requestFocus();
    }
}
