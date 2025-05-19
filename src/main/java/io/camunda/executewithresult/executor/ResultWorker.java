package io.camunda.executewithresult.executor;

import io.camunda.tasklist.dto.Task;
import io.camunda.zeebe.client.api.worker.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public abstract class ResultWorker {
    Logger logger = LoggerFactory.getLogger(ResultWorker.class.getName());

    public abstract LockObjectTransporter openTransaction(String context, String prefixTopicWorker, String jobKey, LockObjectTransporter.CALLER caller);

    public abstract void closeTransaction(LockObjectTransporter lockObjectTransporter);

    public abstract String getTopic(String context, String prefixTopicWorker, String jobKey);

    public enum WorkerImplementation {DYNAMIC, HOST}

    public class LockObjectTransporter {
        public CALLER caller;
        public String jobKey;
        // With result will return the process variable here
        public Map<String, Object> processVariables;
        // elementId is the ID of the element, defined as the ID in the modeler. Example "ReviewApplication"
        public String elementId;
        // ElementKey is the uniq key. Each instance has its own key
        public long elementInstanceKey;
        public long beginTime = System.currentTimeMillis();
        public Duration timeoutDuration;
        public Task userTask;
        public CompletableFuture<ExecuteWithResult> future = new CompletableFuture<>();
        public String context;
        public JobWorker worker;
        public enum CALLER {USERTASK, PROCESSINSTANCE, MESSAGE}


    }
}
