package com.fizz.io.netty.echo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class EchoServerHandler extends ChannelInboundHandlerAdapter {

//    @Override
//    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
////        super.channelRead(ctx, msg);
//        ctx.write(msg); // (1)
//        ctx.flush(); // (2)
//    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//        super.exceptionCaught(ctx, cause);
        cause.printStackTrace();
        ctx.close();
    }
}
