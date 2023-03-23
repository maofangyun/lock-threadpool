package com.htxy;

import java.lang.reflect.Field;

public class ThreadLocalDemo {

    public static void main(String[] args) throws NoSuchFieldException, IllegalAccessException, InterruptedException {
        Thread t = new Thread(()->test("abc",false));
        t.start();
        t.join();
        System.out.println("--gc后--");
        Thread t2 = new Thread(() -> test("def", true));
        t2.start();
        t2.join();
    }

    /**
    *   问：为什么ThreadLocal中ThreadLocalMap的key要使用弱引用？
    *   答：假设key不使用弱引用，而是使用普通的强引用，设想这样一种情况，在某一个方法执行完毕之后，将变量threadLocal置为null，
    *       线程中栈帧的局部变量表不再指向threadLocal实例，但是由于Thread中的属性ThreadLocalMap中的key指向的也是threadLocal实例，
    *       而且是强引用，导致了代码中即使显式的将变量threadLocal置为null，下次GC时，threadLocal实例由于有Thread中的属性ThreadLocalMap
    *       引用的原因，也不能回收，导致内存泄漏。    
    *
    *   问：为什么ThreadLocal中ThreadLocalMap的key使用了弱引用，还是会出现内存泄漏的问题？
    *   答：即使key使用了弱引用，当代码中显式的将变量threadLocal置为null，GC时可以正常的将threadLocal实例回收掉，但是key对应的value却是
    *       强引用，还是不会被回收(即存在key为null，但value却有值的无效Entry)，如果放任不管的话，将和线程的生命周期一样长，导致内存泄漏，
    *       尤其是各种线程池导致线程复用的情况下，内存泄漏的问题更容易出现。
    *
    *   解决办法：在每次调用ThreadLocal的get()、set()、remove()方法时都会执行expungeStaleEntry()方法，该方法会检测整个Entry[]表中
    *            对key为null的Entry一并擦除，重新调整索引，即ThreadLocal内部已经帮我们做了对key为null的Entry的清理工作。
    *   最佳实践：在ThreadLocal使用前后都调用remove()方法清理，同时对异常情况也要在finally中清理。        
    */
    private static void test(String s,boolean isGC)  {
        try {
            // 这里创建的ThreadLocal并没有指向任何值，也就是没有任何引用,这里在GC之后，key就会被回收
            // 如果是ThreadLocal<Object> threadLocal =new ThreadLocal<>(),则GC之后，key不会被回收
            new ThreadLocal<>().set(s);
            if (isGC) {
                System.gc();
            }
            Thread t = Thread.currentThread();
            Class<? extends Thread> clz = t.getClass();
            Field field = clz.getDeclaredField("threadLocals");
            field.setAccessible(true);
            Object ThreadLocalMap = field.get(t);
            Class<?> tlmClass = ThreadLocalMap.getClass();
            Field tableField = tlmClass.getDeclaredField("table");
            tableField.setAccessible(true);
            Object[] arr = (Object[]) tableField.get(ThreadLocalMap);
            for (Object o : arr) {
                if (o != null) {
                    Class<?> entryClass = o.getClass();
                    Field valueField = entryClass.getDeclaredField("value");
                    Field referenceField = entryClass.getSuperclass().getSuperclass().getDeclaredField("referent");
                    valueField.setAccessible(true);
                    referenceField.setAccessible(true);
                    System.out.println(String.format("弱引用key:%s,值:%s", referenceField.get(o), valueField.get(o)));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

