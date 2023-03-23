/**
 * 向线程池提交任务
 */
public void execute(Runnable command) {
    if (command == null)
        throw new NullPointerException();
    // clt的高3位表示线程状态,低29位表示工作线程数
    int c = ctl.get();
    // workerCountOf(c)作用:得到当前线程池的线程数
    // 若工作线程数小于核心线程数,则创建新的线程来执行任务
    if (workerCountOf(c) < corePoolSize) {
        // 创建一个新的工作线程来执行任务
        // 注意:这里不需要判断线程池的状态,addWorker()方法里面会判定
        if (addWorker(command,true))
            return;
        c = ctl.get();
    }
    // isRunning():判断线程池状态是不是RUNNING
    // workQueue.offer():任务添加到队列中,成功返回true,失败返回fasle
    if (isRunning(c) && workQueue.offer(command)) {
        // 能进入这里,表示任务已经添加到了工作队列中
        int recheck = ctl.get();
        // 再次判断线程池是否RUNNING,防止此时线程池被关闭
        // 若线程池不处于RUNNING状态,则从工作队列中移除刚入队的任务,同时尝试将线程池状态改为TERMINATED,真正的关闭
        if (! isRunning(recheck) && remove(command))
            // 调用线程池的拒绝策略来处理此任务
            reject(command);
        // 这里是一个很有意思的点:如果设置线程池的核心线程数为0,工作队列为无界队列,那么,正常来说,队列中的任务,应该永远也不会执行,因为没有工作线程被创建出来
        // 但是,因为这里有了workerCountOf(recheck) == 0的判断,即使工作队列没有满,仍然会创建一个工作线程来执行工作队列中的任务
        else if (workerCountOf(recheck) == 0)
            addWorker(null,false);
    }
    // 能进入这里,有两种情况:
    //      1. 线程池不处于RUNNING状态
    //      2. 线程池处于RUNNING状态,但是工作队列满了
    // 针对情况1:由于addWorker()会判断线程池状态,这里会创建工作线程失败,直接返回false,然后调用拒绝策略;
    //          小概率情况--线程池是SHUTDOWN状态,firstTask(command)为null,工作队列不为空,可能会创建工作线程成功
    // 针对情况2:创建新的工作线程来当前的任务,若当前工作线程数大于指定的最大工作线程数,也会返回false,然后调用拒绝策略;
    else if (!addWorker(command,false))
        reject(command);
}

/**
 * 增加工作线程
 */
private boolean addWorker(Runnable firstTask,boolean core) {
    retry:
    // 死循环:判断线程池状态是否适合新建工作线程
    for (;;) {
        int c = ctl.get();
        // 获取线程池运行状态
        int rs = runStateOf(c);
        // 线程池状态不是RUNNING(-1),其他状态都大于SHUTDOWN(0),拒绝创建新的线程执行任务
        if (rs >= SHUTDOWN &&
            ! (rs == SHUTDOWN &&
               firstTask == null &&
               ! workQueue.isEmpty()))
            return false;
        // 死循环:CAS修改工作线程数
        for (;;) {
            // 获取工作线程数
            int wc = workerCountOf(c);
            // 若工作线程数大于最大线程容量(536870911),创建新线程失败,返回fasle
            // 若工作线程数大于约束,创建新线程失败,返回fasle
            // 若core=true,表示使用核心线程数做约束,core=false,表示使用最大线程数做约束
            if (wc >= CAPACITY ||
                wc >= (core ? corePoolSize : maximumPoolSize))
                return false;
            // CAS操作修改增加工作线程数
            if (compareAndIncrementWorkerCount(c))
                // 成功则跳出循环
                break retry;
            c = ctl.get();
            // true:表示当前的运行状态不等于rs,说明状态已被改变,回到外层循环重新来
            // false:运行状态未改变,继续内层循环CAS修改工作线程数
            if (runStateOf(c) != rs)
                continue retry;
        }
    }

    boolean workerStarted = false;
    boolean workerAdded = false;
    Worker w = null;
    try {
        // 根据传入的任务,创建工作者对象
        w = new Worker(firstTask);
        // 根据工作者对象创建工作线程
        final Thread t = w.thread;
        if (t != null) {
            final ReentrantLock mainLock = this.mainLock;
            // 加锁,防止workers.add(w)操作产生并发安全问题
            mainLock.lock();
            try {
                // 获取线程池状态
                int rs = runStateOf(ctl.get());
                // 判断线程池状态是否处于RUNNING
                if (rs < SHUTDOWN ||
                    (rs == SHUTDOWN && firstTask == null)) {
                    // 判断工作线程是否提前启动了
                    if (t.isAlive())
                        throw new IllegalThreadStateException();
                    // 将新建的工作者对象加入到workers中,workers包含线程池中的所有工作线程
                    workers.add(w);
                    int s = workers.size();
                    if (s > largestPoolSize)
                        largestPoolSize = s;
                    // 标识新建工作线程成功
                    workerAdded = true;
                }
            } finally {
                // 解锁
                mainLock.unlock();
            }
            if (workerAdded) {
                // 启动线程
                // 由于t指向w.thread所引用的对象,而w是Runnable的实现类,工作线程t是以w作为Runnable参数所创建的一个线程对象,
                // 所以启动t,也就是要执行w的run()方法,最终调用runWorker()
                t.start();
                // 标识工作线程启动成功
                workerStarted = true;
            }
        }
    } finally {
        if (! workerStarted)
            // 工作线程启动失败,将CAS操作减少工作线程数,移除workers中的w
            addWorkerFailed(w);
    }
    // 返回线程启动成功标识
    return workerStarted;
}