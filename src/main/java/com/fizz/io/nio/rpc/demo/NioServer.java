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

        int cpuSize = Runtime.getRuntime().availableProcessors();

        WorkerEventLoop[] workerEventLoops = new WorkerEventLoop[cpuSize];
        for (int i = 0; i < cpuSize; i++) {
            workerEventLoops[i] = new WorkerEventLoop();
        }

        int loopIndex = 0;

        while (true) {
            int select = selector.select();
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
                    if (key.isAcceptable()) {
                        SocketChannel clientChannel = serverChannel.accept();
                        log.info("{} connected", clientChannel.getRemoteAddress());

                        int i = loopIndex++ % cpuSize;
                        log.info("selected loop: {}", i);
                        WorkerEventLoop workerEventLoop = workerEventLoops[i];
                        workerEventLoop.register(clientChannel);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

}
