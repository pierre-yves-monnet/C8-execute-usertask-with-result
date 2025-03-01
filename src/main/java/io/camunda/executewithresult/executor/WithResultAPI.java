package io.camunda.executewithresult.executor;


import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public abstract class WithResultAPI {

    Logger logger = LoggerFactory.getLogger(WithResultAPITaskList.class.getName());

    protected HandleMarker handleMarker = new HandleMarker();

    /**
     * Do not instantiate this class. Create a WithResultAPITaskList or WithResultAPIZeebe
     */
    protected void WithResultAPI() {

    }


    /**
     * Execute a task and wait for the result. Result is a mark by a worker in the process
     * @param userTaskKey User task Key
     * @param processInstanceKey ProcessInstancekey
     * @param assignUser true if the user must be assigned before
     * @param userName username, if assignUser is true
     * @param variables variables to update
     * @param timeoutDurationInMs lock duration. If the duration is over this time, return with executionWithResult.timeout=true
     * @return the result information
     * @throws Exception if a command failed
     */
    public abstract ExecuteWithResult executeTaskWithResult(Long userTaskKey,
                                                   Long processInstanceKey,
                                                   boolean assignUser,
                                                   String userName,
                                                   Map<String, Object> variables,
                                                   long timeoutDurationInMs) throws Exception;



        /**
         * processInstanceWithResult
         *
         * @param processId           processId to start
         * @param variables           Variables to update the task at completion
         * @param timeoutDurationInMs maximum duration time, after the ExceptionWithResult.timeOut is true
         * @return the result status
         * @throws Exception
         */
    public ExecuteWithResult processInstanceWithResult(String processId,
                                                       Map<String, Object> variables,
                                                       long timeoutDurationInMs) throws Exception {
        // To be done
        return null;
    }

    /**
     * Handle the job. This worker register under the correct topic, and capture when it's come here
     */
    private class HandleMarker implements JobHandler {
        public void handle(JobClient jobClient, ActivatedJob activatedJob) throws Exception {
            // Get the variable "lockKey"
            String jobKey = (String) activatedJob.getVariable("jobKey");
            logger.info("Handle marker for jobKey[{}]", jobKey);
            LockObjectTransporter lockObjectTransporter = lockObjectsMap.get(jobKey);

            if (lockObjectTransporter == null) {
                logger.error("No object for jobKey[{}]", jobKey);
                return;
            }
            lockObjectTransporter.processVariables = activatedJob.getVariablesAsMap();
            lockObjectTransporter.taskId = activatedJob.getElementId();
            logger.debug("HandleMarker jobKey[{}] variables[{}]", jobKey, lockObjectTransporter.processVariables);

            // Notify the thread waiting on this item
            lockObjectTransporter.notifyResult();
        }
    }

    protected LockObjectTransporter createLockObjectTransporter() {
        return new WithResultAPI.LockObjectTransporter();
    }

    protected class LockObjectTransporter {
        public Long jobKey;
        // With result will return the process variable here
        public Map<String, Object> processVariables;
        public String taskId;

        public boolean notification = false;

        public synchronized void waitForResult(long timeoutDurationInMs) {
            try {
                logger.debug("Wait on object[{}]", this);
                wait(timeoutDurationInMs);
            } catch (InterruptedException e) {
                logger.error("Can' wait ", e);
            }
        }

        public synchronized void notifyResult() {
            logger.debug("Notify on object[{}]", this);
            notification = true;
            notify();
        }
    }

    static Map<Long, LockObjectTransporter> lockObjectsMap = new HashMap<>();

}
