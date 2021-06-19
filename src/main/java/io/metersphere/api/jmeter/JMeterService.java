package io.metersphere.api.jmeter;

import io.metersphere.api.controller.request.RunRequest;
import io.metersphere.api.jmeter.constants.ApiRunMode;
import io.metersphere.api.jmeter.utils.JmeterProperties;
import io.metersphere.api.jmeter.utils.MSException;
import io.metersphere.node.util.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.backend.BackendListener;
import org.apache.jorphan.collections.HashTree;
import org.python.antlr.ast.arguments;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.File;
import java.lang.reflect.Field;

@Service
public class JMeterService {

    @Resource
    private JmeterProperties jmeterProperties;

    @PostConstruct
    public void init() {
        String JMETER_HOME = getJmeterHome();

        String JMETER_PROPERTIES = JMETER_HOME + "/bin/jmeter.properties";
        JMeterUtils.loadJMeterProperties(JMETER_PROPERTIES);
        JMeterUtils.setJMeterHome(JMETER_HOME);
        JMeterUtils.setLocale(LocaleContextHolder.getLocale());
    }

    public String getJmeterHome() {
        String home = getClass().getResource("/").getPath() + "jmeter";
        try {
            File file = new File(home);
            if (file.exists()) {
                return home;
            } else {
                return jmeterProperties.getHome();
            }
        } catch (Exception e) {
            return jmeterProperties.getHome();
        }
    }

    public static HashTree getHashTree(Object scriptWrapper) throws Exception {
        Field field = scriptWrapper.getClass().getDeclaredField("testPlan");
        field.setAccessible(true);
        return (HashTree) field.get(scriptWrapper);
    }


    private void addBackendListener(HashTree testPlan, RunRequest request) {
        BackendListener backendListener = new BackendListener();
        if (StringUtils.isNotEmpty(request.getReportId())) {
            backendListener.setName(request.getReportId());
        } else {
            backendListener.setName(request.getTestId());
        }
        Arguments arguments = new Arguments();
        if (request.getConfig() != null && request.getConfig().getMode().equals("serial") && request.getConfig().getReportType().equals("setReport")) {
            arguments.addArgument(APIBackendListenerClient.TEST_REPORT_ID, request.getConfig().getReportName());
        }
        if (StringUtils.isNotEmpty(request.getReportId()) && ApiRunMode.API_PLAN.name().equals(request.getRunMode())) {
            arguments.addArgument(APIBackendListenerClient.TEST_ID, request.getReportId());
        } else {
            arguments.addArgument(APIBackendListenerClient.TEST_ID, request.getTestId());
        }
        if (StringUtils.isNotBlank(request.getRunMode())) {
            arguments.addArgument("runMode", request.getRunMode());
        }
        arguments.addArgument("DEBUG", request.isDebug() ? "DEBUG" : "RUN");
        arguments.addArgument("USER_ID", request.getUserId());
        backendListener.setArguments(arguments);
        backendListener.setClassname(APIBackendListenerClient.class.getCanonicalName());
        testPlan.add(testPlan.getArray()[0], backendListener);
    }

    public void run(RunRequest request, HashTree testPlan) {
        try {
            init();
            addBackendListener(testPlan, request);
            LocalRunner runner = new LocalRunner(testPlan);
            runner.run();
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
            MSException.throwException("读取脚本失败");
        }
    }
}
