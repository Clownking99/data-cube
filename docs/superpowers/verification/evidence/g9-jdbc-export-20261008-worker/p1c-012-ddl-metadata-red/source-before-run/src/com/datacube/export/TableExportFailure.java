package com.datacube.export;

/** Value/cause-free table-export diagnostics. */
public final class TableExportFailure extends RuntimeException {
    public enum Kind { TARGET_CHANGED, TIMEOUT, TYPE, LIMIT, STRUCTURE, SOURCE, CLEANUP }
    private final Kind kind;
    public TableExportFailure(Kind kind){super(message(kind));this.kind=kind;}
    public Kind kind(){return kind;}
    private static String message(Kind kind){return switch(kind){
        case TARGET_CHANGED->"连接目标已变化，请重新选择导出；原目标文件未修改";
        case TIMEOUT->"导出超过总时限，已停止自有资源；原目标文件未修改";
        case TYPE->"存在无法完整表示的值类型或数值精度，请选择兼容格式；原目标文件未修改";
        case LIMIT->"导出值、行、结构或工作表超过完整保存额度；原目标文件未修改";
        case STRUCTURE->"无法完整读取表结构；原目标文件未修改";
        case SOURCE->"读取导出来源失败；原目标文件未修改";
        case CLEANUP->"导出资源释放失败，未发布结果文件";
    };}
}
