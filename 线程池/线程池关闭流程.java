/**
 * 线程池的关闭有两种方法:shutdown()和shutdownNow()
 * shutdown():线程池状态将从RUNNING转变成SHUTDOWN,不再接受新的任务,打断所有工作线程,由于线程池SHUTDOWN状态,其实只能打断空闲工作线程并销毁;
 *            正在执行的任务看业务代码中有没有响应打断来决定是不是正常执行,工作队列中剩余的任务还是会执行完,线程池将会关闭
 * shutdownNow():线程池状态将从RUNNING转变成STOP,不再接受新的任务,打断所有工作线程并销毁;
 *               正在执行的任务看业务代码中有没有响应打断来决定是不是正常执行,工作队列中剩余的任务会被抛弃,线程池将会关闭
 */

public void shutdown() {
    final ReentrantLock mainLock = this.mainLock;
    // 加锁,同时只能有一个线程执行关闭方法
    mainLock.lock();
    try {
        // 权限验证?
        checkShutdownAccess();
        // 通过死循环CAS操作将线程池状态修改成SHUTDOWN
        advanceRunState(SHUTDOWN);
        // 打断所有的线程,其实只能打断空闲的工作线程,核心线程将在tryTerminate()中销毁
        interruptIdleWorkers();
        // 钩子方法
        onShutdown();
    } finally {
        mainLock.unlock();
    }
    // 尝试关闭线程池
    tryTerminate();
}

public List<Runnable> shutdownNow() {
    List<Runnable> tasks;
    final ReentrantLock mainLock = this.mainLock;
    mainLock.lock();
    try {
        // 权限验证?
        checkShutdownAccess();
        // 通过死循环CAS操作将线程池状态修改成STOP,STOP状态下,从工作队列中获取任务的方法getTask(),将返回null
        advanceRunState(STOP);
        // 打断所有的线程,这些线程随后将被销毁
        interruptWorkers();
        // 移除工作队列中的全部任务
        tasks = drainQueue();
    } finally {
        mainLock.unlock();
    }
    // 尝试关闭线程池
    tryTerminate();
    //返回工作队列中被抛弃的任务
    return tasks;
}

/**
 * 尝试关闭线程池
 */
final void tryTerminate() {
    for (;;) {
        int c = ctl.get();
        if (isRunning(c) ||     // 判断当前线程池状态是否RUNNING,若true,直接返回,因为状态不能从RUNNING直接转换到TIDYING
            // runStateAtLeast(c, TIDYING)=true:表明当前线程池状态是TIDYING或者TERMINATED,由于TIDYING状态只能在此方法中设置,
            // 若状态为TIDYING,说明有另一个线程也正在关闭线程池,则当前线程不需再执行后续关闭线程池代码;若状态为TERMINATED,更不需要执行了,
            // 都是直接返回就行
            runStateAtLeast(c, TIDYING) ||
            // 当线程池状态为SHUTDOWN而且任务队列不为空,此时,线程池需要将队列中的任务执行完毕,才能关闭;
            // 也是直接返回,不执行后续的关闭线程池代码
            (runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty()))
            return;
        
        // 代码能执行到这里,说明当前线程池符合关闭的条件
        // 此时线程池有两种情况:
        //      情况1:线程池状态为STOP
        //      情况2:线程池状态为SHUTDOWN,且工作队列为空
        // workerCountOf(c) != 0为true:表明线程池关闭之前,还有工作线程需要被销毁
        if (workerCountOf(c) != 0) {
            // 注意ONLY_ONE=true:表示interruptIdleWorkers()方法此时只会打断一个工作线程,
            // 然后此被打断的线程会从getTask()中马上返回null(情况1和情况2),此时工作线程退回到runWorker()方法的栈帧中,
            // 由于getTask()返回的是null,所以会跳出循环,执行processWorkerExit()方法,又会执行到tryTerminate()方法,
            // 再次选出另一个工作线程打断,直到所有活着的工作线程都被打断了,其实就是一个打断的传播过程;
            // 其实代码能执行到这里,存活的工作线程肯定不多了,这么做,是为了防止关闭线程池引发的混乱
            interruptIdleWorkers(ONLY_ONE);
            return;
        }

        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // CAS操作将ctl修改为TIDYING+0
            if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                try {
                    // 空方法
                    terminated();
                } finally {
                    // CAS操作将ctl修改为TERMINATED+0
                    ctl.set(ctlOf(TERMINATED, 0));
                    // 唤醒正在条件队列中等待线程池关闭信号的线程,进入同步队列尝试获取mainLock执行
                    // 其实就是唤醒调用了awaitTermination()方法的线程,此方法中调用了termination.awaitNanos(nanos)
                    termination.signalAll();
                }
                return;
            }
        } finally {
            mainLock.unlock();
        }
    }
}