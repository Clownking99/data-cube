package com.datacube.spi;

/**
 * 多语句脚本执行中遇到失败语句时的处置策略回调。
 *
 * <p>由 UI 层实现并传入 {@link SqlRunner#executeScript}；runner 位于 provider 层
 * （无 JavaFX 依赖），仅通过本接口把「是否继续」的决策上交给上层，避免反向依赖。
 */
public interface ScriptErrorPolicy {

    /** 遇错处置：继续下一条 / 后续全部继续（不再回调）/ 中止。 */
    enum Decision { CONTINUE, CONTINUE_ALL, ABORT }

    /**
     * 某条语句失败时回调，返回处置决策。
     *
     * @param index   失败语句的序号（从 1 起）
     * @param sql     失败的 SQL 文本
     * @param message 错误信息
     */
    Decision onError(int index, String sql, String message);
}
