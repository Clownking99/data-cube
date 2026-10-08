package com.datacube.migration;

import java.util.*;

/** Local review only; contains object names and must not be exported as the redacted report. */
public final class MigrationReview {
    private MigrationReview() { }
    public static String target(MigrationRequest request) {
        String address="当前表单中的 JDBC 目标";
        try {var uri=new java.net.URI(request.target().url().replaceFirst("^jdbc:",""));if(uri.getHost()!=null)address=uri.getHost()+(uri.getPort()<0?"":":"+uri.getPort())+Objects.toString(uri.getPath(),"");}catch(java.net.URISyntaxException ignored){}
        return address+" / 用户 "+request.target().user()+" / Schema "+request.schema();
    }
    public static String describe(MigrationPlan plan) {
        String mode=plan.request().mode()==MigrationRequest.Mode.EMPTY_TABLES_ONLY?"仅导入空表，已有数据则拒绝":"跳过已有数据，不是增量同步";
        StringBuilder text=new StringBuilder("目标："+target(plan.request())+"\n模式："+mode+"\n表数："+plan.tables().size()+"\n只创建缺失表或导入已确认的空表；不会清空、覆盖或自动执行参考 DDL。\n单表事务；取消不撤回先前已经提交的表。\n\n");
        for(var item:plan.findings())text.append(level(item.level())).append("：").append(item.message()).append('\n');
        int ordinal=0;
        for(var table:plan.tables()){
            text.append("\nT").append(String.format(Locale.ROOT,"%04d",++ordinal)).append(" ").append(table.sourceName()).append(" → ").append(table.targetName()).append('\n');
            for(var column:table.columns())text.append("  ").append(column.sourceName()).append(" : ").append(column.sourceType()).append(" → ").append(column.pgType().isEmpty()?"需人工映射":column.pgType()).append('\n');
            for(var item:table.findings())text.append("  ").append(level(item.level())).append("：").append(item.message()).append('\n');
        }
        return text.toString();
    }
    private static String level(MigrationPlan.Level level){return switch(level){case INFO->"提示";case MANUAL->"需人工审阅";case BLOCK->"阻断";};}
}
