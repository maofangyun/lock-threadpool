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