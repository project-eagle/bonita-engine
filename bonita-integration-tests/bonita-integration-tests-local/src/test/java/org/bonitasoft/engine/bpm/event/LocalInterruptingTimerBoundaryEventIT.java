/**
 * Copyright (C) 2019 Bonitasoft S.A.
 * Bonitasoft, 32 rue Gustave Eiffel - 38000 Grenoble
 * This library is free software; you can redistribute it and/or modify it under the terms
 * of the GNU Lesser General Public License as published by the Free Software Foundation
 * version 2.1 of the License.
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, write to the Free Software Foundation, Inc., 51 Franklin Street, Fifth
 * Floor, Boston, MA 02110-1301, USA.
 **/
package org.bonitasoft.engine.bpm.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bonitasoft.engine.bpm.flownode.ActivityInstanceCriterion;
import org.bonitasoft.engine.bpm.flownode.ArchivedActivityInstance;
import org.bonitasoft.engine.bpm.flownode.FlowNodeInstance;
import org.bonitasoft.engine.bpm.flownode.TimerType;
import org.bonitasoft.engine.bpm.process.ProcessDefinition;
import org.bonitasoft.engine.bpm.process.ProcessInstance;
import org.bonitasoft.engine.bpm.process.ProcessInstanceCriterion;
import org.bonitasoft.engine.bpm.process.impl.ProcessDefinitionBuilder;
import org.bonitasoft.engine.bpm.process.impl.UserTaskDefinitionBuilder;
import org.bonitasoft.engine.event.AbstractEventIT;
import org.bonitasoft.engine.exception.BonitaRuntimeException;
import org.bonitasoft.engine.expression.Expression;
import org.bonitasoft.engine.expression.ExpressionBuilder;
import org.bonitasoft.engine.scheduler.SchedulerService;
import org.bonitasoft.engine.service.ServiceAccessor;
import org.bonitasoft.engine.service.impl.ServiceAccessorFactory;
import org.bonitasoft.engine.session.APISession;
import org.bonitasoft.engine.sessionaccessor.SessionAccessor;
import org.bonitasoft.engine.test.TestStates;
import org.bonitasoft.engine.transaction.TransactionService;
import org.junit.Test;

public class LocalInterruptingTimerBoundaryEventIT extends AbstractEventIT {

    private static final String TIMER_EVENT_PREFIX = "Timer_Ev_";

    protected static void setSessionInfo(final APISession session) throws Exception {
        final SessionAccessor sessionAccessor = ServiceAccessorFactory.getInstance().createSessionAccessor();
        sessionAccessor.setSessionInfo(session.getId(), session.getTenantId());
    }

    protected ServiceAccessor getServiceAccessor() {
        try {
            return ServiceAccessorFactory.getInstance().createServiceAccessor();
        } catch (final Exception e) {
            throw new BonitaRuntimeException(e);
        }
    }

    private boolean containsTimerJob(final String jobName) throws Exception {
        setSessionInfo(getSession());
        final SchedulerService schedulerService = getServiceAccessor().getSchedulerService();
        final TransactionService transactionService = getServiceAccessor().getTransactionService();
        transactionService.begin();
        try {
            final List<String> jobs = schedulerService.getJobs();
            for (final String serverJobName : jobs) {
                if (serverJobName.contains(jobName)) {
                    return true;
                }
            }
        } finally {
            transactionService.complete();
        }
        return false;
    }

    private String getJobName(final long eventInstanceId) {
        return TIMER_EVENT_PREFIX + eventInstanceId;
    }

    @Test
    // when the boundary event is not triggered we will have the same behavior for interrupting and non-interrupting events; only interrupting will be tested
    public void timerBoundaryEventNotTriggered() throws Exception {
        // given
        final long timerDuration = 20000;
        final ProcessDefinition processDefinition = deployAndEnableProcessWithBoundaryTimerEvent(timerDuration, true,
                "step1", "exceptionStep", "step2");

        final ProcessInstance processInstance = getProcessAPI().startProcess(processDefinition.getId());

        final FlowNodeInstance timer = waitForFlowNodeInWaitingState(processInstance, "timer", false);
        final Long boundaryId = timer.getId();
        assertThat(containsTimerJob(getJobName(boundaryId))).isTrue();

        // when
        waitForUserTaskAndExecuteIt(processInstance, "step1", user);

        // then
        waitForFlowNodeInState(processInstance, "timer", TestStates.ABORTED, false);
        assertThat(containsTimerJob(getJobName(boundaryId))).isFalse();

        waitForUserTaskAndExecuteIt(processInstance, "step2", user);
        waitForProcessToFinish(processInstance);

        checkFlowNodeWasntExecuted(processInstance.getId(), "exceptionStep");

        disableAndDeleteProcess(processDefinition);
    }

    @Test
    // when the boundary event is not triggered we will have the same behavior for interrupting and non-interrupting events; only interrupting will be tested
    public void timerBoundaryEventNotTriggeredOnCallActivity() throws Exception {
        // given
        final long timerDuration = 20000;
        final String simpleProcessName = "targetProcess";
        final String simpleTaskName = "stepCA";

        // deploy a simple process p1
        final ProcessDefinition targetProcessDefinition = deployAndEnableSimpleProcess(simpleProcessName,
                simpleTaskName);

        // deploy a process, p2, with a call activity calling p1. The call activity has an interrupting timer boundary event
        final ProcessDefinition processDefinition = deployAndEnableProcessWithBoundaryTimerEventOnCallActivity(
                timerDuration, true, simpleProcessName);

        final ProcessInstance processInstance = getProcessAPI().startProcess(processDefinition.getId());

        final FlowNodeInstance timer = waitForFlowNodeInWaitingState(processInstance, "timer", false);
        final Long boundaryId = timer.getId();
        assertThat(containsTimerJob(getJobName(boundaryId))).isTrue();

        // when
        waitForUserTaskAndExecuteIt(processInstance, "stepCA", user);

        // then
        waitForFlowNodeInState(processInstance, "timer", TestStates.ABORTED, false);
        assertThat(containsTimerJob(getJobName(boundaryId))).isFalse();

        waitForUserTaskAndExecuteIt(processInstance, PARENT_PROCESS_USER_TASK_NAME, user);
        waitForProcessToFinish(processInstance);
        checkFlowNodeWasntExecuted(processInstance.getId(), EXCEPTION_STEP);

        disableAndDeleteProcess(processDefinition);
        disableAndDeleteProcess(targetProcessDefinition);
    }

    @Test
    // when the boundary event is not triggered we will have the same behavior for interrupting and non-interrupting events; only interrupting will be tested
    public void timerBoundaryEventNotTriggeredOnSequentialMultiInstance() throws Exception {
        // given
        final long timerDuration = 20000;
        final int loopCardinality = 1;
        final boolean isSequential = true;
        final ProcessDefinition processDefinition = deployAndEnableProcessMultiInstanceWithBoundaryEvent(timerDuration,
                true, "step1", loopCardinality,
                isSequential, "step2", "exceptionStep");

        final ProcessInstance processInstance = getProcessAPI().startProcess(processDefinition.getId());

        final FlowNodeInstance timer = waitForFlowNodeInWaitingState(processInstance, "timer", false);
        final Long boundaryId = timer.getId();
        assertThat(containsTimerJob(getJobName(boundaryId))).isTrue();

        // when
        waitForUserTasksAndExecuteIt("step1", processInstance, loopCardinality);

        // then
        waitForFlowNodeInState(processInstance, "timer", TestStates.ABORTED, false);
        assertThat(containsTimerJob(getJobName(boundaryId))).isFalse();

        waitForUserTaskAndExecuteIt(processInstance, "step2", user);
        waitForProcessToFinish(processInstance);

        checkFlowNodeWasntExecuted(processInstance.getId(), "exceptionStep");

        disableAndDeleteProcess(processDefinition);
    }

    @Test
    // when the boundary event is not triggered we will have the same behavior for interrupting and non-interrupting events; only interrupting will be tested
    public void timerBoundaryEventNotTriggeredOnParallelMultiInstance() throws Exception {
        // given
        final long timerDuration = 20000;
        final int loopCardinality = 2;
        final boolean isSequential = false;
        final ProcessDefinition processDefinition = deployAndEnableProcessMultiInstanceWithBoundaryEvent(timerDuration,
                true, "step1", loopCardinality,
                isSequential, "step2", "exceptionStep");

        final ProcessInstance processInstance = getProcessAPI().startProcess(processDefinition.getId());

        final FlowNodeInstance timer = waitForFlowNodeInWaitingState(processInstance, "timer", false);
        final Long boundaryId = timer.getId();
        assertThat(containsTimerJob(getJobName(boundaryId))).isTrue();

        // when
        waitForUserTasksAndExecuteIt("step1", processInstance, loopCardinality);

        // then
        waitForFlowNodeInState(processInstance, "timer", TestStates.ABORTED, false);
        assertThat(containsTimerJob(getJobName(boundaryId))).isFalse();

        waitForUserTaskAndExecuteIt(processInstance, "step2", user);
        waitForProcessToFinish(processInstance);
        checkFlowNodeWasntExecuted(processInstance.getId(), "exceptionStep");

        disableAndDeleteProcess(processDefinition);
    }

    @Test
    // when the boundary event is not triggered we will have the same behavior for interrupting and non-interrupting events; only interrupting will be tested
    public void timerBoundaryEventNotTriggeredOnLoopActivity() throws Exception {
        // given
        final long timerDuration = 20000;
        final int loopMax = 1;
        final ProcessDefinition processDefinition = deployAndEnableProcessWithBoundaryTimerEventOnLoopActivity(
                timerDuration, true, loopMax, "step1", "step2",
                "exceptionStep");

        final ProcessInstance processInstance = getProcessAPI().startProcess(processDefinition.getId());
        final FlowNodeInstance timer = waitForFlowNodeInWaitingState(processInstance, "timer", false);
        final Long boundaryId = timer.getId();
        assertThat(containsTimerJob(getJobName(boundaryId))).isTrue();

        // when
        waitForUserTasksAndExecuteIt("step1", processInstance, loopMax);

        // then
        waitForFlowNodeInState(processInstance, "timer", TestStates.ABORTED, false);
        assertThat(containsTimerJob(getJobName(boundaryId))).isFalse();

        waitForUserTaskAndExecuteIt(processInstance, "step2", user);
        waitForProcessToFinish(processInstance);
        checkFlowNodeWasntExecuted(processInstance.getId(), "exceptionStep");

        disableAndDeleteProcess(processDefinition);
    }

    @Test
    public void deleteProcessInstanceAlsoDeleteChildrenProcessesEvents() throws Exception {
        // deploy a simple process with BoundaryEvent P1
        List<ProcessDefinition> processDefinitions = new ArrayList<>();
        final String simpleStepName = "simpleStep";
        final ProcessDefinition simpleProcess = deployAndEnableSimpleProcessWithBoundaryEvent("simpleProcess",
                simpleStepName);
        processDefinitions.add(simpleProcess); // To clean in the end

        // deploy a process P2 containing a call activity calling P1
        final String intermediateStepName = "intermediateStep1";
        final String intermediateCallActivityName = "intermediateCall";
        final ProcessDefinition intermediateProcess = deployAndEnableProcessWithCallActivity("intermediateProcess",
                simpleProcess.getName(),
                intermediateStepName, intermediateCallActivityName);
        processDefinitions.add(intermediateProcess); // To clean in the end

        // deploy a process P3 containing a call activity calling P2
        final String rootStepName = "rootStep1";
        final String rootCallActivityName = "rootCall";
        final ProcessDefinition rootProcess = deployAndEnableProcessWithCallActivity("rootProcess",
                intermediateProcess.getName(), rootStepName,
                rootCallActivityName);
        processDefinitions.add(rootProcess); // To clean in the end

        // start P3, the call activities will start instances of P2 a and P1
        final ProcessInstance rootProcessInstance = getProcessAPI().startProcess(rootProcess.getId());
        waitForUserTask(rootProcessInstance, simpleStepName);
        List<String> allJobs = getServiceAccessor().getSchedulerService().getAllJobs();

        boolean timer_ev_isCreated = false;
        for (String job : allJobs) {
            if (job.contains("Timer_Ev")) {
                timer_ev_isCreated = true;
            }
        }
        //make sure timer events are created
        assertThat(timer_ev_isCreated).isTrue();

        // delete the root process instance
        getProcessAPI().deleteProcessInstance(rootProcessInstance.getId());

        // check that the instances of p1 and p2 were deleted
        List<ProcessInstance> processInstances = getProcessAPI().getProcessInstances(0, 10,
                ProcessInstanceCriterion.NAME_ASC);
        assertThat(processInstances.size()).isEqualTo(0);

        // check that archived flow nodes were deleted.
        List<ArchivedActivityInstance> taskInstances = getProcessAPI().getArchivedActivityInstances(
                rootProcessInstance.getId(), 0, 100,
                ActivityInstanceCriterion.DEFAULT);
        assertThat(taskInstances.size()).isEqualTo(0);

        //check the quartz events got deleted correctly
        allJobs = getServiceAccessor().getSchedulerService().getAllJobs();

        for (String job : allJobs) {
            // There might be a few of those left in the DB, it should be the only ones
            assertThat(job).isEqualToIgnoringCase("CleanInvalidSessions");
        }
        //cleanup
        disableAndDeleteProcess(processDefinitions);
    }

    private ProcessDefinition deployAndEnableSimpleProcessWithBoundaryEvent(final String processName,
            final String userTaskName) throws Exception {
        final ProcessDefinitionBuilder processDefBuilder = new ProcessDefinitionBuilder().createNewInstance(processName,
                "1.0");
        processDefBuilder.addActor(ACTOR_NAME);
        processDefBuilder.addStartEvent("tStart");
        processDefBuilder.addUserTask(userTaskName, ACTOR_NAME).addBoundaryEvent("TheBoundaryEvent")
                .addTimerEventTriggerDefinition(TimerType.DURATION,
                        new ExpressionBuilder().createConstantLongExpression(6000L));
        processDefBuilder.addEndEvent("tEnd");
        processDefBuilder.addEndEvent("tBoundaryEnd");
        processDefBuilder.addTransition("TheBoundaryEvent", "tBoundaryEnd");
        processDefBuilder.addTransition("tStart", userTaskName);
        processDefBuilder.addTransition(userTaskName, "tEnd");
        return deployAndEnableProcessWithActor(processDefBuilder.done(), ACTOR_NAME, user);
    }

    private ProcessDefinition deployAndEnableProcessWithCallActivity(final String processName,
            final String targetProcessName, final String userTaskName,
            final String callActivityName) throws Exception {
        final Expression targetProcessNameExpr = new ExpressionBuilder()
                .createConstantStringExpression(targetProcessName);

        final ProcessDefinitionBuilder processDefBuilder = new ProcessDefinitionBuilder().createNewInstance(processName,
                "1.0");
        processDefBuilder.addActor(ACTOR_NAME);
        processDefBuilder.addStartEvent("start");
        processDefBuilder.addCallActivity(callActivityName, targetProcessNameExpr, null);
        processDefBuilder.addUserTask(userTaskName, ACTOR_NAME);
        processDefBuilder.addEndEvent("end");
        processDefBuilder.addTransition("start", callActivityName);
        processDefBuilder.addTransition(callActivityName, userTaskName);
        processDefBuilder.addTransition(userTaskName, "end");
        return deployAndEnableProcessWithActor(processDefBuilder.done(), ACTOR_NAME, user);
    }

    @Test
    public void timerBoundaryEvent_should_not_trigger_and_be_deleted_at_flownode_abortion() throws Exception {
        final int timerDuration = 20_000;//long enough not to trigger
        SchedulerService schedulerService = getServiceAccessor().getSchedulerService();

        final ProcessDefinitionBuilder processDefinitionBuilder = new ProcessDefinitionBuilder()
                .createNewInstance("pTimerBoundary", "2.0");
        processDefinitionBuilder.addActor(ACTOR_NAME);
        processDefinitionBuilder.addStartEvent("start");
        final UserTaskDefinitionBuilder userTaskDefinitionBuilder = processDefinitionBuilder.addUserTask("step1",
                ACTOR_NAME);
        userTaskDefinitionBuilder.addBoundaryEvent("Boundary timer").addTimerEventTriggerDefinition(TimerType.DURATION,
                new ExpressionBuilder().createConstantLongExpression(timerDuration));
        userTaskDefinitionBuilder.addUserTask("exceptionStep", ACTOR_NAME);
        processDefinitionBuilder.addUserTask("step2", ACTOR_NAME);
        processDefinitionBuilder.addEndEvent("end").addTerminateEventTrigger();
        processDefinitionBuilder.addEndEvent("end2").addTerminateEventTrigger();
        processDefinitionBuilder.addTransition("start", "step1");
        processDefinitionBuilder.addTransition("start", "step2");
        processDefinitionBuilder.addTransition("step1", "end");
        processDefinitionBuilder.addTransition("step2", "end2");
        processDefinitionBuilder.addTransition("Boundary timer", "exceptionStep");
        processDefinitionBuilder.addTransition("exceptionStep", "end");

        final ProcessDefinition processDefinition = deployAndEnableProcessWithActor(processDefinitionBuilder.done(),
                ACTOR_NAME, user);

        final ProcessInstance processInstance = getProcessAPI().startProcess(processDefinition.getId());

        waitForUserTask(processInstance.getId(), "step1");
        waitForUserTaskAssignAndExecuteIt(processInstance, "step2", user, Map.of());
        waitForProcessToFinish(processInstance);
        assertThat(schedulerService.getAllJobs()).isEmpty();
        disableAndDeleteProcess(processDefinition);
    }

}
