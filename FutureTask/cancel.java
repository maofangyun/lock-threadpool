public boolean cancel(boolean mayInterruptIfRunning) {
    // 若任务状态不为NEW或者CAS操作修改任务状态失败,直接返回false,取消任务失败
    if (!(state == NEW &&
          UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
              mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
        return false;
    try {  // 防止打断操作抛出了异常,所以用try
        // 当允许运行时打断时
        if (mayInterruptIfRunning) {
            try {
                Thread t = runner;
                if (t != null)
                    // 打断线程
                    t.interrupt();
            } finally {
                // CAS操作修改任务状态为INTERRUPTED
                UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
            }
        }
    } finally {
        // 唤醒所有等待执行结果的线程
        finishCompletion();
    }
    // 取消任务成功
    return true;
}
