package io.cardioops.api.customerservice;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把服务身份过滤器挂到案件内部路径上。没有凭证的请求到不了用例。
 */

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CustomerServiceApiProperties.class)
class CustomerServiceApiSecurityConfiguration {

    @Bean
    CustomerServiceApiSecurityFilter customerServiceApiSecurityFilter(
            CustomerServiceApiProperties properties
    ) {
        return new CustomerServiceApiSecurityFilter(properties);
    }
}
