/**
 * 读锁尝试解锁流程
 */
protected final boolean tryReleaseShared(int unused) {
    Thread current = Thread.currentThread();
    // 判断当前线程是否第一个加读锁的线程
    if (firstReader == current) {
        // 第一个加读锁的线程是否存在重入
        if (firstReaderHoldCount == 1)
            firstReader = null;
        else
            firstReaderHoldCount--;
    } else {
        // 取出最后一个加读锁的线程
        HoldCounter rh = cachedHoldCounter;
        // rh == null为true:表示目前只有一个线程加了读锁(并发量高的情况下,一定是false)
        // rh.tid != getThreadId(current)为true:表示最后一个加读锁的线程不是当前线程
        if (rh == null || rh.tid != getThreadId(current))
            // 从ThreadLocal中取出锁计数信息HoldCounter
            rh = readHolds.get();
        int count = rh.count;
        // count <= 1为true:表示非重入
        if (count <= 1) {
            // 移除ThreadLocal中的锁计数信息
            readHolds.remove();
            if (count <= 0)
                throw unmatchedUnlockException();
        }
        // 锁计数次数减1
        --rh.count;
    }
    // 死循环进行CAS操作修改读锁资源(释放读锁)
    for (;;) {
        int c = getState();
        int nextc = c - SHARED_UNIT;
        if (compareAndSetState(c, nextc))
            // 返回读锁资源是否为零
            return nextc == 0;
    }
}