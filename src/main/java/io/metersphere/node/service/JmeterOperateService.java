package io.metersphere.node.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.core.InvocationBuilder;
import io.metersphere.node.controller.request.TestRequest;
import io.metersphere.node.util.DockerClientService;
import io.metersphere.node.util.LogUtil;
import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class JmeterOperateService {
    @Resource
    private KafkaProducer kafkaProducer;

    public void startContainer(TestRequest testRequest) {
        Map<String, String> env = testRequest.getEnv();
        String testId = env.get("TEST_ID");
        LogUtil.info("Receive start container request, test id: {}", testId);
        String bootstrapServers = env.get("BOOTSTRAP_SERVERS");
        // 检查kafka连通性
        checkKafka(bootstrapServers);

        DockerClient dockerClient = DockerClientService.connectDocker(testRequest);

        String containerImage = testRequest.getImage();

        // 查找镜像
        searchImage(dockerClient, testRequest.getImage());
        // 检查容器是否存在
        checkContainerExists(dockerClient, testId);
        // 启动测试
        startContainer(testRequest, dockerClient, testId, containerImage);
    }

    private void startContainer(TestRequest testRequest, DockerClient dockerClient, String testId, String containerImage) {
        // 创建 hostConfig
        HostConfig hostConfig = HostConfig.newHostConfig();
        hostConfig.withNetworkMode("host");
        String[] envs = getEnvs(testRequest);
        String containerId = DockerClientService.createContainers(dockerClient, testId, containerImage, hostConfig, envs).getId();

        DockerClientService.startContainer(dockerClient, containerId);
        LogUtil.info("Container create started containerId: " + containerId);

        String topic = testRequest.getEnv().getOrDefault("LOG_TOPIC", "JMETER_LOGS");
        String reportId = testRequest.getEnv().get("REPORT_ID");

        dockerClient.waitContainerCmd(containerId)
                .exec(new WaitContainerResultCallback() {
                    @Override
                    public void onComplete() {
                        // 清理文件夹
                        try {
                            if (DockerClientService.existContainer(dockerClient, containerId) > 0) {
                                DockerClientService.removeContainer(dockerClient, containerId);
                            }
                            // 上传结束消息
                            String[] contents = new String[]{reportId, "none", "0", "Remove container completed"};
                            String log = StringUtils.join(contents, " ");
                            kafkaProducer.sendMessage(topic, log);
                            LogUtil.info("Remove container completed: " + containerId);
                        } catch (Exception e) {
                            LogUtil.error("Remove container error: ", e);
                        }
                        LogUtil.info("completed....");
                    }
                });

        dockerClient.logContainerCmd(containerId)
                .withFollowStream(true)
                .withStdOut(true)
                .withStdErr(true)
                .withTailAll()
                .exec(new InvocationBuilder.AsyncResultCallback<Frame>() {
                    @Override
                    public void onNext(Frame item) {
                        String log = new String(item.getPayload()).trim();
                        String oomMessage = "There is insufficient memory for the Java Runtime Environment to continue.";
                        if (StringUtils.contains(log, oomMessage)) {
                            // oom 退出
                            String[] contents = new String[]{reportId, "none", "0", oomMessage};
                            String message = StringUtils.join(contents, " ");
                            kafkaProducer.sendMessage(topic, message);
                        }
                        LogUtil.info(log);
                    }
                });
    }

    private void checkContainerExists(DockerClient dockerClient, String testId) {
        List<Container> list = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withStatusFilter(Arrays.asList("created", "restarting", "running", "paused", "exited"))
                .withNameFilter(Collections.singletonList(testId))
                .exec();
        if (!CollectionUtils.isEmpty(list)) {
            list.forEach(container -> DockerClientService.removeContainer(dockerClient, container.getId()));
        }
    }

    private void checkKafka(String bootstrapServers) {
        String[] servers = StringUtils.split(bootstrapServers, ",");
        try {
            for (String s : servers) {
                String[] ipAndPort = s.split(":");
                //1,建立tcp
                String ip = ipAndPort[0];
                int port = Integer.parseInt(ipAndPort[1]);
                try (
                        Socket soc = new Socket()
                ) {
                    soc.connect(new InetSocketAddress(ip, port), 1000); // 1s timeout
                }
            }
        } catch (Exception e) {
            LogUtil.error(e);
            throw new RuntimeException("Failed to connect to Kafka");
        }
    }

    private String[] getEnvs(TestRequest testRequest) {
        Map<String, String> env = testRequest.getEnv();
        return env.keySet().stream().map(k -> k + "=" + env.get(k)).toArray(String[]::new);
    }

    private void searchImage(DockerClient dockerClient, String imageName) {
        // image
        List<Image> imageList = dockerClient.listImagesCmd().exec();
        if (CollectionUtils.isEmpty(imageList)) {
            throw new RuntimeException("Image List is empty");
        }
        List<Image> collect = imageList.stream().filter(image -> {
            String[] repoTags = image.getRepoTags();
            if (repoTags == null) {
                return false;
            }
            for (String repoTag : repoTags) {
                if (repoTag.equals(imageName)) {
                    return true;
                }
            }
            return false;
        }).collect(Collectors.toList());

        if (collect.size() == 0) {
            throw new RuntimeException("Image Not Found: " + imageName);
        }
    }


    public void stopContainer(String testId) {
        LogUtil.info("Receive stop container request, test: {}", testId);
        DockerClient dockerClient = DockerClientService.connectDocker();

        // container filter
        List<Container> list = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withStatusFilter(Collections.singletonList("running"))
                .withNameFilter(Collections.singletonList(testId))
                .exec();
        // container stop
        list.forEach(container -> DockerClientService.removeContainer(dockerClient, container.getId()));
    }

    public List<Container> taskStatus(String testId) {
        DockerClient dockerClient = DockerClientService.connectDocker();
        List<Container> containerList = dockerClient.listContainersCmd()
                .withStatusFilter(Arrays.asList("created", "restarting", "running", "paused", "exited"))
                .withNameFilter(Collections.singletonList(testId))
                .exec();
        // 查询执行的状态
        return containerList;
    }

    public String logContainer(String testId) {
        LogUtil.info("Receive logs container request, test: {}", testId);
        DockerClient dockerClient = DockerClientService.connectDocker();

        // container filter
        List<Container> list = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withStatusFilter(Collections.singletonList("running"))
                .withNameFilter(Collections.singletonList(testId))
                .exec();

        StringBuilder sb = new StringBuilder();
        if (list.size() > 0) {
            try {
                dockerClient.logContainerCmd(list.get(0).getId())
                        .withFollowStream(true)
                        .withStdOut(true)
                        .withStdErr(true)
                        .withTailAll()
                        .exec(new InvocationBuilder.AsyncResultCallback<Frame>() {
                            @Override
                            public void onNext(Frame item) {
                                sb.append(new String(item.getPayload()).trim()).append("\n");
                            }
                        }).awaitCompletion(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                LogUtil.error(e);
            }
        }
        return sb.toString();
    }
}
