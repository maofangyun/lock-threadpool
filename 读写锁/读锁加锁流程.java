/**
 * 尝试加读锁的流程
 */
protected final int tryAcquireShared(int unused) {
    Thread current = Thread.currentThread();
    // 获取锁资源的数值
    int c = getState();
    // 若exclusiveCount(c) != 0为true:表示写锁资源不为空,有线程持有写锁
    // 若getExclusiveOwnerThread() != current为true:表示不存在锁降级(写->读)
    // 当两项都为true:表示出现了读写锁,由于读写互斥,加读锁失败,直接返回-1
    if (exclusiveCount(c) != 0 &&
        getExclusiveOwnerThread() != current)
        return -1;
    // 获取读锁的重入次数
    int r = sharedCount(c);
    // readerShouldBlock():判断当前环境,线程是否可以加读锁,true表示不适合,false表示适合
    // readerShouldBlock()的实现有两种,公平锁和非公平锁
    //      1.公平锁:调用hasQueuedPredecessors(),判断是否队列中是否有线程排队,若有线程排队,返回true,否则,返回false
    //      2.非公平锁:调用apparentlyFirstQueuedIsExclusive(),判断队列中的第一个线程是以独占模式获取锁(即准备获取写锁)
    //                若为true:表示即将唤醒队列中需要加写锁的线程,此时即使是非公平锁,此时加读锁线程也不要抢锁,否则会造成写线程的饥饿
    //                若为false:表示即将唤醒队列中Node不是加写锁的线程,可以抢锁
    if (!readerShouldBlock() &&
        // 判断读锁是否超出了最大数值的限制
        r < MAX_COUNT &&
        // 执行CAS操作修改高16位的读锁状态
        compareAndSetState(c, c + SHARED_UNIT)) {
        // 注意,能进入这段代码,表示加读锁已经成功,接下来,是进行锁计数的操作
        // 此时的读锁资源由于上一步的CAS操作已经不为零,r表示的是CAS操作之前的读锁资源
        if (r == 0) {
            // 保存首次获取读锁的线程,及其读锁重入次数
            firstReader = current;
            firstReaderHoldCount = 1;
        } else if (firstReader == current) {    // 判断是否重入
            // 记录读锁的重入次数
            firstReaderHoldCount++;
        } else {
            // cachedHoldCounter是最后获取锁的线程的读锁计数器(HoldCounter类封装了线程ThreadID和重入次数)
            HoldCounter rh = cachedHoldCounter;
            // 对于第二次加读锁线程,rh == null为true,
            // 第n次的加读锁线程(非重入情况),rh.tid != getThreadId(current)为true,
            // 都会进入下面的代码
            if (rh == null || rh.tid != getThreadId(current))
                // readHolds:保存每一个加读锁线程对应的HoldCounter信息
                // readHolds是ThreadLocalHoldCounter类型,继承了ThreadLocal,并重写了initialValue()方法,
                // 所以readHolds.get()在线程第一次加读锁时,会返回new HoldCounter(),重入的情况才会返回线程对象中保存的对应HoldCounter
                // 当前线程自然是最后加读锁的线程(即使是重入的情况),故将当前线程的HoldCounter赋给cachedHoldCounter
                cachedHoldCounter = rh = readHolds.get();
            // rh.count == 0为true,表示当前线程非重入,将对应的HoldCounter信息保存到readHolds中
            else if (rh.count == 0)
                readHolds.set(rh);
            // 将当前线程的读锁重入次数加1
            rh.count++;
        }
        return 1;
    }
    // 首次获取读锁失败后,重试获取
    return fullTryAcquireShared(current);
}

public final boolean hasQueuedPredecessors() {
    Node t = tail;
    Node h = head;
    Node s;
    return h != t &&
        ((s = h.next) == null || s.thread != Thread.currentThread());
}

final boolean apparentlyFirstQueuedIsExclusive() {
    Node h, s;
    return (h = head) != null &&
        (s = h.next)  != null &&
        !s.isShared()         &&
        s.thread != null;
}

final int fullTryAcquireShared(Thread current) {

    HoldCounter rh = null;
    // 死循环,当前线程要么加读锁成功,要么加读锁失败,否则将一直循环
    // 对于重入的情况,会一直尝试加读锁,不成功不会跳出循环
    // 对于首次加读锁的情况,在第一轮循环中,就有可能触发return,跳出循环
    for (;;) {
        // 获取锁资源的数值
        int c = getState();
        // 若exclusiveCount(c) != 0为true:表示写锁资源不为空,有线程持有写锁
        if (exclusiveCount(c) != 0) {
            // 若getExclusiveOwnerThread() != current为true:表示不存在锁降级(写->读),出现了读写锁,由于读写互斥,加读锁失败,直接返回-1
            if (getExclusiveOwnerThread() != current)
                return -1;
        } else if (readerShouldBlock()) {   // 判断当前环境,线程是否可以加读锁,true表示不适合,false表示适合
            // 进入了这段代码,对于首次加读锁的线程,表示加读锁失败了,会返回-1
            if (firstReader == current) {
            } else {
                if (rh == null) {
                    rh = cachedHoldCounter;
                    if (rh == null || rh.tid != getThreadId(current)) {
                        rh = readHolds.get();
                        // 当前线程没有获取到读锁,从readHolds中移除HoldCounter锁记录,
                        // 什么情况下rh.count != 0,当前线程加读锁是重入的情况
                        if (rh.count == 0)
                            readHolds.remove();
                    }
                }
                if (rh.count == 0)
                    return -1;
            }
        }
        // 判断读锁资源是否达到了最大数值限制
        if (sharedCount(c) == MAX_COUNT)
            throw new Error("Maximum lock count exceeded");
        // 执行CAS操作修改高16位的读锁状态
        if (compareAndSetState(c, c + SHARED_UNIT)) {
            // 判断读锁资源是否为零,即当前线程是否为第一个加读锁的线程
            if (sharedCount(c) == 0) {
                firstReader = current;
                firstReaderHoldCount = 1;
            } else if (firstReader == current) {    // 判断当前线程是否是第一个已经加读锁的线程
                // 重入次数加1
                firstReaderHoldCount++;
            } else {
                if (rh == null)
                    rh = cachedHoldCounter;
                if (rh == null || rh.tid != getThreadId(current))
                    rh = readHolds.get();
                else if (rh.count == 0)
                    readHolds.set(rh);
                rh.count++;
                cachedHoldCounter = rh; // cache for release
            }
            return 1;
        }
    }
}


/**
 * 读锁尝试加锁失败之后的流程
 */
private void doAcquireShared(int arg) {
    // 将当前线程包装成Node(共享模式)加入同步队列
    final Node node = addWaiter(Node.SHARED);
    boolean failed = true;
    try {
        boolean interrupted = false;
        for (;;) {
            final Node p = node.predecessor();
            if (p == head) {
                // 若前置节点是头节点,再次尝试加读锁
                int r = tryAcquireShared(arg);
                // r基本只有1和-1两个值,特殊情况下(读锁溢出),可能是0
                if (r >= 0) {
                    // 加读锁成功,将当前node设置成头节点,
                    setHeadAndPropagate(node, r);
                    p.next = null; // help GC
                    if (interrupted)
                        selfInterrupt();
                    failed = false;
                    return;
                }
            }
            // shouldParkAfterFailedAcquire(p, node)作用:将前一个节点的状态改成SIGNAL,这样前置节点解锁时,将唤醒后继节点
            // parkAndCheckInterrupt()作用:阻塞当前线程
            if (shouldParkAfterFailedAcquire(p, node) &&
                parkAndCheckInterrupt())
                interrupted = true;
        }
    } finally {
        if (failed)
            cancelAcquire(node);
    }
}


private void setHeadAndPropagate(Node node, int propagate) {
    Node h = head;
    // 设置新的头节点,
    setHead(node);
    if (propagate > 0 || h == null || h.waitStatus < 0 ||
        (h = head) == null || h.waitStatus < 0) {
        Node s = node.next;
        if (s == null || s.isShared())
            // 唤醒同步队列中的头节点的后继节点线程
            doReleaseShared();
    }
}

private void doReleaseShared() {
    for (;;) {
      // 从头节点开始执行唤醒操作
      // 这里需要注意,如果从setHeadAndPropagate方法调用该方法,那么这里的head是新的头节点
      Node h = head;
      if (h != null && h != tail) {
        int ws = h.waitStatus;
        // 表示后继节点需要被唤醒
        if (ws == Node.SIGNAL) {
          // 初始化头节点状态
          // 这里需要CAS原子操作,因为setHeadAndPropagate和releaseShared这两个方法都会顶用doReleaseShared,避免多次unpark唤醒操作
          if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
            // 如果初始化头节点状态失败,继续循环执行
            continue;
          // 执行唤醒操作
          unparkSuccessor(h);
        }
        // 很少的情况会走到下面的代码,只有当头节点的waitStatus还没有被后继节点调用shouldParkAfterFailedAcquire()改成SIGNAL状态,才可能出现ws == 0为true
        // 如果后继节点暂时不需要唤醒,那么当前头节点状态更新为PROPAGATE,确保后续可以传递给后继节点
        else if (ws == 0 &&
                 !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
          continue;              
      }
      // 如果在唤醒的过程中头节点没有更改,退出循环
      // 这里防止其它线程又设置了头节点,说明其它线程获取了共享锁,会继续循环操作
      if (h == head)                 
        break;
    }
  }

