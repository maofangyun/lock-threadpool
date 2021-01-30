public final void signal() {
    // 判断当前线程是否占有锁
    if (!isHeldExclusively())
        throw new IllegalMonitorStateException();
    // 获取条件队列的头节点
    Node first = firstWaiter;
    if (first != null)
        // 唤醒条件队列中的头节点
        doSignal(first);
}

private void doSignal(Node first) {
    do {
        // 若这个判断为true:说明头节点的后继节点为null,那么将头节点移到同步队列之后,这个队列就空了,需要将头尾节点置为null,
        // 这里头节点不会被置为null,在await()的addConditionWaiter()中,会判断lastWaiter == null,直接替换头节点
        if ( (firstWaiter = first.nextWaiter) == null)
            // 将尾节点置为null
            lastWaiter = null;
        // 将头节点的后继节点置为null,即该node从条件队列中移除
        first.nextWaiter = null;
    } while (!transferForSignal(first) &&   // 将node从同步队列中移到条件队列
             (first = firstWaiter) != null);
}

final boolean transferForSignal(Node node) {
    // CAS操作,将node的状态改成0(默认状态)
    if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
        return false;
    // 将node添加到同步队列的尾部,并返回原尾节点
    Node p = enq(node);
    int ws = p.waitStatus;
    // 若该node的前置节点waitStatus是CANCELLED或者CAS修改前置节点状态为SIGNAL失败,
    // 总而言之,就是同步队列中的前置节点无法唤醒该node,那么直接unpark该线程,去竞争锁资源
    if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
        LockSupport.unpark(node.thread);
    return true;
}