package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.util.List;
import java.util.Optional;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.stage.Window;

final class SqlDraftConnectionChooser {
    private SqlDraftConnectionChooser() { }

    record Choice(ConnConfig config) {
        @Override public String toString() {
            return SqlDraftManagerPane.preview(config.name(), 80) + " · " + config.type()
                    + " · " + SqlDraftManagerPane.preview(config.id(), 80);
        }
    }

    static List<Choice> choices(List<ConnConfig> configs) {
        return configs.stream().filter(config -> config != null && config.id() != null
                && !config.id().isBlank()
                && (config.type() == DbType.POSTGRESQL || config.type() == DbType.ORACLE))
                .map(Choice::new).toList();
    }

    static Optional<ConnConfig> show(List<ConnConfig> configs, Window owner) {
        return show(configs, owner, "选择草稿连接");
    }

    static Optional<ConnConfig> show(List<ConnConfig> configs, Window owner, String title) {
        List<Choice> available = choices(configs);
        ChoiceDialog<Choice> dialog = new ChoiceDialog<>(null, available);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(available.isEmpty() ? "没有可用连接，请先新建 PostgreSQL 或 Oracle 连接。"
                : "仅选择目标，不连接或执行 SQL；首次执行或会话操作将锁定连接。");
        dialog.setContentText("PostgreSQL / Oracle：");
        dialog.setSelectedItem(null);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty()
                .bind(dialog.selectedItemProperty().isNull());
        return dialog.showAndWait().map(Choice::config);
    }
}
