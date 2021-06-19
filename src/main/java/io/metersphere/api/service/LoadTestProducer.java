package io.metersphere.api.service;

import io.metersphere.api.config.KafkaConfig;
import io.metersphere.api.config.KafkaProperties;
import io.metersphere.api.jmeter.utils.CommonBeanFactory;
import io.metersphere.api.jmeter.utils.MSException;
import io.metersphere.node.util.LogUtil;
import org.apache.commons.lang.StringUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

@Service
public class LoadTestProducer {

    public void checkKafka() {
        KafkaProperties kafkaProperties = CommonBeanFactory.getBean(KafkaProperties.class);
        String[] servers = StringUtils.split(kafkaProperties.getBootstrapServers(), ",");
        try {
            for (String s : servers) {
                String[] ipAndPort = s.split(":");
                //1,建立tcp
                String ip = ipAndPort[0];
                int port = Integer.parseInt(ipAndPort[1]);
                Socket soc = new Socket();
                // 1s timeout
                soc.connect(new InetSocketAddress(ip, port), 1000);
                //2.输入内容
                String content = "1010";
                byte[] bs = content.getBytes();
                OutputStream os = soc.getOutputStream();
                os.write(bs);
                //3.关闭
                soc.close();
            }
        } catch (Exception e) {
            LogUtil.error(e);
            MSException.throwException("Failed to connect to Kafka");
        }
    }


    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;

    public void sendMessage(String report) {
        this.kafkaTemplate.send(KafkaConfig.testTopic, report);
    }
}
