package net.momirealms.sparrow.ui.exception;

/**
 * 表示 Window 打开期间, Viewer 的命令管道已无法继续接受 Window 发布的实体线程任务.
 */
public final class ViewerUnavailableException extends IllegalStateException {

    public ViewerUnavailableException() {
        super("viewer entity scheduler retired while opening Window");
    }
}
