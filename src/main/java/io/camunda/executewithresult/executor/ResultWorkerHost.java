package io.camunda.executewithresult.executor;


import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.api.worker.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This implementation create one worker immediately, per host.
 * In the process variable, a value "jobKey" is present, to find the correct thread to unlock
 */
public class ResultWorkerHost extends ResultWorker {

    static final Map<String, LockObjectTransporter> lockObjectsMap = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, JobWorker> mapWorker = new ConcurrentHashMap<>();
    final ZeebeClient zeebeClient;
    final HandlerEndResult handleMarker;
    final String podName;
    Logger logger = LoggerFactory.getLogger(ResultWorkerHost.class.getName());

    ResultWorkerHost(ZeebeClient zeebeClient, String podName, WithResultAPI withResultAPI) {
        this.zeebeClient = zeebeClient;
        this.podName = podName;
        this.handleMarker = new HandlerEndResult(withResultAPI);
    }


    /**
     * Open the transaction. Worker may be created the first time, then reuse. It return a lockObjectTransporter, and the caller can modify it.
     * The caller will execute the command (create a process instance, execute the user task, publish the message). The caller must pass the jobKey
     * as a process variable WithResultAPI.PROCESS_VARIABLE_JOB_KEY. The handler retrieve the key, then the object, and will call the callback according
     * the caller.
     *
     * @param context           information
     * @param prefixTopicWorker prefix of the worker, to calculate the topic for the dynamic worker
     * @param jobKey            key for the handler to retrieve the correct lockObjetTransporter
     * @param caller            who call the transaction, to have the callback method
     * @return objectTransporter
     */
    @Override
    public LockObjectTransporter openTransaction(String context, String prefixTopicWorker, String jobKey, LockObjectTransporter.CALLER caller) {
        LockObjectTransporter lockObjectTransporter = new LockObjectTransporter();
        lockObjectTransporter.jobKey = jobKey;
        lockObjectTransporter.context = context;
        lockObjectTransporter.caller = caller;

        logger.debug("Register worker[{}] jobKey[{}]", getTopic(context, prefixTopicWorker, jobKey), jobKey);

        JobWorker worker = getWorker(getTopic(context, prefixTopicWorker, jobKey));

        synchronized (lockObjectsMap) {
            lockObjectsMap.put(jobKey, lockObjectTransporter);
        }

        return lockObjectTransporter;
    }

    /**
     * The topic is different per host
     *
     * @param context           context for the log if needed
     * @param prefixTopicWorker prefix to create the worker
     * @param jobKey            unique key
     * @return a topic
     */
    @Override
    public String getTopic(String context, String prefixTopicWorker, String jobKey) {
        return prefixTopicWorker + podName;
    }


    @Override
    public void closeTransaction(LockObjectTransporter lockObjectTransporter) {
        // we got the result
        // we can close the worker now
        synchronized (lockObjectsMap) {
            lockObjectsMap.remove(lockObjectTransporter.jobKey);
        }
    }


    private JobWorker getWorker(String topicName) {
        JobWorker worker = mapWorker.get(topicName);
        if (worker == null) {
            synchronized (this) {
                if (mapWorker.get(topicName) == null) {
                    worker = zeebeClient.newWorker()
                            .jobType(topicName)
                            .handler(handleMarker)
                            .streamEnabled(true)
                            .open();
                    mapWorker.put(topicName, worker);
                }
            }
        }
        return worker;

    }

    /**
     * Handle the job. This worker register under the correct topic, and capture when it's come here
     */
    private class HandlerEndResult implements JobHandler {
        WithResultAPI withResultAPI;

        HandlerEndResult(WithResultAPI withResultAPI) {
            this.withResultAPI = withResultAPI;
        }

        public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {
            // Get the variable "lockKey"
            jobClient.newCompleteCommand(activatedJob.getKey()).send();

            String jobKey = (String) activatedJob.getVariable(WithResultAPI.PROCESS_VARIABLE_JOB_KEY);
            // logger.info("Handle marker for jobKey[{}]", jobKey);
            ResultWorkerDynamic.LockObjectTransporter lockObjectTransporter = lockObjectsMap.get(jobKey);

            if (lockObjectTransporter == null) {
                logger.error("No object for jobKey[{}]", jobKey);
                return;
            }
            lockObjectTransporter.processVariables = activatedJob.getVariablesAsMap();
            lockObjectTransporter.elementId = activatedJob.getElementId();
            lockObjectTransporter.elementInstanceKey = activatedJob.getElementInstanceKey();
            logger.debug("HandleMarkerDynamicWorker jobKey[{}] variables[{}]", jobKey, lockObjectTransporter.processVariables);

            // notify withResult that we got the answer
            switch (lockObjectTransporter.caller) {
                case PROCESSINSTANCE -> withResultAPI.completeLaterProcessInstanceWithResult(lockObjectTransporter);
                case USERTASK -> withResultAPI.completeLaterExecuteTaskWithResult(lockObjectTransporter);
                case MESSAGE -> withResultAPI.completeLaterPublishMessageWithResult(lockObjectTransporter);

            }
            withResultAPI.completeLaterProcessInstanceWithResult(lockObjectTransporter);

        }
    }


}
