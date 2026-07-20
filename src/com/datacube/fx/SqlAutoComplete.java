package com.datacube.fx;

import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Text;
import javafx.stage.Popup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * 为 {@link TextArea} 提供轻量级自动补全：SQL 关键字 + 元数据名称（表/视图/schema）。
 *
 * <p>无第三方依赖：用 {@link Popup} + {@link ListView} 呈现候选，前缀过滤，
 * 方向键/Enter/Tab 选中，Ctrl+Space 强制触发，Esc 关闭。候选词由外部
 * {@link Supplier} 惰性提供（含后台预热的元数据名称）。
 *
 * <p>光标屏幕坐标基于等宽字体度量估算（行高 × 行号、字符宽 × 列号），
 * 并扣除滚动偏移；这是纯 JavaFX 无法精确取 caret bounds 的折中，够用即可。
 */
final class SqlAutoComplete {

    private static final int MAX_ITEMS = 200;

    /** 限定符成员供给：给定 {@code 别名/表名} 返回其列名候选（用于 {@code a.} 补全）。 */
    @FunctionalInterface
    interface MemberProvider {
        Collection<String> membersFor(String qualifier);
    }

    private final TextArea area;
    private final Supplier<Collection<String>> candidateSupplier;
    private MemberProvider memberProvider;
    private final Popup popup = new Popup();
    private final ListView<String> list = new ListView<>();

    /** 抑制补全替换文本时触发的 textProperty 递归。 */
    private boolean mutating = false;

    SqlAutoComplete(TextArea area, Supplier<Collection<String>> candidateSupplier) {
        this.area = area;
        this.candidateSupplier = candidateSupplier;
        setup();
    }

    /** 设置限定符成员供给（如列名）；为空时仅按前缀补全全局候选。 */
    void setMemberProvider(MemberProvider provider) {
        this.memberProvider = provider;
    }

    private void setup() {
        list.setPrefWidth(280);
        list.setMaxHeight(220);
        list.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");
        popup.getContent().add(list);
        popup.setAutoHide(true);

        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) applySelection();
        });

        area.textProperty().addListener((obs, o, n) -> {
            if (mutating) return;
            Platform.runLater(this::maybeShow);
        });

        // 键盘导航同时注册到编辑器与弹窗列表：弹窗显示时其窗口可能抢占系统焦点，
        // 此时按键事件派发到列表所在场景而非编辑器，故两处都需处理才能保证
        // Enter/Tab/方向键在任一焦点归属下都生效（仅靠编辑器过滤器时只有鼠标点击可用）。
        area.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        list.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);

        area.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) {
                // 若焦点转移到弹窗列表（部分平台弹窗会抢焦点）则保留，否则隐藏。
                Platform.runLater(() -> {
                    if (!area.isFocused() && !list.isFocused()) hide();
                });
            }
        });
        area.scrollTopProperty().addListener((o, a, b) -> hide());
        area.scrollLeftProperty().addListener((o, a, b) -> hide());
    }

    private void onKeyPressed(KeyEvent e) {
        if (e.isControlDown() && e.getCode() == KeyCode.SPACE) {
            e.consume();
            maybeShow();
            return;
        }
        if (!popup.isShowing()) return;
        switch (e.getCode()) {
            case DOWN -> { move(1); e.consume(); }
            case UP -> { move(-1); e.consume(); }
            case ENTER, TAB -> { applySelection(); e.consume(); }
            case ESCAPE -> { hide(); e.consume(); }
            default -> { }
        }
    }

    private void move(int delta) {
        int size = list.getItems().size();
        if (size == 0) return;
        int i = list.getSelectionModel().getSelectedIndex() + delta;
        if (i < 0) i = 0;
        if (i >= size) i = size - 1;
        list.getSelectionModel().select(i);
        list.scrollTo(i);
    }

    private void maybeShow() {
        int caret = area.getCaretPosition();
        int start = wordStart(caret);
        String prefix = caret <= start ? "" : area.getText(start, caret);
        String qualifier = qualifierBefore(start);

        Collection<String> pool;
        if (qualifier != null && memberProvider != null) {
            // 限定符上下文（如 a.col）：即使前缀为空也展示该限定符的全部成员
            pool = memberProvider.membersFor(qualifier);
        } else {
            if (prefix.length() < 1) { hide(); return; }
            pool = candidateSupplier.get();
        }
        if (pool == null || pool.isEmpty()) { hide(); return; }

        String lower = prefix.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (String c : pool) {
            if (c == null) continue;
            if (c.toLowerCase().startsWith(lower) && !c.equalsIgnoreCase(prefix)) {
                matches.add(c);
            }
        }
        if (matches.isEmpty()) { hide(); return; }
        matches.sort(String.CASE_INSENSITIVE_ORDER);
        if (matches.size() > MAX_ITEMS) matches = matches.subList(0, MAX_ITEMS);
        list.getItems().setAll(matches);
        list.getSelectionModel().select(0);
        showAtCaret();
    }

    /**
     * 若光标前当前单词的紧邻左侧是 {@code .} 且其前为标识符，返回该标识符（限定符）；
     * 否则返回 null。用于识别 {@code 别名./表名.} 的列补全上下文。
     */
    private String qualifierBefore(int wordStart) {
        String text = area.getText();
        if (wordStart <= 0 || wordStart > text.length()) return null;
        if (text.charAt(wordStart - 1) != '.') return null;
        int qEnd = wordStart - 1;
        int qStart = qEnd;
        while (qStart > 0) {
            char ch = text.charAt(qStart - 1);
            if (Character.isLetterOrDigit(ch) || ch == '_') qStart--;
            else break;
        }
        return qStart < qEnd ? text.substring(qStart, qEnd) : null;
    }

    /**
     * 异步数据（如后台加载的列名）就绪后重算候选：仅当编辑器仍聚焦时尝试重新展示，
     * 由 {@link #maybeShow()} 自行决定是否弹出，避免打断用户。
     */
    void refresh() {
        Platform.runLater(() -> {
            if (area.isFocused()) maybeShow();
        });
    }

    private void showAtCaret() {
        Point2D p = caretScreenPos();
        if (p == null) { hide(); return; }
        popup.show(area, p.getX(), p.getY());
    }

    private Point2D caretScreenPos() {
        if (area.getScene() == null || area.getScene().getWindow() == null) return null;
        Point2D origin = area.localToScreen(0, 0);
        if (origin == null) return null;

        int caret = Math.max(0, area.getCaretPosition());
        String before = area.getText(0, Math.min(caret, area.getLength()));
        int row = 0, lastNl = -1;
        for (int i = 0; i < before.length(); i++) {
            if (before.charAt(i) == '\n') { row++; lastNl = i; }
        }
        int col = before.length() - (lastNl + 1);

        Text sample = new Text("M");
        sample.setFont(area.getFont());
        double charW = sample.getLayoutBounds().getWidth();
        double lineH = sample.getLayoutBounds().getHeight();
        if (charW <= 0) charW = 8;
        if (lineH <= 0) lineH = 16;

        double padX = 8, padY = 8;
        double x = origin.getX() + padX + col * charW - area.getScrollLeft();
        double y = origin.getY() + padY + (row + 1) * lineH - area.getScrollTop();
        return new Point2D(x, y);
    }

    private void applySelection() {
        String sel = list.getSelectionModel().getSelectedItem();
        if (sel == null) { hide(); return; }
        int caret = area.getCaretPosition();
        int start = wordStart(caret);
        mutating = true;
        try {
            area.replaceText(start, caret, sel);
            area.positionCaret(start + sel.length());
        } finally {
            mutating = false;
        }
        hide();
        // 弹窗列表可能曾抢占焦点，应用后将焦点交回编辑器以便继续输入。
        area.requestFocus();
    }

    /** 光标前的当前单词（字母/数字/下划线）。 */
    private String currentWord() {
        int caret = area.getCaretPosition();
        int start = wordStart(caret);
        return caret <= start ? "" : area.getText(start, caret);
    }

    private int wordStart(int caret) {
        String text = area.getText();
        int i = Math.min(caret, text.length());
        while (i > 0) {
            char ch = text.charAt(i - 1);
            if (Character.isLetterOrDigit(ch) || ch == '_') i--;
            else break;
        }
        return i;
    }

    void hide() {
        if (popup.isShowing()) popup.hide();
    }
}
