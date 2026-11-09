package com.nexachat.testing;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts SQL executions on the calling thread without recording SQL or values. */
public final class SqlStatementCounter {

    private final ThreadLocal<AtomicInteger> active = new ThreadLocal<>();

    public <T> Measurement<T> measure(Callable<T> work) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        if (active.get() != null) {
            throw new IllegalStateException("SQL measurements cannot be nested");
        }
        active.set(executions);
        try {
            return new Measurement<>(work.call(), executions.get());
        } finally {
            active.remove();
        }
    }

    public Object wrap(DataSource target) {
        return Proxy.newProxyInstance(
                SqlStatementCounter.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    if (result instanceof Connection connection && method.getName().equals("getConnection")) {
                        return wrapConnection(connection);
                    }
                    return result;
                });
    }

    private Connection wrapConnection(Connection target) {
        return (Connection) Proxy.newProxyInstance(
                SqlStatementCounter.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    Class<?> returnType = method.getReturnType();
                    if (result instanceof Statement statement && returnType.isInterface()
                            && (returnType == Statement.class || returnType == PreparedStatement.class
                            || returnType == CallableStatement.class)) {
                        return wrapStatement(statement, returnType);
                    }
                    return result;
                });
    }

    private Object wrapStatement(Statement target, Class<?> statementType) {
        return Proxy.newProxyInstance(
                SqlStatementCounter.class.getClassLoader(),
                new Class<?>[]{statementType},
                (proxy, method, args) -> {
                    if (isExecution(method.getName())) {
                        AtomicInteger executions = active.get();
                        if (executions != null) {
                            executions.incrementAndGet();
                        }
                    }
                    return invoke(target, method, args);
                });
    }

    private static boolean isExecution(String methodName) {
        return methodName.equals("execute")
                || methodName.equals("executeQuery")
                || methodName.equals("executeUpdate")
                || methodName.equals("executeLargeUpdate")
                || methodName.equals("executeBatch")
                || methodName.equals("executeLargeBatch");
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getTargetException();
        }
    }

    public record Measurement<T>(T result, int sqlExecutions) {
    }
}
