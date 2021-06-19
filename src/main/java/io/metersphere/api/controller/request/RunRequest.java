package io.metersphere.api.controller.request;

import io.metersphere.api.jmeter.utils.RunModeConfig;
import lombok.Data;

@Data
public class RunRequest {
    private String testId;
    // api / case 或有这个属性值
    private String reportId;
    private String url;
    private String userId;
    private boolean isDebug;
    private String runMode;
    private String jmx;
    private RunModeConfig config;
}
