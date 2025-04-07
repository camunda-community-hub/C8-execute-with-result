package io.camunda.executewithresult.executor;

import io.camunda.tasklist.CamundaTaskListClient;
import io.camunda.tasklist.dto.Task;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import io.camunda.zeebe.client.api.response.PublishMessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WithResultAPI {
    public static final String PROCESS_VARIABLE_JOB_KEY = "jobKey";
    public static final String PROCESS_VARIABLE_TOPIC_END_RESULT = "topicEndResult";
    private final static String PROCESS_VARIABLE_SNITCH = "SNITCH";
    /**
     * Create a more large scheduler to handle if many timeout fire at the same time
     */
    private static final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4);
    private final boolean doubleCheck;
    private final ZeebeClient zeebeClient;
    private final CamundaTaskListClient taskClient;
    private final boolean useTaskAPI;
    Logger logger = LoggerFactory.getLogger(WithResultAPI.class.getName());
    Random random = new Random();
    ResultWorker resultWorker;

    public WithResultAPI(ZeebeClient zeebeClient,
                         CamundaTaskListClient taskClient,
                         boolean doubleCheck,
                         boolean useTaskAPI,
                         ResultWorker.WorkerImplementation resultWorker) {
        this.zeebeClient = zeebeClient;
        this.taskClient = taskClient;
        this.doubleCheck = doubleCheck;
        this.useTaskAPI = useTaskAPI;
        String podName = String.valueOf(System.currentTimeMillis());
        try {
            podName = InetAddress.getLocalHost().getHostName();

        } catch (Exception e) {
            logger.error("Can't get inetAddress: " + e.getMessage());
        }

        switch (resultWorker) {
            case HOST -> this.resultWorker = new ResultWorkerHost(zeebeClient, podName, this);
            case DYNAMIC -> this.resultWorker = new ResultWorkerDynamic(zeebeClient, this);
        }
    }


    /**
     * executeTaskWithResult
     *
     * @param userTask        user task to execute
     * @param assignUser      the user wasn't assign to the user task, so do it
     * @param userName        userName to execute the user task
     * @param variables       Variables to update the task at completion
     * @param timeoutDuration maximum duration time, after the ExceptionWithResult.timeOut is true
     * @return the result variable
     * @throws Exception for any error
     */
    public CompletableFuture<ExecuteWithResult> executeTaskWithResult(Task userTask,
                                                                      boolean assignUser,
                                                                      String userName,
                                                                      Map<String, Object> variables,
                                                                      String prefixTopicWorker,
                                                                      Duration timeoutDuration) throws Exception {
        // We need to create a unique ID
        String jobKey = userTask.getId();

        logger.debug("ExecuteTaskWithResult[{}]", jobKey);
        int snitchValue = random.nextInt(10000);

        // get the transporter
        ResultWorkerDynamic.LockObjectTransporter lockObjectTransporter = resultWorker.openTransaction("ExecuteTask", prefixTopicWorker, jobKey, ResultWorker.LockObjectTransporter.CALLER.USERTASK);
        lockObjectTransporter.userTask = userTask;
        lockObjectTransporter.timeoutDuration = timeoutDuration;

        Map<String, Object> userVariables = new HashMap<>();
        userVariables.put(PROCESS_VARIABLE_JOB_KEY, jobKey);
        userVariables.put(PROCESS_VARIABLE_TOPIC_END_RESULT, resultWorker.getTopic("userTask", prefixTopicWorker, jobKey));
        userVariables.putAll(variables);
        if (doubleCheck)
            userVariables.put(PROCESS_VARIABLE_SNITCH, snitchValue);
        ExecuteWithResult executeWithResult = new ExecuteWithResult();

        // save the variable jobId
        if (useTaskAPI) {
            try {
                if (assignUser)
                    taskClient.claim(userTask.getId(), userName);
                taskClient.completeTask(userTask.getId(), userVariables);
            } catch (Exception e) {
                logger.error("Can't complete Task [{}] : {}", userTask.getId(), e.getMessage());
                executeWithResult.taskNotFound = true;
                lockObjectTransporter.future.complete(executeWithResult);
                return lockObjectTransporter.future;
            }
        } else {
            try {
                if (assignUser)
                    zeebeClient.newUserTaskAssignCommand(Long.parseLong(userTask.getId())).assignee("demo").send().join();
                zeebeClient.newUserTaskCompleteCommand(Long.parseLong(userTask.getId())).variables(userVariables).send().join();
            } catch (Exception e) {
                logger.error("Can't complete Task [{}] : {}", userTask.getId(), e.getMessage());
                executeWithResult.taskNotFound = true;
                lockObjectTransporter.future.complete(executeWithResult);
            }
        }

        // Now, we block the thread and wait for a result
        scheduler.schedule(() -> {
            if (!lockObjectTransporter.future.isDone()) {
                executeWithResult.timeOut = true;
                lockObjectTransporter.future.complete(executeWithResult);
            }
        }, timeoutDuration.toMillis(), TimeUnit.MILLISECONDS);

        return lockObjectTransporter.future;
    }

    /**
     * Complete execute task with result: the worker handle the result, and call back.
     *
     * @param lockObjectTransporter
     */
    public void completeLaterExecuteTaskWithResult(ResultWorker.LockObjectTransporter lockObjectTransporter) {
        if (lockObjectTransporter.future.isDone())
            return;
        ExecuteWithResult executeWithResult = new ExecuteWithResult();


        // retrieve the taskId where the currentprocess instance is
        executeWithResult.elementId = lockObjectTransporter.elementId;
        executeWithResult.elementInstanceKey = lockObjectTransporter.elementInstanceKey;

        // we got the result
        // we can close the worker now
        resultWorker.closeTransaction(lockObjectTransporter);

        Long endTime = System.currentTimeMillis();
        executeWithResult.processInstance = Long.valueOf(lockObjectTransporter.userTask.getProcessInstanceKey());
        executeWithResult.executionTime = endTime - lockObjectTransporter.beginTime;

        executeWithResult.timeOut = false;
        executeWithResult.processVariables = lockObjectTransporter.processVariables;
        String doubleCheckAnalysis = "";
        if (doubleCheck) {
            String jobKeyProcess = (String) lockObjectTransporter.processVariables.get("jobKey");
            Integer snitchProcess = (Integer) lockObjectTransporter.processVariables.get(PROCESS_VARIABLE_SNITCH);
            doubleCheckAnalysis = snitchProcess == null || !snitchProcess.equals(lockObjectTransporter.snitchValue) ?
                    String.format("Snitch_Different(snitch[%1d] SnichProcess[%2d])", lockObjectTransporter.snitchValue, snitchProcess) :
                    "Snitch_marker_OK";
        }
        logger.debug("RESULT JobKey[{}] in {} ms (timeout {} ms) Pid[{}] {} variables[{}]", lockObjectTransporter.jobKey, endTime - lockObjectTransporter.beginTime,
                lockObjectTransporter.timeoutDuration.toMillis(), lockObjectTransporter.userTask.getProcessInstanceKey(), doubleCheckAnalysis,
                lockObjectTransporter.processVariables);


        lockObjectTransporter.future.complete(executeWithResult);
    }


    /**
     * processInstanceWithResult
     *
     * @param processId       processId to start
     * @param variables       Variables to update the task at completion
     * @param jobKey          key to wait for the worker
     * @param timeoutDuration maximum duration time, after the ExceptionWithResult.timeOut is true
     * @return the result status
     * @throws Exception in case of error
     */
    public CompletableFuture<ExecuteWithResult> processInstanceWithResult(String processId,
                                                                          Map<String, Object> variables,
                                                                          String jobKey,
                                                                          String prefixTopicWorker,
                                                                          Duration timeoutDuration) throws Exception {

        // We need to create a unique ID

        logger.debug("ExecuteTaskWithResult[{}]", jobKey);


        // get the transporter
        ResultWorker.LockObjectTransporter lockObjectTransporter = resultWorker.openTransaction("createProcessInstance", prefixTopicWorker, jobKey, ResultWorker.LockObjectTransporter.CALLER.PROCESSINSTANCE);
        lockObjectTransporter.snitchValue = random.nextInt(10000);
        lockObjectTransporter.timeoutDuration = timeoutDuration;

        Map<String, Object> processVariables = new HashMap<>();
        processVariables.put(PROCESS_VARIABLE_JOB_KEY, jobKey);
        processVariables.put(PROCESS_VARIABLE_TOPIC_END_RESULT, resultWorker.getTopic("createProcessInstance", prefixTopicWorker, jobKey));
        processVariables.putAll(variables);
        ExecuteWithResult executeWithResult = new ExecuteWithResult();

        // save the variable jobId
        try {

            ProcessInstanceEvent processInstanceEvent = zeebeClient.newCreateInstanceCommand().bpmnProcessId(processId).latestVersion().variables(processVariables).send().join();
            executeWithResult.processInstance = processInstanceEvent.getProcessInstanceKey();
            // logger.info("Create process instance {} jobKey [{}]", executeWithResult.processInstance,jobKey);
        } catch (Exception e) {
            logger.error("Can't create process instance[{}] : {}", processId, e.getMessage());
            executeWithResult.creationError = true;
            lockObjectTransporter.future.complete(executeWithResult);
            return lockObjectTransporter.future;
        }

        // Now, we block the thread and wait for a result
        scheduler.schedule(() -> {
            if (!lockObjectTransporter.future.isDone()) {
                executeWithResult.timeOut = true;
                lockObjectTransporter.future.complete(executeWithResult);
            }
        }, timeoutDuration.toMillis(), TimeUnit.MILLISECONDS);

        return lockObjectTransporter.future;
    }

    /**
     * Callback here when we got a result
     *
     * @param lockObjectTransporter
     */
    public void completeLaterProcessInstanceWithResult(ResultWorker.LockObjectTransporter lockObjectTransporter) {
        if (lockObjectTransporter.future.isDone())
            return;

        // logger.debug("Receive answer jobKey[{}] notification? {} inprogress {}", jobKey, lockObjectTransporter.notification, lockObjectsMap.size());
        ExecuteWithResult executeWithResult = new ExecuteWithResult();

        // retrieve the taskId where the currentprocess instance is
        executeWithResult.elementId = lockObjectTransporter.elementId;
        executeWithResult.elementInstanceKey = lockObjectTransporter.elementInstanceKey;

        resultWorker.closeTransaction(lockObjectTransporter);

        Long endTime = System.currentTimeMillis();
        executeWithResult.executionTime = endTime - lockObjectTransporter.beginTime;

        executeWithResult.timeOut = false;
        executeWithResult.processVariables = lockObjectTransporter.processVariables;
        String doubleCheckAnalysis = "";
        if (doubleCheck) {
            String jobKeyProcess = (String) lockObjectTransporter.processVariables.get("jobKey");
            Integer snitchProcess = (Integer) lockObjectTransporter.processVariables.get(PROCESS_VARIABLE_SNITCH);
            doubleCheckAnalysis = snitchProcess == null || !snitchProcess.equals(lockObjectTransporter.snitchValue) ?
                    String.format("Snitch_Different(snitch[%1d] SnichProcess[%2d])", lockObjectTransporter.snitchValue, snitchProcess) :
                    "Snitch_marker_OK";
        }
        logger.debug("RESULT JobKey[{}] in {} ms (timeout {} ms) Pid[{}] {} variables[{}]", lockObjectTransporter.jobKey, endTime - lockObjectTransporter.beginTime,
                lockObjectTransporter.timeoutDuration.toMillis(), executeWithResult.processInstance, doubleCheckAnalysis,
                lockObjectTransporter.processVariables);


        lockObjectTransporter.future.complete(executeWithResult);


    }


    /**
     * Publish message and wait until the worker handle the point
     *
     * @param messageName       message name
     * @param correlationKey    key to send to the corelation
     * @param timeToLive        Duration to live the message
     * @param variables         variables to send to the message
     * @param jobKey            Key, must be unique to match when the message will arrive at the handle method
     * @param prefixTopicWorker prefix to use for the worker
     * @param timeoutDuration   duration, after this delay, the API will be unlocked
     * @return a Future
     * @throws Exception
     */
    public CompletableFuture<ExecuteWithResult> publishNewMessageWithResult(String messageName,
                                                                            String correlationKey,
                                                                            Duration timeToLive,
                                                                            Map<String, Object> variables,
                                                                            String jobKey,
                                                                            String prefixTopicWorker,
                                                                            Duration timeoutDuration) throws Exception {
        logger.debug("publishNewMessageWithResult[{}]", correlationKey);

        // get the transporter
        ResultWorkerDynamic.LockObjectTransporter lockObjectTransporter = resultWorker.openTransaction("publishMessage",
                prefixTopicWorker,
                jobKey,
                ResultWorker.LockObjectTransporter.CALLER.MESSAGE);
        assert (lockObjectTransporter.future != null);

        lockObjectTransporter.timeoutDuration = timeoutDuration;
        // save the variable jobId
        Map<String, Object> messageVariables = new HashMap<>();
        messageVariables.put(WithResultAPI.PROCESS_VARIABLE_JOB_KEY, jobKey);
        messageVariables.put(PROCESS_VARIABLE_TOPIC_END_RESULT, resultWorker.getTopic("createProcessInstance", prefixTopicWorker, jobKey));
        messageVariables.putAll(variables);
        logger.debug("Register worker[{}]", resultWorker.getTopic("createProcessInstance", prefixTopicWorker, jobKey));
        ExecuteWithResult executeWithResult = new ExecuteWithResult();


        try {

            PublishMessageResponse publishMessageResponse = zeebeClient.newPublishMessageCommand()
                    .messageName(messageName)
                    .correlationKey(correlationKey)
                    .variables(messageVariables)
                    .timeToLive(timeToLive)
                    .send()
                    .join();


            // logger.info("Create process instance {} jobKey [{}]", executeWithResult.processInstance,jobKey);
        } catch (Exception e) {
            logger.error("Can't send message[{}] : {}", messageName, e.getMessage());
            executeWithResult.messageError = true;
            lockObjectTransporter.future.complete(executeWithResult);
        }

        scheduler.schedule(() -> {
            if (!lockObjectTransporter.future.isDone()) {
                executeWithResult.timeOut = true;
                lockObjectTransporter.future.complete(executeWithResult);
            }
        }, timeoutDuration.toMillis(), TimeUnit.MILLISECONDS);


        return lockObjectTransporter.future;
    }

    public void completeLaterPublishMessageWithResult(ResultWorker.LockObjectTransporter lockObjectTransporter) {
        if (lockObjectTransporter.future.isDone())
            return;

        ExecuteWithResult executeWithResult = new ExecuteWithResult();
        logger.debug("Receive answer jobKey[{}] ", lockObjectTransporter.jobKey);

        // retrieve the taskId where the currentprocess instance is
        executeWithResult.elementId = lockObjectTransporter.elementId;
        executeWithResult.elementInstanceKey = lockObjectTransporter.elementInstanceKey;

        resultWorker.closeTransaction(lockObjectTransporter);

        Long endTime = System.currentTimeMillis();
        executeWithResult.executionTime = endTime - lockObjectTransporter.beginTime;

        executeWithResult.timeOut = false;
        executeWithResult.processVariables = lockObjectTransporter.processVariables;
        String doubleCheckAnalysis = "";
        logger.debug("RESULT JobKey[{}] in {} ms (timeout {} ms) Pid[{}] {} variables[{}]", lockObjectTransporter.jobKey,
                endTime - lockObjectTransporter.beginTime,
                lockObjectTransporter.timeoutDuration.toMillis(), executeWithResult.processInstance, doubleCheckAnalysis,
                lockObjectTransporter.processVariables);

        lockObjectTransporter.future.complete(executeWithResult);

    }

}
