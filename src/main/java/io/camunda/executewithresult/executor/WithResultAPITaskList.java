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

public class WithResultAPITaskList extends WithResultAPI{
    Logger logger = LoggerFactory.getLogger(WithResultAPITaskList.class.getName());

    private final static String PROCESS_VARIABLE_SNITCH = "SNITCH";

    private boolean doubleCheck = true;
    Random random = new Random();

    private ZeebeClient zeebeClient;

    private CamundaTaskListClient taskClient;



    public WithResultAPITaskList(ZeebeClient zeebeClient, CamundaTaskListClient taskClient, boolean doubleCheck) {
        this.zeebeClient = zeebeClient;
        this.taskClient = taskClient;
        this.doubleCheck = doubleCheck;
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
     * @throws Exception
     */
    public ExecuteWithResult executeTaskWithResult(Task userTask,
                                                   boolean assignUser,
                                                   String userName,
                                                   Map<String, Object> variables,
                                                   long timeoutDurationInMs) throws Exception {
        return executeTaskWithResult(Long.valueOf(userTask.getId()),
                Long.valueOf(userTask.getProcessInstanceKey()),
                assignUser,
                userName,
                variables,
                timeoutDurationInMs);
    }

    /**
     * Method using only an UserTaskId
     * @param userTaskKey userTaskId (from TaskList)
     * @param processInstanceKey processInstanceKey
     * @param assignUser assignUser
     * @param userName
     * @param variables
     * @param timeoutDurationInMs
     * @return
     * @throws Exception
     */
    @Override
    public ExecuteWithResult executeTaskWithResult(Long userTaskKey,
                                                   Long processInstanceKey,
                                                   boolean assignUser,
                                                   String userName,
                                                   Map<String, Object> variables,
                                                   long timeoutDurationInMs) throws Exception {


        // We need to create a unique ID
        Long beginTime = System.currentTimeMillis();

        logger.debug("ExecuteTaskWithResult[{}]", userTaskKey);
        int snitchValue = random.nextInt(10000);

        LockObjectTransporter lockObjectTransporter = createLockObjectTransporter();
        lockObjectTransporter.jobKey = userTaskKey;
        synchronized (lockObjectsMap) {
            lockObjectsMap.put(userTaskKey, lockObjectTransporter);
        }
        // Now, create a worker just for this jobKey
        logger.debug("Register worker[{}]", "end-result-" + userTaskKey);
        JobWorker worker = zeebeClient.newWorker()
                .jobType("end-result-" + userTaskKey)
                .handler(handleMarker)
                .streamEnabled(true)
                .open();

        Map<String, Object> userVariables = new HashMap<>();
        userVariables.put("jobKey", userTaskKey);
        userVariables.putAll(variables);
        if (doubleCheck)
            userVariables.put(PROCESS_VARIABLE_SNITCH, snitchValue);
        ExecuteWithResult executeWithResult = new ExecuteWithResult();

        // save the variable jobId

            try {
                if (assignUser)
                    zeebeClient.newUserTaskAssignCommand(userTaskKey).assignee(userName).send().join();
                zeebeClient.newUserTaskCompleteCommand(userTaskKey).variables(userVariables).send().join();
            } catch (Exception e) {
                logger.error("Can't complete Task [{}] : {}", userTaskKey, e);
                executeWithResult.taskNotFound = true;
                worker.close();
                return executeWithResult;
            }

        // Now, we block the thread and wait for a result
        lockObjectTransporter.waitForResult(timeoutDurationInMs);

        logger.info("Receive answer jobKey[{}] notification? {} inprogress{}", userTaskKey, lockObjectTransporter.notification,
                lockObjectsMap.size());

        // retrieve the taskId where the currentprocess instance is
        executeWithResult.taskId = lockObjectTransporter.taskId;
        // we got the result
        // we can close the worker now
        worker.close();
        synchronized (lockObjectsMap) {
            lockObjectsMap.remove(userTaskKey);
        }

        Long endTime = System.currentTimeMillis();
        executeWithResult.processInstanceKey = processInstanceKey;
        executeWithResult.executionTime = endTime - beginTime;

        if (lockObjectTransporter.notification) {
            executeWithResult.timeOut = false;
            executeWithResult.processVariables = lockObjectTransporter.processVariables;
            String doubleCheckAnalysis = "";
            if (doubleCheck) {
                String jobKeyProcess = (String) lockObjectTransporter.processVariables.get("jobKey");
                Integer snitchProcess = (Integer) lockObjectTransporter.processVariables.get(PROCESS_VARIABLE_SNITCH);
                doubleCheckAnalysis = snitchProcess == null || !snitchProcess.equals(snitchValue) ?
                        String.format("Snitch_Different(snitch[%1] SnichProcess[%2])", snitchValue, snitchProcess) :
                        "Snitch_marker_OK";
            }
            logger.debug("RESULT JobKey[{}] in {} ms (timeout {} ms) Pid[{}] {} variables[{}]", userTaskKey, endTime - beginTime,
                    timeoutDurationInMs, processInstanceKey, doubleCheckAnalysis,
                    lockObjectTransporter.processVariables);
        } else {
            executeWithResult.timeOut = true;
            executeWithResult.processVariables = null;

            logger.debug("RESULT TIMEOUT  JobKey[{}]  in {} ms (timeout {} ms) Pid[{}] ", userTaskKey, endTime - beginTime,
                    timeoutDurationInMs, processInstanceKey);
        }

        return executeWithResult;
    }


}
