package com.datacube.fx;

import com.datacube.spi.model.ResultColumn;
import com.datacube.sqleditor.result.FilterCondition;
import com.datacube.sqleditor.result.FilterConnector;
import com.datacube.sqleditor.result.FilterOperator;
import com.datacube.sqleditor.result.FilterValueParser;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import javafx.util.StringConverter;

/** Type-aware editor for one flat, left-to-right result filter condition. */
final class FilterConditionDialog {
    private FilterConditionDialog() {
    }

    static Optional<FilterCondition> show(
            Window owner, List<ResultColumn> columns,
            int conditionIndex, FilterConnector initialConnector) {
        Objects.requireNonNull(columns, "columns");
        if (conditionIndex < 0) {
            throw new IllegalArgumentException("conditionIndex must be non-negative");
        }

        Dialog<FilterCondition> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("添加筛选条件");

        ComboBox<ResultColumn> column = new ComboBox<>(
                FXCollections.observableArrayList(columns));
        column.setId("filter-condition-column");
        column.setAccessibleText("筛选列");
        column.setConverter(columnConverter());

        ComboBox<FilterConnector> connector = new ComboBox<>(
                FXCollections.observableArrayList(FilterConnector.values()));
        connector.setId("filter-condition-connector");
        connector.setAccessibleText("条件连接方式");
        connector.setValue(initialConnector == null ? FilterConnector.AND : initialConnector);
        connector.setVisible(conditionIndex > 0);
        connector.setManaged(conditionIndex > 0);

        ComboBox<FilterOperator> operator = new ComboBox<>();
        operator.setId("filter-condition-operator");
        operator.setAccessibleText("筛选运算符");
        operator.setConverter(operatorConverter());

        TextField value = new TextField();
        value.setId("filter-condition-value");
        value.setAccessibleText("筛选值");
        Label error = new Label();
        error.setId("filter-condition-error");
        error.setAccessibleText("");
        error.setWrapText(true);
        error.getStyleClass().add("filter-condition-error");
        error.setStyle("-fx-text-fill: -status-error;");

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        Label connectorLabel = new Label("连接:");
        connectorLabel.setId("filter-condition-connector-label");
        connectorLabel.setVisible(conditionIndex > 0);
        connectorLabel.setManaged(conditionIndex > 0);
        form.addRow(0, connectorLabel, connector);
        form.addRow(1, new Label("列:"), column);
        form.addRow(2, new Label("运算符:"), operator);
        form.addRow(3, new Label("值:"), value);
        form.add(error, 1, 4);

        ButtonType ok = new ButtonType("添加", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(form);
        Node okButton = dialog.getDialogPane().lookupButton(ok);

        Runnable validate = () -> validate(column, operator, value, error, okButton);
        Runnable refreshOperators = () -> {
            ResultColumn selected = column.getValue();
            List<FilterOperator> allowed = selected == null
                    ? List.of() : FilterOperator.allowedFor(selected);
            operator.setItems(FXCollections.observableArrayList(allowed));
            operator.getSelectionModel().selectFirst();
            validate.run();
        };

        value.textProperty().addListener((ignored, oldValue, newValue) -> validate.run());
        operator.valueProperty().addListener((ignored, oldValue, newValue) -> validate.run());
        column.valueProperty().addListener((ignored, oldValue, newValue) -> refreshOperators.run());
        column.getSelectionModel().selectFirst();
        refreshOperators.run();

        dialog.setResultConverter(button -> {
            if (button != ok) return null;
            ResultColumn selectedColumn = column.getValue();
            FilterOperator selectedOperator = operator.getValue();
            Object parsed = selectedOperator.valueRequired()
                    ? FilterValueParser.parse(selectedColumn, selectedOperator, value.getText())
                    : null;
            FilterConnector selectedConnector = conditionIndex == 0
                    ? FilterConnector.AND : connector.getValue();
            if (selectedConnector == null) selectedConnector = FilterConnector.AND;
            return new FilterCondition(selectedColumn.index(), selectedConnector,
                    selectedOperator, parsed);
        });
        return dialog.showAndWait();
    }

    private static void validate(
            ComboBox<ResultColumn> column, ComboBox<FilterOperator> operator,
            TextField value, Label error, Node okButton) {
        try {
            ResultColumn selectedColumn = column.getValue();
            FilterOperator selectedOperator = operator.getValue();
            boolean required = selectedOperator != null && selectedOperator.valueRequired();
            value.setDisable(!required);
            if (selectedColumn == null || selectedOperator == null) {
                error.setText("");
                error.setAccessibleText("");
                okButton.setDisable(true);
                return;
            }
            if (required) {
                FilterValueParser.parse(selectedColumn, selectedOperator, value.getText());
            }
            error.setText("");
            error.setAccessibleText("");
            okButton.setDisable(false);
        } catch (IllegalArgumentException invalid) {
            error.setText(invalid.getMessage());
            error.setAccessibleText(invalid.getMessage());
            okButton.setDisable(true);
        }
    }

    private static StringConverter<ResultColumn> columnConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(ResultColumn column) {
                if (column == null) return "";
                String type = column.jdbcTypeName().isBlank() ? "" : " (" + column.jdbcTypeName() + ")";
                return column.label() + type;
            }

            @Override
            public ResultColumn fromString(String value) {
                return null;
            }
        };
    }

    private static StringConverter<FilterOperator> operatorConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(FilterOperator operator) {
                if (operator == null) return "";
                return switch (operator) {
                    case EQ -> "等于";
                    case NE -> "不等于";
                    case CONTAINS -> "包含";
                    case STARTS_WITH -> "开头是";
                    case ENDS_WITH -> "结尾是";
                    case GT -> "大于";
                    case GTE -> "大于等于";
                    case LT -> "小于";
                    case LTE -> "小于等于";
                    case IS_NULL -> "为空";
                    case IS_NOT_NULL -> "非空";
                };
            }

            @Override
            public FilterOperator fromString(String value) {
                return null;
            }
        };
    }
}
