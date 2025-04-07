package io.camunda.executewithresult.executor;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This implementation use the Dynamic worker implementation
 */
public class ResultWorkerDynamic extends ResultWorker {
    static final Map<String, LockObjectTransporter> lockObjectsMap = new ConcurrentHashMap<>();
    final ZeebeClient zeebeClient;
    final HandleMarkerDynamicWorker handleMarkerDynamicWorker;
    Logger logger = LoggerFactory.getLogger(ResultWorkerDynamic.class.getName());

    ResultWorkerDynamic(ZeebeClient zeebeClient, WithResultAPI withResultAPI) {
        this.zeebeClient = zeebeClient;
        handleMarkerDynamicWorker = new HandleMarkerDynamicWorker(withResultAPI);
    }


    /**
     * Open the transaction. Create a dedicated worker for this transaction.
     *
     * @param context
     * @param prefixTopicWorker
     * @param jobKey
     * @return
     */
    @Override
    public LockObjectTransporter openTransaction(String context, String prefixTopicWorker, String jobKey, LockObjectTransporter.CALLER caller) {
        LockObjectTransporter lockObjectTransporter = new LockObjectTransporter();
        lockObjectTransporter.jobKey = jobKey;
        lockObjectTransporter.context = context;
        lockObjectTransporter.caller = caller;

        logger.debug("Register worker[{}]", getTopic(context, prefixTopicWorker, jobKey));

        lockObjectTransporter.worker = zeebeClient.newWorker()
                .jobType(getTopic(context, prefixTopicWorker, jobKey))
                .handler(handleMarkerDynamicWorker)
                .streamEnabled(true)
                .open();

        synchronized (lockObjectsMap) {
            lockObjectsMap.put(jobKey, lockObjectTransporter);
        }

        return lockObjectTransporter;
    }

    /**
     * The topic is unique for each job worker. So it contains the prefix and the jobkey
     *
     * @param context           information to log if needed
     * @param prefixTopicWorker prefix of the worker
     * @param jobKey            uniq key
     * @return the topic
     */
    @Override
    public String getTopic(String context, String prefixTopicWorker, String jobKey) {
        return prefixTopicWorker + jobKey;
    }


    public void closeTransaction(LockObjectTransporter lockObjectTransporter) {
        // we got the result
        // we can close the worker now
        lockObjectTransporter.worker.close();
        synchronized (lockObjectsMap) {
            lockObjectsMap.remove(lockObjectTransporter.jobKey);
        }
    }


    /**
     * Handle the job. This worker register under the correct topic, and capture when it's come here
     */
    private class HandleMarkerDynamicWorker implements JobHandler {
        final WithResultAPI withResultAPI;

        HandleMarkerDynamicWorker(WithResultAPI withResultAPI) {
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

        }
    }


}
