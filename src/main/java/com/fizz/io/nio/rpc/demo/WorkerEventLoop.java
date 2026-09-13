package com.fizz.io.nio.rpc.demo;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class WorkerEventLoop implements Runnable {

    private final Selector selector;
    private Queue<SocketChannel> queue = new ConcurrentLinkedQueue<>();

    public WorkerEventLoop() {
        try {
            selector = Selector.open();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Thread thread = new Thread(this, "worker-event-loop");
        thread.setDaemon(true);
        thread.start(); // 只启动一次
    }

    public void register(SocketChannel socketChannel) {
        queue.add(socketChannel);
        selector.wakeup();
    }

    @Override
    public void run() {
        while (true) {
            int select;
            try {
                select = selector.select();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            while (true) {
                SocketChannel socketChannel = queue.poll();
                if (socketChannel == null) {
                    break;
                }
                try {
                    socketChannel.configureBlocking(false);
                    socketChannel.register(selector, SelectionKey.OP_READ);
                } catch (Exception e) {
                    log.error("register 失败", e);
                    try { socketChannel.close(); } catch (IOException ignored) { }
                }
            }

            if (select <= 0) {
                log.info("select <= 0");
                continue;
            }

            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectionKeys.iterator();
            log.info("select: {}, keySize: {}", select, selectionKeys.size());

            while (iterator.hasNext()) {
                try {
                    SelectionKey key = iterator.next();
                    log.info("key: {}", key);
                    iterator.remove();
                    log.info("key.isAcceptable() = {}, key.isReadable() = {}, key.isWritable() = {}", key.isAcceptable(), key.isReadable(), key.isWritable());
                    if (key.isReadable()) {
                        SocketChannel clientChannel = (SocketChannel) key.channel();
                        ChannelAttr attachment = (ChannelAttr) key.attachment();
                        if (attachment == null) {
                            attachment = new ChannelAttr(clientChannel, key);
                            key.attach(attachment);
                        }

                        ByteBuffer buffer = attachment.expandIfNeeded();
                        try {
                            int n = clientChannel.read(buffer);
                            log.info("read {} bytes, position: {}, limit: {}, capacity: {}", n, buffer.position(), buffer.limit(), buffer.capacity());
                            if (n == -1) {
                                clientChannel.close();
                            } else {
                                split(buffer, clientChannel, attachment);
                            }
                        } catch (IOException e) {
                            key.channel().close();
                            log.info("客户端[{}]异常关闭连接", clientChannel.socket().getPort(), e);
                        }
                    }
                    else if (key.isWritable()) {
                        ChannelAttr attr = (ChannelAttr) key.attachment();
                        SocketChannel clientChannel = attr.getSocketChannel();
                        // 先注销写事件，写不完时再按需重新注册，避免 select 空转
                        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);

                        try {
                            // 1. 先续写上次没写完的剩余数据
                            ByteBuffer pending = attr.getPendingWrite();
                            if (pending != null && writeFully(clientChannel, pending)) {
                                attr.setPendingWrite(null);
                            }

                            // 2. 没有堆积数据时，再从队列取新响应写
                            if (attr.getPendingWrite() == null) {
                                RpcResponse response;
                                while ((response = attr.getResponseQueue().poll()) != null) {
                                    byte[] bytes = (response.getUuid() + response.getResult().toString() + '\n').getBytes(UTF_8);
                                    ByteBuffer wrap = ByteBuffer.wrap(bytes);
                                    if (!writeFully(clientChannel, wrap)) {
                                        // 内核发送缓冲已满：保留剩余部分，等下次可写事件继续
                                        attr.setPendingWrite(wrap);
                                        break;
                                    }
                                }
                            }

                            // 3. 还有没写完的数据 → 重新注册写事件
                            if (attr.getPendingWrite() != null || !attr.getResponseQueue().isEmpty()) {
                                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                            }
                        } catch (IOException e) {
                            key.channel().close();
                            log.info("客户端[{}]异常关闭连接", clientChannel.socket().getPort(), e);
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 尽力把 buffer 写出去；true=已全部写完，false=内核发送缓冲已满，需要等下次可写事件
     */
    private boolean writeFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int n = channel.write(buffer);
            if (n == 0) {
                return false;
            }
        }
        return true;
    }

    private void split(ByteBuffer source, SocketChannel clientChannel, ChannelAttr attr) throws IOException {
        source.flip();
        int remaining = source.remaining();
        for (int i = 0; i < remaining; i++) {
            if (source.get(i) == '\n') {
                // i是下标，长度需要+1
                byte[] bytes = new byte[i + 1 - source.position()];
                source.get(bytes);
                log.info("收到客户端[{}]一条完整消息：{}", clientChannel.socket().getPort(), new String(bytes, UTF_8));

                doWork(bytes, attr);
            }
        }

        source.compact();
    }

    private void doWork(byte[] bytes, ChannelAttr attr) {
        String uuid = new String(bytes, 0, 36).trim();
        String interfaceName = new String(bytes, 36, 32).trim();
        String methodName = new String(bytes, 68, 16).trim();
        String param = new String(bytes, 84, 128).trim();

        new Thread(() -> {
            Map<String, Object> map = new HashMap<>();
            map.put("com.fizz.io.nio.rpc.RpcService", new RpcServiceImpl());

            Object o = map.get(interfaceName);
            try {
                Method method = o.getClass().getMethod(methodName, String.class);
                Object invoke = method.invoke(o, param);
                attr.getResponseQueue().add(new RpcResponse(uuid, invoke));

                // 注册写事件并唤醒 select，让事件循环线程尽快把响应写出去
                SelectionKey selectionKey = attr.getSelectionKey();
                selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
                selectionKey.selector().wakeup();
                log.info("uuid:{}, 执行接口:{}, 方法: {}, 结果: {}", uuid, interfaceName, methodName, invoke);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).start();
    }
}
