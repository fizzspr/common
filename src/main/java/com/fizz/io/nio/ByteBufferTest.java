package com.fizz.io.nio;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

import static com.fizz.utils.ByteBufferUtil.debugAll;

public class ByteBufferTest {

    static StringBuilder sb = new StringBuilder();

    public static void main(String[] args) throws IOException {
        byte[] bytes = "中国".getBytes(StandardCharsets.UTF_8);
        ByteBuffer source = ByteBuffer.allocate(10);
        source.put(bytes[0]);
        source.put(bytes[1]);
        source.put(bytes[2]);
//        source.put(bytes[3]);
        source.put(bytes[4]);
        source.put(bytes[5]);

        source.flip();
        ByteBuffer target = ByteBuffer.allocate(6);
        target.put(source);
        target.flip();
        System.out.println(StandardCharsets.UTF_8.decode(target));
    }


}
