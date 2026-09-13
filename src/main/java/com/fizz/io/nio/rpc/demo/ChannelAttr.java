package com.fizz.io.nio.rpc.demo;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Data
public class ChannelAttr {

    private static final int INITIAL_CAPACITY = 20;

    private SocketChannel socketChannel;

    private SelectionKey selectionKey;

    private Queue<RpcResponse> responseQueue = new ConcurrentLinkedQueue<>();

    private ByteBuffer buffer = ByteBuffer.allocate(INITIAL_CAPACITY);

    /**
     * 未写完的响应数据，等下次可写事件继续写
     */
    private ByteBuffer pendingWrite;

    /**
     * 待发送队列：写不完时消息暂存，等可写事件续写
     */
    private Queue<ByteBuffer> outQueue = new ConcurrentLinkedQueue<>();

    private Map<String, Object> data = new ConcurrentHashMap<>(256);

    public ChannelAttr(SocketChannel clientChannel, SelectionKey selectionKey) {
        this.socketChannel = clientChannel;
        this.selectionKey = selectionKey;
    }

    /**
     * 返回可写状态的 buffer；若已写满则自动扩容为 2 倍并更新到自身
     */
    public ByteBuffer expandIfNeeded() {
        if (!buffer.hasRemaining()) {
            ByteBuffer newBuffer = ByteBuffer.allocate(buffer.capacity() * 2);
            log.info("扩容从 {} -> {}", buffer.capacity(), newBuffer.capacity());
            buffer.flip();
            newBuffer.put(buffer);
            buffer = newBuffer;
        }
        return buffer;
    }
}


