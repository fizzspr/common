package com.fizz.io.nio.rpc.demo;

import com.fizz.utils.ByteBufferUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;


@Slf4j
public class NioServer {

    public static void main(String[] args) throws Exception {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(9002));
        serverChannel.configureBlocking(false);
        Selector selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        while (true) {
            int select = selector.select();
            if (select <= 0) {
                log.info("select <= 0");
                continue;
            }
            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectionKeys.iterator();
            log.info("select {}, keySize:{}", select, selectionKeys.size());
            while (iterator.hasNext()) {
                try {
                    SelectionKey key = iterator.next();
                    log.info("key: {}", key);
                    iterator.remove();
                    log.info("key.isAcceptable() = {}, key.isReadable() = {}, key.isWritable() = {}", key.isAcceptable(), key.isReadable(), key.isWritable());
                    if (key.isAcceptable()) {
                        SocketChannel clientChannel = serverChannel.accept();
                        log.info("{} connected", clientChannel.getRemoteAddress());
                        clientChannel.configureBlocking(false);
                        clientChannel.register(selector, SelectionKey.OP_READ);
                    } else if (key.isReadable()) {
                        SocketChannel clientChannel = (SocketChannel) key.channel();
                        ByteBuffer attachment = (ByteBuffer) key.attachment();
                        if (attachment == null) {
                            ByteBuffer bb = ByteBuffer.allocate(200);
                            key.attach(bb);
                            attachment = bb;
                        }
                        try {
                            if (!attachment.hasRemaining()) {
                                ByteBuffer newAttr = ByteBuffer.allocate(attachment.capacity() * 2);
                                log.info("扩容从 {} -> {}", attachment.capacity(), attachment.capacity() * 2);
                                key.attach(newAttr);
                                attachment.flip();
                                newAttr.put(attachment);
                                attachment = newAttr;
                            }
                            int n = clientChannel.read(attachment);
                            log.info("read {} bytes, position: {}, limit: {}, capacity: {}", n, attachment.position(), attachment.limit(), attachment.capacity());
                            if (n == -1) {
                                clientChannel.close();
                            } else {
                                split(attachment, clientChannel);
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

    private static void split(ByteBuffer source, SocketChannel clientChannel) throws IOException {
        source.flip();
        int remaining = source.remaining();
        for (int i = 0; i < remaining; i++) {
            if (source.get(i) == '\n') {
                // i是下标，长度需要+1
                byte[] bytes = new byte[i + 1 - source.position()];
                source.get(bytes);
                log.info("收到客户端[{}]一条完整消息：{}", clientChannel.socket().getPort(), new String(bytes, UTF_8));

                doWork(bytes);
            }
        }

        source.compact();
    }

    private static void doWork(byte[] bytes) {
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
                log.info("uuid:{}, 执行接口:{}, 方法: {}, 结果: {}", uuid, interfaceName, methodName, invoke);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).start();
    }

    static class UserThread extends Thread {

    }
}
