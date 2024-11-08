package io.camunda.executewithresult.executor;

import io.camunda.tasklist.CamundaTaskListClient;
import io.camunda.tasklist.dto.Task;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.api.worker.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class WithResultAPI {
  Logger logger = LoggerFactory.getLogger(WithResultAPI.class.getName());

  private final static String PROCESS_VARIABLE_SNITCH = "SNITCH";

  private final boolean doubleCheck;
  Random random = new Random();

  private final ZeebeClient zeebeClient;

  private final CamundaTaskListClient taskClient;

  private final boolean useTaskAPI;

  HandleMarker handleMarker = new HandleMarker();

  public WithResultAPI(ZeebeClient zeebeClient,
                       CamundaTaskListClient taskClient,
                       boolean doubleCheck,
                       boolean useTaskAPI) {
    this.zeebeClient = zeebeClient;
    this.taskClient = taskClient;
    this.doubleCheck = doubleCheck;
    this.useTaskAPI = useTaskAPI;

  }

  /**
   * executeTaskWithResult
   *
   * @param userTask            user task to execute
   * @param assignUser          the user wasn't assign to the user task, so do it
   * @param userName            userName to execute the user task
   * @param variables           Variables to update the task at completion
   * @param timeoutDurationInMs maximum duration time, after the ExceptionWithResult.timeOut is true
   * @return the result variable
   * @throws Exception for any error
   */
  public ExecuteWithResult executeTaskWithResult(Task userTask,
                                                 boolean assignUser,
                                                 String userName,
                                                 Map<String, Object> variables,
                                                 long timeoutDurationInMs) throws Exception {
    // We need to create a unique ID
    Long beginTime = System.currentTimeMillis();
    String jobKey = userTask.getId();

    logger.debug("ExecuteTaskWithResult[{}]", jobKey);
    int snitchValue = random.nextInt(10000);

    LockObjectTransporter lockObjectTransporter = new LockObjectTransporter();
    lockObjectTransporter.jobKey = jobKey;
    synchronized (lockObjectsMap) {
      lockObjectsMap.put(jobKey, lockObjectTransporter);
    }
    // Now, create a worker just for this jobKey
    logger.debug("Register worker[{}]", "end-result-" + jobKey);
    JobWorker worker = zeebeClient.newWorker()
        .jobType("end-result-" + jobKey)
        .handler(handleMarker)
        .streamEnabled(true)
        .open();

    Map<String, Object> userVariables = new HashMap<>();
    userVariables.put("jobKey", jobKey);
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
        return executeWithResult;
      }
    } else {
      try {
        if (assignUser)
          zeebeClient.newUserTaskAssignCommand(Long.parseLong(userTask.getId())).assignee("demo").send().join();
        zeebeClient.newUserTaskCompleteCommand(Long.parseLong(userTask.getId())).variables(userVariables).send().join();
      } catch (Exception e) {
        logger.error("Can't complete Task [{}] : {}", userTask.getId(), e.getMessage());
        executeWithResult.taskNotFound = true;
        return executeWithResult;
      }
    }

    // Now, we block the thread and wait for a result
    lockObjectTransporter.waitForResult(timeoutDurationInMs);

    logger.info("Receive answer jobKey[{}] notification? {} inprogress{}", jobKey, lockObjectTransporter.notification,
        lockObjectsMap.size());

    // retrieve the taskId where the currentprocess instance is
    executeWithResult.elementId = lockObjectTransporter.elementId;
    executeWithResult.elementInstanceKey = lockObjectTransporter.elementInstanceKey;

    // we got the result
    // we can close the worker now
    worker.close();
    synchronized (lockObjectsMap) {
      lockObjectsMap.remove(jobKey);
    }

    Long endTime = System.currentTimeMillis();
    executeWithResult.processInstance = userTask.getProcessInstanceKey();
    executeWithResult.executionTime = endTime - beginTime;

    if (lockObjectTransporter.notification) {
      executeWithResult.timeOut = false;
      executeWithResult.processVariables = lockObjectTransporter.processVariables;
      String doubleCheckAnalysis = "";
      if (doubleCheck) {
        String jobKeyProcess = (String) lockObjectTransporter.processVariables.get("jobKey");
        Integer snitchProcess = (Integer) lockObjectTransporter.processVariables.get(PROCESS_VARIABLE_SNITCH);
        doubleCheckAnalysis = snitchProcess == null || !snitchProcess.equals(snitchValue) ?
            String.format("Snitch_Different(snitch[%1d] SnichProcess[%2d])", snitchValue, snitchProcess) :
            "Snitch_marker_OK";
      }
      logger.debug("RESULT JobKey[{}] in {} ms (timeout {} ms) Pid[{}] {} variables[{}]", jobKey, endTime - beginTime,
          timeoutDurationInMs, userTask.getProcessInstanceKey(), doubleCheckAnalysis,
          lockObjectTransporter.processVariables);
    } else {
      executeWithResult.timeOut = true;
      executeWithResult.processVariables = null;

      logger.debug("RESULT TIMEOUT  JobKey[{}]  in {} ms (timeout {} ms) Pid[{}] ", jobKey, endTime - beginTime,
          timeoutDurationInMs, userTask.getProcessInstanceKey());
    }

    return executeWithResult;
  }

  /**
   * processInstanceWithResult
   *
   * @param processId           processId to start
   * @param variables           Variables to update the task at completion
   * @param timeoutDurationInMs maximum duration time, after the ExceptionWithResult.timeOut is true
   * @return the result status
   * @throws Exception in case of error
   */
  public ExecuteWithResult processInstanceWithResult(String processId,
                                                     Map<String, Object> variables,
                                                     long timeoutDurationInMs) throws Exception {
    // To be done
    return null;
  }

  /**
   * Handle the job. This worker register under the correct topic, and capture when it's come here
   */
  private class HandleMarker implements JobHandler {
    public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {
      // Get the variable "lockKey"
      String jobKey = (String) activatedJob.getVariable("jobKey");
      logger.info("Handle marker for jobKey[{}]", jobKey);
      LockObjectTransporter lockObjectTransporter = lockObjectsMap.get(jobKey);

      if (lockObjectTransporter == null) {
        logger.error("No object for jobKey[{}]", jobKey);
        return;
      }
      lockObjectTransporter.processVariables = activatedJob.getVariablesAsMap();
      lockObjectTransporter.elementId = activatedJob.getElementId();
      lockObjectTransporter.elementInstanceKey = activatedJob.getElementInstanceKey();
      logger.debug("HandleMarker jobKey[{}] variables[{}]", jobKey, lockObjectTransporter.processVariables);

      // Notify the thread waiting on this item
      lockObjectTransporter.notifyResult();
    }
  }

  private class LockObjectTransporter {
    public String jobKey;
    // With result will return the process variable here
    public Map<String, Object> processVariables;
    // elementId is the ID of the element, defined as the ID in the modeler. Example "ReviewApplication"
    public String elementId;
    // EleemntKey is the uniq key. Each instance has it's own key
    public long elementInstanceKey;

    public boolean notification = false;

    public synchronized void waitForResult(long timeoutDurationInMs) {
      try {
        logger.debug("Wait on object[{}]", this);
        wait(timeoutDurationInMs);
      } catch (InterruptedException e) {
        logger.error("Can' wait ", e);
      }
    }

    public synchronized void notifyResult() {
      logger.debug("Notify on object[{}]", this);
      notification = true;
      notify();
    }
  }

  static final Map<String, LockObjectTransporter> lockObjectsMap = new HashMap<>();

}
