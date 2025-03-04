package io.camunda.executewithresult.scenario;

import io.camunda.tasklist.CamundaTaskListClient;
import io.camunda.tasklist.dto.Pagination;
import io.camunda.tasklist.dto.TaskList;
import io.camunda.tasklist.dto.TaskSearch;
import io.camunda.tasklist.dto.TaskState;
import io.camunda.tasklist.exception.TaskListException;
import io.camunda.tasklist.generated.model.TaskOrderBy;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import io.camunda.zeebe.client.api.search.filter.UserTaskFilter;
import io.camunda.zeebe.client.api.search.query.UserTaskQuery;
import io.camunda.zeebe.client.api.search.response.SearchQueryResponse;
import io.camunda.zeebe.client.api.search.response.UserTask;
import io.camunda.zeebe.client.impl.search.SearchRequestPageImpl;
import io.camunda.zeebe.client.impl.search.filter.UserTaskFilterImpl;
import io.camunda.zeebe.client.impl.search.sort.UserTaskSortImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class EngineCommand {
    Logger logger = LoggerFactory.getLogger(EngineCommand.class.getName());

    private final ZeebeClient zeebeClient;

    private final CamundaTaskListClient taskClient;

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

    /**
     * create one process instance and return it
     *
     * @param processId processId to create the process instance
     * @param variables process variable
     * @return the process instance
     */
    public ProcessInstanceEvent createProcessInstancesWithResult(String processId, Map<String, Object> variables) {

        ProcessInstanceEvent processInstance = zeebeClient.newCreateInstanceCommand() //
                .bpmnProcessId(processId).latestVersion() //
                .variables(variables) //
                .send().join();

        logger.info("Created process instance [{}]", processInstance.getProcessInstanceKey());
        return processInstance;
    }

    /**
     * Search user task using the AccessTaskAPI
     *
     * @param processID processId to search (not working for the moment)
     * @return TaskList
     */
    public TaskList searchUserTaskWithTaskList(String processID) {
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

        try {
            return taskClient.getTasks(taskSearch);
        } catch (TaskListException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Ask the Zeebe engine to return the list of task
     * Visit https://docs.camunda.io/docs/apis-tools/camunda-api-rest/specifications/query-user-tasks-alpha/
     *
     * @param processInstanceKey processid to query
     * @return the list of task
     */
    public List<UserTask> searchUserTaskWithZeebe(Long processInstanceKey, String activityId) {
        UserTaskQuery userTaskQuery = zeebeClient.newUserTaskQuery();
        UserTaskFilter userTaskFilter = new UserTaskFilterImpl();
        userTaskFilter = userTaskFilter.state("CREATED");
        if (processInstanceKey != null) {
            // userTaskFilter = userTaskFilter.processInstanceKey(processInstanceKey);
        }
        if (activityId != null) {
            // userTaskFilter = userTaskFilter.elementId(activityId);
        }
        userTaskQuery.filter(userTaskFilter);
        userTaskQuery.page(new SearchRequestPageImpl().from(0).limit(100));
        userTaskQuery.sort(new UserTaskSortImpl().creationDate().desc());

        SearchQueryResponse<UserTask> result = userTaskQuery.send().join();
        logger.info("SearchTask processId[{}] ActivityId[{}] : found {} result", processInstanceKey, activityId, result.items().

                size());
        return result.items();

    }
}
