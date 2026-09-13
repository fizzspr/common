package com.fizz.io.nio.rpc.demo;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.fizz.io.nio.rpc.demo.ByteBufferSupport.toFixedBytesByBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class NioClient {
    public static void main(String[] args) throws Exception {
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);
        InetSocketAddress inetSocketAddress = new InetSocketAddress("127.0.0.1", 9002);
        Selector selector = Selector.open();
        socketChannel.register(selector, SelectionKey.OP_CONNECT);
        socketChannel.connect(inetSocketAddress);

        // 待发队列：Scanner 线程只入队 + wakeup，实际写由 select 线程执行，避免跨线程写产生竞态
        Queue<ByteBuffer> outQueue = new ConcurrentLinkedQueue<>();

        new Thread(() -> {
            try {
                Scanner scanner = new Scanner(System.in);
                while(scanner.hasNextLine()) {
                    String s = scanner.nextLine();
                    if ("close".equals(s)) {
                        socketChannel.close();
                        break;
                    }

                    List<ByteBuffer> list = new ArrayList<>();
                    for (int i = 0; i < 10; i++) {
                        String interfaceName = "com.fizz.io.nio.rpc.RpcService";
                        String methodName = "hello";
                        String param = "xiaoming" + i;
                        // 拼装协议字节数据
                        ByteBuffer msg = ByteBuffer.allocate(36 + 32 + 16 +128 + 1);
                        ByteBuffer interfaceNameBB = toFixedBytesByBuffer(interfaceName, 32);
                        ByteBuffer methodNameBB = toFixedBytesByBuffer(methodName, 16);
                        ByteBuffer paramBB = toFixedBytesByBuffer(param, 128);
                        msg.put(UUID.randomUUID().toString().getBytes());
                        msg.put(interfaceNameBB);
                        msg.put(methodNameBB);
                        msg.put(paramBB);
                        msg.put((byte) '\n');

                        msg.flip();
                        list.add(msg);
                    }

                    for (ByteBuffer byteBuffer : list) {
                        outQueue.add(byteBuffer);
                    }
                    // 唤醒 select 线程，由它把消息发出去
                    selector.wakeup();

                }
            } catch (IOException e) {
                log.error(e.getMessage(), e);
            }
        }).start();

        ByteBuffer pendingWrite = null; // 未写完的数据，仅 select 线程访问

        while (true) {
            int select = selector.select();

            // 每轮都尽量把待发消息写出去，写不完的数据等可写事件续写
            pendingWrite = flush(socketChannel, pendingWrite, outQueue, selector);

            if (select <= 0) {
                log.info("select <= 0");
                continue;
            }

            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                log.info("key: {}", key);
                iterator.remove();
                log.info("key.isAcceptable() = {}, key.isReadable() = {}, key.isWritable() = {}", key.isAcceptable(), key.isReadable(), key.isWritable());
                if(key.isConnectable()){
                    SocketChannel clientSocket = (SocketChannel)key.channel();
                    //这里需要检测是否完成连接
                    if(clientSocket.finishConnect()) {
                        log.info("客户端[{}]已连接", socketChannel.socket().getLocalPort());
                        //连接成功后需要将选择器设置为对该通道的读事件感兴趣（不然同样会无限循环）
                        key.interestOps(SelectionKey.OP_READ);
                    } else {
                        log.info("客户端[{}]连接失败", socketChannel.socket().getLocalPort());
                        System.exit(1);
                    }
                } else if (key.isReadable()) {
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
            }
        }
    }

    private static void split(ByteBuffer source, SocketChannel clientChannel, ChannelAttr attr) throws IOException {
        source.flip();
        int remaining = source.remaining();
        for (int i = 0; i < remaining; i++) {
            if (source.get(i) == '\n') {
                // i是下标，长度需要+1
                byte[] bytes = new byte[i + 1 - source.position()];
                source.get(bytes);
                log.info("收到服务端[{}]一条完整消息：{}", clientChannel.socket().getPort(), new String(bytes, UTF_8));

                doWork(bytes, attr);
            }
        }

        source.compact();
    }

    private static void doWork(byte[] bytes, ChannelAttr attr) {
        String uuid = new String(bytes, 0, 36).trim();
        String result = new String(bytes, 36, bytes.length - 36).trim();

        log.info("RPC接口已返回, uuid:{}, 结果: {}", uuid, result);
    }

    /**
     * 依次写出待发数据；返回未写完的 buffer（等可写事件续写），全部写完返回 null
     */
    private static ByteBuffer flush(SocketChannel channel, ByteBuffer pending, Queue<ByteBuffer> outQueue, Selector selector) {
        if (pending == null && outQueue.isEmpty()) {
            return null; // 没有待发数据
        }
        try {
            if (pending != null && writeFully(channel, pending)) {
                pending = null;
            }
            if (pending == null) {
                ByteBuffer buf;
                while ((buf = outQueue.poll()) != null) {
                    if (!writeFully(channel, buf)) {
                        pending = buf; // 内核发送缓冲已满，保留剩余部分
                        break;
                    }
                }
            }

            SelectionKey key = channel.keyFor(selector);
            if (key != null && key.isValid()) {
                int ops = key.interestOps();
                int newOps = pending != null ? (ops | SelectionKey.OP_WRITE) : (ops & ~SelectionKey.OP_WRITE);
                if (newOps != ops) {
                    key.interestOps(newOps);
                }
            }
            return pending;
        } catch (IOException e) {
            log.error("发送失败，关闭连接", e);
            try { channel.close(); } catch (IOException ignored) { }
            return null;
        }
    }

    /**
     * 尽力把 buffer 写出去；true=已全部写完，false=内核发送缓冲已满，需要等下次可写事件
     */
    private static boolean writeFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int n = channel.write(buffer);
            if (n == 0) {
                return false;
            }
        }
        return true;
    }

}
