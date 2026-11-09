package com.nexachat.testing;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

@TestConfiguration(proxyBeanMethods = false)
public class SqlStatementCounterTestConfiguration {

    @Bean
    SqlStatementCounter sqlStatementCounter() {
        return new SqlStatementCounter();
    }

    @Bean
    static BeanPostProcessor countingDataSourcePostProcessor(SqlStatementCounter counter) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (beanName.equals("dataSource") && bean instanceof DataSource dataSource) {
                    return counter.wrap(dataSource);
                }
                return bean;
            }
        };
    }
}
