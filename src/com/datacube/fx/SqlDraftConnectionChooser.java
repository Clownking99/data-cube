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
        ChoiceDialog<Choice> dialog = new ChoiceDialog<>(null, choices(configs));
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("选择草稿连接");
        dialog.setHeaderText("仅选择连接意图；执行时才连接数据库");
        dialog.setContentText("PostgreSQL / Oracle：");
        dialog.setSelectedItem(null);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty()
                .bind(dialog.selectedItemProperty().isNull());
        return dialog.showAndWait().map(Choice::config);
    }
}
