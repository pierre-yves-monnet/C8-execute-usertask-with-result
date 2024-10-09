package io.camunda.executewithresult.scenario;

import io.camunda.tasklist.CamundaTaskListClient;
import io.camunda.tasklist.dto.Pagination;
import io.camunda.tasklist.dto.TaskList;
import io.camunda.tasklist.dto.TaskSearch;
import io.camunda.tasklist.dto.TaskState;
import io.camunda.tasklist.exception.TaskListException;
import io.camunda.zeebe.client.ZeebeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EngineCommand {
  Logger logger = LoggerFactory.getLogger(EngineCommand.class.getName());


  private ZeebeClient zeebeClient;

  private CamundaTaskListClient taskClient;

  public  EngineCommand(ZeebeClient zeebeClient, CamundaTaskListClient taskClient) {
    this.zeebeClient = zeebeClient;
    this.taskClient = taskClient;
  }


  /**
   * preparation of the test: generate XXX process instance
   */
  public void createProcessInstances(String processId,  int number) {
    for (int i = 0; i < number; i++) {
      zeebeClient.newCreateInstanceCommand().bpmnProcessId(processId).latestVersion().send().thenAccept(t -> {
        logger.info("Create process instance[{}]", t.getProcessInstanceKey());
      }).exceptionally(t -> {
        logger.error("Error while creating process instance");
        return null;
      });
    }
    logger.info("Creatd {} process instance sent",number);
  }



  public TaskList searchUserTask() {
    logger.info("------------------- Search for userTask to run");
    TaskSearch taskSearch = new TaskSearch();
    taskSearch.setState(TaskState.CREATED);
    taskSearch.setAssigned(Boolean.FALSE);
    taskSearch.setWithVariables(true);
    taskSearch.setPagination(new Pagination().setPageSize(100));

    TaskList tasksList = null;
    try {
      return taskClient.getTasks(taskSearch);
    } catch (TaskListException e) {
      throw new RuntimeException(e);
    }
  }




}
