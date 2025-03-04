package io.camunda.executewithresult.scenario;

import io.camunda.common.auth.*;
import io.camunda.executewithresult.executor.ExecuteWithResult;
import io.camunda.executewithresult.executor.WithResultAPI;
import io.camunda.executewithresult.executor.WithResultAPITaskList;
import io.camunda.executewithresult.executor.WithResultAPIZeebe;
import io.camunda.executewithresult.worker.DelayWorker;
import io.camunda.executewithresult.worker.LogWorker;
import io.camunda.tasklist.CamundaTaskListClient;
import io.camunda.tasklist.CamundaTaskListClientBuilder;
import io.camunda.tasklist.dto.Task;
import io.camunda.tasklist.dto.TaskList;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import io.camunda.zeebe.client.api.search.response.UserTask;
import io.camunda.zeebe.client.api.worker.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

@Component
@ConfigurationProperties()

public class ScenarioUserTask {
    public static final String PROCESS_ID = "executeUserTaskWithResult";
    Logger logger = LoggerFactory.getLogger(ScenarioUserTask.class.getName());

    private CamundaTaskListClient taskClient;

    @Value("${tasklist.url}")
    public String taskListUrl;
    @Value("${tasklist.username}")
    public String taskListUserName;
    @Value("${tasklist.userpassword:}")
    public String taskListUserPassword;

    @Value("${tasklist.clientId}")
    public String taskListClientId;
    @Value("${tasklist.clientSecret}")
    public String taskListClientSecret;
    @Value("${tasklist.taskListKeycloakUrl}")
    public String taskListKeycloakUrl;

    @Value("${usertaskwithresult.modeExecution:'single'}")
    public String modeExecution;

    @Value("${usertaskwithresult.pleaseLogWorker:'false'}")
    public Boolean pleaseLogWorker;

    @Value("${usertaskwithresult.pleaseLogWorker:'true'}")
    public Boolean doubleCheck;

    private int numberExecution = 0;

    @Autowired
    ZeebeClient zeebeClient;


    EngineCommand engineCommand;

    List<JobWorker> listWorkers = new ArrayList<>();

    Random random = new Random();
    ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(100);

    Set<Long> registerUserTask = new HashSet<>();

    boolean initialisation = false;

    /**
     * Initialize all environment
     */
    public void initialisation() {
        if (taskListUrl!=null && ! taskListUrl.isEmpty()) {
            if (!connectionTaskList()) {
                return;
            }
        }
        engineCommand = new EngineCommand(zeebeClient, taskClient);
        // create workers
        listWorkers.add(DelayWorker.registerWorker(zeebeClient));
        listWorkers.add(LogWorker.registerWorker(zeebeClient));
        initialisation = true;
    }

    @Scheduled(fixedDelay = 30000)
    public void execute() {
        if (!initialisation)
            return;
        numberExecution++;
        try {
            // -------------- single
            if ("single".equals(modeExecution)) {
                if (numberExecution > 1)
                    return;
                executeSingleExecutionTaskList();

            }
            // -------------- multiple
            else if ("multiple".equals(modeExecution)) {
                executeMultipleExecutionTaskList();

            }
            // -------------- singlezeebe
            else if ("singlezeebe".equals(modeExecution)) {
                if (numberExecution > 1)
                    return;
                executeSingleExecutionZeebeTask();
            }
        } catch (Exception e) {
            logger.error("Error execution [{}]", e);
        }

    }

    /**
     * ExecuteOne Simple execution
     */
    public void executeSingleExecutionTaskList() throws Exception {
        try {
            engineCommand.createProcessInstances("executeUserTaskWithResult",
                    Map.of(LogWorker.PROCESS_VARIABLE_PLEASELOG, Boolean.TRUE, DelayWorker.PROCESS_VARIABLE_CREDITSCORE,
                            random.nextInt(1000)), 1, true);
            int loop = 0;
            while (loop < 10) {
                loop++;
                TaskList taskList = engineCommand.searchUserTaskWithTaskList(PROCESS_ID);
                if (taskList.getItems().isEmpty()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    continue;
                }
                for (Task task : taskList.getItems()) {
                    executeOneTask(AccessTaskAPI.TASKLIST, Long.valueOf(task.getId()),Long.valueOf(task.getProcessInstanceKey()));
                }
            }
        } catch (Exception e) {
            logger.error("Exception {}", e);
        }
    }

    /**
     * ExecuteOne Simple execution
     */
    public void executeSingleExecutionZeebeTask() throws Exception {
        try {
            ProcessInstanceEvent processInstance = engineCommand.createProcessInstancesWithResult("executeUserTaskWithResult",
                    Map.of(LogWorker.PROCESS_VARIABLE_PLEASELOG, Boolean.TRUE, DelayWorker.PROCESS_VARIABLE_CREDITSCORE,
                            random.nextInt(1000)));
            WithResultAPI taskWithResult = new WithResultAPIZeebe(zeebeClient, doubleCheck);
            int loop = 0;
            while (loop < 10) {
                loop++;
                List<UserTask> taskList = engineCommand.searchUserTaskWithZeebe(processInstance.getProcessInstanceKey(), "Activity_StartTheGame");
                logger.info("Search userTask, found {} tasks - {}/10", taskList.size(), loop);
                if (taskList.isEmpty()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    continue;
                }
                // logs
                for (UserTask task : taskList) {
                    logger.info("TaskKey[{}] Name[{}] ProcessInstance[{}] ", task.getKey(), task.getElementId(), task.getProcessInstanceKey());
                }
                for (UserTask task : taskList) {
                    executeOneTask(AccessTaskAPI.ZEEBE, task.getKey(), task.getProcessInstanceKey());
                }
            }
        } catch (Exception e) {
            logger.error("Exception {}", e);
        }
    }

    /**
     *
     */
    public void executeMultipleExecutionTaskList() {
        logger.info(">>>>>>>>>>>>>>>>>>>>>>>>>> Synthesis {} queueSize=[{}] <<<<<<<<<<<<<<<<<<<", statResult.getSynthesis(),
                registerUserTask.size());

        try {

            if (registerUserTask.size() < 10) {
                engineCommand.createProcessInstances(PROCESS_ID,
                        Map.of(LogWorker.PROCESS_VARIABLE_PLEASELOG, pleaseLogWorker, DelayWorker.PROCESS_VARIABLE_CREDITSCORE,
                                random.nextInt(1000)), 10, pleaseLogWorker.booleanValue());
            }

            TaskList tasksList = null;
            tasksList = engineCommand.searchUserTaskWithTaskList(PROCESS_ID);
            // Register the task: the same task can show up multiple time because of the delay between Zee
            logger.info("------------------- Search for userTask to run found[{}]", tasksList.size());

            for (Task task : tasksList.getItems()) {
                if (registerUserTask.contains(Long.valueOf(task.getId())))
                    continue; // already executed
                registerUserTask.add(Long.valueOf(task.getId()));
                Callable<ExecuteWithResult> taskWithResultCallable = () -> {
                    return executeOneTask(AccessTaskAPI.TASKLIST, Long.valueOf(task.getId()), Long.valueOf(task.getProcessInstanceKey()));
                    // Use lambda to pass the parameter
                };
                Future future = executor.submit(taskWithResultCallable);
            }

            // Ok, now we can purge the registerUserTask. If the task does not show up in the taskList, we can purge it
            Set<Long> taskIds = tasksList.getItems().stream().map(t -> Long.valueOf(t.getId()))  // Get the ID of each Task
                    .collect(Collectors.toSet());
            registerUserTask.retainAll(taskIds);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Each task? Let's create a unique thread to manage it with Result

    }


    public enum AccessTaskAPI {ZEEBE, TASKLIST}

    /**
     *
     * @param accessTaskAPI type of API to access
     * @param userTaskKey user task key to execute
     * @param processInstanceKey processinstancekey
     * @return a result
     * @throws Exception
     */
    private ExecuteWithResult executeOneTask(AccessTaskAPI accessTaskAPI,
                                             long userTaskKey,
                                             long processInstanceKey) throws Exception {

        WithResultAPI taskWithResult=null;
        if (accessTaskAPI == AccessTaskAPI.TASKLIST) {
            taskWithResult = new WithResultAPITaskList(zeebeClient, taskClient, doubleCheck);
            logger.info("Play with task from TaskList TaskKey[{}] processInstanceKey[{}]", userTaskKey, processInstanceKey);
        }
        else if (accessTaskAPI == AccessTaskAPI.ZEEBE) {
            taskWithResult = new WithResultAPIZeebe(zeebeClient, doubleCheck);
            logger.info("Play with task from Zeebe TaskKey[{}] processInstanceKey[{}]", userTaskKey, processInstanceKey);
        }
        else
            throw new Exception("Unknown API");


        long beginTimeRun = System.currentTimeMillis();

        ExecuteWithResult executeWithResult= taskWithResult.executeTaskWithResult(userTaskKey, processInstanceKey, true, "demo",
                    Map.of("Cake", "Cherry"), 10000L);

        long executionTime = System.currentTimeMillis() - beginTimeRun;

        // Check the result now
        if (executeWithResult.taskNotFound) {
            return executeWithResult;
        }
        if (!executeWithResult.timeOut) {
            Integer signature = (Integer) executeWithResult.processVariables.get(DelayWorker.PROCESS_VARIABLE_CREDITSCORE);
            Integer resultCalculation = (Integer) executeWithResult.processVariables.get(
                    DelayWorker.PROCESS_VARIABLE_CALCULATION);
            if (resultCalculation != signature.intValue() + 10)
                logger.error("Calculation is wrong, not the expected result Signature[{}] Result[{}] (expect signature+10)",
                        signature, resultCalculation);

        }
        statResult.addResult(executionTime, !executeWithResult.timeOut);
        // Use lambda to pass the parameter
        return executeWithResult;
    }


    /**
     * connection To TaskList
     *
     * @return
     */
    private boolean connectionTaskList() {
        try {
            CamundaTaskListClientBuilder taskListBuilder = CamundaTaskListClient.builder();
            if (taskListClientId != null && !taskListClientId.isEmpty()) {

                taskListBuilder.taskListUrl(taskListUrl)
                        .selfManagedAuthentication(taskListClientId, taskListClientSecret, taskListKeycloakUrl);
            } else {
                SimpleConfig simpleConf = new SimpleConfig();
                simpleConf.addProduct(Product.TASKLIST,
                        new SimpleCredential(taskListUrl, taskListUserName, taskListUserPassword));
                Authentication auth = SimpleAuthentication.builder().withSimpleConfig(simpleConf).build();

                taskListBuilder.taskListUrl(taskListUrl).authentication(auth).cookieExpiration(Duration.ofSeconds(500));
            }
            logger.info("Connection to TaskList");
            taskClient = taskListBuilder.build();
            logger.info("Connection with success to TaskList");
            return true;
        } catch (Exception e) {
            logger.error("------------------ Connection error to taskList {}", e);
            return false;
        }
    }

    StatResult statResult = new StatResult();

    private class StatResult {
        public long sumExecutionTime = 0;
        public long badExecution = 0;
        public long successExecution = 0;

        public synchronized void addResult(long timeExecution, boolean success) {
            if (success) {
                sumExecutionTime += timeExecution;
                successExecution++;
            } else {
                badExecution++;
            }
        }

        public String getSynthesis() {
            return String.format("%1d ms average for %2d executions (error %d)",
                    sumExecutionTime / (successExecution == 0 ? 1 : successExecution), successExecution, badExecution);
        }
    }

}
