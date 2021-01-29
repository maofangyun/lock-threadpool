private Runnable getTask() {
    // 是否有超时时间
    boolean timedOut = false;

    // 死循环
    for (;;) {
        int c = ctl.get();
        // 获取线程池状态
        int rs = runStateOf(c);

        // 情况1:线程池状态大于SHUTDOWN,即STOP,TIDYING和TERMINATED状态,线程池已经不会处理任何任务,
        //      将工作线程数减1,并返回null,导致runWorker()会跳出循环,执行退出流程processWorkerExit(),从workers集合中移除当前的worker
        // 情况2:线程池状态等于SHUTDOWN,且工作队列为空,由于SHUTDOWN状态的线程池不会接收新的任务,只会在处理完工作队列中的任务,就切换到STOP状态
        //      所以,当工作队列为空时,处理流程和情况1相同
        // 情况3:线程池状态等于SHUTDOWN,且工作队列不为空,跳过;这个是shutdown()明明打断了所有的工作线程,但是只有空闲线程会被销毁的原因,结合timed变量和poll()方法理解
        if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {
            decrementWorkerCount();
            return null;
        }

        // 获取工作线程的数量
        int wc = workerCountOf(c);
        // 当允许核心线程超时销毁或者当前工作线程数大于核心线程数,timed=true
        // timed=true的意思:当前线程若从工作队列中获取任务超时,可以被销毁回收
        boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

        // 情况1:工作线程数大于最大线程数,需要减少工作线程数,故CAS操作将工作线程数减1,返回null
        // 情况2:(timed && timedOut)=true且工作线程数大于1或者工作队列为空(这个绝大多数情况都是true),
        //      CAS操作将工作线程数减1,返回null(timedOut=true的意义后面说明);
        //      情况2意思是,当前工作线程已经被标记为销毁,但是,当工作队列还有任务为处理,且当前工作线程数只有一个或者没有,
        //      则此线程不会被销毁(毕竟就没人干活,不能再开除了),其余的情形都会被销毁
        if ((wc > maximumPoolSize || (timed && timedOut))
            && (wc > 1 || workQueue.isEmpty())) {
            if (compareAndDecrementWorkerCount(c))
                return null;
            continue;
        }

        try {
            // timed=true:将调用poll()阻塞获取任务,超时将返回null
            // timed=false:将调用take()阻塞获取任务,不会超时,直到获取到任务才会返回
            // 注意点:poll()和take()方法都能响应打断
            Runnable r = timed ?
                workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :
                workQueue.take();
            // r = null的情况,只有在poll()获取任务超时才会发生
            if (r != null)
                return r;
            // 能执行到这里,说明当前工作线程获取任务超时了,设置timedOut=true,准备销毁
            timedOut = true;
        } catch (InterruptedException retry) {
            // 若当前线程获取任务的时候,被打断了,直接进入下一轮的循环,不会响应打断
            timedOut = false;
        }
    }
}
