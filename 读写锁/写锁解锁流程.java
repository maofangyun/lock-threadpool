/**
 * 写锁尝试解锁
 */
protected final boolean tryRelease(int releases) {
    // 判断当前线程是否持有写锁
    if (!isHeldExclusively())
        throw new IllegalMonitorStateException();
    // 计算当前写锁释放之后的锁资源
    int nextc = getState() - releases;
    // 计算当前写锁释放之后,写锁资源是否为零(针对写锁重入的情况)
    boolean free = exclusiveCount(nextc) == 0;
    // 如果写锁资源为零,将exclusiveOwnerThread的值设置为null
    if (free)
        setExclusiveOwnerThread(null);
    // 正式修改写锁资源
    setState(nextc);
    // 返回写锁资源是否为零
    return free;
}