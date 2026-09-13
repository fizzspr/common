package com.fizz.io.nio.rpc.demo;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ByteBufferSupport {

    public static ByteBuffer toFixedBytesByBuffer(String str, int capacity) {
        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        if (str != null) {
            byte[] srcBytes = str.getBytes(StandardCharsets.UTF_8);
            if (srcBytes.length > capacity) {
                throw new RuntimeException("str too large");
            }
            buffer.put(srcBytes);
        }

        buffer.rewind();
        return buffer;
    }
}
