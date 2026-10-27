package vn.werwa.bidvsim;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class SimulatorConfig {

    @Bean
    MessageSigner messageSigner(SimulatorProperties props) {
        return new MessageSigner(props.privateKey());
    }

    @Bean
    RestClient platform(RestClient.Builder builder, SimulatorProperties props) {
        return builder.baseUrl(props.platformUrl()).build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
