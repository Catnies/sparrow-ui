package net.momirealms.sparrow.ui.exception;

/**
 * 表示 Window 打开期间查看者已无法继续接受实体线程任务.
 * 该异常用于中止尚未发布的打开事务, 调用方通常应将其转换为查看者不可用的打开结果.
 */
public final class ViewerUnavailableException extends IllegalStateException {

    public ViewerUnavailableException() {
        super("viewer entity scheduler retired while opening Window");
    }
}
