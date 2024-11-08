package io.camunda.executewithresult.worker;

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

public class DelayWorker implements JobHandler {
  public static final String PROCESS_VARIABLE_CALCULATION = "calculation";
  public static final String PROCESS_VARIABLE_CREDITSCORE = "creditscore";
  public static final String PROCESS_VARIABLE_PLEASELOG = "pleaselog";
  public static final String PROCESS_VARIABLE_REVIEW="review";
  public static final String PROCESS_VARIABLE_DELAYMS = "delayms";
  Logger logger = LoggerFactory.getLogger(DelayWorker.class.getName());

  Random random = new Random();

  public static JobWorker registerWorker(ZeebeClient zeebeClient) {
    return zeebeClient.newWorker().jobType("delay").handler(new DelayWorker()).open();

  }

  public void handle(JobClient jobClient, ActivatedJob job) throws Exception {
    Object delayMsObj = job.getVariablesAsMap().get(PROCESS_VARIABLE_DELAYMS);
    Integer creditScore = (Integer) job.getVariablesAsMap().get(PROCESS_VARIABLE_CREDITSCORE);
    Object pleaseLog = job.getVariablesAsMap().get(PROCESS_VARIABLE_PLEASELOG);

    if (pleaseLog != null && "true".equalsIgnoreCase(pleaseLog.toString()))
      logger.info("WorkerDelay. sleep[{}] ms signature[{}]", delayMsObj, creditScore);

    if (delayMsObj != null) {
      long delayMs = Long.valueOf(delayMsObj.toString());
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException e) {
        logger.error("Interruption workerDelay");
      }
    }
    Map<String, Object> variables = new HashMap<>();
    variables.put(PROCESS_VARIABLE_CALCULATION, creditScore.intValue() + 10);
    variables.put(PROCESS_VARIABLE_REVIEW, random.nextInt(1000));
    jobClient.newCompleteCommand(job.getKey()).variables(variables).send();

  }

}
