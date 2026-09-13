package com.fizz.io.nio.rpc.demo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class RpcResponse {

    private String uuid;

    private Object result;
}
