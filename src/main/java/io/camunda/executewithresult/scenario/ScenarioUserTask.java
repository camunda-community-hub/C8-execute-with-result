package io.camunda.executewithresult.scenario;

import io.camunda.common.auth.*;
import io.camunda.executewithresult.executor.ExecuteWithResult;
import io.camunda.executewithresult.executor.ResultWorker;
import io.camunda.executewithresult.executor.WithResultAPI;
import io.camunda.executewithresult.worker.DelayWorker;
import io.camunda.executewithresult.worker.LogWorker;
import io.camunda.tasklist.CamundaTaskListClient;
import io.camunda.tasklist.CamundaTaskListClientBuilder;
import io.camunda.tasklist.dto.Task;
import io.camunda.tasklist.dto.TaskList;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.worker.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

@Component
@ConfigurationProperties()

public class ScenarioUserTask {
    public static final String PROCESS_ID_USERTASK = "executeUserTaskWithResultListener";

    public static final String PROCESS_ID_PROCESSINSTANCE = "ExecuteProcessInstanceWithResult";
    @Value("${tasklist.url}")
    public String taskListUrl;
    @Value("${tasklist.username}")
    public String taskListUserName;
    @Value("${tasklist.userpassword:}")
    public String taskListUserPassword;
    @Value("${tasklist.clientId}")
    public String taskListClientId;
    @Value("${tasklist.clientSecret}")
    public String taskListClientSecret;
    @Value("${tasklist.taskListKeycloakUrl}")
    public String taskListKeycloakUrl;
    @Value("${usertaskwithresult.modeExecution:'single'}")
    public String modeExecution;
    @Value("${usertaskwithresult.pleaseLogWorker:'false'}")
    public Boolean pleaseLogWorker;
     @Value("${usertaskwithresult.useTaskAPI:'false'}")
    public Boolean useTaskAAPI;
    Logger logger = LoggerFactory.getLogger(ScenarioUserTask.class.getName());

    Random random = new Random();
    @Autowired
    ZeebeClient zeebeClient;
    WithResultAPI withResultAPI;
    EngineCommand engineCommand;
    List<JobWorker> listWorkers = new ArrayList<>();
    ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(100);
    Set<Long> registerUserTask = new HashSet<>();
    boolean initialisation = false;
    StatResult statResult = new StatResult();
    private CamundaTaskListClient taskClient;
    private int numberExecution = 0;

    /**
     * Initialize all environment
     */
    public void initialisation() {
        if (!connectionTaskList()) {
            return;
        }
        withResultAPI = new WithResultAPI(zeebeClient, taskClient, useTaskAAPI, ResultWorker.WorkerImplementation.HOST);
        engineCommand = new EngineCommand(zeebeClient, taskClient);
        // create workers
        listWorkers.add(DelayWorker.registerWorker(zeebeClient));
        listWorkers.add(LogWorker.registerWorker(zeebeClient));
        initialisation = true;
        logger.info("Initialisation done");
    }

    @Scheduled(fixedDelay = 30000)
    public void execute() {
        if (!initialisation)
            return;
        logger.info("ScenarioUserTask ---------------- Start a new batch");
        numberExecution++;
        try {
            if ("single" .equals(modeExecution)) {
                if (numberExecution > 1) {
                    logger.info("Single execution, do nothing");
                    return;
                }
             //   executeSingleExecutionUserTask();
                executeSingleExecutionProcessInstance();
            } else if ("multiple" .equals(modeExecution)) {
                executeMultipleExecution();
            }
        } catch (Exception e) {
            logger.error("Error execution [{}]", e);
        }
        logger.info("ScenarioUserTask ---------------- End batch");

    }

    /**
     * ExecuteOne Simple execution
     */
    public void executeSingleExecutionUserTask() throws Exception {
        try {
            engineCommand.createProcessInstances(PROCESS_ID_USERTASK,
                    Map.of(LogWorker.PROCESS_VARIABLE_PLEASELOG, Boolean.TRUE, DelayWorker.PROCESS_VARIABLE_CREDITSCORE,
                            random.nextInt(1000)), 1, true);
            int loop = 0;
            while (loop < 10) {
                loop++;
                TaskList taskList = engineCommand.searchUserTask(PROCESS_ID_USERTASK);
                if (taskList.getItems().isEmpty()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    continue;
                }
                for (Task task : taskList.getItems()) {
                    executeOneTask(task);
                }
            }
        } catch (Exception e) {
            logger.error("Exception {}", e);
        }
    }
    /**
     * ExecuteOne Simple execution
     */
    public void executeSingleExecutionProcessInstance() throws Exception {
        try {
            logger.info("ProcessInstanceWithResult Start");
                    ExecuteWithResult executeWithResult=withResultAPI.processInstanceWithResult(PROCESS_ID_PROCESSINSTANCE,
                    Map.of(LogWorker.PROCESS_VARIABLE_PLEASELOG, Boolean.TRUE,
                            DelayWorker.PROCESS_VARIABLE_CREDITSCORE,
                            random.nextInt(1000)),  "ProcessWithResult",Duration.ofMinutes(1)).join();
            logger.info("ProcessInstanceWithResult PI[{}] TimeOut (false expected)? {}",
                    executeWithResult.processInstance,
                    executeWithResult.timeOut);

        } catch (Exception e) {
            logger.error("Exception {}", e);
        }
    }
    /**
     *
     */
    public void executeMultipleExecution() {
        logger.info(">>>>>>>>>>>>>>>>>>>>>>>>>> Synthesis {} queueSize=[{}] <<<<<<<<<<<<<<<<<<<", statResult.getSynthesis(),
                registerUserTask.size());

        try {

            if (registerUserTask.size() < 10) {
                engineCommand.createProcessInstances(PROCESS_ID_USERTASK,
                        Map.of(LogWorker.PROCESS_VARIABLE_PLEASELOG, pleaseLogWorker, DelayWorker.PROCESS_VARIABLE_CREDITSCORE,
                                random.nextInt(1000)), 10, pleaseLogWorker.booleanValue());
            }

            TaskList tasksList = null;
            tasksList = engineCommand.searchUserTask(PROCESS_ID_USERTASK);
            // Register the task: the same task can show up multiple time because of the delay between Zee
            logger.info("------------------- Search for userTask to run found[{}]", tasksList.size());

            for (Task task : tasksList.getItems()) {
                if (registerUserTask.contains(Long.valueOf(task.getId())))
                    continue; // already executed
                registerUserTask.add(Long.valueOf(task.getId()));
                Callable<ExecuteWithResult> taskWithResultCallable = () -> {
                    return executeOneTask(task);
                    // Use lambda to pass the parameter
                };
                Future future = executor.submit(taskWithResultCallable);
            }

            // Ok, now we can purge the registerUserTask. If the task does not show up in the taskList, we can purge it
            Set<Long> taskIds = tasksList.getItems().stream().map(t -> Long.valueOf(t.getId()))  // Get the ID of each Task
                    .collect(Collectors.toSet());
            registerUserTask.retainAll(taskIds);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Each task? Let's create a unique thread to manage it with Result

    }

    private ExecuteWithResult executeOneTask(Task task) throws Exception {

        logger.info("Play with task [{}]", task.getId());
        long beginTimeRun = System.currentTimeMillis();
        ExecuteWithResult executeWithResult = withResultAPI.executeTaskWithResult(task, true,
                "demo",
                Map.of("Cake", "Cherry"),
                "UserTaskWithResult",
                Duration.ofMinutes(2)).join();


        long executionTime = System.currentTimeMillis() - beginTimeRun;

        // Check the result now
        if (executeWithResult.taskNotFound) {
            return executeWithResult;
        }
        if (!executeWithResult.timeOut) {
            Integer signature = (Integer) executeWithResult.processVariables.get(DelayWorker.PROCESS_VARIABLE_CREDITSCORE);
            Integer resultCalculation = (Integer) executeWithResult.processVariables.get(
                    DelayWorker.PROCESS_VARIABLE_CALCULATION);
            if (resultCalculation != signature.intValue() + 10)
                logger.error("Calculation is wrong, not the expected result Signature[{}] Result[{}] (expect signature+10)",
                        signature, resultCalculation);

        }
        statResult.addResult(executionTime, !executeWithResult.timeOut);
        // Use lambda to pass the parameter
        return executeWithResult;
    }

    /**
     * connection To TaskList
     *
     * @return true if the connection can be establish
     */
    private boolean connectionTaskList() {
        try {
            CamundaTaskListClientBuilder taskListBuilder = CamundaTaskListClient.builder();
            if (taskListClientId != null && !taskListClientId.isEmpty()) {

                taskListBuilder.taskListUrl(taskListUrl)
                        .selfManagedAuthentication(taskListClientId, taskListClientSecret, taskListKeycloakUrl);
            } else {
                SimpleConfig simpleConf = new SimpleConfig();
                simpleConf.addProduct(Product.TASKLIST,
                        new SimpleCredential(taskListUrl, taskListUserName, taskListUserPassword));
                Authentication auth = SimpleAuthentication.builder().withSimpleConfig(simpleConf).build();

                taskListBuilder.taskListUrl(taskListUrl).authentication(auth).cookieExpiration(Duration.ofSeconds(500));
            }
            logger.info("Connection to TaskList");
            taskClient = taskListBuilder.build();
            logger.info("Connection with success to TaskList");
            return true;
        } catch (Exception e) {
            logger.error("------------------ Connection error to taskList {}", e);
            return false;
        }
    }

    private class StatResult {
        public long sumExecutionTime = 0;
        public long badExecution = 0;
        public long successExecution = 0;

        public synchronized void addResult(long timeExecution, boolean success) {
            if (success) {
                sumExecutionTime += timeExecution;
                successExecution++;
            } else {
                badExecution++;
            }
        }

        public String getSynthesis() {
            return String.format("%1d ms average for %2d executions (error %d)",
                    sumExecutionTime / (successExecution == 0 ? 1 : successExecution), successExecution, badExecution);
        }
    }

}
