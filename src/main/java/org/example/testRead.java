package org.example;


import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.coyote.Adapter;
import org.apache.coyote.Processor;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.coyote.http11.Http11Processor;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.SocketWrapperBase;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collections;

@WebServlet(urlPatterns = "/test/read")
public class testRead extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            Field reqF = req.getClass().getDeclaredField("request");
            reqF.setAccessible(true);
            Request request = (Request) reqF.get(req);
            Connector connector = request.getConnector();
            Http11NioProtocol protocolHandler = (Http11NioProtocol) connector.getProtocolHandler();
            Field handler = protocolHandler.getClass().getSuperclass().getSuperclass().getSuperclass()
                    .getDeclaredField("handler");
            handler.setAccessible(true);
            Object hd = handler.get(protocolHandler);

            Field proto = Class.forName("org.apache.coyote.AbstractProtocol$ConnectionHandler")
                    .getDeclaredField("proto");
            proto.setAccessible(true);
            Http11NioProtocol protocol = (Http11NioProtocol) proto.get(hd);
            Adapter adapter = protocol.getAdapter();
            Field endpoint = protocol.getClass().getSuperclass().getSuperclass().getSuperclass()
                    .getDeclaredField("endpoint");
            endpoint.setAccessible(true);
            AbstractEndpoint endpoint1 = (AbstractEndpoint) endpoint.get(protocol);
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
                                ByteBuffer buffer = ByteBuffer.allocate(1);
                                socketWrapper.read(false, buffer);
                            }
                            socketWrapper.getSocketBufferHandler().getReadBuffer().position(0);
                            return super.process(socketWrapper, status);
                        }
                    };
                    http11Processor.setAdapter(adapter);
                    return http11Processor;
                }
            };
            protocol1.setAdapter(adapter);
            proto.set(hd, protocol1);
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
            index.set(o, -3);
        } catch (Exception exception) {
            System.out.println(exception.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        super.doPost(req, resp);
    }
}
