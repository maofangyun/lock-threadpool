/**
 * Worker类的runWorker方法,run()方法就是调用的它
 */
final void runWorker(Worker w) {
    Thread wt = Thread.currentThread();
    // firstTask表示创建工作线程时,加入的任务,会优先执行
    Runnable task = w.firstTask;
    w.firstTask = null;
    w.unlock(); // allow interrupts
    boolean completedAbruptly = true;
    try {
        // 第一次循环,task肯定不为null,不会从工作队列中取任务
        // 后面的循环,task=null,会通过getTask()从工作队列中取任务
        // 注意,工作队列当前若没有任务,则getTask()会阻塞
        while (task != null || (task = getTask()) != null) {
            // 加锁,防止任务在执行的时候,被其他线程中断
            w.lock();
            // 当线程池状态>=STOP且线程中断标志位为false时,将线程中断标志位设置为true
            if ((runStateAtLeast(ctl.get(), STOP) ||
                 (Thread.interrupted() &&   // Thread.interrupted()会擦除中断标志
                  runStateAtLeast(ctl.get(), STOP))) &&
                !wt.isInterrupted())    // isInterrupted()不会擦除中断标志
                wt.interrupt();     // 设置中断标志位设置为true
            try {
                // 扩展点,在任务执行之前
                beforeExecute(wt, task);
                Throwable thrown = null;
                try {
                    // 执行任务
                    task.run();
                } catch (RuntimeException x) {
                    thrown = x; throw x;
                } catch (Error x) {
                    thrown = x; throw x;
                } catch (Throwable x) {
                    thrown = x; throw new Error(x);
                } finally {
                    // 扩展点,在任务执行之后
                    afterExecute(task, thrown);
                }
            } finally {
                // 每次任务执行完之后,都置为null,让下次循环从工作队列中获取任务
                task = null;
                // 工作者w完成的任务加1
                w.completedTasks++;
                w.unlock();
            }
        }
        completedAbruptly = false;
    } finally {
        // 能执行到这里,表示此工作线程即将结束
        processWorkerExit(w, completedAbruptly);
    }
}

/**
 * 工作线程退出流程
 */
private void processWorkerExit(Worker w, boolean completedAbruptly) {
    // completedAbruptly=true:表示这个工作线程抛出了异常,导致completedAbruptly = false没有正常执行
    // completedAbruptly=false:表示工作线程正常结束
    // 为啥正常结束的,工作线程数不用减1 ? 正常退出,说明是由于getTask()方法返回null，线程数量减1已在getTask()方法返回之前处理
    if (completedAbruptly) 
        // 将工作线程数减1
        decrementWorkerCount();

    final ReentrantLock mainLock = this.mainLock;
    mainLock.lock();
    try {
        // 统计全部任务的完成数量
        completedTaskCount += w.completedTasks;
        // 从工作者集合中移除结束工作的工作者
        workers.remove(w);
    } finally {
        mainLock.unlock();
    }

    // 每一次工作线程退出,都会尝试关闭线程池
    tryTerminate();

    int c = ctl.get();
    // 判断线程池的状态,是否还能继续执行工作队列中的任务,即线程池状态是否处于RUNNING或者SHUTDOWN
    if (runStateLessThan(c, STOP)) {
        // 判断此工作线程是否正常结束的
        // 若completedAbruptly=true:表示异常结束,直接再创建一个工作线程,填补这个结束的工作线程
        // 若completedAbruptly=false:表示正常结束,若线程池的工作线程数小于最小线程数,也创建一个工作线程,提高工作队列中任务的执行效率
        if (!completedAbruptly) {
            int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
            if (min == 0 && ! workQueue.isEmpty())
                min = 1;
            if (workerCountOf(c) >= min)
                return;
        }
        // 创建一个工作线程,初始任务为null
        addWorker(null, false);
    }
}


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
            // 再次选出另一个工作线程打断,直到所有活着的工作线程都被打断了;其实就是一个打断的传播过程
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