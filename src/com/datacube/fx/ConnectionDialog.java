package com.datacube.fx;

import com.datacube.config.CredentialCipher;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

import java.util.Map;
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
                                            ConnectionManager connMgr) {
        Dialog<ConnConfig> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? "新建连接" : "编辑连接");
        dialog.setHeaderText("PostgreSQL 连接");

        ButtonType saveType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        ButtonType testType = new ButtonType("测试连接", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(testType, saveType, ButtonType.CANCEL);

        TextField nameField = new TextField();
        TextField hostField = new TextField("127.0.0.1");
        TextField portField = new TextField(String.valueOf(DbType.POSTGRESQL.defaultPort()));
        TextField dbField = new TextField("postgres");
        TextField userField = new TextField("postgres");
        PasswordField passField = new PasswordField();

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
        grid.addRow(0, new Label("名称:"), nameField);
        grid.addRow(1, new Label("主机:"), hostField);
        grid.addRow(2, new Label("端口:"), portField);
        grid.addRow(3, new Label("数据库:"), dbField);
        grid.addRow(4, new Label("用户名:"), userField);
        grid.addRow(5, new Label("密码:"), passField);
        dialog.getDialogPane().setContent(grid);

        // 测试连接：拦截 OTHER 按钮，不关闭对话框
        final Button testBtn = (Button) dialog.getDialogPane().lookupButton(testType);
        testBtn.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
            evt.consume();
            ConnConfig probe = build(existing, cipher, nameField, hostField, portField,
                    dbField, userField, passField);
            if (probe == null) return;
            String err = connMgr.test(probe);
            Alert alert = new Alert(err == null ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                    err == null ? "连接成功" : "连接失败: " + err, ButtonType.OK);
            alert.setHeaderText(null);
            alert.showAndWait();
        });

        dialog.setResultConverter(bt -> {
            if (bt == saveType) {
                return build(existing, cipher, nameField, hostField, portField,
                        dbField, userField, passField);
            }
            return null;
        });

        return dialog.showAndWait();
    }

    private static ConnConfig build(ConnConfig existing, CredentialCipher cipher,
                                    TextField nameField, TextField hostField, TextField portField,
                                    TextField dbField, TextField userField, PasswordField passField) {
        String name = nameField.getText().trim();
        String host = hostField.getText().trim();
        String db = dbField.getText().trim();
        String user = userField.getText().trim();
        if (name.isEmpty() || host.isEmpty() || db.isEmpty() || user.isEmpty()) {
            warn("名称/主机/数据库/用户名均不能为空");
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            warn("端口必须为数字");
            return null;
        }

        String enc;
        String plain = passField.getText();
        if (existing != null && plain.isEmpty()) {
            enc = existing.encryptedPassword();   // 沿用原密文
        } else {
            enc = cipher.encrypt(plain);
        }

        String id = existing != null ? existing.id() : UUID.randomUUID().toString();
        return new ConnConfig(id, name, DbType.POSTGRESQL, host, port, db, user, enc, Map.of());
    }

    private static void warn(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
