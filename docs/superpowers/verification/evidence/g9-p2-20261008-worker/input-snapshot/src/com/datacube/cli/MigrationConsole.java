package com.datacube.cli;

import com.datacube.core.MigrationLogger;
import com.datacube.migration.*;
import java.nio.file.Path;
import java.util.Locale;

/** The optional CLI uses exactly the same preflight and approval boundary as the GUI. */
public final class MigrationConsole {
    @FunctionalInterface public interface Prompt { String ask(String label,String defaultValue,String hint); }
    private MigrationConsole() { }
    public static void run() {
        ConsolePrompter prompts=new ConsolePrompter();ConsoleLogger logger=new ConsoleLogger();logger.openLog();
        try {
            String sourceUrl=prompts.prompt("Oracle JDBC URL","jdbc:oracle:thin:@127.0.0.1:1521/orcl","");
            String sourceUser=prompts.prompt("Oracle 用户名","scott","");String sourcePassword=prompts.secret("Oracle 密码");
            String targetUrl=prompts.prompt("PostgreSQL JDBC URL","jdbc:postgresql://127.0.0.1:5432/postgres","");
            String targetUser=prompts.prompt("PostgreSQL 用户名","postgres","");String targetPassword=prompts.secret("PostgreSQL 密码");
            String schema=prompts.prompt("目标 Schema",sourceUser.toLowerCase(Locale.ROOT),"");
            Path root=Path.of(prompts.prompt("导出根目录","pg_migration","数据文件明文存储；参考 DDL 不会自动执行"));
            var request=new MigrationRequest(new MigrationRequest.Endpoint(sourceUrl,sourceUser,sourcePassword),new MigrationRequest.Endpoint(targetUrl,targetUser,targetPassword),sourceUser.toUpperCase(Locale.ROOT),schema,root.resolve(schema),MigrationRequest.Mode.EMPTY_TABLES_ONLY,false);
            session(prompts::prompt,logger,new MigrationOperations(logger),request,20);
        } catch(Exception failure){logger.logErr("迁移未完成；未输出凭据、SQL 或原始异常");}
        finally{logger.closeLog();}
    }
    public static void session(Prompt prompt,MigrationLogger logger,MigrationOperations operations,MigrationRequest initial,int concurrency) {
        MigrationRequest request=initial;MigrationPlan prepared=null;MigrationRun previous=null;
        while(true) {
            System.out.println("1. 导出参考 DDL（人工审阅）  2. 导出数据  3. 预检查：仅空表  4. 预检查：跳过已有数据");
            System.out.println("5. 导出并预检查  6. 目标端统计（非一致性验证）  7. 确认执行计划  8. 重新预检查可重试项  9. 查看脱敏报告  0. 退出");
            String choice=prompt.ask("请选择","0","");if(choice.equals("0"))return;
            try(MigrationCancellation cancellation=new MigrationCancellation()) {
                switch(choice) {
                    case "1","2"->{prepared=null;operations.export(request,cancellation,concurrency,choice.equals("2"));logger.logInfo("导出结束；未写入目标数据库");}
                    case "3","4","5"->{
                        prepared=null;
                        request=new MigrationRequest(request.source(),request.target(),request.owner(),request.schema(),request.directory(),choice.equals("4")?MigrationRequest.Mode.SKIP_NONEMPTY:MigrationRequest.Mode.EMPTY_TABLES_ONLY,request.convertBoolean());
                        if(choice.equals("5")){operations.export(request,cancellation,concurrency,false);cancellation.checkCancelled();operations.export(request,cancellation,concurrency,true);cancellation.checkCancelled();}
                        prepared=operations.prepare(request,cancellation);System.out.println(MigrationReview.describe(prepared));
                        logger.logInfo(prepared.canRun()?"预检查完成，等待独立确认":"存在阻断项，不能执行");
                    }
                    case "6"->operations.statistics(request,cancellation);
                    case "7"->{
                        if(prepared==null || !prepared.canRun() || !prepared.request().equals(request)){logger.logWarn("请先完成当前目标的预检查");break;}
                        String answer=prompt.ask("确认目标："+MigrationReview.target(request),"","逐表提交，不清空已有数据；输入目标 Schema 确认，其他输入取消");
                        if(!answer.equals(request.schema())){logger.logInfo("已取消确认，未写入目标");break;}
                        var plan=prepared;prepared=null;
                        previous=operations.execute(plan,plan.approve(request),request,cancellation);
                        System.out.println(MigrationReport.of(previous).display());
                        logger.logInfo(previous.completeWithinScope()?(previous.plan().previousRun()==null?"表数据导入和文件对账完成，其他对象仍需审阅":"本次选定重试项完成；原报告其他项仍需查看"):"存在跳过/失败/未知状态，请查看逐表报告");
                    }
                    case "8"->{
                        prepared=null;if(previous==null){logger.logWarn("没有本进程可重试的运行");break;}
                        prepared=previous.retryPlan(operations.prepare(request,cancellation));System.out.println(MigrationReview.describe(prepared));
                        logger.logInfo("重试计划已重新预检查，仍需独立确认");
                    }
                    case "9"->{prepared=null;previous=null;String file=prompt.ask("报告文件路径","","只读查看，不恢复执行权限");if(!file.isBlank())System.out.println(MigrationReport.read(Path.of(file)).display());}
                    default->logger.logWarn("无效选择");
                }
            } catch(Exception failure){prepared=null;logger.logErr("操作失败，未生成成功结论；请重新预检查或查看逐表报告");}
        }
    }
}
