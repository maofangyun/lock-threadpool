/**
 * 尝试加写锁的流程
 */
protected final boolean tryAcquire(int acquires) {
    Thread current = Thread.currentThread();
    // 获取锁资源的数值(读+写)
    int c = getState();
    // 获取写锁的重入次数
    int w = exclusiveCount(c);
    // 锁资源不为零,说明有线程加了锁(读锁或者写锁)
    if (c != 0) {
        // 若w==0为true:表示没有线程持有写锁,但是此时c又不为零,说明只能是读锁,由于读写互斥,因此加写锁失败,直接返回false;
        // 若w==0为false:表示有线程持有写锁,是否有读锁未知;由于读锁不会修改exclusiveOwnerThread的值,接下来判断目前的线程是否重入;
        // 若current != getExclusiveOwnerThread()为false,表示写锁的重入,执行后续代码并加写锁成功,返回true,
        // 若current != getExclusiveOwnerThread()为true,表示出现了写锁竞争,由于写写互斥,因此加写锁失败,直接返回false
        if (w == 0 || current != getExclusiveOwnerThread())
            return false;
        // 能执行到这里,表示当前线程是重入的
        // 判断写锁重入次数是否超过了最大的次数限制
        if (w + exclusiveCount(acquires) > MAX_COUNT)
            throw new Error("Maximum lock count exceeded");
        // 记录写锁重入次数
        setState(c + acquires);
        return true;
    }
    // 代码能执行到这里,表示锁资源为零;
    // writerShouldBlock():判断是否需要排队
    //      1. 非公平锁直接返回false,不管队列中是否有线程排队等待锁资源,准备抢锁
    //      2. 公平锁执行hasQueuedPredecessors(),判断是否队列中是否有线程排队,若有线程排队,返回true,否则,返回false
    // 若writerShouldBlock()为true,加写锁失败,直接返回false;
    // 若writerShouldBlock()为false,执行CAS操作修改锁资源
    if (writerShouldBlock() ||
        !compareAndSetState(c, c + acquires))
        return false;
    // 修改exclusiveOwnerThread的值
    setExclusiveOwnerThread(current);
    return true;
}

public final boolean hasQueuedPredecessors() {
    Node t = tail;
    Node h = head;
    Node s;
    return h != t &&
        ((s = h.next) == null || s.thread != Thread.currentThread());
}