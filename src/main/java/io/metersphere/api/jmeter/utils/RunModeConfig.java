package io.metersphere.api.jmeter.utils;

import lombok.Data;

@Data
public class RunModeConfig {
    private String mode;
    private String reportType;
    private String reportName;
    private boolean onSampleError;
}
