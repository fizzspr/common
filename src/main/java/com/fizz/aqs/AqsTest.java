package com.fizz.aqs;

import java.util.concurrent.locks.ReentrantLock;

public class AqsTest {

    public static void main(String[] args) throws Exception {
        ReentrantLock lock = new ReentrantLock(true);
        Thread t1 = new Thread(() -> {
            lock.lock();
            try {
                System.out.println("t1 获取到了锁，持有 10 秒方便观察等待队列");
                // 持锁期间 t2、t3 会阻塞在 AQS 的等待队列中
                Thread.sleep(600_000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
                System.out.println("t1 释放了锁");
            }
        }, "t1");
        t1.start();
        // 等 t1 成功持锁后再启动 t2、t3，保证它们阻塞入队而不是直接拿锁
        Thread.sleep(1000);

        Thread t2 = new Thread(() -> {
            lock.lock();
            try {
                System.out.println("t2 获取到了锁");
            } finally {
                lock.unlock();
            }
        }, "t2");
        t2.start();

        Thread t3 = new Thread(() -> {
            lock.lock();
            try {
                System.out.println("t3 获取到了锁");
            } finally {
                lock.unlock();
            }
        }, "t3");
        t3.start();
    }
}
