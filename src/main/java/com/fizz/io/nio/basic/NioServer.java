package com.fizz.io.nio.basic;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

public class NioServer {

    public static void main(String[] args) throws Exception {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(9001));
        serverChannel.configureBlocking(false); // 配置通道为非阻塞模式
        Selector selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        while (true) {
            int select = selector.select();
            if (select <= 0) {
                System.out.println("select <= 0");
                continue;
            }
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()) {
                try {
                    SelectionKey key = iterator.next();
                    System.out.println("key: " + key);
                    iterator.remove();
                    System.out.println("key.isAcceptable() = " + key.isAcceptable() + ", key.isReadable() = "
                            + key.isReadable() + ", key.isWritable() = " + key.isWritable());
                    if (key.isAcceptable()) {
                        SocketChannel clientChannel = serverChannel.accept();
                        System.out.println(clientChannel.getRemoteAddress() + " connected");
                        clientChannel.configureBlocking(false);
                        clientChannel.write(ByteBuffer.wrap("欢迎来到聊天室".getBytes()));
                        clientChannel.register(selector, SelectionKey.OP_READ);
                    } else if (key.isReadable()) {
                        SocketChannel clientChannel = (SocketChannel) key.channel();
                        try {
                            ByteBuffer buffer = ByteBuffer.allocate(5);
                            int n = clientChannel.read(buffer);
                            if (n == -1) {
                                clientChannel.close();
                            } else {
                                String message = new String(buffer.array(), 0, n).trim();
                                System.out.printf("客户端[%s]说: %s%n", clientChannel.socket().getPort(), message);

                                if ("close".equalsIgnoreCase(message)) {
                                    clientChannel.close();
                                    System.out.println("客户端请求关闭连接");
                                } else {
                                    ByteBuffer outBuffer = ByteBuffer.wrap(("服务器收到: " + message).getBytes());
                                    clientChannel.write(outBuffer);
                                }
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            key.channel().close();
                            System.out.printf("客户端[%s]已关闭连接", clientChannel.socket().getPort());
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

//        serverChannel.close();

    }
}
