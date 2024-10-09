package io.camunda.executewithresult.executor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.camunda.executewithresult.ExecuteApplication;
import io.camunda.executewithresult.worker.LogWorker;
import io.camunda.tasklist.CamundaTaskListClient;
import io.camunda.tasklist.dto.Task;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.api.worker.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

public class TaskWithResult {
  Logger logger = LoggerFactory.getLogger(ExecuteApplication.class.getName());

  Random random = new Random();


  private ZeebeClient zeebeClient;

  private CamundaTaskListClient taskClient;

  private boolean useUserTaskAPI=true;

  public TaskWithResult(ZeebeClient zeebeClient, CamundaTaskListClient taskClient) {
    this.zeebeClient = zeebeClient;
    this.taskClient = taskClient;
  }

  /**
   * executeTaskWithResult
   * @param userTask user task to execute
   * @param timeoutDurationInMs maximum duration time, adter an exeption is returned
   * @return the process variable
   * @throws Exception
   */
  public Map<String, Object> executeTaskWithResult(Task userTask, long timeoutDurationInMs) throws Exception {
    // We need to create a unique ID
    Long beginTime = System.currentTimeMillis();
    String jobKey = userTask.getId();
    int signature = random.nextInt(10000);
    logger.info("ExecuteTaskWithResult[{}] signature[{}]", jobKey,signature);

    LockObjectTransporter lockObjectTransporter = new LockObjectTransporter();
    lockObjectTransporter.jobKey = jobKey;
    lockObjectTransporter.signature = signature;
    lockObjectsMap.put(jobKey, lockObjectTransporter);

    // Now, create a worker just for this jobKey
    HandleMarker handleMarker = new HandleMarker();
    JobWorker worker =zeebeClient.newWorker().jobType("end-result-" + jobKey).handler(handleMarker).streamEnabled(true).open();

    Map<String, Object> userVariables = new HashMap<>();
    userVariables.put("jobKey", jobKey);
    userVariables.put(LogWorker.PROCESS_VARIABLE_MESSAGE, "Hello "+signature);
    userVariables.put(LogWorker.PROCESS_VARIABLE_SIGNATURE, signature);

    // save the variable jobId
    if (useUserTaskAPI) {
      taskClient.claim(userTask.getId(), "demo");

      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {

          taskClient.completeTask(userTask.getId(), userVariables);
        } catch (Exception e) {
          logger.error("Can't complete Task [{}] : {}", userTask.getId(),e);
        }
      });
    } else {
      try {
        zeebeClient.newUserTaskAssignCommand(Long.valueOf(userTask.getId())).assignee("demo").send().join();
        zeebeClient.newUserTaskCompleteCommand(Long.valueOf(userTask.getId())).variables(userVariables).send().join();
      }catch(Exception e) {
        logger.error("Can't complete Task [{}] : {}", userTask.getId(), e);
      }
    }


    // Now, we block the thread and wait for a result
    lockObjectTransporter.waitForResult(timeoutDurationInMs);

    // we got the result

    // we can close the worker now
    worker.close();
    Long endTime = System.currentTimeMillis();

    if (lockObjectTransporter.notification) {
      String jobKeyProcess = (String) lockObjectTransporter.processVariables.get("jobKey");
      Integer signatureProcess = (Integer) lockObjectTransporter.processVariables.get(
          LogWorker.PROCESS_VARIABLE_SIGNATURE);
      Integer resultCalculationProcess = (Integer) lockObjectTransporter.processVariables.get(
          LogWorker.PROCESS_VARIABLE_CALCULATION);
      logger.info("RESULT JobKey[{}] Signature[{}] in {} ms (timeout {} ms) Pid[{}] status:{} variables[{}]",jobKey,
          signature,
          endTime-beginTime,
          timeoutDurationInMs,
          userTask.getProcessInstanceKey(),
          signatureProcess!=null && signature==signatureProcess? true : false,
          lockObjectTransporter.processVariables);
    } else {
      logger.info("RESULT TIMEOUT  JobKey[{}] Signature[{}] in {} ms (timeout {} ms) Pid[{}] ",jobKey,
          signature,
          endTime-beginTime,
          timeoutDurationInMs,
          userTask.getProcessInstanceKey());
    }


    return lockObjectTransporter.processVariables;
  }

  public class HandleMarker implements JobHandler {
    public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {
      // Get the variable "lockKey"
      String jobKey = (String) activatedJob.getVariable("jobKey");

      String type = activatedJob.getType();
      LockObjectTransporter lockObjectTransporter = lockObjectsMap.get(jobKey);

      if (lockObjectTransporter == null) {
        logger.error("No object fir jobKey[{}]", jobKey);
        return;
      }
      if (lockObjectTransporter.signature != ((Integer) activatedJob.getVariable(
          LogWorker.PROCESS_VARIABLE_SIGNATURE)).intValue()) {
        logger.error("Not the correct object!! jobKey[{}] signature[{}] jobTaskVariable[#{}]", jobKey,
            lockObjectTransporter.signature, activatedJob.getVariable("signature"));
      }
      lockObjectTransporter.processVariables = activatedJob.getVariablesAsMap();
      logger.info("HandleMarker jobKey[{}] signature [{}] variables[{}]", jobKey, lockObjectTransporter.signature, lockObjectTransporter.processVariables);

      // Check if this is what we expect
      lockObjectTransporter.notifyResult();
    }
  }

  private class LockObjectTransporter {
    public String jobKey;
    // This is just to verify
    public int signature;
    // With result will return the process variable here
    public Map<String, Object> processVariables;

    public boolean notification=false;

    public synchronized void waitForResult(long timeoutDurationInMs) {
      try {
        logger.debug("Wait on object[{}]", this);
        wait(timeoutDurationInMs);
      } catch (InterruptedException e) {
        logger.error("Can' wait ",e);
      }
    }
    public synchronized void notifyResult() {
      logger.debug("Notify on object[{}]", this);
      notification=true;
      notify();
    }
  }

  static Map<String, LockObjectTransporter> lockObjectsMap = new HashMap<>();


}
