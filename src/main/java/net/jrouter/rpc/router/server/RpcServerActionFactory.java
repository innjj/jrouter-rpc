/*
 * Copyright (C) 2010-2111 sunjumper@163.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package net.jrouter.rpc.router.server;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import jrouter.ActionInvocation;
import jrouter.annotation.Dynamic;
import jrouter.annotation.Namespace;
import jrouter.impl.InvocationProxyException;
import jrouter.impl.PathActionFactory;
import net.jrouter.rpc.RpcException;
import net.jrouter.rpc.annotation.RpcProvider;
import net.jrouter.rpc.protocol.Protocol;
import net.jrouter.rpc.protocol.RpcProtocol;
import net.jrouter.rpc.util.Constants;
import jrouter.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import net.jrouter.rpc.router.RpcActionInvocation;
import net.jrouter.rpc.router.RpcActionFactory;
import net.jrouter.rpc.router.impl.ProtocolSerialization;
import net.jrouter.rpc.serialize.ObjectSerialization;
import jrouter.support.ActionInvocationDelegate;

/**
 * 提供接收RPC消息，基于{@code String}型路径调用的{@code RpcActionFactory}实现。
 *
 * @param <S> Session type.
 */
@Slf4j
public class RpcServerActionFactory<S> extends PathActionFactory implements RpcActionFactory<S> {

    /**
     * {@code Protocol} 序列/反序列化工具。
     */
    private final ProtocolSerialization protocolSerialization;

    /**
     * Constructor.
     *
     * @param properties Properties.
     */
    public RpcServerActionFactory(Properties properties) {
        super(properties);
        protocolSerialization = new ProtocolSerialization(properties.objectSerialization, properties.supportedObjectSerializations);
        if (log.isInfoEnabled()) {
            log.info("Use protocolSerialization : {}", protocolSerialization);
        }
    }

    /**
     * RpcServerActionFactory 属性.
     */
    @lombok.Getter
    @lombok.Setter
    public static class Properties extends PathActionFactory.Properties {

        /**
         * Default Constructor.
         */
        public Properties() {
            super();
            //default RpcProviderActionFilter
            setActionFilter(new RpcProviderActionFilter());
        }

        /**
         * @see DispatchServerActionFactory#protocolSerialization
         */
        private ObjectSerialization objectSerialization = Constants.DEFAULT_OBJECT_SERIALIZATION;

        /**
         * @see DispatchServerActionFactory#protocolSerialization
         */
        private Map<Byte, ObjectSerialization> supportedObjectSerializations = Constants.SUPPORTED_OBJECT_SERIALIZATIONS;

    }

    /**
     * 接收 {@code byte[]}类型消息，转换至{@link Protocol}协议对象，
     * 进而根据协议对象中指定路径调用相应的方法。
     *
     * @param <T> type.
     * @param messages bytes data.
     * @param session RPC Session object.
     *
     * @return 调用结果。
     *
     * @throws RpcException 如果发生调用错误。
     */
    @Override
    public <T> T onMessage(byte[] messages, S session) throws RpcException {
        Protocol<String> protocol = parseRpcProtocol(messages, session);
        if (protocol != null) {
            //invoke and pass rpc parameters
            if (log.isDebugEnabled()) {
                log.debug("Received protocol [{}].", protocol);
            }
            return invokeAction(protocol, session);
        }
        return null;
    }

    /**
     * 处理调用异常；此异常将不会调用业务方法。
     *
     * @param protocol {@code Protocol}对象。
     * @param session {@code Session}对象。
     * @param t 异常对象。
     *
     * @see #onMessage
     */
    protected void handleError(Protocol<String> protocol, S session, Throwable t) {
        //log.error("Exception occured when handling rpc messages : " + protocol, t);
        throw new RpcException(t);
    }

    /**
     * 通过{@code Protocol}指定的路径调用相应的方法（Action）.
     *
     * @param <T> type.
     * @param protocol {@code Protocol}对象。
     * @param session {@code Session}对象。
     *
     * @return 调用结果。
     *
     * @throws RpcException 如果发生调用错误。
     */
    protected <T> T invokeAction(Protocol<String> protocol, S session) throws RpcException {
        try {
            //invoke and pass parameters
            return (T) super.invokeAction(protocol.getPath(), protocol, session);
        } catch (Exception e) {
            Throwable thr = e;
            if (e instanceof InvocationProxyException) {
                thr = ((InvocationProxyException) e).getSource();
            }
            handleError(protocol, session, thr);
        }
        return null;
    }

    @Override
    protected Object invokeResult(ActionInvocation invocation, Object res) {
        return super.invokeResult(invocation, res);
    }

    /**
     * 创建并返回{@link RpcActionInvocation}接口对象。
     *
     * @return {@link RpcActionInvocation}接口对象。
     */
    @Override
    protected ActionInvocation<String> createActionInvocation(String path, Object... params) {
        Protocol<String> protocol = (Protocol) params[0];
        //parse original parameters
        ActionInvocation invocation = super.createActionInvocation(path, protocol.getParameters());
        RpcServerActionInvocation rpcActionInvocation = null;
        if (checkActionInvocationParameters(params)) {
            rpcActionInvocation = new RpcServerActionInvocation(
                    invocation,
                    protocol,
                    (S) params[1],
                    this,
                    //TODO
                    new HashMap<>(4));
            invocation = rpcActionInvocation;
        } else {
            log.warn("Check ActionInvocation Parameters not matched : {}", protocol);
        }
        //设置调用ActionInvocation的转换参数
        invocation.setConvertParameters(
                new Object[]{
                    //不传递Protocol至转换参数
                    //params[0],
                    params[1],
                    invocation});
        return invocation;
    }

    /**
     * 根据RPC消息解析{@code Protocol}对象。
     *
     * @param messages byte[]类型消息。
     * @param session {@code Session}对象。
     *
     * @return {@code Protocol}对象。
     */
    private Protocol<String> parseRpcProtocol(byte[] messages, S session) {
        try {
            return getObjectSerialization().deserialize(messages, RpcProtocol.class);
        } catch (Throwable t) {
            log.error("Exception ocurred when parsing Rpc Protocol.", t);
            Protocol<String> protocol = new RpcProtocol();
            protocol = getObjectSerialization().fillProtocol(protocol, messages);
            handleError(protocol, session, t);
        }
        return null;
    }

    /**
     * 检测{@link #invokeAction}方法传递过来参数的正确性。
     *
     * @param params 由{@link #invokeAction}方法传递过来参数。
     *
     * @return 参数是否为正确的参数对象。
     */
    private boolean checkActionInvocationParameters(Object... params) {
        return params != null && params.length == 2
                && (params[0] instanceof Protocol);
//                && (params[1] == null || params[1] instanceof S);
    }

    @Override
    public ProtocolSerialization getObjectSerialization() {
        return this.protocolSerialization;
    }

    /**
     * 扩展{@code ActionInvocation}，提供获取RPC参数对象，并提供给参数转换器。
     */
    @Dynamic
    public class RpcServerActionInvocation extends ActionInvocationDelegate<String> implements RpcActionInvocation<S> {

        @lombok.Getter
        private final Protocol<String> protocol;

        @lombok.Getter
        private final S session;

        @lombok.Getter
        private final RpcActionFactory actionFactory;

        @lombok.Getter
        @lombok.Setter
        private RpcException rpcException;

        /** Store key-value */
        private final Map<String, Object> contextMap;

        @SuppressWarnings("PMD.ExcessiveParameterList")
        public RpcServerActionInvocation(ActionInvocation<String> invocation, Protocol protocol,
                S session, RpcActionFactory actionFactory, Map<String, Object> contextMap) {
            super();
            this.delegate = invocation;
            this.protocol = protocol;
            this.session = session;
            this.actionFactory = actionFactory;
            this.contextMap = contextMap;
        }

        @Override
        public Map<String, Object> getContextMap() {
            return contextMap;
        }
    }

    /**
     * RpcProviderActionFilter.
     *
     * @see RpcProvider
     */
    protected static class RpcProviderActionFilter extends PathActionFactory.DefaultActionFilter {

        /**
         * 提供{@link RpcProvider}至{@link Namespace}的转换。
         */
        @Override
        public Namespace getNamespace(Object obj, Method method) {
            Class<?> clazz = method.getDeclaringClass();
            final RpcProvider provider = clazz.getAnnotation(RpcProvider.class);
            if (provider != null) {
                String namespace = provider.namespace();
                if (StringUtil.isBlank(namespace)) {
                    namespace = clazz.getCanonicalName();
                }
                final String name = namespace;
                return new Namespace() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public String interceptorStack() {
                        return provider.interceptorStack();
                    }

                    @Override
                    public String[] interceptors() {
                        return provider.interceptors();
                    }

                    @Override
                    public boolean autoIncluded() {
                        return provider.autoIncluded();
                    }

                    @Override
                    public Class<? extends Annotation> annotationType() {
                        return RpcProvider.class;
                    }
                };
            }
            return super.getNamespace(obj, method);
        }
    }

}
