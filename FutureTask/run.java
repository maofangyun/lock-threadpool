/**
 * 由于FutureTask实现了Runnable接口,当任务被线程池中的线程选中执行时,调用的是FutureTask的run()方法,
 * 注意callable参数,属于构造入参,在实例化一个FutureTask对象时,就会完成赋值
 */
public void run() {
    // 若当前任务不是初始状态NEW或者CAS操作修改runner指向执行任务的当前线程失败,则直接返回
    if (state != NEW ||
        !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                     null, Thread.currentThread()))
        return;
    try {
        Callable<V> c = callable;
        // 再次判断状态是不是NEW,防止中途状态被改变了
        if (c != null && state == NEW) {
            V result;
            boolean ran;
            try {
                // 执行任务,获取返回值
                result = c.call();
                ran = true;
            } catch (Throwable ex) {
                // 发生异常,将结果置为null
                result = null;
                ran = false;
                // CAS操作设置任务异常状态
                setException(ex);
            }
            if (ran)
                // CAS操作设置任务正常完成状态
                set(result);
        }
    } finally {
        runner = null;
        int s = state;
        // s >= INTERRUPTING:遇到任务被取消打断的情况
        if (s >= INTERRUPTING)
            // 一直自旋等待任务状态尽快转变成INTERRUPTED
            handlePossibleCancellationInterrupt(s);
    }
}


protected void setException(Throwable t) {
    // 先CAS操作将任务的状态设置成临界状态COMPLETING
    if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
        outcome = t;
        // CAS操作将任务的状态设置成EXCEPTIONAL
        UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL);
        // 唤醒所有等待执行结果的线程
        finishCompletion();
    }
}


protected void set(V v) {
    // 先CAS操作将任务的状态设置成临界状态COMPLETING
    if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
        outcome = v;
        // CAS操作将任务的状态设置成NORMAL
        UNSAFE.putOrderedInt(this, stateOffset, NORMAL);
        // 唤醒所有等待执行结果的线程
        finishCompletion();
    }
}


private void finishCompletion() {
    // 判断栈顶的node是不是null,然后进入循环
    for (WaitNode q; (q = waiters) != null;) {
        // 通过CAS操作将栈顶node设置为null
        if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
            // 死循环遍历栈空间的node,唤醒所有阻塞等待执行结果的线程,任务执行完毕已经有结果,可以去获取返回值了
            for (;;) {
                Thread t = q.thread;
                if (t != null) {
                    q.thread = null;
                    // 唤醒线程
                    LockSupport.unpark(t);
                }
                WaitNode next = q.next;
                // 栈空间已经没有node了,直接跳出循环
                if (next == null)
                    break;
                // 唤醒的线程对应node移除栈,帮助gc
                q.next = null;
                q = next;
            }
            break;
        }
    }
    // 空方法
    done();
    // 已经执行完的任务,置为null
    callable = null;
}


private void handlePossibleCancellationInterrupt(int s) {
    // 当任务状态是INTERRUPTING时,一直自旋让出CPU资源等待任务状态转变成INTERRUPTED
    if (s == INTERRUPTING)
        while (state == INTERRUPTING)
            Thread.yield(); // wait out pending interrupt
}