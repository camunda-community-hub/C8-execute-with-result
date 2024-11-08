package io.camunda.executewithresult.worker;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.api.worker.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LogWorker implements JobHandler {
  public static final String PROCESS_VARIABLE_PLEASELOG = "pleaselog";
  public static final String PROCESS_VARIABLE_MESSAGE = "message";
  Logger logger = LoggerFactory.getLogger(LogWorker.class.getName());

  public static JobWorker registerWorker(ZeebeClient zeebeClient) {
    return zeebeClient.newWorker().jobType("log").handler(new LogWorker()).open();

  }

  public void handle(JobClient jobClient, ActivatedJob job) throws Exception {

    Object message = job.getVariablesAsMap().get(PROCESS_VARIABLE_MESSAGE);
    Object pleaseLog = job.getVariablesAsMap().get(PROCESS_VARIABLE_PLEASELOG);

    if (pleaseLog != null && "true".equalsIgnoreCase(pleaseLog.toString()))
      logger.info("WorkerLog. message[{}]", message);

    jobClient.newCompleteCommand(job.getKey()).send();

  }

}
