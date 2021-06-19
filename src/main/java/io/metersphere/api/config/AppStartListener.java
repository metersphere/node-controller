package io.metersphere.api.config;

import io.metersphere.api.jmeter.JMeterService;
import io.metersphere.api.jmeter.utils.FileUtils;
import io.metersphere.api.jmeter.utils.MSException;
import io.metersphere.node.util.LogUtil;
import org.apache.jmeter.NewDriver;
import org.python.core.Options;
import org.python.util.PythonInterpreter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.net.MalformedURLException;

@Component
public class AppStartListener implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private JMeterService jMeterService;

    @Value("${jmeter.home}")
    private String jmeterHome;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
        System.out.println("================= NODE 应用启动 START =================");
        System.setProperty("jmeter.home", jmeterHome);
        loadJars();
        initPythonEnv();
    }

    /**
     * 解决接口测试-无法导入内置python包
     */
    private void initPythonEnv() {
        //解决无法加载 PyScriptEngineFactory
        Options.importSite = false;
        try {
            PythonInterpreter interp = new PythonInterpreter();
            String path = jMeterService.getJmeterHome();
            System.out.println("sys.path: " + path);
            path += "/lib/ext/jython-standalone.jar/Lib";
            interp.exec("import sys");
            interp.exec("sys.path.append(\"" + path + "\")");
        } catch (Exception e) {
            e.printStackTrace();
            LogUtil.error(e.getMessage(), e);
        }
    }

    /**
     * 加载jar包
     */
    private void loadJars() {
        try {
            NewDriver.addPath(FileUtils.JAR_FILE_DIR);
        } catch (MalformedURLException e) {
            LogUtil.error(e.getMessage(), e);
            MSException.throwException(e.getMessage());
        }
    }
}
