package io.camunda.executewithresult.scenario;

import io.camunda.tasklist.CamundaTaskListClient;
import io.camunda.tasklist.dto.Pagination;
import io.camunda.tasklist.dto.TaskList;
import io.camunda.tasklist.dto.TaskSearch;
import io.camunda.tasklist.dto.TaskState;
import io.camunda.tasklist.exception.TaskListException;
import io.camunda.tasklist.generated.model.TaskOrderBy;
import io.camunda.zeebe.client.ZeebeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class EngineCommand {
  Logger logger = LoggerFactory.getLogger(EngineCommand.class.getName());

  private ZeebeClient zeebeClient;

  private CamundaTaskListClient taskClient;

  public EngineCommand(ZeebeClient zeebeClient, CamundaTaskListClient taskClient) {
    this.zeebeClient = zeebeClient;
    this.taskClient = taskClient;
  }

  /**
   * preparation of the test: generate XXX process instance
   */
  public void createProcessInstances(String processId, Map<String, Object> variables, int number, boolean log) {
    for (int i = 0; i < number; i++) {
      zeebeClient.newCreateInstanceCommand() //
          .bpmnProcessId(processId).latestVersion() //
          .variables(variables) //
          .send().thenAccept(t -> { //
            if (log)
              logger.debug("Create process instance[{}]", t.getProcessInstanceKey());
          }).exceptionally(t -> {
            logger.error("Error while creating process instance {} ", t);
            return null;
          });
    }
    logger.info("Created {} process instance sent", number);
  }

  public TaskList searchUserTask(String processID) {
    // logger.info("------------------- Search for userTask to run");
    TaskSearch taskSearch = new TaskSearch();
    taskSearch.setState(TaskState.CREATED);
    taskSearch.setAssigned(Boolean.FALSE);
    taskSearch.setWithVariables(true);
    // taskSearch.setProcessDefinitionKey(processID);
    TaskOrderBy taskOrderBy = new TaskOrderBy();
    taskOrderBy.setField(TaskOrderBy.FieldEnum.CREATIONTIME);
    taskOrderBy.setOrder(TaskOrderBy.OrderEnum.DESC);

    Pagination pagination = new Pagination().setPageSize(1000).setSort(List.of(taskOrderBy));

    taskSearch.setPagination(pagination);

    TaskList tasksList = null;
    try {
      return taskClient.getTasks(taskSearch);
    } catch (TaskListException e) {
      throw new RuntimeException(e);
    }
  }

}
