# C8-execute-usertask-with-result!

# Principle

Via the API, when a call to "execute a user task" is perform, the API send the command and give back the control.
The execution is done asynchronously by Zeebe.

Imagine you want to block the thread during this execution, waiting until the process reach a point in the process.
For example, the process call a service to book a ticket for a concert, and you want to give back the result of the reservation to the API.
The thread must be block, waiting for this point, and then collect the process variable (the reservation number).

This is the role of this library.
There is two use case:
* after a user task, block the thread and wait for a certain point in the process
* after a task creation, block the thread.

Note: for the second user, an API "createWithResult" exist, but this API has two limitations:
* it waits until the end of the process, not until the process reach a milestone
* if the execution is over the duration timeout, it returns an exception, and not the process instance created. It you want to cancel the process instance because it's too long, then it is not possible.


# User Task With Result


[ProcessExampleUserTaskWithResult.png](doc%2FProcessExampleUserTaskWithResult.png)


# Create process instance with result
