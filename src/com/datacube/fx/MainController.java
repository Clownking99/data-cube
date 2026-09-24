package com.datacube.fx;

import com.datacube.core.MigrationLogger;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.migration.*;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

/** Migration UI with immutable request snapshots, explicit approval and owner-scoped callbacks. */
public class MainController {
    private enum Job { TEST, DDL, DATA, EXPORT_PREPARE, PREPARE, EXECUTE, RETRY, STATISTICS }
    private final FxTaskScope tasks;
    private final Executor cleanupExecutor;
    private final Function<MigrationLogger,MigrationOperations> backendFactory;
    private final Predicate<MigrationPlan> confirmation;
    private final AtomicReference<MigrationCancellation> activeOperation=new AtomicReference<>();
    private final List<Control> inputs=new ArrayList<>();
    private final List<Button> actions=new ArrayList<>();
    private TextField sourceUrl,sourceUser,targetUrl,targetUser,schema,root;
    private PasswordField sourcePassword,targetPassword;
    private ComboBox<String> mode;
    private Spinner<Integer> concurrency;
    private CheckBox bool;
    private ProgressBar progress;
    private Label status;
    private TextArea log,review,report;
    private TabPane views;
    private Button cancel,execute,retry;
    private FxLogger logger;
    private MigrationOperations backend;
    private MigrationPlan prepared;
    private MigrationRun lastRun;
    private long revision;
    private volatile boolean running,shuttingDown;

    MainController(FxTaskScope tasks) { this(tasks,Runnable::run); }
    MainController(FxTaskScope tasks,Executor cleanupExecutor) { this(tasks,cleanupExecutor,MigrationOperations::new,null); }
    MainController(FxTaskScope tasks,Executor cleanupExecutor,Function<MigrationLogger,MigrationOperations> backendFactory,Predicate<MigrationPlan> confirmation) {
        this.tasks=Objects.requireNonNull(tasks);this.cleanupExecutor=Objects.requireNonNull(cleanupExecutor);
        this.backendFactory=Objects.requireNonNull(backendFactory);this.confirmation=confirmation==null?this::confirmPlan:confirmation;
    }
    public VBox createMigrationContent() {
        sourceUrl=field("source.url","jdbc:oracle:thin:@127.0.0.1:1521/orcl");
        sourceUser=field("source.user","scott");sourcePassword=password("source.password");
        targetUrl=field("target.url","jdbc:postgresql://127.0.0.1:5432/postgres");
        targetUser=field("target.user","postgres");targetPassword=password("target.password");schema=field("schema","scott");
        root=field("directory",Path.of("pg_migration").toAbsolutePath().normalize().toString());
        root.setTooltip(new Tooltip("导出文件包含明文数据；脱敏报告另存本地，不包含数据值或凭据"));
        mode=new ComboBox<>(FXCollections.observableArrayList("仅导入空表（拒绝已有数据）","跳过已有数据（不是增量同步）"));
        mode.setId("migration.mode");mode.getSelectionModel().selectFirst();inputs.add(mode);mode.valueProperty().addListener((o,a,b)->invalidatePlan());
        concurrency=new Spinner<>(1,100,20);concurrency.setPrefWidth(80);inputs.add(concurrency);
        bool=new CheckBox("旧布尔推断（自动迁移不支持）");bool.setId("migration.boolean");inputs.add(bool);bool.selectedProperty().addListener((o,a,b)->invalidatePlan());
        Button choose=new Button("选择导出根目录");choose.setId("migration.directory.choose");actions.add(choose);
        choose.setOnAction(event->{DirectoryChooser picker=new DirectoryChooser();picker.setTitle("选择迁移导出根目录");var selected=picker.showDialog(root.getScene()==null?null:root.getScene().getWindow());if(selected!=null)root.setText(selected.getAbsolutePath());});
        GridPane source=grid(new String[]{"JDBC URL","用户名","密码"},sourceUrl,sourceUser,sourcePassword);
        GridPane target=grid(new String[]{"JDBC URL","用户名","密码","Schema"},targetUrl,targetUser,targetPassword,schema);
        var sourcePane=new TitledPane("Oracle 源（仅读取）",source);sourcePane.setCollapsible(false);
        var targetPane=new TitledPane("PostgreSQL 目标（执行前确认）",target);targetPane.setCollapsible(false);
        FlowPane options=new FlowPane(10,8,new Label("导出并发"),concurrency,mode,bool);
        HBox directory=new HBox(8,new Label("导出根目录"),root,choose);HBox.setHgrow(root,Priority.ALWAYS);
        FlowPane buttons=new FlowPane(10,8);
        buttons.getChildren().addAll(
                button("test","测试连接",Job.TEST),button("ddl","导出参考 DDL",Job.DDL),
                button("data","导出数据",Job.DATA),button("prepare","预检查（仅读取）",Job.PREPARE),
                button("all","导出并预检查",Job.EXPORT_PREPARE));
        execute=button("execute","确认并执行导入",Job.EXECUTE);retry=button("retry","重新预检查可重试项",Job.RETRY);
        Button load=new Button("读取脱敏报告");load.setId("migration.report.load");actions.add(load);load.setOnAction(e->loadReport());
        cancel=new Button("取消");cancel.setId("migration.cancel");cancel.setVisible(false);cancel.setManaged(false);
        cancel.setOnAction(e->{var operation=activeOperation.get();if(operation!=null){cancel.setDisable(true);status.setText("正在取消，等待资源关闭");operation.cancelAsync(cleanupExecutor);}});
        buttons.getChildren().addAll(execute,retry,button("statistics","目标端统计",Job.STATISTICS),load,cancel);
        progress=new ProgressBar(0);progress.setMaxWidth(Double.MAX_VALUE);status=new Label("先导出数据，再预检查并确认导入；参考 DDL 不会自动执行");
        status.setId("migration.status");
        log=area("log");review=area("review");report=area("report");
        views=new TabPane(tab("预检查",review),tab("逐表报告",report),tab("日志",log));
        VBox.setVgrow(views,Priority.ALWAYS);
        VBox content=new VBox(10,sourcePane,targetPane,directory,options,buttons,progress,status,views);content.setPadding(new Insets(15));
        logger=new FxLogger(log,progress,status,tasks::dispatch);backend=backendFactory.apply(logger);
        updateControls();return content;
    }
    private TextField field(String id,String value) {
        TextField field=new TextField(value);field.setId("migration."+id);inputs.add(field);field.textProperty().addListener((o,a,b)->invalidatePlan());return field;
    }
    private PasswordField password(String id) {
        PasswordField field=new PasswordField();field.setId("migration."+id);inputs.add(field);field.textProperty().addListener((o,a,b)->invalidatePlan());return field;
    }
    private static GridPane grid(String[] labels,Control... controls) {
        GridPane grid=new GridPane();grid.setHgap(10);grid.setVgap(6);grid.setPadding(new Insets(8));
        for(int i=0;i<labels.length;i++){grid.add(new Label(labels[i]),0,i);grid.add(controls[i],1,i);GridPane.setHgrow(controls[i],Priority.ALWAYS);}
        return grid;
    }
    private TextArea area(String id) {TextArea area=new TextArea();area.setId("migration."+id);area.setEditable(false);area.setWrapText(true);return area;}
    private static Tab tab(String title,TextArea content){Tab tab=new Tab(title,content);tab.setClosable(false);return tab;}
    private Button button(String id,String text,Job job){Button button=new Button(text);button.setId("migration."+id);button.setOnAction(event->start(job));actions.add(button);return button;}
    private void invalidatePlan() {revision++;prepared=null;if(execute!=null)updateControls();}
    private void updateControls() {
        inputs.forEach(input->input.setDisable(running));actions.forEach(action->action.setDisable(running));
        if(execute!=null)execute.setDisable(running || prepared==null || !prepared.canRun());
        if(retry!=null)retry.setDisable(running || lastRun==null);
    }
    private MigrationRequest readInputs(boolean sourceRequired) {
        String owner=sourceUser.getText().trim().toUpperCase(Locale.ROOT),scope=schema.getText().trim();
        if(sourceRequired && (sourceUrl.getText().isBlank() || owner.isEmpty()))throw new IllegalArgumentException("请输入 Oracle 源连接信息");
        if(targetUrl.getText().isBlank() || targetUser.getText().isBlank() || scope.isEmpty() || root.getText().isBlank())throw new IllegalArgumentException("请输入 PostgreSQL 目标和导出目录");
        return new MigrationRequest(new MigrationRequest.Endpoint(sourceUrl.getText().trim(),sourceUser.getText().trim(),sourcePassword.getText()),
                new MigrationRequest.Endpoint(targetUrl.getText().trim(),targetUser.getText().trim(),targetPassword.getText()),
                owner.isEmpty()?"UNUSED":owner,scope,Path.of(root.getText().trim()).resolve(scope),
                mode.getSelectionModel().getSelectedIndex()==1?MigrationRequest.Mode.SKIP_NONEMPTY:MigrationRequest.Mode.EMPTY_TABLES_ONLY,bool.isSelected());
    }
    private void start(Job job) {
        if(shuttingDown || running)return;
        final MigrationRequest request;
        try {request=readInputs(job!=Job.STATISTICS);}
        catch(RuntimeException invalid){status.setText("输入不完整或目录无效");return;}
        final MigrationPlan plan=prepared;
        MigrationPlan.Approval approved=null;
        if(job==Job.EXECUTE) {
            if(plan==null || !plan.request().equals(request) || !plan.canRun()){invalidatePlan();status.setText("目标或配置已变化，请重新预检查");return;}
            if(!confirmation.test(plan)){status.setText("已取消确认，未开始导入");return;}
            // A modal confirmation may process nested UI events.
            if(shuttingDown || running || prepared!=plan || !request.equals(readInputs(true))){invalidatePlan();status.setText("确认期间配置已变化，请重新预检查");return;}
            approved=plan.approve(request);
        }
        final MigrationPlan.Approval approval=approved;
        final MigrationRun prior=lastRun;
        final int parallelism=concurrency.getValue();
        final long expectedRevision=revision;
        MigrationCancellation cancellation=new MigrationCancellation();
        if(!activeOperation.compareAndSet(null,cancellation))return;
        running=true;updateControls();cancel.setVisible(true);cancel.setManaged(true);cancel.setDisable(false);progress.setProgress(-1);status.setText("执行中");
        if(job==Job.PREPARE || job==Job.RETRY || job==Job.EXPORT_PREPARE)prepared=null;
        try {
            tasks.submit(()-> {
                Object value=null;boolean wasCancelled=false;boolean failed=false;
                try {
                    value=switch(job) {
                        case TEST->{backend.test(request,cancellation);yield null;}
                        case DDL,DATA->{backend.export(request,cancellation,parallelism,job==Job.DATA);yield null;}
                        case PREPARE->backend.prepare(request,cancellation);
                        case EXPORT_PREPARE->{backend.export(request,cancellation,parallelism,false);cancellation.checkCancelled();backend.export(request,cancellation,parallelism,true);cancellation.checkCancelled();yield backend.prepare(request,cancellation);}
                        case RETRY->{if(prior==null)throw new IllegalStateException();yield prior.retryPlan(backend.prepare(request,cancellation));}
                        case EXECUTE->backend.execute(plan,approval,request,cancellation);
                        case STATISTICS->backend.statistics(request,cancellation);
                    };
                    wasCancelled=cancellation.isCancelled();
                } catch(CancellationException cancelled){wasCancelled=true;}
                catch(Exception error){failed=true;wasCancelled=cancellation.isCancelled();}
                finally {
                    cancellation.close();
                    try {cancellation.awaitCleanup();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();wasCancelled=true;}
                    activeOperation.compareAndSet(cancellation,null);
                }
                return new Completion(value,wasCancelled,failed);
            }, result->finish(job,result,expectedRevision), error->finish(job,new Completion(null,false,true),expectedRevision));
        } catch(RuntimeException rejected) {
            cancellation.cancelAsync(cleanupExecutor);activeOperation.compareAndSet(cancellation,null);
            finish(job,new Completion(null,false,true),expectedRevision);
        }
    }
    private record Completion(Object value,boolean cancelled,boolean failed) { }
    private void finish(Job job,Completion result,long expectedRevision) {
        if(shuttingDown)return;
        running=false;cancel.setVisible(false);cancel.setManaged(false);progress.setProgress(0);
        if(result.value() instanceof MigrationRun run){lastRun=run;prepared=null;report.setText(MigrationReport.of(run).display()+"\n本地报告目录："+MigrationReport.defaultDirectory());views.getSelectionModel().select(1);}
        if(result.failed()){status.setText("操作失败，未生成成功结论");logger.logErr("迁移操作失败；原始异常、连接信息及 SQL 未写入报告");}
        else if(result.cancelled())status.setText("已取消；已提交或未知结果仍需查看逐表报告");
        else if(expectedRevision!=revision){prepared=null;status.setText("配置已变化，结果不可用于执行；请重新预检查");}
        else if(result.value() instanceof MigrationPlan next){
            prepared=next;review.setText(describe(next));views.getSelectionModel().select(0);
            status.setText(next.canRun()?"预检查完成，请审阅目标、范围和限制后确认":"预检查存在阻断项，不能执行");
        } else if(result.value() instanceof MigrationRun run)status.setText(run.completeWithinScope()?(run.plan().previousRun()==null?"表数据导入和文件对账完成；其他对象仍需审阅":"本次选定重试项完成；原报告其他项仍需查看"):"存在跳过、失败或未知结果，请查看逐表报告");
        else if(job==Job.STATISTICS)status.setText("目标端统计完成（非迁移一致性结论）");
        else if(job==Job.DDL)status.setText("参考 DDL 已导出，需人工审阅，不自动执行");
        else if(job==Job.DATA)status.setText("数据导出完成；各表读取窗口独立");
        else status.setText("连接检查完成，未写入目标");
        updateControls();
    }
    static String describe(MigrationPlan plan) { return MigrationReview.describe(plan); }
    private static String targetLabel(MigrationRequest request) { return MigrationReview.target(request); }
    private boolean confirmPlan(MigrationPlan plan) {
        Alert dialog=new Alert(Alert.AlertType.CONFIRMATION,"确认向此目标创建缺失表并写入已审阅的空表？\n"+targetLabel(plan.request())+"\n共 "+plan.tables().size()+" 个表任务；逐表提交，取消不能回滚已提交表。\n请先阅读预检查中的未支持对象和对账范围。",ButtonType.CANCEL,ButtonType.OK);
        dialog.setHeaderText("确认本次迁移目标和范围");if(status.getScene()!=null)dialog.initOwner(status.getScene().getWindow());
        return dialog.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;
    }
    private void loadReport() {
        if(running || shuttingDown)return;
        FileChooser chooser=new FileChooser();chooser.setTitle("读取迁移脱敏报告（不会恢复写入）");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("迁移报告","*.report"));
        var selected=chooser.showOpenDialog(status.getScene()==null?null:status.getScene().getWindow());if(selected==null)return;
        prepared=null;lastRun=null;running=true;updateControls();
        try {
            tasks.submit(()->MigrationReport.read(selected.toPath()),value->{running=false;report.setText(value.display());views.getSelectionModel().select(1);status.setText("已读取报告，仅供查看，不授权重放");updateControls();},
                    error->{running=false;status.setText("报告损坏、版本不支持或无法读取；原文件保留");updateControls();});
        } catch(RuntimeException rejected) {running=false;status.setText("报告读取任务无法启动");updateControls();}
    }
    public boolean isRunning(){return running;}
    public void shutdown(){
        shuttingDown=true;prepared=null;lastRun=null;var operation=activeOperation.getAndSet(null);if(operation!=null)operation.cancelAsync(cleanupExecutor);
        tasks.close();if(logger!=null)logger.closeLog();
    }
}
