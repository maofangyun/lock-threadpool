public final void await() throws InterruptedException {
    // 能响应打断
    if (Thread.interrupted())
        throw new InterruptedException();
    // 将此线程包装成一个node,添加到条件队列尾部
    Node node = addConditionWaiter();
    // 释放当前线程占有的所有锁资源(可能有锁重入的情况),返回占有的锁资源(重入次数)
    int savedState = fullyRelease(node);
    int interruptMode = 0;
    // 判断当前node是否还在同步队列中,此时node一般都不在同步队列了
    while (!isOnSyncQueue(node)) {
        // 阻塞当前的线程
        LockSupport.park(this);
        // 当前线程从阻塞状态醒来,有两种情况
        //      情况1:被signal()方法唤醒(两种方式,一是在signal()中直接unpark,二是调用signal()后,在同步队列中被unparkSuccessor()给unpark)
        //      情况2:被其他线程打断了
        // interruptMode=0:正常情形,没有被打断,而是被signal()方法唤醒
        // interruptMode=THROW_IE:被其他线程打断了,而且发生在signal()方法之前
        // interruptMode=REINTERRUPT:被其他线程打断了,而且发生在signal()方法之后
        // 不论是情况1还是情况2,只要被唤醒了,都会跳出循环
        if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
            break;
    }
    // acquireQueued(node, savedState):竞争锁资源,注意savedState是重入的次数,因为获取到锁之后,执行完业务代码,可能需要多次释放锁
    if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
        interruptMode = REINTERRUPT;
    // 这里判断若为true:说明打断发生在signal()方法之前,因为signal()方法中会将node.nextWaiter置为null,
    // 所以,此时条件队列中其实多余了一个node节点,而且这个node的状态肯定不是CONDITION,需要从条件队列中清理掉
    if (node.nextWaiter != null)
        // 清除条件队列中的状态不是CONDITION的节点
        unlinkCancelledWaiters();
    if (interruptMode != 0)
        // 当interruptMode=REINTERRUPT时,打断发生在signal()方法后,也算是正常的唤醒流程,所以不需要抛出打断异常,只需要重新将线程的打断标志置为true就行
        // 当interruptMode=THROW_IE时,打断发生在signal()方法前,属于唤醒的异常流程,因此需要抛出InterruptedException异常
        reportInterruptAfterWait(interruptMode);
}

private Node addConditionWaiter() {
    // 获取条件队列的尾节点
    Node t = lastWaiter;
    // 若尾节点的状态是CANCELLED,移除条件队列
    if (t != null && t.waitStatus != Node.CONDITION) {
        // 清除条件队列中的状态不是CONDITION的节点
        unlinkCancelledWaiters();
        // 再次获取尾节点
        t = lastWaiter;
    }
    // 将当前的线程包装成一个状态是CONDITION的Node
    Node node = new Node(Thread.currentThread(), Node.CONDITION);
    // 若t == null为true:表明当前条件队列没有一个节点
    if (t == null)
        // 头节点赋值
        firstWaiter = node;
    else
        // 前尾节点指向当前Node,形成链条
        t.nextWaiter = node;
    // 尾节点赋值
    lastWaiter = node;
    // 返回当前Node
    return node;
}

final int fullyRelease(Node node) {
    boolean failed = true;
    try {
        // 获取当前线程独占的锁资源(重入次数)
        int savedState = getState();
        // 释放锁资源,并唤醒头节点的后继节点准备竞争锁
        if (release(savedState)) {
            failed = false;
            return savedState;
        } else {
            throw new IllegalMonitorStateException();
        }
    } finally {
        if (failed)
            node.waitStatus = Node.CANCELLED;
    }
}


final boolean isOnSyncQueue(Node node) {
    // 若节点的状态是CONDITION或者前置节点是null,则该节点一定不在同步队列中;因为只有头节点的前置节点是null,而该node肯定不是头节点,因为头节点的thread也是null
    if (node.waitStatus == Node.CONDITION || node.prev == null)
        return false;
    // 若节点的后继节点不为null,肯定是在同步队列中
    if (node.next != null)
        return true;
    // 兜底的方法(很极端的情况会调用)
    return findNodeFromTail(node);
}

private boolean findNodeFromTail(Node node) {
    // 获取同步队列的队尾元素
    Node t = tail;
    //死循环,从队尾元素一直往前找,找到相等的节点就返回true,说明节点在队列中;若循环到node为null,说明前面已经没有节点可以找了,那就返回false
    for (;;) {
    for (;;) {
        if (t == node)
            return true;
        if (t == null)
            return false;
        t = t.prev;
    }
}

private int checkInterruptWhileWaiting(Node node) {
    // 线程被打断了,根据transferAfterCancelledWait(node)的返回值决定是THROW_IE还是REINTERRUPT,
    // 线程未被打断,返回0
    // Thread.interrupted()这里会重置线程的打断标志
    return Thread.interrupted() ?
        (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
        0;
}

final boolean transferAfterCancelledWait(Node node) {
    // 若为true:表示打断发生在signal()方法之前
    // 若为false:表示打断发生在signal()方法之后
    // 原因:signal()方法也会调用这个方法,将node的状态从CONDITION修改成0,若这里能修改成功,说明signal()并没有执行,或者打断执行的更早
    if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
        // 由于打断发生在signal()方法之前,那么即使signal()方法执行了,也会因为CAS修改node状态失败直接返回fasle,也无法让node进入同步队列,
        // 因此这里需要调用enq(node),让node进入同步队列
        enq(node);
        return true;
    }

    // 代码能执行到这里,说明打断发生在signal()方法之后,有一种可能,signal()方法仍然在执行,但是还没有将node加入同步队列,
    // 所以,需要一直判断node到底是不是在同步队列中,要是不在,调用Thread.yield(),让出CPU资源,给signal()方法继续执行,
    // 直到signal()方法将node加入同步队列加入同步队列为止
    while (!isOnSyncQueue(node))
        Thread.yield();
    return false;
}

private void reportInterruptAfterWait(int interruptMode)
throws InterruptedException {
// signal()之前的中断,需要抛出异常
if (interruptMode == THROW_IE)
    throw new InterruptedException();
// signal()之后发生的中断,需要重新中断
else if (interruptMode == REINTERRUPT)
    selfInterrupt();
}