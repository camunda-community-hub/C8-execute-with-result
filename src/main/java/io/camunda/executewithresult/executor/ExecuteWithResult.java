package io.camunda.executewithresult.executor;

import java.util.Map;

/**
 * Object returned by the method
 */
public class ExecuteWithResult {
    public Map<String, Object> processVariables;
    public boolean taskNotFound = false;
    public boolean creationError = false;
    public boolean messageError = false;
    public boolean timeOut = false;
    public long executionTime;
    public Long processInstance;

    // elementId is the ID of the element, defined as the ID in the modeler. Example "ReviewApplication"
    public String elementId;

    // elementInstanceKey is the uniq key. Each instance has it's own key
    public long elementInstanceKey;

}
