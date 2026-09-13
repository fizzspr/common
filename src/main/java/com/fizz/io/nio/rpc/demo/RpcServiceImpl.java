package com.fizz.io.nio.rpc.demo;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class RpcServiceImpl implements RpcService {
    @Override
    public String hello(String name) {
        try {
//            log.info("================hello执行中=====================");
//            Thread.sleep(5000);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return "hello " + name;
    }
}
