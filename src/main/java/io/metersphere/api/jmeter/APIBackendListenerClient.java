package io.metersphere.api.jmeter;

import com.alibaba.fastjson.JSON;
import io.metersphere.api.service.LoadTestProducer;
import io.metersphere.api.jmeter.constants.ApiRunMode;
import io.metersphere.api.jmeter.constants.RequestType;
import io.metersphere.api.module.*;
import io.metersphere.api.jmeter.utils.CommonBeanFactory;
import io.metersphere.node.util.LogUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.springframework.http.HttpMethod;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.*;

/**
 * JMeter BackendListener扩展, jmx脚本中使用
 */
public class APIBackendListenerClient extends AbstractBackendListenerClient implements Serializable {

    public final static String TEST_ID = "ms.test.id";

    public final static String TEST_REPORT_ID = "ms.test.report.name";

    private final static String THREAD_SPLIT = " ";

    private final static String ID_SPLIT = "-";

    private final List<SampleResult> queue = new ArrayList<>();

    private String runMode = ApiRunMode.RUN.name();

    private String userId;

    private boolean isDebug;

    private LoadTestProducer loadTestProducer;

    /**
     * 测试ID
     */
    private String testId;

    /**
     * 只有合并报告是这个有值
     */
    private String setReportId;

    /**
     * 获得控制台内容
     */
    private PrintStream oldPrintStream = System.out;
    private ByteArrayOutputStream bos = new ByteArrayOutputStream();

    private void setConsole() {
        // 设置新的out
        System.setOut(new PrintStream(bos));
    }

    private String getConsole() {
        System.setOut(oldPrintStream);
        return bos.toString();
    }

    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        setConsole();
        setParam(context);
        super.setupTest(context);
    }


    @Override
    public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
        queue.addAll(sampleResults);
    }

    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        TestResult testResult = new TestResult();
        testResult.setTestId(testId);
        testResult.setTotal(queue.size());
        testResult.setSetReportId(this.setReportId);
        testResult.setDebug(this.isDebug);
        testResult.setUserId(this.userId);
        loadTestProducer = CommonBeanFactory.getBean(LoadTestProducer.class);
        // 一个脚本里可能包含多个场景(ThreadGroup)，所以要区分开，key: 场景Id
        final Map<String, ScenarioResult> scenarios = new LinkedHashMap<>();
        queue.forEach(result -> {
            // 线程名称: <场景名> <场景Index>-<请求Index>, 例如：Scenario 2-1
            if (StringUtils.equals(result.getSampleLabel(), "RunningDebugSampler")) {
                String evnStr = result.getResponseDataAsString();
                testResult.setRunningDebugSampler(result.getResponseDataAsString());
             } else {
                String scenarioName = StringUtils.substringBeforeLast(result.getThreadName(), THREAD_SPLIT);
                String index = StringUtils.substringAfterLast(result.getThreadName(), THREAD_SPLIT);
                String scenarioId = StringUtils.substringBefore(index, ID_SPLIT);
                ScenarioResult scenarioResult;
                if (!scenarios.containsKey(scenarioId)) {
                    scenarioResult = new ScenarioResult();
                    try {
                        scenarioResult.setId(Integer.parseInt(scenarioId));
                    } catch (Exception e) {
                        scenarioResult.setId(0);
                        LogUtil.error("场景ID转换异常: " + e.getMessage());
                    }
                    scenarioResult.setName(scenarioName);
                    scenarios.put(scenarioId, scenarioResult);
                } else {
                    scenarioResult = scenarios.get(scenarioId);
                }

                if (result.isSuccessful()) {
                    scenarioResult.addSuccess();
                    testResult.addSuccess();
                } else {
                    scenarioResult.addError(result.getErrorCount());
                    testResult.addError(result.getErrorCount());
                }

                RequestResult requestResult = getRequestResult(result);
                scenarioResult.getRequestResults().add(requestResult);
                scenarioResult.addResponseTime(result.getTime());

                testResult.addPassAssertions(requestResult.getPassAssertions());
                testResult.addTotalAssertions(requestResult.getTotalAssertions());

                scenarioResult.addPassAssertions(requestResult.getPassAssertions());
                scenarioResult.addTotalAssertions(requestResult.getTotalAssertions());
            }
        });
        testResult.getScenarios().addAll(scenarios.values());
        testResult.getScenarios().sort(Comparator.comparing(ScenarioResult::getId));
        testResult.setRunMode(this.runMode);
        // 推送执行结果
        loadTestProducer.sendMessage(JSON.toJSONString(testResult));
        queue.clear();
        super.teardownTest(context);
    }

    private RequestResult getRequestResult(SampleResult result) {
        RequestResult requestResult = new RequestResult();
        requestResult.setId(result.getSamplerId());
        requestResult.setName(result.getSampleLabel());
        requestResult.setUrl(result.getUrlAsString());
        requestResult.setMethod(getMethod(result));
        requestResult.setBody(result.getSamplerData());
        requestResult.setHeaders(result.getRequestHeaders());
        requestResult.setRequestSize(result.getSentBytes());
        requestResult.setStartTime(result.getStartTime());
        requestResult.setEndTime(result.getEndTime());
        requestResult.setTotalAssertions(result.getAssertionResults().length);
        requestResult.setSuccess(result.isSuccessful());
        requestResult.setError(result.getErrorCount());
        requestResult.setScenario(result.getScenario());
        if (result instanceof HTTPSampleResult) {
            HTTPSampleResult res = (HTTPSampleResult) result;
            requestResult.setCookies(res.getCookies());
        }

        for (SampleResult subResult : result.getSubResults()) {
            requestResult.getSubRequestResults().add(getRequestResult(subResult));
        }
        ResponseResult responseResult = requestResult.getResponseResult();
        responseResult.setBody(result.getResponseDataAsString());
        responseResult.setHeaders(result.getResponseHeaders());
        responseResult.setLatency(result.getLatency());
        responseResult.setResponseCode(result.getResponseCode());
        responseResult.setResponseSize(result.getResponseData().length);
        responseResult.setResponseTime(result.getTime());
        responseResult.setResponseMessage(result.getResponseMessage());
        if (JMeterVars.get(result.hashCode()) != null && CollectionUtils.isNotEmpty(JMeterVars.get(result.hashCode()).entrySet())) {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, Object> entry : JMeterVars.get(result.hashCode()).entrySet()) {
                builder.append(entry.getKey()).append("：").append(entry.getValue()).append("\n");
            }
            if (StringUtils.isNotEmpty(builder)) {
                responseResult.setVars(builder.toString());
            }
            JMeterVars.remove(result.hashCode());
        }
        for (AssertionResult assertionResult : result.getAssertionResults()) {
            ResponseAssertionResult responseAssertionResult = getResponseAssertionResult(assertionResult);
            if (responseAssertionResult.isPass()) {
                requestResult.addPassAssertions();
            }
            //xpath 提取错误会添加断言错误
            if (StringUtils.isBlank(responseAssertionResult.getMessage()) ||
                    (StringUtils.isNotBlank(responseAssertionResult.getName()) && !responseAssertionResult.getName().endsWith("XPath2Extractor"))) {
                responseResult.getAssertions().add(responseAssertionResult);
            }
        }
        responseResult.setConsole(getConsole());

        return requestResult;
    }

    private String getMethod(SampleResult result) {
        String body = result.getSamplerData();
        // Dubbo Protocol
        String start = "RPC Protocol: ";
        String end = "://";
        if (StringUtils.contains(body, start)) {
            String protocol = StringUtils.substringBetween(body, start, end);
            if (StringUtils.isNotEmpty(protocol)) {
                return protocol.toUpperCase();
            }
            return RequestType.DUBBO;
        } else if (StringUtils.contains(result.getResponseHeaders(), "url:jdbc")) {
            return "SQL";
        } else {
            // Http Method
            String method = StringUtils.substringBefore(body, " ");
            for (HttpMethod value : HttpMethod.values()) {
                if (StringUtils.equals(method, value.name())) {
                    return method;
                }
            }
            return "Request";
        }
    }

    private void setParam(BackendListenerContext context) {
        this.testId = context.getParameter(TEST_ID);
        this.setReportId = context.getParameter(TEST_REPORT_ID);
        this.runMode = context.getParameter("runMode");
        this.isDebug = StringUtils.equals(context.getParameter("DEBUG"), "DEBUG") ? true : false;
        this.userId = context.getParameter("USER_ID");
        if (StringUtils.isBlank(this.runMode)) {
            this.runMode = ApiRunMode.RUN.name();
        }
    }

    private ResponseAssertionResult getResponseAssertionResult(AssertionResult assertionResult) {
        ResponseAssertionResult responseAssertionResult = new ResponseAssertionResult();
        responseAssertionResult.setName(assertionResult.getName());
        responseAssertionResult.setPass(!assertionResult.isFailure() && !assertionResult.isError());
        if (!responseAssertionResult.isPass()) {
            responseAssertionResult.setMessage(assertionResult.getFailureMessage());
        }
        return responseAssertionResult;
    }

}
