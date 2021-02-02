/**
 * 首先要明确一点,get()方法可能会被多个线程同时调用,并且获取同一个线程的返回结果
 */
public V get() throws InterruptedException, ExecutionException {
    // private static final int NEW          = 0;
    // private static final int COMPLETING   = 1;
    // private static final int NORMAL       = 2;
    // private static final int EXCEPTIONAL  = 3;
    // private static final int CANCELLED    = 4;
    // private static final int INTERRUPTING = 5;
    // private static final int INTERRUPTED  = 6;
    // 这些就是FutureTask的全部状态,
    // NEW时初始状态;
    // COMPLETING是个临界状态,很快会转变成其他的状态;
    // NORMAL是正常执行完任务后的状态;
    // EXCEPTIONAL是执行任务发生异常后的状态;
    // CANCELLED是任务被取消后的状态,不过是在mayInterruptIfRunning=false的情况下
    // INTERRUPTING也是任务被取消后的临界状态,不过是在mayInterruptIfRunning=true的情况下
    // INTERRUPTED是由INTERRUPTING转变成的最终状态
    int s = state;
    // 任务状态未执行完成,才会陷入等待,否则直接返回结果
    if (s <= COMPLETING)
        // 阻塞等待
        s = awaitDone(false, 0L);
    // 顺利执行完返回结果值,发生异常,返回Exception对象
    return report(s);
}


private int awaitDone(boolean timed, long nanos) throws InterruptedException {
    // 设置await的超时时间
    final long deadline = timed ? System.nanoTime() + nanos : 0L;
    WaitNode q = null;
    boolean queued = false;
    // 死循环
    for (;;) {
        // 若该线程被打断,则从等待栈中移除该node,同时抛出打断异常
        if (Thread.interrupted()) {
            removeWaiter(q);
            throw new InterruptedException();
        }
        // 获取状态
        int s = state;
        // 若s > COMPLETING为true:表示任务已经执行完毕,不管是正常结束,异常,取消和被打断
        if (s > COMPLETING) {
            // 对于第一轮自旋,这里肯定是false,直接返回状态值,
            // 若为true,将q.thread置为null,是为了帮助GC
            if (q != null)
                q.thread = null;
            return s;
        }
        // COMPLETING其实任务已经执行结束了,只是还没有将状态改变,所以若当前状态为COMPLETING,
        // 调用Thread.yield()让出CPU资源,让状态尽快转变
        else if (s == COMPLETING)
            Thread.yield();
        // 第一次自旋能走到这里的,q肯定是null
        else if (q == null)
            // 创建一个当前线程的node
            q = new WaitNode();
        // 第一次自旋能走到这里的,queued肯定是false
        else if (!queued)
            // 将栈顶的node替换成q,同时将q的后继节点指向waiters(原栈顶node)
            queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                    q.next = waiters, q);
        // 若存在超时设置
        else if (timed) {
            // 计算还需要等待的时间
            nanos = deadline - System.nanoTime();
            // 若达到超时时间,则移除当前node,且返回任务状态
            if (nanos <= 0L) {
                removeWaiter(q);
                return state;
            }
            // 带等待时间的阻塞
            LockSupport.parkNanos(this, nanos);
        }
        else
            // 无等待时间的阻塞
            LockSupport.park(this);
    }
}


private V report(int s) throws ExecutionException {
    Object x = outcome;
    // 任务顺利执行完毕,返回结果值
    if (s == NORMAL)
        return (V)x;
    // 任务被取消或者打断,返回取消异常
    // 即使任务已经正常执行完成,没有任务异常,只要其他线程调用了cancel(),也会返回取消异常
    if (s >= CANCELLED)
        throw new CancellationException();
    // 任务执行异常,返回执行异常
    throw new ExecutionException((Throwable)x);
}
