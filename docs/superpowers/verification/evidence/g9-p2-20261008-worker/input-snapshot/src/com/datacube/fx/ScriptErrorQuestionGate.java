package com.datacube.fx;

import com.datacube.spi.ScriptErrorPolicy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Window;

/** One script execution owns its questions; cancellation seals them before releasing the worker. */
final class ScriptErrorQuestionGate implements ScriptErrorPolicy {
    private final BooleanSupplier closed;
    private final Supplier<Window> owner;
    private final Consumer<Runnable> dispatcher;
    private boolean sealed;
    private boolean finished;
    private Question pending;

    ScriptErrorQuestionGate(BooleanSupplier closed,Supplier<Window> owner) {
        this(closed,owner,Platform::runLater);
    }
    ScriptErrorQuestionGate(BooleanSupplier closed,Supplier<Window> owner,Consumer<Runnable> dispatcher) {
        this.closed=closed;this.owner=owner;this.dispatcher=dispatcher;
    }
    @Override public Decision onError(int index,String sql,String message) {
        Question question=new Question();
        synchronized(this) {
            if(finished || closed.getAsBoolean())return Decision.ABORT;
            pending=question;
        }
        try {dispatcher.accept(()->show(question,index,message));}
        catch(RuntimeException|Error dispatchFailure) {finish();return Decision.ABORT;}
        try {return question.answer.get();}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();finish();return Decision.ABORT;}
        catch(ExecutionException impossible) {finish();return Decision.ABORT;}
    }
    /** May dismiss the FX dialog, but cannot release its worker before the captured cancel finishes. */
    void seal() {
        Question question;
        synchronized(this){sealed=true;question=pending;}
        dismiss(question);
    }
    /** Called in the physical cancel/execute finally, never solely from a suppressible UI callback. */
    void finish() {
        Question question;
        synchronized(this) {
            sealed=true;finished=true;question=pending;pending=null;
            if(question!=null)question.answer.complete(Decision.ABORT);
        }
        dismiss(question);
    }
    private void show(Question question,int index,String message) {
        synchronized(this) {
            if(finished || pending!=question)return;
            if(sealed)return; // The captured physical cancel still owns completion of this wait.
        }
        if(closed.getAsBoolean()){finish();return;}
        Decision decision=Decision.ABORT;
        try {
            ButtonType cont=new ButtonType("继续"),all=new ButtonType("全部继续"),abort=new ButtonType("取消",ButtonBar.ButtonData.CANCEL_CLOSE);
            String detail=message==null ? "" : message;
            if(detail.length()>300)detail=detail.substring(0,300)+"...";
            Alert alert=new Alert(Alert.AlertType.ERROR,"第 "+index+" 条语句失败：\n"+detail+"\n\n是否继续执行剩余语句？",cont,all,abort);
            Window window=owner.get();if(window!=null)alert.initOwner(window);
            alert.setHeaderText(null);alert.setTitle("执行遇错");
            synchronized(this){if(sealed || finished || pending!=question)return;question.dialog=alert;}
            ButtonType chosen=alert.showAndWait().orElse(abort);
            if(chosen==cont)decision=Decision.CONTINUE;else if(chosen==all)decision=Decision.CONTINUE_ALL;
        }finally {
            question.dialog=null;
            synchronized(this) {
                // A seal-induced showAndWait return must not beat session.cancel's finally.
                if(!sealed && pending==question){pending=null;question.answer.complete(decision);}
            }
        }
    }
    private void dismiss(Question question) {
        if(question==null)return;
        Runnable close=()-> {if(question.dialog!=null)question.dialog.close();};
        try {if(Platform.isFxApplicationThread())close.run();else dispatcher.accept(close);}
        catch(RuntimeException|Error unavailableFx) {
            // Worker completion was already published by finish; no off-FX scene mutation is safe.
        }
    }
    private static final class Question {
        final CompletableFuture<Decision> answer=new CompletableFuture<>();
        Alert dialog; // FX-only; each queued closure retains precisely this question, never a successor.
    }
}
