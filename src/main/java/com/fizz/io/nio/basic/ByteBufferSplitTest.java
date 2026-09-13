package com.fizz.io.nio.basic;

import java.nio.ByteBuffer;

import static com.fizz.utils.ByteBufferUtil.debugAll;

public class ByteBufferSplitTest {

    public static void main(String[] args) {
        ByteBuffer source = ByteBuffer.allocate(32);
        source.put("Hello,world\nI'm zhangsan\nHo".getBytes());
        split(source);

        source.put("w are you?\n".getBytes());
        split(source);
    }

    private static void split(ByteBuffer source) {
        source.flip(); // 切换到读模式
        for (int i = 0; i < source.limit(); i++) {
            // 找到一条完整消息
            if (source.get(i) == '\n') {
                int length = i + 1 - source.position();
                // 把这条完整消息存入新的 ByteBuffer
                ByteBuffer target = ByteBuffer.allocate(length);
                // 从 source 读，向 target 写
                for (int j = 0; j < length; j++) {
                    target.put(source.get());
                }
                // 截图里缺失的 flip()。如果不加，target 处于写模式，debugAll 什么都读不到
                target.flip();
                debugAll(target);
            }
        }
        source.compact(); // 把未读完的半包数据移到前面，准备下一次写入
    }


}
