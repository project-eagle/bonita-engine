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
package org.bonitasoft.engine.api.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonMap;
import static org.bonitasoft.engine.classloader.ClassLoaderIdentifier.identifier;
import static org.bonitasoft.engine.core.process.instance.model.event.trigger.STimerEventTriggerInstance.EXECUTION_DATE;
import static org.bonitasoft.engine.search.AbstractSearchEntity.search;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.bonitasoft.engine.actor.mapping.ActorMappingService;
import org.bonitasoft.engine.actor.mapping.SActorNotFoundException;
import org.bonitasoft.engine.actor.mapping.model.SActor;
import org.bonitasoft.engine.actor.mapping.model.SActorMember;
import org.bonitasoft.engine.actor.mapping.model.SActorUpdateBuilder;
import org.bonitasoft.engine.actor.mapping.model.SActorUpdateBuilderFactory;
import org.bonitasoft.engine.api.DocumentAPI;
import org.bonitasoft.engine.api.ProcessAPI;
import org.bonitasoft.engine.api.impl.connector.ConnectorReseter;
import org.bonitasoft.engine.api.impl.connector.ResetAllFailedConnectorStrategy;
import org.bonitasoft.engine.api.impl.flownode.FlowNodeRetrier;
import org.bonitasoft.engine.api.impl.transaction.CustomTransactions;
import org.bonitasoft.engine.api.impl.transaction.activity.GetArchivedActivityInstance;
import org.bonitasoft.engine.api.impl.transaction.activity.GetArchivedActivityInstances;
import org.bonitasoft.engine.api.impl.transaction.activity.GetNumberOfActivityInstance;
import org.bonitasoft.engine.api.impl.transaction.actor.*;
import org.bonitasoft.engine.api.impl.transaction.category.*;
import org.bonitasoft.engine.api.impl.transaction.connector.GetConnectorImplementation;
import org.bonitasoft.engine.api.impl.transaction.event.GetEventInstances;
import org.bonitasoft.engine.api.impl.transaction.expression.EvaluateExpressionsDefinitionLevel;
import org.bonitasoft.engine.api.impl.transaction.expression.EvaluateExpressionsInstanceLevel;
import org.bonitasoft.engine.api.impl.transaction.expression.EvaluateExpressionsInstanceLevelAndArchived;
import org.bonitasoft.engine.api.impl.transaction.flownode.SetExpectedEndDate;
import org.bonitasoft.engine.api.impl.transaction.identity.GetSUser;
import org.bonitasoft.engine.api.impl.transaction.process.*;
import org.bonitasoft.engine.api.impl.transaction.task.*;
import org.bonitasoft.engine.archive.ArchiveService;
import org.bonitasoft.engine.bpm.actor.*;
import org.bonitasoft.engine.bpm.actor.ActorUpdater.ActorField;
import org.bonitasoft.engine.bpm.bar.BusinessArchive;
import org.bonitasoft.engine.bpm.bar.BusinessArchiveBuilder;
import org.bonitasoft.engine.bpm.bar.BusinessArchiveFactory;
import org.bonitasoft.engine.bpm.bar.InvalidBusinessArchiveFormatException;
import org.bonitasoft.engine.bpm.category.Category;
import org.bonitasoft.engine.bpm.category.CategoryCriterion;
import org.bonitasoft.engine.bpm.category.CategoryNotFoundException;
import org.bonitasoft.engine.bpm.category.CategoryUpdater;
import org.bonitasoft.engine.bpm.category.CategoryUpdater.CategoryField;
import org.bonitasoft.engine.bpm.comment.ArchivedComment;
import org.bonitasoft.engine.bpm.comment.Comment;
import org.bonitasoft.engine.bpm.connector.*;
import org.bonitasoft.engine.bpm.contract.ContractDefinition;
import org.bonitasoft.engine.bpm.contract.ContractViolationException;
import org.bonitasoft.engine.bpm.contract.validation.ContractValidator;
import org.bonitasoft.engine.bpm.contract.validation.ContractValidatorFactory;
import org.bonitasoft.engine.bpm.data.*;
import org.bonitasoft.engine.bpm.document.*;
import org.bonitasoft.engine.bpm.flownode.*;
import org.bonitasoft.engine.bpm.parameter.ParameterCriterion;
import org.bonitasoft.engine.bpm.parameter.ParameterInstance;
import org.bonitasoft.engine.bpm.process.*;
import org.bonitasoft.engine.bpm.process.impl.ProcessInstanceUpdater;
import org.bonitasoft.engine.bpm.supervisor.ProcessSupervisor;
import org.bonitasoft.engine.bpm.supervisor.ProcessSupervisorSearchDescriptor;
import org.bonitasoft.engine.bpm.supervisor.SupervisorNotFoundException;
import org.bonitasoft.engine.builder.BuilderFactory;
import org.bonitasoft.engine.classloader.ClassLoaderService;
import org.bonitasoft.engine.classloader.SClassLoaderException;
import org.bonitasoft.engine.commons.ExceptionUtils;
import org.bonitasoft.engine.commons.exceptions.SBonitaException;
import org.bonitasoft.engine.commons.exceptions.SBonitaRuntimeException;
import org.bonitasoft.engine.commons.transaction.TransactionContent;
import org.bonitasoft.engine.commons.transaction.TransactionContentWithResult;
import org.bonitasoft.engine.core.category.CategoryService;
import org.bonitasoft.engine.core.category.exception.SCategoryAlreadyExistsException;
import org.bonitasoft.engine.core.category.exception.SCategoryInProcessAlreadyExistsException;
import org.bonitasoft.engine.core.category.exception.SCategoryNotFoundException;
import org.bonitasoft.engine.core.category.model.SCategory;
import org.bonitasoft.engine.core.category.model.SProcessCategoryMapping;
import org.bonitasoft.engine.core.category.model.builder.SCategoryUpdateBuilder;
import org.bonitasoft.engine.core.category.model.builder.SCategoryUpdateBuilderFactory;
import org.bonitasoft.engine.core.category.model.builder.SProcessCategoryMappingBuilderFactory;
import org.bonitasoft.engine.core.connector.ConnectorInstanceService;
import org.bonitasoft.engine.core.connector.ConnectorResult;
import org.bonitasoft.engine.core.connector.ConnectorService;
import org.bonitasoft.engine.core.connector.exception.SConnectorException;
import org.bonitasoft.engine.core.connector.parser.SConnectorImplementationDescriptor;
import org.bonitasoft.engine.core.contract.data.ContractDataService;
import org.bonitasoft.engine.core.contract.data.SContractDataNotFoundException;
import org.bonitasoft.engine.core.data.instance.TransientDataService;
import org.bonitasoft.engine.core.expression.control.api.ExpressionResolverService;
import org.bonitasoft.engine.core.expression.control.model.SExpressionContext;
import org.bonitasoft.engine.core.filter.FilterResult;
import org.bonitasoft.engine.core.filter.UserFilterService;
import org.bonitasoft.engine.core.filter.exception.SUserFilterExecutionException;
import org.bonitasoft.engine.core.operation.OperationService;
import org.bonitasoft.engine.core.operation.model.SOperation;
import org.bonitasoft.engine.core.process.comment.api.SCommentNotFoundException;
import org.bonitasoft.engine.core.process.comment.api.SCommentService;
import org.bonitasoft.engine.core.process.comment.model.SComment;
import org.bonitasoft.engine.core.process.comment.model.archive.SAComment;
import org.bonitasoft.engine.core.process.definition.ProcessDefinitionService;
import org.bonitasoft.engine.core.process.definition.exception.SProcessDefinitionNotFoundException;
import org.bonitasoft.engine.core.process.definition.model.*;
import org.bonitasoft.engine.core.process.definition.model.builder.event.trigger.SThrowMessageEventTriggerDefinitionBuilder;
import org.bonitasoft.engine.core.process.definition.model.builder.event.trigger.SThrowMessageEventTriggerDefinitionBuilderFactory;
import org.bonitasoft.engine.core.process.definition.model.builder.event.trigger.SThrowSignalEventTriggerDefinitionBuilderFactory;
import org.bonitasoft.engine.core.process.definition.model.event.SBoundaryEventDefinition;
import org.bonitasoft.engine.core.process.definition.model.event.SCatchEventDefinition;
import org.bonitasoft.engine.core.process.definition.model.event.SIntermediateCatchEventDefinition;
import org.bonitasoft.engine.core.process.definition.model.event.SStartEventDefinition;
import org.bonitasoft.engine.core.process.definition.model.event.trigger.SThrowMessageEventTriggerDefinition;
import org.bonitasoft.engine.core.process.definition.model.event.trigger.SThrowSignalEventTriggerDefinition;
import org.bonitasoft.engine.core.process.instance.api.ActivityInstanceService;
import org.bonitasoft.engine.core.process.instance.api.ProcessInstanceService;
import org.bonitasoft.engine.core.process.instance.api.event.EventInstanceService;
import org.bonitasoft.engine.core.process.instance.api.exceptions.*;
import org.bonitasoft.engine.core.process.instance.api.exceptions.event.trigger.SEventTriggerInstanceDeletionException;
import org.bonitasoft.engine.core.process.instance.api.states.FlowNodeState;
import org.bonitasoft.engine.core.process.instance.model.*;
import org.bonitasoft.engine.core.process.instance.model.archive.SAFlowNodeInstance;
import org.bonitasoft.engine.core.process.instance.model.archive.SAProcessInstance;
import org.bonitasoft.engine.core.process.instance.model.archive.builder.SAProcessInstanceBuilderFactory;
import org.bonitasoft.engine.core.process.instance.model.builder.SAutomaticTaskInstanceBuilderFactory;
import org.bonitasoft.engine.core.process.instance.model.event.SCatchEventInstance;
import org.bonitasoft.engine.core.process.instance.model.event.SEventInstance;
import org.bonitasoft.engine.core.process.instance.model.event.trigger.STimerEventTriggerInstance;
import org.bonitasoft.engine.data.definition.model.SDataDefinition;
import org.bonitasoft.engine.data.definition.model.builder.SDataDefinitionBuilder;
import org.bonitasoft.engine.data.definition.model.builder.SDataDefinitionBuilderFactory;
import org.bonitasoft.engine.data.instance.api.DataInstanceContainer;
import org.bonitasoft.engine.data.instance.api.DataInstanceService;
import org.bonitasoft.engine.data.instance.api.ParentContainerResolver;
import org.bonitasoft.engine.data.instance.exception.SDataInstanceException;
import org.bonitasoft.engine.data.instance.model.SDataInstance;
import org.bonitasoft.engine.data.instance.model.archive.SADataInstance;
import org.bonitasoft.engine.dependency.model.ScopeType;
import org.bonitasoft.engine.exception.*;
import org.bonitasoft.engine.execution.ProcessInstanceInterruptor;
import org.bonitasoft.engine.execution.SUnreleasableTaskException;
import org.bonitasoft.engine.execution.event.EventsHandler;
import org.bonitasoft.engine.execution.job.JobNameBuilder;
import org.bonitasoft.engine.execution.state.FlowNodeStateManager;
import org.bonitasoft.engine.execution.work.BPMWorkFactory;
import org.bonitasoft.engine.expression.*;
import org.bonitasoft.engine.expression.exception.SExpressionDependencyMissingException;
import org.bonitasoft.engine.expression.exception.SExpressionEvaluationException;
import org.bonitasoft.engine.expression.exception.SExpressionTypeUnknownException;
import org.bonitasoft.engine.expression.exception.SInvalidExpressionException;
import org.bonitasoft.engine.expression.model.SExpression;
import org.bonitasoft.engine.form.FormMapping;
import org.bonitasoft.engine.identity.IdentityService;
import org.bonitasoft.engine.identity.User;
import org.bonitasoft.engine.identity.UserNotFoundException;
import org.bonitasoft.engine.identity.model.SUser;
import org.bonitasoft.engine.job.FailedJob;
import org.bonitasoft.engine.lock.BonitaLock;
import org.bonitasoft.engine.lock.LockService;
import org.bonitasoft.engine.lock.SLockException;
import org.bonitasoft.engine.lock.SLockTimeoutException;
import org.bonitasoft.engine.log.LogMessageBuilder;
import org.bonitasoft.engine.message.MessagesHandlingService;
import org.bonitasoft.engine.operation.LeftOperand;
import org.bonitasoft.engine.operation.Operation;
import org.bonitasoft.engine.operation.OperationBuilder;
import org.bonitasoft.engine.persistence.FilterOption;
import org.bonitasoft.engine.persistence.OrderAndField;
import org.bonitasoft.engine.persistence.OrderByOption;
import org.bonitasoft.engine.persistence.OrderByType;
import org.bonitasoft.engine.persistence.QueryOptions;
import org.bonitasoft.engine.persistence.ReadPersistenceService;
import org.bonitasoft.engine.persistence.SBonitaReadException;
import org.bonitasoft.engine.recorder.model.EntityUpdateDescriptor;
import org.bonitasoft.engine.resources.BARResourceType;
import org.bonitasoft.engine.resources.SBARResource;
import org.bonitasoft.engine.scheduler.JobService;
import org.bonitasoft.engine.scheduler.SchedulerService;
import org.bonitasoft.engine.scheduler.exception.SSchedulerException;
import org.bonitasoft.engine.scheduler.model.SFailedJob;
import org.bonitasoft.engine.scheduler.model.SJobParameter;
import org.bonitasoft.engine.search.*;
import org.bonitasoft.engine.search.activity.SearchActivityInstances;
import org.bonitasoft.engine.search.activity.SearchArchivedActivityInstances;
import org.bonitasoft.engine.search.comment.SearchArchivedComments;
import org.bonitasoft.engine.search.comment.SearchComments;
import org.bonitasoft.engine.search.comment.SearchCommentsInvolvingUser;
import org.bonitasoft.engine.search.comment.SearchCommentsManagedBy;
import org.bonitasoft.engine.search.connector.SearchArchivedConnectorInstance;
import org.bonitasoft.engine.search.descriptor.*;
import org.bonitasoft.engine.search.events.trigger.SearchTimerEventTriggerInstances;
import org.bonitasoft.engine.search.flownode.SearchArchivedFlowNodeInstances;
import org.bonitasoft.engine.search.flownode.SearchFlowNodeInstances;
import org.bonitasoft.engine.search.identity.SearchUsersWhoCanExecutePendingHumanTaskDeploymentInfo;
import org.bonitasoft.engine.search.identity.SearchUsersWhoCanStartProcessDeploymentInfo;
import org.bonitasoft.engine.search.impl.SearchFilter;
import org.bonitasoft.engine.search.impl.SearchResultImpl;
import org.bonitasoft.engine.search.process.*;
import org.bonitasoft.engine.search.supervisor.SearchArchivedHumanTasksSupervisedBy;
import org.bonitasoft.engine.search.supervisor.SearchProcessDeploymentInfosSupervised;
import org.bonitasoft.engine.search.supervisor.SearchSupervisors;
import org.bonitasoft.engine.search.task.SearchArchivedTasks;
import org.bonitasoft.engine.search.task.SearchArchivedTasksManagedBy;
import org.bonitasoft.engine.service.ModelConvertor;
import org.bonitasoft.engine.service.ServiceAccessor;
import org.bonitasoft.engine.service.ServiceAccessorSingleton;
import org.bonitasoft.engine.session.model.SSession;
import org.bonitasoft.engine.supervisor.mapping.SSupervisorDeletionException;
import org.bonitasoft.engine.supervisor.mapping.SSupervisorNotFoundException;
import org.bonitasoft.engine.supervisor.mapping.SupervisorMappingService;
import org.bonitasoft.engine.supervisor.mapping.model.SProcessSupervisor;
import org.bonitasoft.engine.transaction.UserTransactionService;
import org.bonitasoft.engine.work.WorkDescriptor;
import org.bonitasoft.engine.work.WorkService;

/**
 * @author Baptiste Mesta
 * @author Matthieu Chaffotte
 * @author Yanyan Liu
 * @author Elias Ricken de Medeiros
 * @author Zhao Na
 * @author Zhang Bole
 * @author Emmanuel Duchastenier
 * @author Celine Souchet
 * @author Arthur Freycon
 * @author Haroun EL ALAMI
 */
@Slf4j
public class ProcessAPIImpl implements ProcessAPI {

    private static final int BATCH_SIZE = 500;

    private static final String CONTAINER_TYPE_PROCESS_INSTANCE = "PROCESS_INSTANCE";

    private static final String CONTAINER_TYPE_ACTIVITY_INSTANCE = "ACTIVITY_INSTANCE";

    private static final String PENDING_OR_ASSIGNED = "PendingOrAssigned";

    private static final String PENDING_OR_ASSIGNED_OR_ASSIGNED_TO_OTHERS = "PendingOrAssignedOrAssignedToOthers";

    protected final ProcessConfigurationAPIImpl processConfigurationAPI;
    private final ProcessManagementAPIImplDelegate processManagementAPIImplDelegate;
    private final DocumentAPI documentAPI;
    private final TaskInvolvementDelegate taskInvolvementDelegate;
    private final ProcessInvolvementDelegate processInvolvementDelegate;
    private final ProcessDeploymentAPIDelegate processDeploymentAPIDelegate;

    public ProcessAPIImpl() {
        this(new ProcessManagementAPIImplDelegate(), new DocumentAPIImpl(), new ProcessConfigurationAPIImpl(),
                new TaskInvolvementDelegate(), new ProcessInvolvementDelegate());
    }

    public ProcessAPIImpl(final ProcessManagementAPIImplDelegate processManagementAPIDelegate,
            final DocumentAPI documentAPI,
            ProcessConfigurationAPIImpl processConfigurationAPI, TaskInvolvementDelegate taskInvolvementDelegate,
            ProcessInvolvementDelegate processInvolvementDelegate) {
        this.processManagementAPIImplDelegate = processManagementAPIDelegate;
        this.documentAPI = documentAPI;
        this.processConfigurationAPI = processConfigurationAPI;
        this.taskInvolvementDelegate = taskInvolvementDelegate;
        this.processInvolvementDelegate = processInvolvementDelegate;
        this.processDeploymentAPIDelegate = ProcessDeploymentAPIDelegate.getInstance();
    }

    @Override
    public SearchResult<HumanTaskInstance> searchHumanTaskInstances(final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        return AbstractHumanTaskInstanceSearchEntity
                .searchHumanTaskInstance(searchEntitiesDescriptor.getSearchHumanTaskInstanceDescriptor(),
                        searchOptions,
                        flowNodeStateManager,
                        activityInstanceService::getNumberOfHumanTasks,
                        activityInstanceService::searchHumanTasks)
                .search();
    }

    @Override
    public void deleteProcessDefinition(final long processDefinitionId) throws DeletionException {
        final SearchOptionsBuilder builder = new SearchOptionsBuilder(0, 1);
        builder.filter(ProcessInstanceSearchDescriptor.PROCESS_DEFINITION_ID, processDefinitionId);
        final SearchOptions searchOptions = builder.done();
        try {
            final boolean hasOpenProcessInstances = searchProcessInstances(getServiceAccessor(), searchOptions)
                    .getCount() > 0;
            checkIfItIsPossibleToDeleteProcessInstance(processDefinitionId, hasOpenProcessInstances);
            final boolean hasArchivedProcessInstances = searchArchivedProcessInstancesInAllStates(searchOptions)
                    .getCount() > 0;
            checkIfItIsPossibleToDeleteProcessInstance(processDefinitionId, hasArchivedProcessInstances);

            removeAllCategoriesFromProcessDefinition(processDefinitionId);
            deleteAllSupervisorsOfProcess(processDefinitionId);

            processManagementAPIImplDelegate.deleteProcessDefinition(processDefinitionId);
        } catch (final SProcessDefinitionNotFoundException spdnfe) {
            throw new DeletionException(new ProcessDefinitionNotFoundException(spdnfe));
        } catch (final Exception e) {
            throw new DeletionException(e);
        }
    }

    private void checkIfItIsPossibleToDeleteProcessInstance(final long processDefinitionId,
            final boolean canThrowException) throws DeletionException {
        if (canThrowException) {
            throw new DeletionException("Some active process instances are still found, process #" + processDefinitionId
                    + " can't be deleted.");
        }
    }

    @Override
    public void deleteProcessDefinitions(final List<Long> processDefinitionIds) throws DeletionException {
        for (final Long processDefinitionId : processDefinitionIds) {
            deleteProcessDefinition(processDefinitionId);
        }
    }

    private void releaseLocks(final LockService lockService, final List<BonitaLock> locks, final long tenantId) {
        if (locks == null) {
            return;
        }
        for (final BonitaLock lock : locks) {
            try {
                lockService.unlock(lock, tenantId);
            } catch (final SLockException e) {
                logError(e);
            }
        }
    }

    @Override
    public ProcessDefinition deployAndEnableProcess(final DesignProcessDefinition designProcessDefinition)
            throws ProcessDeployException,
            ProcessEnablementException, AlreadyExistsException, InvalidProcessDefinitionException {
        BusinessArchive businessArchive;
        try {
            businessArchive = new BusinessArchiveBuilder().createNewBusinessArchive()
                    .setProcessDefinition(designProcessDefinition).done();
        } catch (final InvalidBusinessArchiveFormatException e) {
            throw new InvalidProcessDefinitionException(e.getMessage());
        }
        return deployAndEnableProcess(businessArchive);
    }

    @Override
    public ProcessDefinition deployAndEnableProcess(final BusinessArchive businessArchive)
            throws ProcessDeployException, ProcessEnablementException, AlreadyExistsException {
        return processDeploymentAPIDelegate.deployAndEnableProcess(businessArchive);
    }

    @Override
    public ProcessDefinition deploy(final DesignProcessDefinition designProcessDefinition)
            throws AlreadyExistsException, ProcessDeployException {
        try {
            final BusinessArchive businessArchive = new BusinessArchiveBuilder().createNewBusinessArchive()
                    .setProcessDefinition(designProcessDefinition)
                    .done();
            return deploy(businessArchive);
        } catch (final InvalidBusinessArchiveFormatException e) {
            throw new ProcessDeployException(e);
        }
    }

    @Override
    public ProcessDefinition deploy(final BusinessArchive businessArchive)
            throws ProcessDeployException, AlreadyExistsException {
        return processDeploymentAPIDelegate.deploy(businessArchive);
    }

    @Override
    public void importActorMapping(final long pDefinitionId, final byte[] actorMappingXML)
            throws ActorMappingImportException {
        if (actorMappingXML != null) {
            importActorMapping(pDefinitionId, new String(actorMappingXML, UTF_8));
        }
    }

    @Override
    public byte[] exportBarProcessContentUnderHome(final long processDefinitionId) throws ProcessExportException {
        File barExport = null;
        try {
            barExport = File.createTempFile("barExport", ".bar");
            barExport.delete();
            final BusinessArchive export = getServiceAccessor().getBusinessArchiveService().export(processDefinitionId);
            BusinessArchiveFactory.writeBusinessArchiveToFile(export, barExport);
            return FileUtils.readFileToByteArray(barExport);
        } catch (IOException | InvalidBusinessArchiveFormatException | SBonitaException e) {
            throw new ProcessExportException(e);
        } finally {
            if (barExport != null && barExport.exists()) {
                barExport.delete();
            }
        }
    }

    @Override
    public void disableAndDeleteProcessDefinition(final long processDefinitionId)
            throws ProcessDefinitionNotFoundException, ProcessActivationException,
            DeletionException {
        disableProcess(processDefinitionId);
        deleteProcessDefinition(processDefinitionId);
    }

    @Override
    public void disableProcess(final long processDefinitionId)
            throws ProcessDefinitionNotFoundException, ProcessActivationException {
        try {
            processManagementAPIImplDelegate.disableProcess(processDefinitionId);
        } catch (final SProcessDefinitionNotFoundException e) {
            throw new ProcessDefinitionNotFoundException(e);
        } catch (final SBonitaException e) {
            throw new ProcessActivationException(e);
        }
    }

    @Override
    public void enableProcess(final long processDefinitionId)
            throws ProcessDefinitionNotFoundException, ProcessEnablementException {
        processDeploymentAPIDelegate.enableProcess(processDefinitionId);
    }

    SSession getSession() {
        return SessionInfos.getSession();
    }

    @Override
    public void executeFlowNode(final long flownodeInstanceId) throws FlowNodeExecutionException {
        executeFlowNode(0, flownodeInstanceId);
    }

    @Override
    public void executeFlowNode(final long userId, final long flownodeInstanceId) throws FlowNodeExecutionException {
        try {
            ActivityInstanceService activityInstanceService = getServiceAccessor().getActivityInstanceService();
            SFlowNodeInstance flowNodeInstance = activityInstanceService.getFlowNodeInstance(flownodeInstanceId);
            log.warn(String.format(
                    "executeFlowNode was called: <%s> is forcing the execution of the flow node with name = <%s> id = <%d> and type <%s> in current state <%s: %s>. "
                            +
                            "Executing a flow node directly through that API method is not recommended and can affect the normal behavior of the platform. "
                            +
                            "If that flow node was already scheduled for execution, there is no guarantee on how that flow node will behave anymore. "
                            +
                            "If you are trying to execute a user task, please use executeUserTask method instead.",
                    getLoggedUsername(), flowNodeInstance.getName(), flowNodeInstance.getId(),
                    flowNodeInstance.getType().name(),
                    flowNodeInstance.getStateId(), flowNodeInstance.getStateName()));
            executeFlowNode(userId, flownodeInstanceId, new HashMap<>(), false);
        } catch (final ContractViolationException | SBonitaException e) {
            throw new FlowNodeExecutionException(e);
        }
    }

    @Override
    public List<ActivityInstance> getActivities(final long processInstanceId, final int startIndex,
            final int maxResults) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        try {
            return ModelConvertor.toActivityInstances(
                    activityInstanceService.getActivityInstances(processInstanceId, startIndex, maxResults),
                    flowNodeStateManager);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public long getNumberOfProcessDeploymentInfos() {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final TransactionContentWithResult<Long> transactionContentWithResult = new GetNumberOfProcessDeploymentInfos(
                processDefinitionService);
        try {
            transactionContentWithResult.execute();
            return transactionContentWithResult.getResult();
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public ProcessDefinition getProcessDefinition(final long processDefinitionId)
            throws ProcessDefinitionNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        try {
            final SProcessDefinition sProcessDefinition = processDefinitionService
                    .getProcessDefinition(processDefinitionId);
            return ModelConvertor.toProcessDefinition(sProcessDefinition);
        } catch (final SProcessDefinitionNotFoundException e) {
            throw new ProcessDefinitionNotFoundException(e);
        } catch (final SBonitaReadException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public DesignProcessDefinition getDesignProcessDefinition(final long processDefinitionId)
            throws ProcessDefinitionNotFoundException {
        try {
            return getServiceAccessor().getProcessDefinitionService().getDesignProcessDefinition(processDefinitionId);
        } catch (SBonitaReadException | SProcessDefinitionNotFoundException e) {
            throw new ProcessDefinitionNotFoundException(processDefinitionId, e);
        }
    }

    @Override
    public ProcessDeploymentInfo getProcessDeploymentInfo(final long processDefinitionId)
            throws ProcessDefinitionNotFoundException {
        return processDeploymentAPIDelegate.getProcessDeploymentInfo(processDefinitionId);
    }

    private void logError(final Exception e) {
        if (log.isErrorEnabled()) {
            log.error("", e);
        }
    }

    private void logInstanceNotFound(final SBonitaException e) {
        if (log.isDebugEnabled()) {
            log.debug(e.getMessage() + ". It may have been completed.");
        }
    }

    @Override
    public ProcessInstance getProcessInstance(final long processInstanceId) throws ProcessInstanceNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        try {
            final SProcessInstance sProcessInstance = getSProcessInstance(processInstanceId);
            return ModelConvertor
                    .toProcessInstances(Collections.singletonList(sProcessInstance), processDefinitionService).get(0);
        } catch (final SProcessInstanceNotFoundException notFound) {
            throw new ProcessInstanceNotFoundException(notFound);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    protected SProcessInstance getSProcessInstance(final long processInstanceId)
            throws SProcessInstanceNotFoundException, SProcessInstanceReadException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        return processInstanceService.getProcessInstance(processInstanceId);
    }

    @Override
    public List<ArchivedProcessInstance> getArchivedProcessInstances(final long processInstanceId, final int startIndex,
            final int maxResults) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final GetArchivedProcessInstanceList getProcessInstanceList = new GetArchivedProcessInstanceList(
                processInstanceService,
                serviceAccessor.getProcessDefinitionService(), searchEntitiesDescriptor, processInstanceId, startIndex,
                maxResults);
        try {
            getProcessInstanceList.execute();
        } catch (final SBonitaException e) {
            logError(e);
            throw new RetrieveException(e);
        }
        return getProcessInstanceList.getResult();
    }

    @Override
    public ArchivedProcessInstance getArchivedProcessInstance(final long id)
            throws ArchivedProcessInstanceNotFoundException, RetrieveException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        try {
            final SAProcessInstance archivedProcessInstance = processInstanceService.getArchivedProcessInstance(id);
            if (archivedProcessInstance == null) {
                throw new ArchivedProcessInstanceNotFoundException(id);
            }
            final SProcessDefinition sProcessDefinition = processDefinitionService
                    .getProcessDefinition(archivedProcessInstance
                            .getProcessDefinitionId());
            return toArchivedProcessInstance(archivedProcessInstance, sProcessDefinition);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    /**
     * internal use for mocking purpose
     */
    protected ArchivedProcessInstance toArchivedProcessInstance(final SAProcessInstance archivedProcessInstance,
            final SProcessDefinition sProcessDefinition) {
        return ModelConvertor.toArchivedProcessInstance(archivedProcessInstance, sProcessDefinition);
    }

    @Override
    public ArchivedProcessInstance getFinalArchivedProcessInstance(final long sourceProcessInstanceId)
            throws ArchivedProcessInstanceNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();

        final GetLastArchivedProcessInstance getProcessInstance = new GetLastArchivedProcessInstance(
                processInstanceService,
                serviceAccessor.getProcessDefinitionService(), sourceProcessInstanceId,
                serviceAccessor.getSearchEntitiesDescriptor());
        try {
            getProcessInstance.execute();
        } catch (final SProcessInstanceNotFoundException e) {
            logInstanceNotFound(e);
            throw new ArchivedProcessInstanceNotFoundException(e);
        } catch (final SBonitaException e) {
            logError(e);
            throw new RetrieveException(e);
        }
        return getProcessInstance.getResult();
    }

    @Override
    public ProcessInstance startProcess(final long processDefinitionId)
            throws ProcessActivationException, ProcessExecutionException {
        try {
            return startProcess(getUserId(), processDefinitionId);
        } catch (final ProcessDefinitionNotFoundException e) {
            throw new ProcessExecutionException(e);
        }
    }

    @Override
    public ProcessInstance startProcess(final long userId, final long processDefinitionId)
            throws ProcessDefinitionNotFoundException,
            ProcessExecutionException, ProcessActivationException {
        return startProcess(userId, processDefinitionId, null, null);
    }

    @Override
    public int getNumberOfActors(final long processDefinitionId) throws ProcessDefinitionNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();

        final GetNumberOfActors getNumberofActors = new GetNumberOfActors(processDefinitionService,
                processDefinitionId);
        try {
            getNumberofActors.execute();
        } catch (final SBonitaException e) {
            throw new ProcessDefinitionNotFoundException(e);
        }
        return getNumberofActors.getResult();
    }

    @Override
    public List<ActorInstance> getActors(final long processDefinitionId, final int startIndex, final int maxResults,
            final ActorCriterion sort) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
        try {
            final QueryOptions queryOptions = getQueryOptions(startIndex, maxResults, sort);
            final List<SActor> actors = actorMappingService.getActors(processDefinitionId, queryOptions);
            return ModelConvertor.toActors(actors);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    private static QueryOptions getQueryOptions(int startIndex, int maxResults, ActorCriterion sort) {
        OrderByType order;
        if (sort == null) {
            order = OrderByType.ASC;
        } else {
            switch (sort) {
                case NAME_ASC:
                    order = OrderByType.ASC;
                    break;
                default:
                    order = OrderByType.DESC;
                    break;
            }
        }
        return new QueryOptions(startIndex, maxResults, SActor.class, "name", order);
    }

    @Override
    public List<ActorMember> getActorMembers(final long actorId, final int startIndex, final int maxResults) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();

        try {
            final List<SActorMember> actorMembers = actorMappingService.getActorMembers(actorId, startIndex,
                    maxResults);
            return ModelConvertor.toActorMembers(actorMembers);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }

    }

    @Override
    public long getNumberOfActorMembers(final long actorId) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
        final GetNumberOfActorMembers numberOfActorMembers = new GetNumberOfActorMembers(actorMappingService, actorId);
        try {
            numberOfActorMembers.execute();
            return numberOfActorMembers.getResult();
        } catch (final SBonitaException sbe) {
            return 0; // FIXME throw retrieve exception
        }
    }

    @Override
    public long getNumberOfUsersOfActor(final long actorId) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
        final GetNumberOfUsersOfActor numberOfUsersOfActor = new GetNumberOfUsersOfActor(actorMappingService, actorId);
        numberOfUsersOfActor.execute();
        return numberOfUsersOfActor.getResult();
    }

    @Override
    public long getNumberOfRolesOfActor(final long actorId) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
        final GetNumberOfRolesOfActor numberOfRolesOfActor = new GetNumberOfRolesOfActor(actorMappingService, actorId);
        numberOfRolesOfActor.execute();
        return numberOfRolesOfActor.getResult();
    }

    @Override
    public long getNumberOfGroupsOfActor(final long actorId) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
        final GetNumberOfGroupsOfActor numberOfGroupsOfActor = new GetNumberOfGroupsOfActor(actorMappingService,
                actorId);
        numberOfGroupsOfActor.execute();
        return numberOfGroupsOfActor.getResult();
    }

    @Override
    public long getNumberOfMembershipsOfActor(final long actorId) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
        final GetNumberOfMembershipsOfActor getNumber = new GetNumberOfMembershipsOfActor(actorMappingService, actorId);
        getNumber.execute();
        return getNumber.getResult();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Deprecated(since = "9.0.0")
    public ActorInstance updateActor(final long actorId, final ActorUpdater descriptor)
            throws ActorNotFoundException, UpdateException {
        if (descriptor == null || descriptor.getFields().isEmpty()) {
            throw new UpdateException("The update descriptor does not contain field updates");
        }
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
        final SActorUpdateBuilder actorUpdateBuilder = BuilderFactory.get(SActorUpdateBuilderFactory.class)
                .createNewInstance();
        final Map<ActorField, Serializable> fields = descriptor.getFields();
        for (final Entry<ActorField, Serializable> field : fields.entrySet()) {
            switch (field.getKey()) {
                case DISPLAY_NAME:
                    actorUpdateBuilder.updateDisplayName((String) field.getValue());
                    break;
                case DESCRIPTION:
                    actorUpdateBuilder.updateDescription((String) field.getValue());
                    break;
                default:
                    break;
            }
        }
        final EntityUpdateDescriptor updateDescriptor = actorUpdateBuilder.done();
        SActor updateActor;
        try {
            updateActor = actorMappingService.updateActor(actorId, updateDescriptor);
            return ModelConvertor.toActorInstance(updateActor);
        } catch (final SActorNotFoundException e) {
            throw new ActorNotFoundException(e);
        } catch (final SBonitaException e) {
            throw new UpdateException(e);
        }
    }

    @Override
    public ActorMember addUserToActor(final long actorId, final long userId) throws CreationException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();

        try {
            // Check if the mapping already to throw a specific exception
            checkIfActorMappingForUserAlreadyExists(actorId, userId);

            final SActorMember actorMember = actorMappingService.addUserToActor(actorId, userId);
            final long processDefinitionId = actorMappingService.getActor(actorId).getScopeId();
            final ActorMember clientActorMember = ModelConvertor.toActorMember(actorMember);
            serviceAccessor.getBusinessArchiveArtifactsManager().resolveDependencies(processDefinitionId,
                    serviceAccessor);
            return clientActorMember;
        } catch (final SBonitaException sbe) {
            throw new CreationException(sbe);
        }
    }

    private void checkIfActorMappingForUserAlreadyExists(final long actorId, final long userId)
            throws AlreadyExistsException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();

        try {
            final SActorMember sActorMember = actorMappingService.getActorMember(actorId, userId, -1, -1);
            if (sActorMember != null) {
                throw new AlreadyExistsException("The mapping already exists for the actor id = <" + actorId
                        + ">, the user id = <" + userId + ">");
            }
        } catch (final SBonitaReadException e) {
            // Do nothing
        }
    }

    @Override
    public ActorMember addUserToActor(final String actorName, final ProcessDefinition processDefinition,
            final long userId) throws CreationException,
            ActorNotFoundException {
        final List<ActorInstance> actors = getActors(processDefinition.getId(), 0, Integer.MAX_VALUE,
                ActorCriterion.NAME_ASC);
        for (final ActorInstance ai : actors) {
            if (actorName.equals(ai.getName())) {
                return addUserToActor(ai.getId(), userId);
            }
        }
        throw new ActorNotFoundException(
                "Actor " + actorName + " not found in process definition " + processDefinition.getName());
    }

    @Override
    public ActorMember addGroupToActor(final long actorId, final long groupId) throws CreationException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
        try {
            // Check if the mapping already to throw a specific exception
            checkIfActorMappingForGroupAlreadyExists(actorId, groupId);

            final SActorMember actorMember = actorMappingService.addGroupToActor(actorId, groupId);
            final long processDefinitionId = actorMappingService.getActor(actorId).getScopeId();
            final ActorMember clientActorMember = ModelConvertor.toActorMember(actorMember);
            serviceAccessor.getBusinessArchiveArtifactsManager().resolveDependencies(processDefinitionId,
                    serviceAccessor);
            return clientActorMember;
        } catch (final SBonitaException e) {
            throw new CreationException(e);
        }
    }

    private void checkIfActorMappingForGroupAlreadyExists(final long actorId, final long groupId)
            throws AlreadyExistsException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();

        try {
            final SActorMember sActorMember = actorMappingService.getActorMember(actorId, -1, groupId, -1);
            if (sActorMember != null) {
                throw new AlreadyExistsException("The mapping already exists for the actor id = <" + actorId
                        + ">, the group id = <" + groupId + ">");
            }
        } catch (final SBonitaReadException e) {
            // Do nothing
        }
    }

    @Override
    public ActorMember addGroupToActor(final String actorName, final long groupId,
            final ProcessDefinition processDefinition) throws CreationException,
            ActorNotFoundException {
        final List<ActorInstance> actors = getActors(processDefinition.getId(), 0, Integer.MAX_VALUE,
                ActorCriterion.NAME_ASC);
        for (final ActorInstance actorInstance : actors) {
            if (actorName.equals(actorInstance.getName())) {
                return addGroupToActor(actorInstance.getId(), groupId);
            }
        }
        throw new ActorNotFoundException(
                "Actor " + actorName + " not found in process definition " + processDefinition.getName());
    }

    @Override
    public ActorMember addRoleToActor(final long actorId, final long roleId) throws CreationException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();

        try {
            // Check if the mapping already to throw a specific exception
            checkIfActorMappingForRoleAlreadyExists(actorId, roleId);

            final SActorMember actorMember = actorMappingService.addRoleToActor(actorId, roleId);
            final long processDefinitionId = actorMappingService.getActor(actorId).getScopeId();
            final ActorMember clientActorMember = ModelConvertor.toActorMember(actorMember);
            serviceAccessor.getBusinessArchiveArtifactsManager().resolveDependencies(processDefinitionId,
                    serviceAccessor);
            return clientActorMember;
        } catch (final SBonitaException sbe) {
            throw new CreationException(sbe);
        }
    }

    private void checkIfActorMappingForRoleAlreadyExists(final long actorId, final long roleId)
            throws AlreadyExistsException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();

        try {
            final SActorMember sActorMember = actorMappingService.getActorMember(actorId, -1, -1, roleId);
            if (sActorMember != null) {
                throw new AlreadyExistsException("The mapping already exists for the actor id = <" + actorId
                        + ">, the role id = <" + roleId + ">");
            }
        } catch (final SBonitaReadException e) {
            // Do nothing
        }
    }

    @Override
    public ActorMember addRoleToActor(final String actorName, final ProcessDefinition processDefinition,
            final long roleId) throws ActorNotFoundException,
            CreationException {
        final List<ActorInstance> actors = getActors(processDefinition.getId(), 0, Integer.MAX_VALUE,
                ActorCriterion.NAME_ASC);
        for (final ActorInstance ai : actors) {
            if (actorName.equals(ai.getName())) {
                return addRoleToActor(ai.getId(), roleId);
            }
        }
        throw new ActorNotFoundException(
                "Actor " + actorName + " not found in process definition " + processDefinition.getName());
    }

    @Override
    public ActorMember addRoleAndGroupToActor(final String actorName, final ProcessDefinition processDefinition,
            final long roleId, final long groupId)
            throws ActorNotFoundException, CreationException {
        final List<ActorInstance> actors = getActors(processDefinition.getId(), 0, Integer.MAX_VALUE,
                ActorCriterion.NAME_ASC);
        for (final ActorInstance ai : actors) {
            if (actorName.equals(ai.getName())) {
                return addRoleAndGroupToActor(ai.getId(), roleId, groupId);
            }
        }
        throw new ActorNotFoundException(
                "Actor " + actorName + " not found in process definition " + processDefinition.getName());
    }

    @Override
    public ActorMember addRoleAndGroupToActor(final long actorId, final long roleId, final long groupId)
            throws CreationException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();

        try {
            checkIfActorMappingForMembershipAlreadyExists(actorId, roleId, groupId);

            final SActorMember actorMember = actorMappingService.addRoleAndGroupToActor(actorId, roleId, groupId);
            final long processDefinitionId = actorMappingService.getActor(actorId).getScopeId();
            final ActorMember clientActorMember = ModelConvertor.toActorMember(actorMember);
            serviceAccessor.getBusinessArchiveArtifactsManager().resolveDependencies(processDefinitionId,
                    serviceAccessor);
            return clientActorMember;
        } catch (final SBonitaException sbe) {
            throw new CreationException(sbe);
        }
    }

    private void checkIfActorMappingForMembershipAlreadyExists(final long actorId, final long roleId,
            final long groupId) throws AlreadyExistsException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();

        try {
            final SActorMember sActorMember = actorMappingService.getActorMember(actorId, -1, groupId, roleId);
            if (sActorMember != null) {
                throw new AlreadyExistsException(
                        "The mapping already exists for the actor id = <" + actorId + ">, the role id = <" + roleId
                                + ">, the group id = <" + groupId + ">");
            }
        } catch (final SBonitaReadException e) {
            // Do nothing
        }
    }

    @Override
    public void removeActorMember(final long actorMemberId) throws DeletionException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
        final RemoveActorMember removeActorMember = new RemoveActorMember(actorMappingService, actorMemberId);
        // FIXME remove an actor member when process is running!
        try {
            removeActorMember.execute();
            final SActorMember actorMember = removeActorMember.getResult();
            final long processDefinitionId = getActor(actorMember.getActorId()).getProcessDefinitionId();
            serviceAccessor.getBusinessArchiveArtifactsManager().resolveDependencies(processDefinitionId,
                    serviceAccessor);
        } catch (final SBonitaException | ActorNotFoundException sbe) {
            throw new DeletionException(sbe);
        }
    }

    @Override
    public ActorInstance getActor(final long actorId) throws ActorNotFoundException {
        try {
            final ServiceAccessor serviceAccessor = getServiceAccessor();
            final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();

            final GetActor getActor = new GetActor(actorMappingService, actorId);
            getActor.execute();
            return ModelConvertor.toActorInstance(getActor.getResult());
        } catch (final SBonitaException e) {
            throw new ActorNotFoundException(e);
        }
    }

    @Override
    public ActivityInstance getActivityInstance(final long activityInstanceId)
            throws ActivityInstanceNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final SActivityInstance sActivityInstance;
        try {
            sActivityInstance = getSActivityInstance(activityInstanceId);
        } catch (final SActivityInstanceNotFoundException e) {
            throw new ActivityInstanceNotFoundException(activityInstanceId);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
        return ModelConvertor.toActivityInstance(sActivityInstance, flowNodeStateManager);
    }

    protected SActivityInstance getSActivityInstance(final long activityInstanceId)
            throws SActivityInstanceNotFoundException, SActivityReadException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        return activityInstanceService.getActivityInstance(activityInstanceId);
    }

    @Override
    public FlowNodeInstance getFlowNodeInstance(final long flowNodeInstanceId)
            throws FlowNodeInstanceNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        try {
            final SFlowNodeInstance flowNodeInstance = activityInstanceService.getFlowNodeInstance(flowNodeInstanceId);
            return ModelConvertor.toFlowNodeInstance(flowNodeInstance, flowNodeStateManager);
        } catch (final SFlowNodeNotFoundException e) {
            throw new FlowNodeInstanceNotFoundException(e);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public List<HumanTaskInstance> getAssignedHumanTaskInstances(final long userId, final int startIndex,
            final int maxResults,
            final ActivityInstanceCriterion pagingCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final OrderAndField orderAndField = OrderAndFields.getOrderAndFieldForActivityInstance(pagingCriterion);

        final ActivityInstanceService instanceService = serviceAccessor.getActivityInstanceService();
        try {
            final GetAssignedTasks getAssignedTasks = new GetAssignedTasks(instanceService, userId, startIndex,
                    maxResults, orderAndField.getField(),
                    orderAndField.getOrder());
            getAssignedTasks.execute();
            final List<SHumanTaskInstance> assignedTasks = getAssignedTasks.getResult();
            return ModelConvertor.toHumanTaskInstances(assignedTasks, flowNodeStateManager);
        } catch (final SBonitaException e) {
            return Collections.emptyList();
        }
    }

    @Override
    public long getNumberOfPendingHumanTaskInstances(final long userId) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();

        final ProcessDefinitionService processDefService = serviceAccessor.getProcessDefinitionService();
        try {
            final Set<Long> actorIds = getActorsForUser(userId, actorMappingService, processDefService);
            if (actorIds.isEmpty()) {
                return 0L;
            }
            return activityInstanceService.getNumberOfPendingTasksForUser(userId, QueryOptions.countQueryOptions());
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public List<HumanTaskInstance> getPendingHumanTaskInstances(final long userId, final int startIndex,
            final int maxResults,
            final ActivityInstanceCriterion pagingCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final OrderAndField orderAndField = OrderAndFields.getOrderAndFieldForActivityInstance(pagingCriterion);

        final ProcessDefinitionService definitionService = serviceAccessor.getProcessDefinitionService();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        try {
            final Set<Long> actorIds = getActorsForUser(userId, actorMappingService, definitionService);
            final List<SHumanTaskInstance> pendingTasks = activityInstanceService.getPendingTasks(userId, actorIds,
                    startIndex, maxResults,
                    orderAndField.getField(), orderAndField.getOrder());
            return ModelConvertor.toHumanTaskInstances(pendingTasks, flowNodeStateManager);
        } catch (final SBonitaException e) {
            return Collections.emptyList();
        }
    }

    private Set<Long> getActorsForUser(final long userId, final ActorMappingService actorMappingService,
            final ProcessDefinitionService definitionService)
            throws SBonitaReadException {
        final Set<Long> actorIds = new HashSet<>();
        final List<Long> processDefIds = definitionService.getProcessDefinitionIds(0, Integer.MAX_VALUE);
        if (!processDefIds.isEmpty()) {
            final Set<Long> processDefinitionIds = new HashSet<>(processDefIds);
            final List<SActor> actors = actorMappingService.getActors(processDefinitionIds, userId);
            for (final SActor sActor : actors) {
                actorIds.add(sActor.getId());
            }
        }
        return actorIds;
    }

    @Override
    public ArchivedActivityInstance getArchivedActivityInstance(final long sourceActivityInstanceId)
            throws ActivityInstanceNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final GetArchivedActivityInstance getActivityInstance = new GetArchivedActivityInstance(activityInstanceService,
                sourceActivityInstanceId);
        try {
            getActivityInstance.execute();
        } catch (final SActivityInstanceNotFoundException e) {
            throw new ActivityInstanceNotFoundException(sourceActivityInstanceId, e);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
        return ModelConvertor.toArchivedActivityInstance(getActivityInstance.getResult(), flowNodeStateManager);
    }

    @Override
    public ArchivedFlowNodeInstance getArchivedFlowNodeInstance(final long archivedFlowNodeInstanceId)
            throws ArchivedFlowNodeInstanceNotFoundException,
            RetrieveException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();

        try {
            final SAFlowNodeInstance archivedFlowNodeInstance = activityInstanceService
                    .getArchivedFlowNodeInstance(archivedFlowNodeInstanceId);
            return ModelConvertor.toArchivedFlowNodeInstance(archivedFlowNodeInstance, flowNodeStateManager);
        } catch (final SFlowNodeNotFoundException e) {
            throw new ArchivedFlowNodeInstanceNotFoundException(archivedFlowNodeInstanceId);
        } catch (final SFlowNodeReadException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public List<ProcessInstance> getProcessInstances(final int startIndex, final int maxResults,
            final ProcessInstanceCriterion criterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final OrderAndField orderAndField = OrderAndFields.getOrderAndFieldForProcessInstance(criterion);
        final SearchOptionsBuilder searchOptionsBuilder = new SearchOptionsBuilder(startIndex, maxResults);
        searchOptionsBuilder.sort(orderAndField.getField(), Order.valueOf(orderAndField.getOrder().name()));
        List<ProcessInstance> result;
        try {
            result = searchProcessInstances(serviceAccessor, searchOptionsBuilder.done()).getResult();
        } catch (final SBonitaException e) {
            result = Collections.emptyList();
        }
        return result;
    }

    @Override
    public long getNumberOfProcessInstances() {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();

        try {
            final TransactionContentWithResult<Long> transactionContent = new GetNumberOfProcessInstance(
                    processInstanceService, processDefinitionService,
                    searchEntitiesDescriptor);
            transactionContent.execute();
            return transactionContent.getResult();
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    protected SearchResult<ProcessInstance> searchProcessInstances(final ServiceAccessor serviceAccessor,
            final SearchOptions searchOptions)
            throws SBonitaException {
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();

        final SearchProcessInstances searchProcessInstances = new SearchProcessInstances(processInstanceService,
                searchEntitiesDescriptor.getSearchProcessInstanceDescriptor(), searchOptions, processDefinitionService);
        searchProcessInstances.execute();
        return searchProcessInstances.getResult();
    }

    @Override
    public List<ArchivedProcessInstance> getArchivedProcessInstances(final int startIndex, final int maxResults,
            final ProcessInstanceCriterion criterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final OrderAndField orderAndField = OrderAndFields.getOrderAndFieldForProcessInstance(criterion);

        final SearchOptionsBuilder searchOptionsBuilder = new SearchOptionsBuilder(startIndex, maxResults);
        searchOptionsBuilder.sort(orderAndField.getField(), Order.valueOf(orderAndField.getOrder().name()));
        searchOptionsBuilder.filter(ArchivedProcessInstancesSearchDescriptor.STATE_ID,
                ProcessInstanceState.COMPLETED.getId());
        searchOptionsBuilder.filter(ArchivedProcessInstancesSearchDescriptor.CALLER_ID, -1);
        final SearchArchivedProcessInstances searchArchivedProcessInstances = searchArchivedProcessInstances(
                serviceAccessor, searchOptionsBuilder.done());
        try {
            searchArchivedProcessInstances.execute();
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }

        return searchArchivedProcessInstances.getResult().getResult();
    }

    private SearchArchivedProcessInstances searchArchivedProcessInstances(final ServiceAccessor serviceAccessor,
            final SearchOptions searchOptions)
            throws RetrieveException {
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        return new SearchArchivedProcessInstances(processInstanceService, serviceAccessor.getProcessDefinitionService(),
                searchEntitiesDescriptor.getSearchArchivedProcessInstanceDescriptor(), searchOptions);
    }

    @Override
    public long getNumberOfArchivedProcessInstances() {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        try {
            final SAProcessInstanceBuilderFactory saProcessInstanceBuilder = BuilderFactory
                    .get(SAProcessInstanceBuilderFactory.class);
            final List<FilterOption> filterOptions = new ArrayList<>(2);
            filterOptions.add(new FilterOption(SAProcessInstance.class, saProcessInstanceBuilder.getStateIdKey(),
                    ProcessInstanceState.COMPLETED.getId()));
            filterOptions.add(new FilterOption(SAProcessInstance.class, saProcessInstanceBuilder.getCallerIdKey(), -1));
            final QueryOptions queryOptions = new QueryOptions(0, QueryOptions.UNLIMITED_NUMBER_OF_RESULTS,
                    Collections.emptyList(), filterOptions, null);
            return processInstanceService.getNumberOfArchivedProcessInstances(queryOptions);
        } catch (final SBonitaException e) {
            logError(e);
            throw new RetrieveException(e);
        }
    }

    @Override
    public SearchResult<ArchivedProcessInstance> searchArchivedProcessInstances(final SearchOptions searchOptions)
            throws RetrieveException, SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchArchivedProcessInstancesWithoutSubProcess searchArchivedProcessInstances = new SearchArchivedProcessInstancesWithoutSubProcess(
                processInstanceService, serviceAccessor.getProcessDefinitionService(),
                searchEntitiesDescriptor.getSearchArchivedProcessInstanceDescriptor(),
                searchOptions);
        try {
            searchArchivedProcessInstances.execute();
        } catch (final SBonitaException e) {
            throw new SearchException(e);
        }
        return searchArchivedProcessInstances.getResult();
    }

    @Override
    public SearchResult<ArchivedProcessInstance> searchArchivedProcessInstancesInAllStates(
            final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchArchivedProcessInstances searchArchivedProcessInstances = new SearchArchivedProcessInstances(
                processInstanceService,
                serviceAccessor.getProcessDefinitionService(),
                searchEntitiesDescriptor.getSearchArchivedProcessInstanceDescriptor(), searchOptions);
        try {
            searchArchivedProcessInstances.execute();
        } catch (final SBonitaException e) {
            throw new SearchException(e);
        }
        return searchArchivedProcessInstances.getResult();
    }

    @Override
    public List<ActivityInstance> getOpenActivityInstances(final long processInstanceId, final int startIndex,
            final int maxResults,
            final ActivityInstanceCriterion criterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();

        try {
            final int totalNumber = activityInstanceService.getNumberOfOpenActivityInstances(processInstanceId);
            // If there are no instances, return an empty list:
            if (totalNumber == 0) {
                return Collections.emptyList();
            }
            final OrderAndField orderAndField = OrderAndFields.getOrderAndFieldForActivityInstance(criterion);
            return ModelConvertor.toActivityInstances(
                    activityInstanceService.getOpenActivityInstances(processInstanceId, startIndex, maxResults,
                            orderAndField.getField(),
                            orderAndField.getOrder()),
                    flowNodeStateManager);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public List<ArchivedActivityInstance> getArchivedActivityInstances(final long processInstanceId,
            final int startIndex, final int maxResults, final ActivityInstanceCriterion criterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        return getArchivedActivityInstances(processInstanceId, startIndex, maxResults, criterion, serviceAccessor);
    }

    private List<ArchivedActivityInstance> getArchivedActivityInstances(final long processInstanceId,
            final int pageIndex, final int numberPerPage,
            final ActivityInstanceCriterion pagingCriterion, final ServiceAccessor serviceAccessor)
            throws RetrieveException {

        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final OrderAndField orderAndField = OrderAndFields.getOrderAndFieldForActivityInstance(pagingCriterion);
        final GetArchivedActivityInstances getActivityInstances = new GetArchivedActivityInstances(
                activityInstanceService, processInstanceId, pageIndex,
                numberPerPage, orderAndField.getField(), orderAndField.getOrder());
        try {
            getActivityInstances.execute();
        } catch (final SBonitaException e) {
            logError(e);
            throw new RetrieveException(e);
        }
        return ModelConvertor.toArchivedActivityInstances(getActivityInstances.getResult(), flowNodeStateManager);
    }

    @Override
    public int getNumberOfOpenedActivityInstances(final long processInstanceId) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();

        final TransactionContentWithResult<Integer> transactionContentWithResult = new GetNumberOfActivityInstance(
                processInstanceId, activityInstanceService);
        try {
            transactionContentWithResult.execute();
            return transactionContentWithResult.getResult();
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public Category createCategory(final String name, final String description) throws CreationException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final CategoryService categoryService = serviceAccessor.getCategoryService();

        try {
            final CreateCategory createCategory = new CreateCategory(name, description, categoryService);
            createCategory.execute();
            return ModelConvertor.toCategory(createCategory.getResult());
        } catch (final SCategoryAlreadyExistsException scaee) {
            throw new AlreadyExistsException(scaee);
        } catch (final SBonitaException e) {
            throw new CreationException("Category create exception!", e);
        }
    }

    @Override
    public Category getCategory(final long categoryId) throws CategoryNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final CategoryService categoryService = serviceAccessor.getCategoryService();
        try {
            final GetCategory getCategory = new GetCategory(categoryService, categoryId);
            getCategory.execute();
            final SCategory sCategory = getCategory.getResult();
            return ModelConvertor.toCategory(sCategory);
        } catch (final SBonitaException sbe) {
            throw new CategoryNotFoundException(sbe);
        }
    }

    @Override
    public long getNumberOfCategories() {
        final CategoryService categoryService = getServiceAccessor().getCategoryService();
        try {
            final GetNumberOfCategories getNumberOfCategories = new GetNumberOfCategories(categoryService);
            getNumberOfCategories.execute();
            return getNumberOfCategories.getResult();
        } catch (final SBonitaException e) {
            return 0;
        }
    }

    @Override
    public List<Category> getCategories(final int startIndex, final int maxResults,
            final CategoryCriterion sortCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final CategoryService categoryService = serviceAccessor.getCategoryService();
        String field;
        OrderByType order;
        switch (sortCriterion) {
            case NAME_ASC:
                field = SCategory.NAME;
                order = OrderByType.ASC;
                break;
            case NAME_DESC:
                field = SCategory.NAME;
                order = OrderByType.DESC;
                break;
            default:
                throw new IllegalStateException();
        }
        try {
            final GetCategories getCategories = new GetCategories(startIndex, maxResults, field, categoryService,
                    order);
            getCategories.execute();
            return ModelConvertor.toCategories(getCategories.getResult());
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public void addCategoriesToProcess(final long processDefinitionId, final List<Long> categoryIds)
            throws CreationException {
        try {
            final CategoryService categoryService = getServiceAccessor().getCategoryService();
            final ProcessDefinitionService processDefinitionService = getServiceAccessor()
                    .getProcessDefinitionService();
            for (final Long categoryId : categoryIds) {
                new AddProcessDefinitionToCategory(categoryId, processDefinitionId, categoryService,
                        processDefinitionService).execute();
            }
        } catch (final SCategoryInProcessAlreadyExistsException scipaee) {
            throw new AlreadyExistsException(scipaee);
        } catch (final SBonitaException sbe) {
            throw new CreationException(sbe);
        }
    }

    @Override
    public void removeCategoriesFromProcess(final long processDefinitionId, final List<Long> categoryIds)
            throws DeletionException {
        try {
            final CategoryService categoryService = getServiceAccessor().getCategoryService();
            final TransactionContent transactionContent = new RemoveCategoriesFromProcessDefinition(processDefinitionId,
                    categoryIds, categoryService);
            transactionContent.execute();
        } catch (final SBonitaException sbe) {
            throw new DeletionException(sbe);
        }
    }

    @Override
    public void addProcessDefinitionToCategory(final long categoryId, final long processDefinitionId)
            throws CreationException {
        try {
            final CategoryService categoryService = getServiceAccessor().getCategoryService();
            final ProcessDefinitionService processDefinitionService = getServiceAccessor()
                    .getProcessDefinitionService();
            final TransactionContent transactionContent = new AddProcessDefinitionToCategory(categoryId,
                    processDefinitionId, categoryService,
                    processDefinitionService);
            transactionContent.execute();
        } catch (final SCategoryInProcessAlreadyExistsException cipaee) {
            throw new AlreadyExistsException(cipaee);
        } catch (final SBonitaException sbe) {
            throw new CreationException(sbe);
        }
    }

    @Override
    public void addProcessDefinitionsToCategory(final long categoryId, final List<Long> processDefinitionIds)
            throws CreationException {
        try {
            final CategoryService categoryService = getServiceAccessor().getCategoryService();
            final ProcessDefinitionService processDefinitionService = getServiceAccessor()
                    .getProcessDefinitionService();
            for (final Long processDefinitionId : processDefinitionIds) {
                new AddProcessDefinitionToCategory(categoryId, processDefinitionId, categoryService,
                        processDefinitionService).execute();
            }
        } catch (final SCategoryInProcessAlreadyExistsException cipaee) {
            throw new AlreadyExistsException(cipaee);
        } catch (final SBonitaException sbe) {
            throw new CreationException(sbe);
        }
    }

    @Override
    public long getNumberOfCategories(final long processDefinitionId) {
        try {
            final ServiceAccessor serviceAccessor = getServiceAccessor();

            final CategoryService categoryService = serviceAccessor.getCategoryService();
            final GetNumberOfCategoriesOfProcess getNumberOfCategoriesOfProcess = new GetNumberOfCategoriesOfProcess(
                    categoryService, processDefinitionId);
            getNumberOfCategoriesOfProcess.execute();
            return getNumberOfCategoriesOfProcess.getResult();
        } catch (final SBonitaException sbe) {
            throw new RetrieveException(sbe);
        }
    }

    @Override
    public long getNumberOfProcessDefinitionsOfCategory(final long categoryId) {
        try {
            final CategoryService categoryService = getServiceAccessor().getCategoryService();
            return categoryService.getNumberOfProcessDeploymentInfosOfCategory(categoryId);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public List<ProcessDeploymentInfo> getProcessDeploymentInfosOfCategory(final long categoryId, final int startIndex,
            final int maxResults,
            final ProcessDeploymentInfoCriterion sortCriterion) {
        if (sortCriterion == null) {
            throw new IllegalArgumentException("You must to have a criterion to sort your result.");
        }
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final OrderByType order = buildOrderByType(sortCriterion.getOrder());
        final String field = sortCriterion.getField();
        try {
            final QueryOptions queryOptions = new QueryOptions(startIndex, maxResults,
                    SProcessDefinitionDeployInfo.class, field, order);
            final List<SProcessDefinitionDeployInfo> sProcessDefinitionDeployInfos = processDefinitionService
                    .searchProcessDeploymentInfosOfCategory(categoryId, queryOptions);
            return ModelConvertor.toProcessDeploymentInfo(sProcessDefinitionDeployInfos);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public List<Category> getCategoriesOfProcessDefinition(final long processDefinitionId, final int startIndex,
            final int maxResults,
            final CategoryCriterion sortingCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final CategoryService categoryService = serviceAccessor.getCategoryService();
        try {
            final OrderByType order = buildOrderByType(sortingCriterion.getOrder());
            return ModelConvertor.toCategories(categoryService.getCategoriesOfProcessDefinition(processDefinitionId,
                    startIndex, maxResults, order));
        } catch (final SBonitaException sbe) {
            throw new RetrieveException(sbe);
        }
    }

    private OrderByType buildOrderByType(final Order order) {
        return OrderByType.valueOf(order.name());
    }

    @Override
    public List<Category> getCategoriesUnrelatedToProcessDefinition(final long processDefinitionId,
            final int startIndex, final int maxResults,
            final CategoryCriterion sortingCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final CategoryService categoryService = serviceAccessor.getCategoryService();
        try {
            final OrderByType order = buildOrderByType(sortingCriterion.getOrder());
            return ModelConvertor.toCategories(categoryService
                    .getCategoriesUnrelatedToProcessDefinition(processDefinitionId, startIndex, maxResults, order));
        } catch (final SBonitaException sbe) {
            throw new RetrieveException(sbe);
        }
    }

    @Override
    public void updateCategory(final long categoryId, final CategoryUpdater updater)
            throws CategoryNotFoundException, UpdateException {
        if (updater == null || updater.getFields().isEmpty()) {
            throw new UpdateException("The update descriptor does not contain field updates");
        }
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final CategoryService categoryService = serviceAccessor.getCategoryService();

        try {
            final SCategoryUpdateBuilderFactory fact = BuilderFactory.get(SCategoryUpdateBuilderFactory.class);
            final EntityUpdateDescriptor updateDescriptor = getCategoryUpdateDescriptor(fact.createNewInstance(),
                    updater);
            final UpdateCategory updateCategory = new UpdateCategory(categoryService, categoryId, updateDescriptor);
            updateCategory.execute();
        } catch (final SCategoryNotFoundException scnfe) {
            throw new CategoryNotFoundException(scnfe);
        } catch (final SBonitaException sbe) {
            throw new UpdateException(sbe);
        }
    }

    private EntityUpdateDescriptor getCategoryUpdateDescriptor(final SCategoryUpdateBuilder descriptorBuilder,
            final CategoryUpdater updater) {
        final Map<CategoryField, Serializable> fields = updater.getFields();
        final String name = (String) fields.get(CategoryField.NAME);
        if (name != null) {
            descriptorBuilder.updateName(name);
        }
        final String description = (String) fields.get(CategoryField.DESCRIPTION);
        if (description != null) {
            descriptorBuilder.updateDescription(description);
        }
        return descriptorBuilder.done();
    }

    @Override
    public void deleteCategory(final long categoryId) throws DeletionException {
        if (categoryId <= 0) {
            throw new DeletionException("Category id can not be less than 0!");
        }
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final CategoryService categoryService = serviceAccessor.getCategoryService();

        final DeleteSCategory deleteSCategory = new DeleteSCategory(categoryService, categoryId);
        try {
            deleteSCategory.execute();
        } catch (final SCategoryNotFoundException scnfe) {
            throw new DeletionException(new CategoryNotFoundException(scnfe));
        } catch (final SBonitaException sbe) {
            throw new DeletionException(sbe);
        }
    }

    @Override
    public long getNumberOfUncategorizedProcessDefinitions() {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final CategoryService categoryService = serviceAccessor.getCategoryService();
        try {
            final List<Long> processDefinitionIds = processDefinitionService.getProcessDefinitionIds(0,
                    Integer.MAX_VALUE);
            long number;
            if (processDefinitionIds.isEmpty()) {
                number = 0;
            } else {
                number = processDefinitionIds.size()
                        - categoryService.getNumberOfCategorizedProcessIds(processDefinitionIds);
            }
            return number;
        } catch (final SBonitaException e) {
            throw new BonitaRuntimeException(e);// TODO refactor exceptions
        }
    }

    @Override
    public List<ProcessDeploymentInfo> getUncategorizedProcessDeploymentInfos(final int startIndex,
            final int maxResults,
            final ProcessDeploymentInfoCriterion sortCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        try {
            final QueryOptions queryOptions = new QueryOptions(startIndex, maxResults,
                    SProcessDefinitionDeployInfo.class, sortCriterion.getField(),
                    buildOrderByType(sortCriterion.getOrder()));
            final List<SProcessDefinitionDeployInfo> processDefinitionDeployInfos = processDefinitionService
                    .searchUncategorizedProcessDeploymentInfos(queryOptions);
            return ModelConvertor.toProcessDeploymentInfo(processDefinitionDeployInfos);
        } catch (final SBonitaException sbe) {
            throw new RetrieveException(sbe);
        }
    }

    @Override
    public long getNumberOfProcessDeploymentInfosUnrelatedToCategory(final long categoryId) {
        try {
            final ServiceAccessor serviceAccessor = getServiceAccessor();
            final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();

            final GetNumberOfProcessDeploymentInfosUnrelatedToCategory transactionContentWithResult = new GetNumberOfProcessDeploymentInfosUnrelatedToCategory(
                    categoryId, processDefinitionService);
            transactionContentWithResult.execute();
            return transactionContentWithResult.getResult();
        } catch (final SBonitaException sbe) {
            throw new RetrieveException(sbe);
        }
    }

    @Override
    public List<ProcessDeploymentInfo> getProcessDeploymentInfosUnrelatedToCategory(final long categoryId,
            final int startIndex, final int maxResults,
            final ProcessDeploymentInfoCriterion sortingCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();

        try {
            return ModelConvertor.toProcessDeploymentInfo(
                    processDefinitionService.getProcessDeploymentInfosUnrelatedToCategory(categoryId, startIndex,
                            maxResults, sortingCriterion));
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public long removeCategoriesFromProcessDefinition(final long processDefinitionId, final int startIndex,
            final int maxResults) throws DeletionException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final CategoryService categoryService = serviceAccessor.getCategoryService();
        final SProcessCategoryMappingBuilderFactory fact = BuilderFactory
                .get(SProcessCategoryMappingBuilderFactory.class);

        try {
            final FilterOption filterOption = new FilterOption(SProcessCategoryMapping.class, fact.getProcessIdKey(),
                    processDefinitionId);
            final OrderByOption order = new OrderByOption(SProcessCategoryMapping.class, fact.getIdKey(),
                    OrderByType.ASC);
            final QueryOptions queryOptions = new QueryOptions(startIndex, maxResults, Collections.singletonList(order),
                    Collections.singletonList(filterOption), null);
            final List<SProcessCategoryMapping> processCategoryMappings = categoryService
                    .searchProcessCategoryMappings(queryOptions);
            return categoryService.deleteProcessCategoryMappings(processCategoryMappings);
        } catch (final SBonitaException e) {
            throw new DeletionException(e);
        }
    }

    private void removeAllCategoriesFromProcessDefinition(final long processDefinitionId)
            throws DeletionException {
        removeCategoriesFromProcessDefinition(processDefinitionId, 0, Integer.MAX_VALUE);
    }

    @Override
    public long removeProcessDefinitionsFromCategory(final long categoryId, final int startIndex, final int maxResults)
            throws DeletionException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final CategoryService categoryService = serviceAccessor.getCategoryService();
        final SProcessCategoryMappingBuilderFactory fact = BuilderFactory
                .get(SProcessCategoryMappingBuilderFactory.class);

        try {
            final FilterOption filterOption = new FilterOption(SProcessCategoryMapping.class, fact.getCategoryIdKey(),
                    categoryId);
            final OrderByOption order = new OrderByOption(SProcessCategoryMapping.class, fact.getIdKey(),
                    OrderByType.ASC);
            final QueryOptions queryOptions = new QueryOptions(startIndex, maxResults, Collections.singletonList(order),
                    Collections.singletonList(filterOption), null);
            final List<SProcessCategoryMapping> processCategoryMappings = categoryService
                    .searchProcessCategoryMappings(queryOptions);
            return categoryService.deleteProcessCategoryMappings(processCategoryMappings);
        } catch (final SBonitaException e) {
            throw new DeletionException(e);
        }
    }

    @Override
    public List<EventInstance> getEventInstances(final long rootContainerId, final int startIndex, final int maxResults,
            final EventCriterion criterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final EventInstanceService eventInstanceService = serviceAccessor.getEventInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final OrderAndField orderAndField = OrderAndFields.getOrderAndFieldForEvent(criterion);
        final GetEventInstances getEventInstances = new GetEventInstances(eventInstanceService, rootContainerId,
                startIndex, maxResults,
                orderAndField.getField(), orderAndField.getOrder());
        try {
            getEventInstances.execute();
            final List<SEventInstance> result = getEventInstances.getResult();
            return ModelConvertor.toEventInstances(result, flowNodeStateManager);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public void assignUserTask(final long userTaskId, final long userId) throws UpdateException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        try {
            final AssignOrUnassignUserTask assignUserTask = new AssignOrUnassignUserTask(userId, userTaskId,
                    activityInstanceService, serviceAccessor.getFlowNodeStateManager().getStateBehaviors());
            assignUserTask.execute();
        } catch (final SBonitaException sbe) {
            throw new UpdateException(sbe);
        }
    }

    @Override
    public void assignUserTaskIfNotAssigned(final long userTaskId, final long userId) throws UpdateException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        try {
            final AssignUserTaskIfNotAssigned assignUserTask = new AssignUserTaskIfNotAssigned(userId, userTaskId,
                    activityInstanceService, serviceAccessor.getFlowNodeStateManager().getStateBehaviors());
            assignUserTask.execute();
        } catch (final SBonitaException sbe) {
            throw new UpdateException("Unable to assign user task (id: " + userTaskId + ")"
                    + " to user (id: " + userId + ") | " + sbe.getMessage(), sbe);
        }
    }

    @CustomTransactions
    @Override
    public void assignAndExecuteUserTask(long userId, long userTaskInstanceId, Map<String, Serializable> inputs)
            throws UserTaskNotFoundException, ContractViolationException, FlowNodeExecutionException {
        try {
            inTx(() -> {
                assignUserTask(userTaskInstanceId, userId);
                executeFlowNode(userId, userTaskInstanceId, inputs, true);
                return null;
            });
        } catch (final ContractViolationException e) {
            throw e;
        } catch (final SFlowNodeNotFoundException e) {
            throw new UserTaskNotFoundException(
                    String.format("User task %s is not found, it might already be executed", userTaskInstanceId));
        } catch (final Exception e) {
            verifyIfTheActivityWasInTheCorrectStateAndThrowException(userTaskInstanceId, e);
        }
    }

    @Override
    public void updateActorsOfUserTask(final long userTaskId) throws UpdateException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        try {
            final SHumanTaskInstance humanTaskInstance = getSHumanTaskInstance(userTaskId);
            if (humanTaskInstance.getStateId() != 4 || humanTaskInstance.isStateExecuting()) {
                throw new UpdateException(
                        "Unable to update actors of the task " + userTaskId + " because it is not in ready state");
            }
            final long processDefinitionId = humanTaskInstance.getLogicalGroup(0);
            final SProcessDefinition processDefinition = serviceAccessor.getProcessDefinitionService()
                    .getProcessDefinition(processDefinitionId);
            final SHumanTaskDefinition humanTaskDefinition = (SHumanTaskDefinition) processDefinition
                    .getProcessContainer().getFlowNode(
                            humanTaskInstance.getFlowNodeDefinitionId());
            final long humanTaskInstanceId = humanTaskInstance.getId();
            if (humanTaskDefinition != null) {
                final SUserFilterDefinition sUserFilterDefinition = humanTaskDefinition.getSUserFilterDefinition();
                if (sUserFilterDefinition != null) {
                    cleanPendingMappingsAndUnassignHumanTask(userTaskId, humanTaskInstance);

                    final FilterResult result = executeFilter(processDefinitionId, humanTaskInstanceId,
                            humanTaskDefinition.getActorName(),
                            sUserFilterDefinition);
                    final List<Long> userIds = result.getResult();
                    if (userIds == null || userIds.isEmpty() || userIds.contains(0L) || userIds.contains(-1L)) {
                        throw new UpdateException(
                                "no user id returned by the user filter " + sUserFilterDefinition + " on activity "
                                        + humanTaskDefinition.getName());
                    }
                    createPendingMappingsAndAssignHumanTask(humanTaskInstanceId, result);
                }
            }
            log.info("User '" + getUserNameFromSession() + "' has re-executed assignation on activity "
                    + humanTaskInstanceId +
                    " of process instance " + humanTaskInstance.getLogicalGroup(1) + " of process named '" +
                    processDefinition.getName() + "' in version " + processDefinition.getVersion());
        } catch (final SBonitaException sbe) {
            throw new UpdateException(sbe);
        }
    }

    String getUserNameFromSession() {
        return SessionInfos.getUserNameFromSession();
    }

    private void createPendingMappingsAndAssignHumanTask(final long humanTaskInstanceId, final FilterResult result)
            throws SBonitaException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final List<Long> userIds = result.getResult();
        for (final Long userId : userIds) {
            createPendingMappingForUser(humanTaskInstanceId, userId);
        }
        if (userIds.size() == 1 && result.shouldAutoAssignTaskIfSingleResult()) {
            serviceAccessor.getActivityInstanceService().assignHumanTask(humanTaskInstanceId, userIds.get(0));
        }
    }

    private FilterResult executeFilter(final long processDefinitionId, final long humanTaskInstanceId,
            final String actorName,
            final SUserFilterDefinition sUserFilterDefinition)
            throws SUserFilterExecutionException, SClassLoaderException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final UserFilterService userFilterService = serviceAccessor.getUserFilterService();
        final ClassLoader processClassloader = classLoaderService.getClassLoader(
                identifier(ScopeType.PROCESS, processDefinitionId));
        final SExpressionContext expressionContext = new SExpressionContext(humanTaskInstanceId,
                DataInstanceContainer.ACTIVITY_INSTANCE.name(),
                processDefinitionId);
        return userFilterService.executeFilter(processDefinitionId, sUserFilterDefinition,
                sUserFilterDefinition.getInputs(), processClassloader,
                expressionContext, actorName);
    }

    private void cleanPendingMappingsAndUnassignHumanTask(final long userTaskId,
            final SHumanTaskInstance humanTaskInstance) throws SFlowNodeNotFoundException,
            SFlowNodeReadException, SActivityModificationException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        // release
        if (humanTaskInstance.getAssigneeId() > 0) {
            activityInstanceService.assignHumanTask(userTaskId, 0);
        }
        activityInstanceService.deletePendingMappings(humanTaskInstance.getId());
    }

    private SHumanTaskInstance getSHumanTaskInstance(final long userTaskId)
            throws SFlowNodeNotFoundException, SFlowNodeReadException, UpdateException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SFlowNodeInstance flowNodeInstance = serviceAccessor.getActivityInstanceService()
                .getFlowNodeInstance(userTaskId);
        if (!(flowNodeInstance instanceof SHumanTaskInstance)) {
            throw new UpdateException("The identifier does not refer to a human task");
        }
        return (SHumanTaskInstance) flowNodeInstance;
    }

    private void createPendingMappingForUser(final long humanTaskInstanceId, final Long userId)
            throws SBonitaException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final SPendingActivityMapping mapping = SPendingActivityMapping.builder().activityId(humanTaskInstanceId)
                .userId(userId)
                .build();
        activityInstanceService.addPendingActivityMappings(mapping);
    }

    @Override
    public List<DataDefinition> getActivityDataDefinitions(final long processDefinitionId, final String activityName,
            final int startIndex,
            final int maxResults)
            throws ActivityDefinitionNotFoundException, ProcessDefinitionNotFoundException {
        List<DataDefinition> subDataDefinitionList;
        List<SDataDefinition> sdataDefinitionList = Collections.emptyList();
        final ServiceAccessor serviceAccessor;
        serviceAccessor = getServiceAccessor();

        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        try {
            final SProcessDefinition sProcessDefinition = processDefinitionService
                    .getProcessDefinition(processDefinitionId);
            boolean activityFound = false;
            final SFlowElementContainerDefinition processContainer = sProcessDefinition.getProcessContainer();
            final Set<SActivityDefinition> activityDefList = processContainer.getActivities();
            for (final SActivityDefinition sActivityDefinition : activityDefList) {
                if (activityName.equals(sActivityDefinition.getName())) {
                    sdataDefinitionList = sActivityDefinition.getSDataDefinitions();
                    activityFound = true;
                    break;
                }
            }
            if (!activityFound) {
                throw new ActivityDefinitionNotFoundException(activityName);
            }
            final List<DataDefinition> dataDefinitionList = ModelConvertor.toDataDefinitions(sdataDefinitionList);
            if (startIndex >= dataDefinitionList.size()) {
                return Collections.emptyList();
            }
            final int toIndex = Math.min(dataDefinitionList.size(), startIndex + maxResults);
            subDataDefinitionList = new ArrayList<>(dataDefinitionList.subList(startIndex, toIndex));
        } catch (final SProcessDefinitionNotFoundException e) {
            throw new ProcessDefinitionNotFoundException(e);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
        return subDataDefinitionList;
    }

    @Override
    public List<DataDefinition> getProcessDataDefinitions(final long processDefinitionId, final int startIndex,
            final int maxResults)
            throws ProcessDefinitionNotFoundException {
        List<DataDefinition> subDataDefinitionList;
        final ServiceAccessor serviceAccessor;
        serviceAccessor = getServiceAccessor();

        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        try {
            final SProcessDefinition sProcessDefinition = processDefinitionService
                    .getProcessDefinition(processDefinitionId);
            final SFlowElementContainerDefinition processContainer = sProcessDefinition.getProcessContainer();
            final List<SDataDefinition> sdataDefinitionList = processContainer.getDataDefinitions();
            final List<DataDefinition> dataDefinitionList = ModelConvertor.toDataDefinitions(sdataDefinitionList);
            if (startIndex >= dataDefinitionList.size()) {
                return Collections.emptyList();
            }
            final int toIndex = Math.min(dataDefinitionList.size(), startIndex + maxResults);
            subDataDefinitionList = new ArrayList<>(dataDefinitionList.subList(startIndex, toIndex));
            return subDataDefinitionList;
        } catch (final SProcessDefinitionNotFoundException e) {
            throw new ProcessDefinitionNotFoundException(e);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public HumanTaskInstance getHumanTaskInstance(final long activityInstanceId)
            throws ActivityInstanceNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final GetHumanTaskInstance getHumanTaskInstance = new GetHumanTaskInstance(activityInstanceService,
                activityInstanceId);
        try {
            getHumanTaskInstance.execute();
        } catch (final SActivityInstanceNotFoundException e) {
            throw new ActivityInstanceNotFoundException(activityInstanceId, e);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
        return ModelConvertor.toHumanTaskInstance(getHumanTaskInstance.getResult(), flowNodeStateManager);
    }

    @Override
    public long getNumberOfAssignedHumanTaskInstances(final long userId) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        try {
            return activityInstanceService.getNumberOfAssignedHumanTaskInstances(userId);
        } catch (final SActivityReadException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public Map<Long, Long> getNumberOfOpenTasks(final List<Long> userIds) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();

        try {
            final GetNumberOfOpenTasksForUsers transactionContent = new GetNumberOfOpenTasksForUsers(userIds,
                    activityInstanceService);
            transactionContent.execute();
            return transactionContent.getResult();
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public Map<String, byte[]> getProcessResources(final long processDefinitionId, final String filenamesPattern)
            throws RetrieveException {
        try {
            BusinessArchive businessArchive = getServiceAccessor().getBusinessArchiveService()
                    .export(processDefinitionId);
            return businessArchive.getResources(filenamesPattern);
        } catch (SBonitaException | InvalidBusinessArchiveFormatException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public byte[] getExternalProcessResource(final long processDefinitionId, final String fileName)
            throws RetrieveException, FileNotFoundException {
        SBARResource resource;
        try {
            resource = getServiceAccessor().getProcessResourcesService().get(processDefinitionId,
                    BARResourceType.EXTERNAL, fileName);
        } catch (SBonitaException e) {
            throw new RetrieveException(e);
        }
        if (resource == null) {
            throw new FileNotFoundException("No resource named " + fileName + " in process " + processDefinitionId);
        }
        return resource.getContent();
    }

    @Override
    public long getLatestProcessDefinitionId(final String processName) throws ProcessDefinitionNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();

        final TransactionContentWithResult<Long> transactionContent = new GetLatestProcessDefinitionId(
                processDefinitionService, processName);
        try {
            transactionContent.execute();
        } catch (final SBonitaException e) {
            throw new ProcessDefinitionNotFoundException(e);
        }
        return transactionContent.getResult();
    }

    @Override
    public List<DataInstance> getProcessDataInstances(final long processInstanceId, final int startIndex,
            final int maxResults) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final DataInstanceService dataInstanceService = serviceAccessor.getDataInstanceService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final ParentContainerResolver parentContainerResolver = serviceAccessor.getParentContainerResolver();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final long processDefinitionId = processInstanceService.getProcessInstance(processInstanceId)
                    .getProcessDefinitionId();
            final ClassLoader processClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, processDefinitionId));
            Thread.currentThread().setContextClassLoader(processClassLoader);
            final List<SDataInstance> dataInstances = dataInstanceService.getDataInstances(processInstanceId,
                    DataInstanceContainer.PROCESS_INSTANCE.name(),
                    parentContainerResolver,
                    startIndex, maxResults);
            return convertModelToDataInstances(dataInstances);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Override
    public DataInstance getProcessDataInstance(final String dataName, final long processInstanceId)
            throws DataNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final DataInstanceService dataInstanceService = serviceAccessor.getDataInstanceService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final ParentContainerResolver parentContainerResolver = serviceAccessor.getParentContainerResolver();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final long processDefinitionId = processInstanceService.getProcessInstance(processInstanceId)
                    .getProcessDefinitionId();
            final ClassLoader processClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, processDefinitionId));
            Thread.currentThread().setContextClassLoader(processClassLoader);
            final SDataInstance sDataInstance = dataInstanceService.getDataInstance(dataName, processInstanceId,
                    DataInstanceContainer.PROCESS_INSTANCE.toString(), parentContainerResolver);
            return convertModeltoDataInstance(sDataInstance);
        } catch (final SBonitaException e) {
            throw new DataNotFoundException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Override
    public void updateProcessDataInstance(final String dataName, final long processInstanceId,
            final Serializable dataValue) throws UpdateException {
        updateProcessDataInstances(processInstanceId, singletonMap(dataName, dataValue));
    }

    @Override
    public void updateProcessDataInstances(final long processInstanceId, final Map<String, Serializable> dataNameValues)
            throws UpdateException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final DataInstanceService dataInstanceService = serviceAccessor.getDataInstanceService();
        final ParentContainerResolver parentContainerResolver = serviceAccessor.getParentContainerResolver();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final ClassLoader processClassLoader = getProcessInstanceClassloader(serviceAccessor, processInstanceId);
            Thread.currentThread().setContextClassLoader(processClassLoader);
            final List<String> dataNames = new ArrayList<>(dataNameValues.keySet());
            final List<SDataInstance> sDataInstances = dataInstanceService.getDataInstances(dataNames,
                    processInstanceId,
                    DataInstanceContainer.PROCESS_INSTANCE.toString(), parentContainerResolver);
            updateDataInstances(sDataInstances, dataNameValues, processClassLoader);
        } catch (final SBonitaException | ClassNotFoundException e) {
            throw new UpdateException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    protected void updateDataInstances(final List<SDataInstance> sDataInstances,
            final Map<String, Serializable> dataNameValues, ClassLoader classLoader)
            throws ClassNotFoundException,
            UpdateException, SDataInstanceException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final DataInstanceService dataInstanceService = serviceAccessor.getDataInstanceService();
        for (final SDataInstance sDataInstance : sDataInstances) {
            final Serializable dataValue = dataNameValues.get(sDataInstance.getName());
            updateDataInstance(dataInstanceService, sDataInstance, dataValue, classLoader);
        }
    }

    protected void updateDataInstance(final DataInstanceService dataInstanceService, final SDataInstance sDataInstance,
            final Serializable dataNewValue,
            ClassLoader classLoader)
            throws UpdateException, SDataInstanceException {
        verifyTypeOfNewDataValue(sDataInstance, dataNewValue, classLoader);

        final EntityUpdateDescriptor entityUpdateDescriptor = buildEntityUpdateDescriptorForData(dataNewValue);
        dataInstanceService.updateDataInstance(sDataInstance, entityUpdateDescriptor);
    }

    protected void verifyTypeOfNewDataValue(final SDataInstance sDataInstance, final Serializable dataValue,
            ClassLoader classLoader) throws UpdateException {
        final String dataClassName = sDataInstance.getClassName();
        Class<?> dataClass;
        try {
            dataClass = classLoader.loadClass(dataClassName);
        } catch (final ClassNotFoundException e) {
            throw new UpdateException(e);
        }
        if (!dataClass.isInstance(dataValue)) {
            final UpdateException e = new UpdateException("The type of new value [" + dataValue.getClass().getName()
                    + "] is not compatible with the type of the data.");
            e.setDataName(sDataInstance.getName());
            e.setDataClassName(dataClassName);
            throw e;
        }
    }

    private EntityUpdateDescriptor buildEntityUpdateDescriptorForData(final Serializable dataValue) {
        final EntityUpdateDescriptor entityUpdateDescriptor = new EntityUpdateDescriptor();
        entityUpdateDescriptor.addField("value", dataValue);
        return entityUpdateDescriptor;
    }

    protected ClassLoader getProcessInstanceClassloader(final ServiceAccessor serviceAccessor,
            final long processInstanceId)
            throws SProcessInstanceNotFoundException, SProcessInstanceReadException, SClassLoaderException {
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final long processDefinitionId = processInstanceService.getProcessInstance(processInstanceId)
                .getProcessDefinitionId();
        return classLoaderService.getClassLoader(identifier(ScopeType.PROCESS, processDefinitionId));
    }

    @Override
    public List<DataInstance> getActivityDataInstances(final long activityInstanceId, final int startIndex,
            final int maxResults) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final DataInstanceService dataInstanceService = serviceAccessor.getDataInstanceService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ParentContainerResolver parentContainerResolver = serviceAccessor.getParentContainerResolver();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final int processDefinitionIndex = BuilderFactory.get(SAutomaticTaskInstanceBuilderFactory.class)
                .getProcessDefinitionIndex();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final long parentProcessInstanceId = activityInstanceService.getFlowNodeInstance(activityInstanceId)
                    .getLogicalGroup(processDefinitionIndex);
            final ClassLoader processClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, parentProcessInstanceId));
            Thread.currentThread().setContextClassLoader(processClassLoader);
            final List<SDataInstance> dataInstances = dataInstanceService.getDataInstances(activityInstanceId,
                    DataInstanceContainer.ACTIVITY_INSTANCE.name(),
                    parentContainerResolver,
                    startIndex, maxResults);
            return convertModelToDataInstances(dataInstances);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Override
    public DataInstance getActivityDataInstance(final String dataName, final long activityInstanceId)
            throws DataNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final DataInstanceService dataInstanceService = serviceAccessor.getDataInstanceService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ParentContainerResolver parentContainerResolver = serviceAccessor.getParentContainerResolver();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final int processDefinitionIndex = BuilderFactory.get(SAutomaticTaskInstanceBuilderFactory.class)
                .getProcessDefinitionIndex();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final SFlowNodeInstance flowNodeInstance = activityInstanceService.getFlowNodeInstance(activityInstanceId);
            SDataInstance data;
            final long parentProcessInstanceId = flowNodeInstance.getLogicalGroup(processDefinitionIndex);
            final ClassLoader processClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, parentProcessInstanceId));
            Thread.currentThread().setContextClassLoader(processClassLoader);
            data = dataInstanceService.getDataInstance(dataName, activityInstanceId,
                    DataInstanceContainer.ACTIVITY_INSTANCE.toString(),
                    parentContainerResolver);
            return convertModeltoDataInstance(data);
        } catch (final SBonitaException e) {
            throw new DataNotFoundException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Override
    public void updateActivityDataInstance(final String dataName, final long activityInstanceId,
            final Serializable dataValue) throws UpdateException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final DataInstanceService dataInstanceService = serviceAccessor.getDataInstanceService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ParentContainerResolver parentContainerResolver = serviceAccessor.getParentContainerResolver();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final int processDefinitionIndex = BuilderFactory.get(SAutomaticTaskInstanceBuilderFactory.class)
                .getProcessDefinitionIndex();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final SFlowNodeInstance flowNodeInstance = activityInstanceService.getFlowNodeInstance(activityInstanceId);
            final long parentProcessInstanceId = flowNodeInstance.getLogicalGroup(processDefinitionIndex);
            final ClassLoader processClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, parentProcessInstanceId));
            Thread.currentThread().setContextClassLoader(processClassLoader);
            final SDataInstance sDataInstance = dataInstanceService.getDataInstance(dataName, activityInstanceId,
                    DataInstanceContainer.ACTIVITY_INSTANCE.toString(), parentContainerResolver);
            updateDataInstance(dataInstanceService, sDataInstance, dataValue, processClassLoader);
        } catch (final SBonitaException e) {
            throw new UpdateException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Override
    public DataInstance getActivityTransientDataInstance(final String dataName, final long activityInstanceId)
            throws DataNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final TransientDataService transientDataService = serviceAccessor.getTransientDataService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final int processDefinitionIndex = BuilderFactory.get(SAutomaticTaskInstanceBuilderFactory.class)
                .getProcessDefinitionIndex();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final SFlowNodeInstance flowNodeInstance = activityInstanceService.getFlowNodeInstance(activityInstanceId);
            SDataInstance data;
            final long parentProcessInstanceId = flowNodeInstance.getLogicalGroup(processDefinitionIndex);
            final ClassLoader processClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, parentProcessInstanceId));
            Thread.currentThread().setContextClassLoader(processClassLoader);
            data = transientDataService.getDataInstance(dataName, activityInstanceId,
                    DataInstanceContainer.ACTIVITY_INSTANCE.toString());
            return convertModeltoDataInstance(data);
        } catch (final SBonitaException e) {
            throw new DataNotFoundException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    /**
     * isolate static call for mocking reasons
     */
    protected DataInstance convertModeltoDataInstance(final SDataInstance data) {
        return ModelConvertor.toDataInstance(data);
    }

    @Override
    public List<DataInstance> getActivityTransientDataInstances(final long activityInstanceId, final int startIndex,
            final int maxResults) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final TransientDataService transientDataService = serviceAccessor.getTransientDataService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final int processDefinitionIndex = BuilderFactory.get(SAutomaticTaskInstanceBuilderFactory.class)
                .getProcessDefinitionIndex();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final long parentProcessInstanceId = activityInstanceService.getFlowNodeInstance(activityInstanceId)
                    .getLogicalGroup(processDefinitionIndex);
            final ClassLoader processClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, parentProcessInstanceId));
            Thread.currentThread().setContextClassLoader(processClassLoader);
            final List<SDataInstance> dataInstances = transientDataService.getDataInstances(activityInstanceId,
                    DataInstanceContainer.ACTIVITY_INSTANCE.name(),
                    startIndex, maxResults);
            return convertModelToDataInstances(dataInstances);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    /**
     * isolate static call for mocking reasons
     */
    protected List<DataInstance> convertModelToDataInstances(final List<SDataInstance> dataInstances) {
        return ModelConvertor.toDataInstances(dataInstances);
    }

    @Override
    public void updateActivityTransientDataInstance(final String dataName, final long activityInstanceId,
            final Serializable dataValue) throws UpdateException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final TransientDataService transientDataService = serviceAccessor.getTransientDataService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final int processDefinitionIndex = BuilderFactory.get(SAutomaticTaskInstanceBuilderFactory.class)
                .getProcessDefinitionIndex();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final SFlowNodeInstance flowNodeInstance = activityInstanceService.getFlowNodeInstance(activityInstanceId);
            final long parentProcessInstanceId = flowNodeInstance.getLogicalGroup(processDefinitionIndex);
            final ClassLoader processClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, parentProcessInstanceId));
            Thread.currentThread().setContextClassLoader(processClassLoader);
            updateTransientData(dataName, activityInstanceId, dataValue, transientDataService, processClassLoader);
        } catch (final SBonitaException e) {
            throw new UpdateException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }

    }

    protected void updateTransientData(final String dataName, final long activityInstanceId,
            final Serializable dataValue,
            final TransientDataService transientDataInstanceService, ClassLoader classLoader)
            throws SDataInstanceException, UpdateException {
        final SDataInstance sDataInstance = transientDataInstanceService.getDataInstance(dataName, activityInstanceId,
                DataInstanceContainer.ACTIVITY_INSTANCE.toString());
        verifyTypeOfNewDataValue(sDataInstance, dataValue, classLoader);
        final EntityUpdateDescriptor entityUpdateDescriptor = buildEntityUpdateDescriptorForData(dataValue);
        transientDataInstanceService.updateDataInstance(sDataInstance, entityUpdateDescriptor);
    }

    @Override
    public void importActorMapping(final long processDefinitionId, final String xmlContent)
            throws ActorMappingImportException {
        if (xmlContent != null) {
            final ServiceAccessor serviceAccessor = getServiceAccessor();
            final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
            final IdentityService identityService = serviceAccessor.getIdentityService();
            try {
                new ImportActorMapping(actorMappingService, identityService).importActorMappingFromXml(xmlContent,
                        processDefinitionId);
                serviceAccessor.getBusinessArchiveArtifactsManager().resolveDependencies(processDefinitionId,
                        serviceAccessor);
            } catch (final SBonitaException sbe) {
                throw new ActorMappingImportException(sbe);
            }
        }
    }

    @Override
    public String exportActorMapping(final long processDefinitionId) throws ActorMappingExportException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
        final IdentityService identityService = serviceAccessor.getIdentityService();
        try {
            final ExportActorMapping exportActorMapping = new ExportActorMapping(actorMappingService, identityService,
                    processDefinitionId);
            exportActorMapping.execute();
            return exportActorMapping.getResult();
        } catch (final SBonitaException sbe) {
            throw new ActorMappingExportException(sbe);
        }
    }

    @Override
    public boolean isInvolvedInProcessInstance(final long userId, final long processInstanceId)
            throws ProcessInstanceNotFoundException {
        return processInvolvementDelegate.isInvolvedInProcessInstance(userId, processInstanceId);
    }

    public boolean isInvolvedInHumanTaskInstance(long userId, long humanTaskInstanceId)
            throws ActivityInstanceNotFoundException {
        return taskInvolvementDelegate.isInvolvedInHumanTaskInstance(userId, humanTaskInstanceId);
    }

    @Override
    public boolean isManagerOfUserInvolvedInProcessInstance(final long managerUserId, final long processInstanceId)
            throws BonitaException {
        return processInvolvementDelegate.isManagerOfUserInvolvedInProcessInstance(managerUserId, processInstanceId);
    }

    @Override
    public long getProcessInstanceIdFromActivityInstanceId(final long activityInstanceId)
            throws ProcessInstanceNotFoundException {
        try {
            final SActivityInstance sActivityInstance = getSActivityInstance(activityInstanceId);
            return sActivityInstance.getRootContainerId();
        } catch (final SActivityInstanceNotFoundException e) {
            logInstanceNotFound(e);
            throw new ProcessInstanceNotFoundException(e);
        } catch (final SBonitaException e) {
            logError(e);
            throw new ProcessInstanceNotFoundException(e);
        }
    }

    @Override
    public long getProcessDefinitionIdFromActivityInstanceId(final long activityInstanceId)
            throws ProcessDefinitionNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        try {
            final SActivityInstance sActivityInstance = getSActivityInstance(activityInstanceId);
            return processInstanceService.getProcessInstance(sActivityInstance.getParentProcessInstanceId())
                    .getProcessDefinitionId();
        } catch (final SBonitaException e) {
            throw new ProcessDefinitionNotFoundException(e);
        }
    }

    @Override
    public long getProcessDefinitionIdFromProcessInstanceId(final long processInstanceId)
            throws ProcessDefinitionNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        try {
            final SProcessInstance sProcessInstance = getSProcessInstance(processInstanceId);
            final ProcessInstance processInstance = ModelConvertor
                    .toProcessInstances(Collections.singletonList(sProcessInstance),
                            processDefinitionService)
                    .get(0);
            return processInstance.getProcessDefinitionId();
        } catch (final SProcessInstanceNotFoundException e) {
            logInstanceNotFound(e);
            throw new ProcessDefinitionNotFoundException(e);
        } catch (final SBonitaException e) {
            logError(e);
            throw new ProcessDefinitionNotFoundException(e);
        }
    }

    @Override
    public Date getActivityReachedStateDate(final long activityInstanceId, final String stateName) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();

        final int stateId = ModelConvertor.getServerActivityStateId(stateName);
        final GetArchivedActivityInstance getArchivedActivityInstance = new GetArchivedActivityInstance(
                activityInstanceId, stateId, activityInstanceService);
        try {
            getArchivedActivityInstance.execute();
            final long reachedDate = getArchivedActivityInstance.getResult().getReachedStateDate();
            return new Date(reachedDate);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public Set<String> getSupportedStates(final FlowNodeType nodeType) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        return flowNodeStateManager.getSupportedState(SFlowNodeType.valueOf(nodeType.toString()));
    }

    @Override
    public void updateActivityInstanceVariables(final long activityInstanceId,
            final Map<String, Serializable> variables) throws UpdateException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final DataInstanceService dataInstanceService = serviceAccessor.getDataInstanceService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ParentContainerResolver parentContainerResolver = serviceAccessor.getParentContainerResolver();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final int processDefinitionIndex = BuilderFactory.get(SAutomaticTaskInstanceBuilderFactory.class)
                .getProcessDefinitionIndex();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final long parentProcessInstanceId = activityInstanceService.getFlowNodeInstance(activityInstanceId)
                    .getLogicalGroup(processDefinitionIndex);
            final ClassLoader processClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, parentProcessInstanceId));
            Thread.currentThread().setContextClassLoader(processClassLoader);
            final List<SDataInstance> dataInstances = dataInstanceService.getDataInstances(
                    new ArrayList<>(variables.keySet()), activityInstanceId,
                    DataInstanceContainer.ACTIVITY_INSTANCE.toString(), parentContainerResolver);
            if (dataInstances.size() < variables.size()) {
                throw new UpdateException("Some data does not exists, wanted to update " + variables.keySet()
                        + " but there is only " + dataInstances);
            }
            for (final SDataInstance dataInstance : dataInstances) {
                final Serializable newValue = variables.get(dataInstance.getName());
                final EntityUpdateDescriptor entityUpdateDescriptor = buildEntityUpdateDescriptorForData(newValue);
                dataInstanceService.updateDataInstance(dataInstance, entityUpdateDescriptor);
            }
        } catch (final SBonitaException e) {
            throw new UpdateException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Override
    public void updateActivityInstanceVariables(final List<Operation> operations, final long activityInstanceId,
            final Map<String, Serializable> expressionContexts) throws UpdateException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final OperationService operationService = serviceAccessor.getOperationService();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final SActivityInstance activityInstance = activityInstanceService.getActivityInstance(activityInstanceId);
            final ClassLoader processClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, activityInstance.getProcessDefinitionId()));
            Thread.currentThread().setContextClassLoader(processClassLoader);
            final List<SOperation> sOperations = convertOperations(operations);
            final SExpressionContext sExpressionContext = new SExpressionContext(activityInstanceId,
                    DataInstanceContainer.ACTIVITY_INSTANCE.toString(),
                    activityInstance.getProcessDefinitionId());
            sExpressionContext.setSerializableInputValues(expressionContexts);

            operationService.execute(sOperations, sExpressionContext);
        } catch (final SBonitaException e) {
            throw new UpdateException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    protected List<SOperation> convertOperations(final List<Operation> operations) {
        return ModelConvertor.convertOperations(operations);
    }

    @Override
    public long getOneAssignedUserTaskInstanceOfProcessInstance(final long processInstanceId, final long userId)
            throws RetrieveException {
        // FIXME: write specific query that should be more efficient:
        final int assignedUserTaskInstanceNumber = (int) getNumberOfAssignedHumanTaskInstances(userId);
        final List<HumanTaskInstance> userTaskInstances = getAssignedHumanTaskInstances(userId, 0,
                assignedUserTaskInstanceNumber,
                ActivityInstanceCriterion.DEFAULT);
        for (final HumanTaskInstance userTaskInstance : userTaskInstances) {
            final String stateName = userTaskInstance.getState();
            final long userTaskInstanceId = userTaskInstance.getId();
            if (stateName.equals(ActivityStates.READY_STATE)
                    && userTaskInstance.getParentContainerId() == processInstanceId) {
                return userTaskInstanceId;
            }
        }
        return -1;
    }

    @Override
    public long getOneAssignedUserTaskInstanceOfProcessDefinition(final long processDefinitionId, final long userId) {
        final int assignedUserTaskInstanceNumber = (int) getNumberOfAssignedHumanTaskInstances(userId);
        final List<HumanTaskInstance> userTaskInstances = getAssignedHumanTaskInstances(userId, 0,
                assignedUserTaskInstanceNumber,
                ActivityInstanceCriterion.DEFAULT);
        if (!userTaskInstances.isEmpty()) {
            for (final HumanTaskInstance userTaskInstance : userTaskInstances) {
                final String stateName = userTaskInstance.getState();
                try {
                    final SProcessInstance sProcessInstance = getSProcessInstance(
                            userTaskInstance.getRootContainerId());
                    if (stateName.equals(ActivityStates.READY_STATE)
                            && sProcessInstance.getProcessDefinitionId() == processDefinitionId) {
                        return userTaskInstance.getId();
                    }
                } catch (final SBonitaException e) {
                    throw new RetrieveException(e);
                }
            }
        }
        return -1;
    }

    @Override
    public String getActivityInstanceState(final long activityInstanceId) throws ActivityInstanceNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        try {
            final SActivityInstance sActivityInstance = getSActivityInstance(activityInstanceId);
            final ActivityInstance activityInstance = ModelConvertor.toActivityInstance(sActivityInstance,
                    flowNodeStateManager);
            return activityInstance.getState();
        } catch (final SActivityInstanceNotFoundException e) {
            logInstanceNotFound(e);
            throw new ActivityInstanceNotFoundException(activityInstanceId);
        } catch (final SBonitaException e) {
            logError(e);
            throw new ActivityInstanceNotFoundException(e);
        }
    }

    @Override
    public boolean canExecuteTask(final long activityInstanceId, final long userId)
            throws ActivityInstanceNotFoundException, RetrieveException {
        final HumanTaskInstance userTaskInstance = getHumanTaskInstance(activityInstanceId);
        return userTaskInstance.getState().equalsIgnoreCase(ActivityStates.READY_STATE)
                && userTaskInstance.getAssigneeId() == userId;
    }

    @Override
    public long getProcessDefinitionId(final String name, final String version)
            throws ProcessDefinitionNotFoundException {
        return processDeploymentAPIDelegate.getProcessDefinitionId(name, version);
    }

    @Override
    public void releaseUserTask(final long userTaskId) throws ActivityInstanceNotFoundException, UpdateException {
        final ServiceAccessor serviceAccessor;
        serviceAccessor = getServiceAccessor();

        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        try {
            final AssignOrUnassignUserTask assignUserTask = new AssignOrUnassignUserTask(0, userTaskId,
                    activityInstanceService, serviceAccessor
                            .getFlowNodeStateManager().getStateBehaviors());
            assignUserTask.execute();
        } catch (final SUnreleasableTaskException e) {
            throw new UpdateException(e);
        } catch (final SActivityInstanceNotFoundException e) {
            throw new ActivityInstanceNotFoundException(e);
        } catch (final SBonitaException e) {
            logError(e);
            throw new UpdateException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Deprecated(since = "9.0.0")
    public void updateProcessDeploymentInfo(final long processDefinitionId,
            final ProcessDeploymentInfoUpdater processDeploymentInfoUpdater)
            throws ProcessDefinitionNotFoundException, UpdateException {
        if (processDeploymentInfoUpdater == null || processDeploymentInfoUpdater.getFields().isEmpty()) {
            throw new UpdateException("The update descriptor does not contain field updates");
        }
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();

        final UpdateProcessDeploymentInfo updateProcessDeploymentInfo = new UpdateProcessDeploymentInfo(
                processDefinitionService, processDefinitionId,
                processDeploymentInfoUpdater);
        try {
            updateProcessDeploymentInfo.execute();
        } catch (final SProcessDefinitionNotFoundException e) {
            throw new ProcessDefinitionNotFoundException(e);
        } catch (final SBonitaException sbe) {
            throw new UpdateException(sbe);
        }
    }

    @Override
    public List<ProcessDeploymentInfo> getStartableProcessDeploymentInfosForActors(final Set<Long> actorIds,
            final int startIndex, final int maxResults,
            final ProcessDeploymentInfoCriterion sortingCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        try {
            final Set<Long> processDefIds = getIdOfStartableProcessDeploymentInfosForActors(actorIds);

            String field = null;
            OrderByType order = null;
            switch (sortingCriterion) {
                case DEFAULT:
                    break;
                case NAME_ASC:
                    field = SProcessDefinitionDeployInfo.NAME_KEY;
                    order = OrderByType.ASC;
                    break;
                case NAME_DESC:
                    field = SProcessDefinitionDeployInfo.NAME_KEY;
                    order = OrderByType.DESC;
                    break;
                case ACTIVATION_STATE_ASC:
                    field = SProcessDefinitionDeployInfo.ACTIVATION_STATE_KEY;
                    order = OrderByType.ASC;
                    break;
                case ACTIVATION_STATE_DESC:
                    field = SProcessDefinitionDeployInfo.ACTIVATION_STATE_KEY;
                    order = OrderByType.DESC;
                    break;
                case CONFIGURATION_STATE_ASC:
                    field = SProcessDefinitionDeployInfo.CONFIGURATION_STATE_KEY;
                    order = OrderByType.ASC;
                    break;
                case CONFIGURATION_STATE_DESC:
                    field = SProcessDefinitionDeployInfo.CONFIGURATION_STATE_KEY;
                    order = OrderByType.DESC;
                    break;
                case VERSION_ASC:
                    field = SProcessDefinitionDeployInfo.VERSION_KEY;
                    order = OrderByType.ASC;
                    break;
                case VERSION_DESC:
                    field = SProcessDefinitionDeployInfo.VERSION_KEY;
                    order = OrderByType.DESC;
                    break;
                default:
                    break;
            }

            final List<SProcessDefinitionDeployInfo> processDefinitionDeployInfos = processDefinitionService
                    .getProcessDeploymentInfos(new ArrayList<>(
                            processDefIds), startIndex, maxResults, field, order);
            return ModelConvertor.toProcessDeploymentInfo(processDefinitionDeployInfos);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    private Set<Long> getIdOfStartableProcessDeploymentInfosForActors(final Set<Long> actorIds)
            throws SActorNotFoundException, SBonitaReadException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();

        final List<SActor> actors = actorMappingService.getActors(new ArrayList<>(actorIds));
        final Set<Long> processDefIds = new HashSet<>(actors.size());
        for (final SActor sActor : actors) {
            if (sActor.isInitiator()) {
                processDefIds.add(sActor.getScopeId());
            }
        }
        return processDefIds;
    }

    @Override
    public boolean isAllowedToStartProcess(final long processDefinitionId, final Set<Long> actorIds) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();

        final GetActorsByActorIds getActorsByActorIds = new GetActorsByActorIds(actorMappingService,
                new ArrayList<>(actorIds));
        try {
            getActorsByActorIds.execute();
            final List<SActor> actors = getActorsByActorIds.getResult();
            boolean isAllowedToStartProcess = true;
            final Iterator<SActor> iterator = actors.iterator();
            while (isAllowedToStartProcess && iterator.hasNext()) {
                final SActor actor = iterator.next();
                if (actor.getScopeId() != processDefinitionId || !actor.isInitiator()) {
                    isAllowedToStartProcess = false;
                }
            }
            return isAllowedToStartProcess;
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public ActorInstance getActorInitiator(final long processDefinitionId)
            throws ActorNotFoundException, ProcessDefinitionNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
        ActorInstance actorInstance;
        try {
            final SProcessDefinition definition = processDefinitionService.getProcessDefinition(processDefinitionId);
            final SActorDefinition sActorDefinition = definition.getActorInitiator();
            if (sActorDefinition == null) {
                throw new ActorNotFoundException("No actor initiator defined on the process");
            }
            final String name = sActorDefinition.getName();
            final SActor sActor = actorMappingService.getActor(name, processDefinitionId);
            actorInstance = ModelConvertor.toActorInstance(sActor);
        } catch (final SProcessDefinitionNotFoundException e) {
            // no rollback need, we only read
            throw new ProcessDefinitionNotFoundException(e);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
        return actorInstance;
    }

    @Override
    public int getNumberOfActivityDataDefinitions(final long processDefinitionId, final String activityName)
            throws ProcessDefinitionNotFoundException,
            ActivityDefinitionNotFoundException {
        List<SDataDefinition> sdataDefinitionList = Collections.emptyList();
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        try {
            final SProcessDefinition sProcessDefinition = processDefinitionService
                    .getProcessDefinition(processDefinitionId);
            boolean found = false;
            final SFlowElementContainerDefinition processContainer = sProcessDefinition.getProcessContainer();
            final Set<SActivityDefinition> activityDefList = processContainer.getActivities();
            for (final SActivityDefinition sActivityDefinition : activityDefList) {
                if (activityName.equals(sActivityDefinition.getName())) {
                    sdataDefinitionList = sActivityDefinition.getSDataDefinitions();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new ActivityDefinitionNotFoundException(activityName);
            }
            return sdataDefinitionList.size();

        } catch (final SProcessDefinitionNotFoundException e) {
            throw new ProcessDefinitionNotFoundException(e);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public int getNumberOfProcessDataDefinitions(final long processDefinitionId)
            throws ProcessDefinitionNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        try {
            final SProcessDefinition sProcessDefinition = processDefinitionService
                    .getProcessDefinition(processDefinitionId);
            final SFlowElementContainerDefinition processContainer = sProcessDefinition.getProcessContainer();
            return processContainer.getDataDefinitions().size();
        } catch (final SProcessDefinitionNotFoundException e) {
            throw new ProcessDefinitionNotFoundException(e);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public ProcessInstance startProcess(final long processDefinitionId,
            final Map<String, Serializable> initialVariables)
            throws ProcessDefinitionNotFoundException, ProcessActivationException, ProcessExecutionException {
        final List<Operation> operations = createSetDataOperation(processDefinitionId, initialVariables);
        return startProcess(processDefinitionId, operations, initialVariables);
    }

    @Override
    public ProcessInstance startProcessWithInputs(final long processDefinitionId,
            final Map<String, Serializable> instantiationInputs)
            throws ProcessDefinitionNotFoundException, ProcessActivationException, ProcessExecutionException,
            ContractViolationException {
        return startProcessWithInputs(0, processDefinitionId, instantiationInputs);
    }

    @Override
    public ProcessInstance startProcessWithInputs(final long userId, final long processDefinitionId,
            final Map<String, Serializable> instantiationInputs)
            throws ProcessDefinitionNotFoundException, ProcessActivationException, ProcessExecutionException,
            ContractViolationException {
        return new ProcessStarter(userId, processDefinitionId, instantiationInputs).start();
    }

    @Override
    public ProcessInstance startProcess(final long userId, final long processDefinitionId,
            final Map<String, Serializable> initialVariables)
            throws ProcessDefinitionNotFoundException, ProcessActivationException, ProcessExecutionException {
        final List<Operation> operations = createSetDataOperation(processDefinitionId, initialVariables);
        return startProcess(userId, processDefinitionId, operations, initialVariables);
    }

    protected List<Operation> createSetDataOperation(final long processDefinitionId,
            final Map<String, Serializable> initialVariables)
            throws ProcessExecutionException {
        final ClassLoaderService classLoaderService = getServiceAccessor().getClassLoaderService();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        final List<Operation> operations = new ArrayList<>();
        try {
            final ClassLoader localClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, processDefinitionId));
            Thread.currentThread().setContextClassLoader(localClassLoader);
            if (initialVariables != null) {
                for (final Entry<String, Serializable> initialVariable : initialVariables.entrySet()) {
                    final String name = initialVariable.getKey();
                    final Serializable value = initialVariable.getValue();
                    final Expression expression = new ExpressionBuilder().createExpression(name, name,
                            value.getClass().getName(), ExpressionType.TYPE_INPUT);
                    final Operation operation = new OperationBuilder().createSetDataOperation(name, expression);
                    operations.add(operation);
                }
            }
        } catch (final SClassLoaderException | InvalidExpressionException cle) {
            throw new ProcessExecutionException(cle);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
        return operations;
    }

    @Override
    public ProcessInstance startProcess(final long processDefinitionId, final List<Operation> operations,
            final Map<String, Serializable> context)
            throws ProcessExecutionException, ProcessDefinitionNotFoundException, ProcessActivationException {
        try {
            return startProcess(0, processDefinitionId, operations, context);
        } catch (final RetrieveException e) {
            throw new ProcessExecutionException(e);
        }
    }

    @Override
    public ProcessInstance startProcess(final long userId, final long processDefinitionId,
            final List<Operation> operations,
            final Map<String, Serializable> context)
            throws ProcessDefinitionNotFoundException, ProcessActivationException, ProcessExecutionException {
        final ProcessStarter starter = new ProcessStarter(userId, processDefinitionId, operations, context);
        try {
            return starter.start();
        } catch (ContractViolationException e) {
            // To not have an API break, we need to wrapped this new Exception:
            throw new ProcessExecutionException(e);
        }
    }

    @Override
    public long getNumberOfActivityDataInstances(final long activityInstanceId)
            throws ActivityInstanceNotFoundException {
        try {
            return getNumberOfDataInstancesOfContainer(activityInstanceId, DataInstanceContainer.ACTIVITY_INSTANCE);
        } catch (final SBonitaException e) {
            throw new ActivityInstanceNotFoundException(activityInstanceId);
        }
    }

    private long getNumberOfDataInstancesOfContainer(final long instanceId, final DataInstanceContainer containerType)
            throws SBonitaException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ParentContainerResolver parentContainerResolver = serviceAccessor.getParentContainerResolver();
        final DataInstanceService dataInstanceService = serviceAccessor.getDataInstanceService();
        return dataInstanceService.getNumberOfDataInstances(instanceId, containerType.name(), parentContainerResolver);
    }

    @Override
    public long getNumberOfProcessDataInstances(final long processInstanceId) throws ProcessInstanceNotFoundException {
        try {
            return getNumberOfDataInstancesOfContainer(processInstanceId, DataInstanceContainer.PROCESS_INSTANCE);
        } catch (final SBonitaException e) {
            throw new ProcessInstanceNotFoundException(e);
        }
    }

    protected Map<String, Serializable> executeOperations(final ConnectorResult connectorResult,
            final List<Operation> operations,
            final Map<String, Serializable> operationInputValues, final SExpressionContext expressionContext,
            final ClassLoader classLoader,
            final ServiceAccessor serviceAccessor) throws SBonitaException {
        final ConnectorService connectorService = serviceAccessor.getConnectorService();
        final OperationService operationService = serviceAccessor.getOperationService();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(classLoader);
            final Map<String, Serializable> externalDataValue = new HashMap<>(operations.size());
            // convert the client operation to server operation
            final List<SOperation> sOperations = convertOperations(operations);
            // set input values of expression with connector result + provided input for this operation
            final HashMap<String, Object> inputValues = new HashMap<>(operationInputValues);
            inputValues.putAll(connectorResult.getResult());
            expressionContext.setInputValues(inputValues);
            // execute
            final Long containerId = expressionContext.getContainerId();
            operationService.execute(sOperations, containerId == null ? -1 : containerId,
                    expressionContext.getContainerType(), expressionContext);
            // return the value of the data if it's an external data
            for (final Operation operation : operations) {
                final LeftOperand leftOperand = operation.getLeftOperand();
                if (LeftOperand.TYPE_EXTERNAL_DATA.equals(leftOperand.getType())) {
                    externalDataValue.put(leftOperand.getName(),
                            (Serializable) expressionContext.getInputValues().get(leftOperand.getName()));
                }
            }
            return externalDataValue;
        } finally {
            // we finally disconnect the connector
            connectorService.disconnect(connectorResult);
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    private Map<String, Serializable> toSerializableMap(final Map<String, Object> map,
            final String connectorDefinitionId,
            final String connectorDefinitionVersion) throws NotSerializableException {
        final HashMap<String, Serializable> resMap = new HashMap<>(map.size());
        for (final Entry<String, Object> entry : map.entrySet()) {
            try {
                resMap.put(entry.getKey(), (Serializable) entry.getValue());
            } catch (final ClassCastException e) {
                throw new NotSerializableException(connectorDefinitionId, connectorDefinitionVersion, entry.getKey(),
                        entry.getValue());
            }
        }
        return resMap;
    }

    @Override
    public Map<String, Serializable> executeConnectorOnProcessDefinition(final String connectorDefinitionId,
            final String connectorDefinitionVersion,
            final Map<String, Expression> connectorInputParameters,
            final Map<String, Map<String, Serializable>> inputValues, final long processDefinitionId)
            throws ConnectorExecutionException {
        return executeConnectorOnProcessDefinitionWithOrWithoutOperations(connectorDefinitionId,
                connectorDefinitionVersion, connectorInputParameters,
                inputValues, null, null, processDefinitionId);
    }

    @Override
    public Map<String, Serializable> executeConnectorOnProcessDefinition(final String connectorDefinitionId,
            final String connectorDefinitionVersion,
            final Map<String, Expression> connectorInputParameters,
            final Map<String, Map<String, Serializable>> inputValues, final List<Operation> operations,
            final Map<String, Serializable> operationInputValues, final long processDefinitionId)
            throws ConnectorExecutionException {
        return executeConnectorOnProcessDefinitionWithOrWithoutOperations(connectorDefinitionId,
                connectorDefinitionVersion, connectorInputParameters,
                inputValues, operations, operationInputValues, processDefinitionId);
    }

    /**
     * execute the connector and return connector output if there is no operation or operation output if there is
     * operation
     */
    private Map<String, Serializable> executeConnectorOnProcessDefinitionWithOrWithoutOperations(
            final String connectorDefinitionId,
            final String connectorDefinitionVersion, final Map<String, Expression> connectorInputParameters,
            final Map<String, Map<String, Serializable>> inputValues, final List<Operation> operations,
            final Map<String, Serializable> operationInputValues,
            final long processDefinitionId) throws ConnectorExecutionException {
        checkConnectorParameters(connectorInputParameters, inputValues);
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final ConnectorService connectorService = serviceAccessor.getConnectorService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();

        try {
            final ClassLoader classLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, processDefinitionId));

            final Map<String, SExpression> connectorsExps = ModelConvertor
                    .constructExpressions(connectorInputParameters);
            final SExpressionContext expcontext = new SExpressionContext();
            expcontext.setProcessDefinitionId(processDefinitionId);
            expcontext.setContainerState(ContainerState.ACTIVE);
            final SProcessDefinition processDef = processDefinitionService.getProcessDefinition(processDefinitionId);
            if (processDef != null) {
                expcontext.setProcessDefinition(processDef);
            }
            final ConnectorResult connectorResult = connectorService.executeMultipleEvaluation(processDefinitionId,
                    connectorDefinitionId,
                    connectorDefinitionVersion, connectorsExps, inputValues, classLoader, expcontext);
            if (operations != null) {
                // execute operations
                return executeOperations(connectorResult, operations, operationInputValues, expcontext, classLoader,
                        serviceAccessor);
            }
            return getSerializableResultOfConnector(connectorDefinitionVersion, connectorResult, connectorService);
        } catch (final SBonitaException | NotSerializableException e) {
            throw new ConnectorExecutionException(e);
        }
    }

    protected Map<String, Serializable> getSerializableResultOfConnector(final String connectorDefinitionVersion,
            final ConnectorResult connectorResult,
            final ConnectorService connectorService) throws NotSerializableException, SConnectorException {
        connectorService.disconnect(connectorResult);
        return toSerializableMap(connectorResult.getResult(), connectorDefinitionVersion, connectorDefinitionVersion);
    }

    protected void checkConnectorParameters(final Map<String, Expression> connectorInputParameters,
            final Map<String, Map<String, Serializable>> inputValues)
            throws ConnectorExecutionException {
        if (connectorInputParameters.size() != inputValues.size()) {
            throw new ConnectorExecutionException(
                    "The number of input parameters is not consistent with the number of input values. Input parameters: "
                            + connectorInputParameters.size() + ", number of input values: " + inputValues.size());
        }
    }

    @Override
    public void setActivityStateByName(final long activityInstanceId, final String state) throws UpdateException {
        setActivityStateById(activityInstanceId, ModelConvertor.getServerActivityStateId(state));
    }

    @Override
    public void setActivityStateById(final long activityInstanceId, final int stateId) throws UpdateException {
        try {
            ActivityInstanceService activityInstanceService = getServiceAccessor().getActivityInstanceService();
            SFlowNodeInstance flowNodeInstance = activityInstanceService.getFlowNodeInstance(activityInstanceId);
            FlowNodeState state = getServiceAccessor().getFlowNodeStateManager().getState(stateId);
            log.warn(String.format(
                    "setActivityStateById was called: <%s> is forcing state of the flow node with name = <%s> id = <%d> and type <%s> in current state <%s: %s> to the state <%s: %s>. "
                            +
                            "Setting the state of a flow node through the API is not recommended and can affect the normal behavior of the platform. "
                            +
                            "If that flow node was already scheduled for execution, there is no guarantee on how that flow node will behave anymore.",
                    getLoggedUsername(), flowNodeInstance.getName(), flowNodeInstance.getId(),
                    flowNodeInstance.getType().name(),
                    flowNodeInstance.getStateId(), flowNodeInstance.getStateName(),
                    stateId, state.getName()));
            getServiceAccessor().getFlowNodeExecutor().setStateByStateId(activityInstanceId, stateId);
        } catch (final SBonitaException e) {
            throw new UpdateException(e);
        }
    }

    private String getLoggedUsername() {
        SSession session = getSession();
        if (session == null || session.getUserId() <= 0) {
            return "SYSTEM";
        }
        long userId = session.getUserId();
        try {
            return getServiceAccessor().getIdentityService().getUser(userId).getUserName();
        } catch (Exception e) {
            log.warn("Unable to retrieve user with id " + userId
                    + " exception: " + e.getClass().getName() + " " + e.getMessage());
        }
        return String.valueOf(userId);
    }

    @Override
    public void setTaskPriority(final long humanTaskInstanceId, final TaskPriority priority) throws UpdateException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();

        try {
            final SetTaskPriority transactionContent = new SetTaskPriority(activityInstanceService, humanTaskInstanceId,
                    STaskPriority.valueOf(priority.name()));
            transactionContent.execute();
        } catch (final SBonitaException e) {
            throw new UpdateException(e);
        }
    }

    private void deleteProcessInstanceInTransaction(final ServiceAccessor serviceAccessor,
            final long processInstanceId) throws SBonitaException {
        final UserTransactionService userTransactionService = serviceAccessor.getUserTransactionService();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();

        try {
            userTransactionService.executeInTransaction((Callable<Void>) () -> {
                final SProcessInstance sProcessInstance = processInstanceService
                        .getProcessInstance(processInstanceId);

                deleteJobsOnProcessInstance(sProcessInstance);
                processInstanceService.deleteParentProcessInstanceAndElements(sProcessInstance);
                return null;
            });
        } catch (final SBonitaException e) {
            throw e;
        } catch (final Exception e) {
            throw new SBonitaRuntimeException("Error while deleting the parent process instance and elements.", e);
        }
    }

    @Override
    @CustomTransactions
    public long deleteProcessInstances(final long processDefinitionId, final int startIndex, final int maxResults)
            throws DeletionException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final UserTransactionService userTxService = serviceAccessor.getUserTransactionService();
        try {
            final Map<SProcessInstance, List<Long>> processInstancesWithChildrenIds = userTxService
                    .executeInTransaction(() -> {
                        final List<SProcessInstance> sProcessInstances1 = searchProcessInstancesFromProcessDefinition(
                                processInstanceService,
                                processDefinitionId, startIndex, maxResults);
                        final Map<SProcessInstance, List<Long>> sProcessInstanceListHashMap = new LinkedHashMap<>(
                                sProcessInstances1.size());
                        for (final SProcessInstance rootProcessInstance : sProcessInstances1) {
                            List<Long> tmpList;
                            final List<Long> childrenProcessInstanceIds = new ArrayList<>();
                            int fromIndex = 0;
                            do {
                                // from index always will be zero because elements will be deleted
                                tmpList = processInstanceService
                                        .getArchivedChildrenSourceObjectIdsFromRootProcessInstance(
                                                rootProcessInstance.getId(),
                                                fromIndex, BATCH_SIZE, OrderByType.ASC);
                                childrenProcessInstanceIds.addAll(tmpList);
                                fromIndex += BATCH_SIZE;
                            } while (tmpList.size() == BATCH_SIZE);
                            sProcessInstanceListHashMap.put(rootProcessInstance, childrenProcessInstanceIds);
                        }
                        return sProcessInstanceListHashMap;
                    });

            if (processInstancesWithChildrenIds.isEmpty()) {
                return 0;
            }

            final LockService lockService = serviceAccessor.getLockService();
            final String objectType = SFlowElementsContainerType.PROCESS.name();
            List<BonitaLock> locks = null;
            try {
                locks = createLockProcessInstances(lockService, objectType, processInstancesWithChildrenIds,
                        serviceAccessor.getTenantId());
                return userTxService.executeInTransaction(() -> {
                    final List<SProcessInstance> sProcessInstances = new ArrayList<>(
                            processInstancesWithChildrenIds.keySet());
                    deleteJobsOnProcessInstance(processDefinitionId, sProcessInstances);
                    return processInstanceService.deleteParentProcessInstanceAndElements(sProcessInstances);
                });
            } finally {
                releaseLocks(lockService, locks, serviceAccessor.getTenantId());
            }

        } catch (final SProcessInstanceHierarchicalDeletionException e) {
            throw new ProcessInstanceHierarchicalDeletionException(e.getMessage(), e.getProcessInstanceId());
        } catch (final Exception e) {
            throw new DeletionException(e);
        }
    }

    private void deleteJobsOnEventSubProcess(final SProcessDefinition processDefinition,
            final SProcessInstance sProcessInstance) {
        final Set<SSubProcessDefinition> sSubProcessDefinitions = processDefinition.getProcessContainer()
                .getSubProcessDefinitions();
        for (final SSubProcessDefinition sSubProcessDefinition : sSubProcessDefinitions) {
            final List<SStartEventDefinition> startEventsOfSubProcess = sSubProcessDefinition.getSubProcessContainer()
                    .getStartEvents();
            deleteJobsOnEventSubProcess(processDefinition, sProcessInstance, sSubProcessDefinition,
                    startEventsOfSubProcess);

            final List<SIntermediateCatchEventDefinition> intermediateCatchEvents = sSubProcessDefinition
                    .getSubProcessContainer().getIntermediateCatchEvents();
            deleteJobsOnEventSubProcess(processDefinition, sProcessInstance, sSubProcessDefinition,
                    intermediateCatchEvents);

            final List<SBoundaryEventDefinition> sBoundaryEventDefinitions = sSubProcessDefinition
                    .getSubProcessContainer().getBoundaryEvents();
            deleteJobsOnEventSubProcess(processDefinition, sProcessInstance, sSubProcessDefinition,
                    sBoundaryEventDefinitions);
        }
    }

    private void deleteJobsOnEventSubProcess(final SProcessDefinition processDefinition,
            final SProcessInstance sProcessInstance,
            final SSubProcessDefinition sSubProcessDefinition,
            final List<? extends SCatchEventDefinition> sCatchEventDefinitions) {
        final SchedulerService schedulerService = getServiceAccessor().getSchedulerService();

        for (final SCatchEventDefinition sCatchEventDefinition : sCatchEventDefinitions) {
            try {
                if (!sCatchEventDefinition.getTimerEventTriggerDefinitions().isEmpty()) {
                    final String jobName = JobNameBuilder.getTimerEventJobName(processDefinition.getId(),
                            sCatchEventDefinition, sProcessInstance.getId(),
                            sSubProcessDefinition.getId());
                    final boolean delete = schedulerService.delete(jobName);
                    if (!delete && schedulerService.isExistingJob(jobName)) {
                        if (log.isWarnEnabled()) {
                            log.warn("No job found with name '" + jobName
                                    + "' when interrupting timer catch event named '"
                                    + sCatchEventDefinition.getName()
                                    + "' on event sub process with the id '" + sSubProcessDefinition.getId()
                                    + "'. It was probably already triggered.");
                        }
                    }
                }
            } catch (final Exception e) {
                log.warn(ExceptionUtils.printLightWeightStacktrace(e));
            }
        }
    }

    void deleteJobsOnProcessInstance(final SProcessInstance sProcessInstance) throws SBonitaException {
        deleteJobsOnProcessInstance(sProcessInstance.getProcessDefinitionId(),
                Collections.singletonList(sProcessInstance));
    }

    private void deleteJobsOnProcessInstance(final long processDefinitionId,
            final List<SProcessInstance> sProcessInstances)
            throws SBonitaException {
        final SProcessDefinition processDefinition = getServiceAccessor().getProcessDefinitionService()
                .getProcessDefinition(processDefinitionId);

        for (final SProcessInstance sProcessInstance : sProcessInstances) {
            deleteJobsOnProcessInstance(processDefinition, sProcessInstance);
        }
    }

    private void deleteJobsOnProcessInstance(final SProcessDefinition processDefinition,
            final SProcessInstance sProcessInstance)
            throws SBonitaReadException {

        deleteJobsOnCallActivitiesOfProcessInstance(sProcessInstance.getId());

        final List<SStartEventDefinition> startEventsOfSubProcess = processDefinition.getProcessContainer()
                .getStartEvents();
        deleteJobsOnProcessInstance(processDefinition, sProcessInstance, startEventsOfSubProcess);

        final List<SIntermediateCatchEventDefinition> intermediateCatchEvents = processDefinition.getProcessContainer()
                .getIntermediateCatchEvents();
        deleteJobsOnProcessInstance(processDefinition, sProcessInstance, intermediateCatchEvents);

        final List<SBoundaryEventDefinition> sBoundaryEventDefinitions = processDefinition.getProcessContainer()
                .getBoundaryEvents();
        deleteJobsOnProcessInstance(processDefinition, sProcessInstance, sBoundaryEventDefinitions);

        deleteJobsOnEventSubProcess(processDefinition, sProcessInstance);
    }

    private void deleteJobsOnCallActivitiesOfProcessInstance(final long processInstanceId) throws SBonitaReadException {
        List<ActivityInstance> flowNodeInstances;
        int index = 0;
        final ProcessInstanceService processInstanceService = getServiceAccessor().getProcessInstanceService();
        do {
            try {
                flowNodeInstances = searchActivities(new SearchOptionsBuilder(index, index + BATCH_SIZE)
                        .filter(ActivityInstanceSearchDescriptor.PROCESS_INSTANCE_ID, processInstanceId)
                        .filter(ActivityInstanceSearchDescriptor.ACTIVITY_TYPE, FlowNodeType.CALL_ACTIVITY)
                        .done()).getResult();
            } catch (SearchException e) {
                throw new SBonitaReadException(
                        "Unable to delete jobs on call activities of process instance id " + processInstanceId, e);
            }
            for (ActivityInstance callActivityInstance : flowNodeInstances) {
                try {
                    deleteJobsOnProcessInstance(
                            processInstanceService.getChildOfActivity(callActivityInstance.getId()));
                } catch (SBonitaException e) {
                    log.info(
                            "Can't find the process instance called by the activity. This process may be already finished. {}",
                            ExceptionUtils.printLightWeightStacktrace(e));

                }
            }
            index = index + BATCH_SIZE;
        } while (flowNodeInstances.size() == BATCH_SIZE);

    }

    private void deleteJobsOnProcessInstance(final SProcessDefinition processDefinition,
            final SProcessInstance sProcessInstance,
            final List<? extends SCatchEventDefinition> sCatchEventDefinitions) throws SBonitaReadException {
        for (final SCatchEventDefinition sCatchEventDefinition : sCatchEventDefinitions) {
            deleteJobsOnFlowNodeInstances(processDefinition, sCatchEventDefinition, sProcessInstance);
        }
    }

    private void deleteJobsOnFlowNodeInstances(final SProcessDefinition processDefinition,
            final SCatchEventDefinition sCatchEventDefinition,
            final SProcessInstance sProcessInstance) throws SBonitaReadException {
        final ActivityInstanceService activityInstanceService = getServiceAccessor().getActivityInstanceService();

        final List<OrderByOption> orderByOptions = Collections
                .singletonList(new OrderByOption(SCatchEventInstance.class, "id", OrderByType.ASC));
        final FilterOption filterOption1 = new FilterOption(SCatchEventInstance.class, "flowNodeDefinitionId",
                sCatchEventDefinition.getId());
        final FilterOption filterOption2 = new FilterOption(SCatchEventInstance.class, "logicalGroup4",
                sProcessInstance.getId());
        QueryOptions queryOptions = new QueryOptions(0, 100, orderByOptions,
                Arrays.asList(filterOption1, filterOption2), null);
        List<SCatchEventInstance> sCatchEventInstances = activityInstanceService
                .searchFlowNodeInstances(SCatchEventInstance.class, queryOptions);
        while (!sCatchEventInstances.isEmpty()) {
            for (final SCatchEventInstance sCatchEventInstance : sCatchEventInstances) {
                deleteJobsOnFlowNodeInstance(processDefinition, sCatchEventDefinition, sCatchEventInstance);
            }
            queryOptions = QueryOptions.getNextPage(queryOptions);
            sCatchEventInstances = activityInstanceService.searchFlowNodeInstances(SCatchEventInstance.class,
                    queryOptions);
        }
    }

    private void deleteJobsOnFlowNodeInstance(final SProcessDefinition processDefinition,
            final SCatchEventDefinition sCatchEventDefinition,
            final SCatchEventInstance sCatchEventInstance) {
        final SchedulerService schedulerService = getServiceAccessor().getSchedulerService();
        EventInstanceService eventInstanceService = getServiceAccessor().getEventInstanceService();
        try {
            if (!sCatchEventDefinition.getTimerEventTriggerDefinitions().isEmpty()) {
                final String jobName = JobNameBuilder.getTimerEventJobName(processDefinition.getId(),
                        sCatchEventDefinition, sCatchEventInstance);
                final boolean delete = schedulerService.delete(jobName);
                try {
                    eventInstanceService.deleteEventTriggerInstanceOfFlowNode(sCatchEventInstance.getId());
                } catch (SEventTriggerInstanceDeletionException e) {
                    log.warn(
                            "Unable to delete event trigger of flow node instance " + sCatchEventInstance + " because: "
                                    + e.getMessage());
                }
                if (!delete && schedulerService.isExistingJob(jobName)) {
                    if (log.isWarnEnabled()) {
                        log.warn("No job found with name '" + jobName + "' when interrupting timer catch event named '"
                                + sCatchEventDefinition.getName()
                                + "' and id '" + sCatchEventInstance.getId()
                                + "'. It was probably already triggered.");
                    }
                }
            }
        } catch (final Exception e) {
            log.warn(ExceptionUtils.printLightWeightStacktrace(e));
        }
    }

    private List<SProcessInstance> searchProcessInstancesFromProcessDefinition(
            final ProcessInstanceService processInstanceService,
            final long processDefinitionId, final int startIndex, final int maxResults) throws SBonitaReadException {
        final FilterOption filterOption = new FilterOption(SProcessInstance.class, SProcessInstance.PROCESSDEF_ID_KEY,
                processDefinitionId);
        final OrderByOption order2 = new OrderByOption(SProcessInstance.class, SProcessInstance.ID_KEY,
                OrderByType.ASC);
        // Order by caller id ASC because we need to have parent process deleted before their sub processes
        final OrderByOption order = new OrderByOption(SProcessInstance.class, SProcessInstance.CALLER_ID,
                OrderByType.ASC);
        final QueryOptions queryOptions = new QueryOptions(startIndex, maxResults, Arrays.asList(order, order2),
                Collections.singletonList(filterOption), null);
        return processInstanceService.searchProcessInstances(queryOptions);
    }

    @Override
    public long deleteArchivedProcessInstances(final long processDefinitionId, final int startIndex,
            final int maxResults) throws DeletionException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();

        try {
            List<Long> sourceProcessInstanceIds = processInstanceService
                    .getSourceProcessInstanceIdsOfArchProcessInstancesFromDefinition(processDefinitionId, startIndex,
                            maxResults, null);
            if (sourceProcessInstanceIds.isEmpty()) {
                return 0;
            }
            return deleteArchivedProcessInstancesInAllStates(sourceProcessInstanceIds);
        } catch (final SBonitaException e) {
            throw new DeletionException(e);
        }
    }

    @Override
    public long deleteArchivedProcessInstancesInAllStates(final List<Long> sourceProcessInstanceIds)
            throws DeletionException {
        if (sourceProcessInstanceIds == null || sourceProcessInstanceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "The identifier of the archived process instances to deleted are missing !!");
        }
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();

        try {
            return processInstanceService.deleteArchivedProcessInstances(sourceProcessInstanceIds);
        } catch (final SProcessInstanceHierarchicalDeletionException e) {
            throw new ProcessInstanceHierarchicalDeletionException(e.getMessage(), e.getProcessInstanceId());
        } catch (final SBonitaException e) {
            throw new DeletionException(e);
        }
    }

    @Override
    public long deleteArchivedProcessInstancesInAllStates(final long sourceProcessInstanceId) throws DeletionException {
        return deleteArchivedProcessInstancesInAllStates(Collections.singletonList(sourceProcessInstanceId));
    }

    private List<BonitaLock> createLockProcessInstances(final LockService lockService, final String objectType,
            final Map<SProcessInstance, List<Long>> sProcessInstances,
            final long tenantId) throws SLockException, SLockTimeoutException {
        final List<BonitaLock> locks = new ArrayList<>();
        final HashSet<Long> uniqueIds = new HashSet<>();
        for (final Entry<SProcessInstance, List<Long>> processInstanceWithChildrenIds : sProcessInstances.entrySet()) {
            uniqueIds.add(processInstanceWithChildrenIds.getKey().getId());
            uniqueIds.addAll(processInstanceWithChildrenIds.getValue());
        }
        for (final Long id : uniqueIds) {
            final BonitaLock childLock = lockService.lock(id, objectType, tenantId);
            locks.add(childLock);
        }
        return locks;
    }

    @Override
    @CustomTransactions
    public void deleteProcessInstance(final long processInstanceId) throws DeletionException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final LockService lockService = serviceAccessor.getLockService();
        final String objectType = SFlowElementsContainerType.PROCESS.name();
        BonitaLock lock = null;
        try {
            lock = lockService.lock(processInstanceId, objectType, serviceAccessor.getTenantId());
            deleteProcessInstanceInTransaction(serviceAccessor, processInstanceId);
        } catch (final SProcessInstanceHierarchicalDeletionException e) {
            throw new ProcessInstanceHierarchicalDeletionException(e.getMessage(), e.getProcessInstanceId());
        } catch (final SProcessInstanceNotFoundException spinfe) {
            throw new DeletionException(new ProcessInstanceNotFoundException(spinfe));
        } catch (final SBonitaException e) {
            throw new DeletionException(e);
        } finally {
            if (lock != null) {
                try {
                    lockService.unlock(lock, serviceAccessor.getTenantId());
                } catch (final SLockException e) {
                    throw new DeletionException(
                            "Lock was not released. Object type: " + objectType + ", id: " + processInstanceId, e);
                }
            }
        }
    }

    @Override
    public SearchResult<ProcessInstance> searchOpenProcessInstances(final SearchOptions searchOptions)
            throws SearchException {
        // To select all process instances completed, without subprocess
        final SearchOptionsBuilder searchOptionsBuilder = new SearchOptionsBuilder(searchOptions);
        searchOptionsBuilder.differentFrom(ProcessInstanceSearchDescriptor.STATE_ID,
                ProcessInstanceState.COMPLETED.getId());
        searchOptionsBuilder.filter(ProcessInstanceSearchDescriptor.CALLER_ID, -1);
        try {
            return searchProcessInstances(getServiceAccessor(), searchOptionsBuilder.done());
        } catch (final SBonitaException e) {
            throw new SearchException(e);
        }
    }

    @Override
    public SearchResult<ProcessInstance> searchProcessInstances(final SearchOptions searchOptions)
            throws SearchException {
        try {
            return searchProcessInstances(getServiceAccessor(), searchOptions);
        } catch (final SBonitaException e) {
            throw new SearchException(e);
        }
    }

    @Override
    public SearchResult<ProcessInstance> searchFailedProcessInstances(final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();

        try {
            final SearchFailedProcessInstances searchProcessInstances = new SearchFailedProcessInstances(
                    processInstanceService,
                    searchEntitiesDescriptor.getSearchProcessInstanceDescriptor(), searchOptions,
                    processDefinitionService);
            searchProcessInstances.execute();
            return searchProcessInstances.getResult();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
    }

    /*
     * (non-Javadoc)
     * @see org.bonitasoft.engine.api.ProcessRuntimeAPI#searchFailedProcessInstancesSupervisedBy(long,
     * org.bonitasoft.engine.search.SearchOptions)
     */
    @Override
    public SearchResult<ProcessInstance> searchFailedProcessInstancesSupervisedBy(final long userId,
            final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final IdentityService identityService = serviceAccessor.getIdentityService();
        final GetSUser getUser = createTxUserGetter(userId, identityService);
        try {
            getUser.execute();
        } catch (final SBonitaException e) {
            return new SearchResultImpl<>(0, Collections.emptyList());
        }
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchFailedProcessInstancesSupervisedBy searchFailedProcessInstances = createSearchFailedProcessInstancesSupervisedBy(
                userId, searchOptions,
                processInstanceService, searchEntitiesDescriptor, processDefinitionService);
        try {
            searchFailedProcessInstances.execute();
            return searchFailedProcessInstances.getResult();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
    }

    protected SearchFailedProcessInstancesSupervisedBy createSearchFailedProcessInstancesSupervisedBy(final long userId,
            final SearchOptions searchOptions,
            final ProcessInstanceService processInstanceService,
            final SearchEntitiesDescriptor searchEntitiesDescriptor,
            final ProcessDefinitionService processDefinitionService) {
        return new SearchFailedProcessInstancesSupervisedBy(processInstanceService,
                searchEntitiesDescriptor.getSearchProcessInstanceDescriptor(), userId, searchOptions,
                processDefinitionService);
    }

    protected GetSUser createTxUserGetter(final long userId, final IdentityService identityService) {
        return new GetSUser(identityService, userId);
    }

    @Override
    public SearchResult<ProcessInstance> searchOpenProcessInstancesSupervisedBy(final long userId,
            final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final IdentityService identityService = serviceAccessor.getIdentityService();
        final GetSUser getUser = createTxUserGetter(userId, identityService);
        try {
            getUser.execute();
        } catch (final SBonitaException e) {
            return new SearchResultImpl<>(0, Collections.emptyList());
        }
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchOpenProcessInstancesSupervisedBy searchOpenProcessInstances = new SearchOpenProcessInstancesSupervisedBy(
                processInstanceService,
                searchEntitiesDescriptor.getSearchProcessInstanceDescriptor(), userId, searchOptions,
                processDefinitionService);
        try {
            searchOpenProcessInstances.execute();
            return searchOpenProcessInstances.getResult();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
    }

    @Override
    public SearchResult<ProcessDeploymentInfo> searchProcessDeploymentInfosStartedBy(final long userId,
            final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchProcessDefinitionsDescriptor searchDescriptor = searchEntitiesDescriptor
                .getSearchProcessDefinitionsDescriptor();
        final SearchProcessDeploymentInfosStartedBy searcher = new SearchProcessDeploymentInfosStartedBy(
                processDefinitionService, searchDescriptor, userId,
                searchOptions);
        try {
            searcher.execute();
        } catch (final SBonitaException e) {
            throw new SearchException("Can't get ProcessDeploymentInfo startedBy userid " + userId, e);
        }
        return searcher.getResult();
    }

    @Override
    public SearchResult<ProcessDeploymentInfo> searchProcessDeploymentInfos(final SearchOptions searchOptions)
            throws SearchException {
        return processDeploymentAPIDelegate.searchProcessDeploymentInfos(searchOptions);
    }

    @Override
    public SearchResult<ProcessDeploymentInfo> searchProcessDeploymentInfosCanBeStartedBy(final long userId,
            final SearchOptions searchOptions)
            throws RetrieveException, SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchProcessDefinitionsDescriptor searchDescriptor = searchEntitiesDescriptor
                .getSearchProcessDefinitionsDescriptor();
        final SearchProcessDeploymentInfosCanBeStartedBy transactionSearch = new SearchProcessDeploymentInfosCanBeStartedBy(
                processDefinitionService,
                searchDescriptor, searchOptions, userId);
        try {
            transactionSearch.execute();
        } catch (final SBonitaException e) {
            throw new SearchException("Error while retrieving process definitions: " + e.getMessage(), e);
        }
        return transactionSearch.getResult();
    }

    @Override
    public SearchResult<ProcessDeploymentInfo> searchProcessDeploymentInfosCanBeStartedByUsersManagedBy(
            final long managerUserId,
            final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchProcessDefinitionsDescriptor searchDescriptor = searchEntitiesDescriptor
                .getSearchProcessDefinitionsDescriptor();
        final SearchProcessDeploymentInfosCanBeStartedByUsersManagedBy transactionSearch = new SearchProcessDeploymentInfosCanBeStartedByUsersManagedBy(
                processDefinitionService, searchDescriptor, searchOptions, managerUserId);
        try {
            transactionSearch.execute();
        } catch (final SBonitaException e) {
            throw new SearchException(e);
        }
        return transactionSearch.getResult();
    }

    @Override
    public SearchResult<ProcessDeploymentInfo> searchProcessDeploymentInfosWithAssignedOrPendingHumanTasksFor(
            final long userId,
            final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchProcessDefinitionsDescriptor searchDescriptor = searchEntitiesDescriptor
                .getSearchProcessDefinitionsDescriptor();
        final SearchProcessDeploymentInfosWithAssignedOrPendingHumanTasksFor searcher = new SearchProcessDeploymentInfosWithAssignedOrPendingHumanTasksFor(
                processDefinitionService, searchDescriptor, searchOptions, userId);
        try {
            searcher.execute();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
        return searcher.getResult();
    }

    @Override
    public SearchResult<ProcessDeploymentInfo> searchProcessDeploymentInfosWithAssignedOrPendingHumanTasksSupervisedBy(
            final long supervisorId,
            final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchProcessDefinitionsDescriptor searchDescriptor = searchEntitiesDescriptor
                .getSearchProcessDefinitionsDescriptor();
        final SearchProcessDeploymentInfosWithAssignedOrPendingHumanTasksSupervisedBy searcher = new SearchProcessDeploymentInfosWithAssignedOrPendingHumanTasksSupervisedBy(
                processDefinitionService, searchDescriptor, searchOptions, supervisorId);
        try {
            searcher.execute();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
        return searcher.getResult();
    }

    @Override
    public SearchResult<ProcessDeploymentInfo> searchProcessDeploymentInfosWithAssignedOrPendingHumanTasks(
            final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchProcessDefinitionsDescriptor searchDescriptor = searchEntitiesDescriptor
                .getSearchProcessDefinitionsDescriptor();
        final SearchProcessDeploymentInfosWithAssignedOrPendingHumanTasks searcher = new SearchProcessDeploymentInfosWithAssignedOrPendingHumanTasks(
                processDefinitionService, searchDescriptor, searchOptions);
        try {
            searcher.execute();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
        return searcher.getResult();
    }

    @Override
    public Map<String, Map<String, Long>> getActiveFlownodeStateCountersForProcessDefinition(
            final long processDefinitionId) throws ProcessDefinitionNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final HashMap<String, Map<String, Long>> countersForProcessDefinition = new HashMap<>();
        try {
            //make sure a process definition with this Id exists
            serviceAccessor.getProcessDefinitionService().getProcessDeploymentInfo(processDefinitionId);
            // Active flownodes:
            final List<SFlowNodeInstanceStateCounter> flownodes = serviceAccessor.getActivityInstanceService()
                    .getNumberOfFlownodesOfProcessDefinitionInAllStates(
                            processDefinitionId);
            for (final SFlowNodeInstanceStateCounter nodeCounter : flownodes) {
                final String flownodeName = nodeCounter.getFlownodeName();
                final Map<String, Long> flownodeCounters = getFlowNodeCounters(countersForProcessDefinition,
                        flownodeName);
                flownodeCounters.put(nodeCounter.getStateName(), nodeCounter.getNumberOf());
                countersForProcessDefinition.put(flownodeName, flownodeCounters);
            }
        } catch (final SBonitaReadException e) {
            throw new RetrieveException(e);
        } catch (SProcessDefinitionNotFoundException e) {
            throw new ProcessDefinitionNotFoundException(processDefinitionId, e);
        }
        return countersForProcessDefinition;
    }

    @Override
    public Map<String, Map<String, Long>> getFlownodeStateCounters(final long processInstanceId) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final HashMap<String, Map<String, Long>> countersForProcessInstance = new HashMap<>();
        try {
            //make sure a process instance with this Id exists
            if (serviceAccessor.getProcessInstanceService().getLastArchivedProcessInstance(processInstanceId) == null) {
                throw new SProcessInstanceNotFoundException(processInstanceId);
            }
            // Active flownodes:
            final List<SFlowNodeInstanceStateCounter> flownodes = serviceAccessor.getActivityInstanceService()
                    .getNumberOfFlownodesInAllStates(
                            processInstanceId);
            for (final SFlowNodeInstanceStateCounter nodeCounter : flownodes) {
                final String flownodeName = nodeCounter.getFlownodeName();
                final Map<String, Long> flownodeCounters = getFlowNodeCounters(countersForProcessInstance,
                        flownodeName);
                flownodeCounters.put(nodeCounter.getStateName(), nodeCounter.getNumberOf());
                countersForProcessInstance.put(flownodeName, flownodeCounters);
            }
            // Archived flownodes:
            final List<SFlowNodeInstanceStateCounter> archivedFlownodes = serviceAccessor.getActivityInstanceService()
                    .getNumberOfArchivedFlownodesInAllStates(
                            processInstanceId);
            for (final SFlowNodeInstanceStateCounter nodeCounter : archivedFlownodes) {
                final String flownodeName = nodeCounter.getFlownodeName();
                final Map<String, Long> flownodeCounters = getFlowNodeCounters(countersForProcessInstance,
                        flownodeName);
                flownodeCounters.put(nodeCounter.getStateName(), nodeCounter.getNumberOf());
                countersForProcessInstance.put(flownodeName, flownodeCounters);
            }
        } catch (final SProcessInstanceNotFoundException e) {
            //cannot throw directly a ProcessInstanceNotFoundException to avoid API break
            throw new RetrieveException(new ProcessInstanceNotFoundException(processInstanceId));
        } catch (final SBonitaReadException e) {
            throw new RetrieveException(e);
        }
        return countersForProcessInstance;
    }

    private Map<String, Long> getFlowNodeCounters(final HashMap<String, Map<String, Long>> counters,
            final String flowNodeName) {
        return counters.computeIfAbsent(flowNodeName, k -> new HashMap<>());
    }

    @Override
    public SearchResult<ProcessDeploymentInfo> searchProcessDeploymentInfosSupervisedBy(final long userId,
            final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchProcessDefinitionsDescriptor searchDescriptor = searchEntitiesDescriptor
                .getSearchProcessDefinitionsDescriptor();
        final SearchProcessDeploymentInfosSupervised searcher = new SearchProcessDeploymentInfosSupervised(
                processDefinitionService, searchDescriptor,
                searchOptions, userId);
        try {
            searcher.execute();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
        return searcher.getResult();
    }

    @Override
    public SearchResult<HumanTaskInstance> searchAssignedTasksSupervisedBy(final long supervisorId,
            final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        return AbstractHumanTaskInstanceSearchEntity.searchHumanTaskInstance(
                searchEntitiesDescriptor.getSearchHumanTaskInstanceDescriptor(),
                searchOptions,
                flowNodeStateManager,
                (queryOptions) -> activityInstanceService.getNumberOfAssignedTasksSupervisedBy(supervisorId,
                        queryOptions),
                (queryOptions) -> activityInstanceService.searchAssignedTasksSupervisedBy(supervisorId, queryOptions))
                .search();
    }

    @Override
    public SearchResult<ArchivedHumanTaskInstance> searchArchivedHumanTasksSupervisedBy(final long supervisorId,
            final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchArchivedHumanTasksSupervisedBy searchedTasksTransaction = new SearchArchivedHumanTasksSupervisedBy(
                supervisorId, activityInstanceService,
                flowNodeStateManager, searchEntitiesDescriptor.getSearchArchivedHumanTaskInstanceDescriptor(),
                searchOptions);

        try {
            searchedTasksTransaction.execute();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
        return searchedTasksTransaction.getResult();
    }

    @Override
    public SearchResult<ProcessSupervisor> searchProcessSupervisors(final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SupervisorMappingService supervisorService = serviceAccessor.getSupervisorService();
        final SearchProcessSupervisorDescriptor searchDescriptor = new SearchProcessSupervisorDescriptor();
        final SearchSupervisors searchSupervisorsTransaction = new SearchSupervisors(supervisorService,
                searchDescriptor, searchOptions);
        try {
            searchSupervisorsTransaction.execute();
            return searchSupervisorsTransaction.getResult();
        } catch (final SBonitaException e) {
            throw new SearchException(e);
        }
    }

    @Override
    public boolean isUserProcessSupervisor(final long processDefinitionId, final long userId) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SupervisorMappingService supervisorService = serviceAccessor.getSupervisorService();
        try {
            return supervisorService.isProcessSupervisor(processDefinitionId, userId);
        } catch (final SBonitaReadException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public void deleteSupervisor(final long supervisorId) throws DeletionException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SupervisorMappingService supervisorService = serviceAccessor.getSupervisorService();
        try {
            supervisorService.deleteProcessSupervisor(supervisorId);
            log.info("The process manager has been deleted with id = <" + supervisorId + ">.");

        } catch (final SSupervisorNotFoundException spsnfe) {
            throw new DeletionException(new SupervisorNotFoundException(
                    "The process manager was not found with id = <" + supervisorId + ">"));
        } catch (final SSupervisorDeletionException e) {
            throw new DeletionException(e);
        }
    }

    @Override
    public void deleteSupervisor(final Long processDefinitionId, final Long userId, final Long roleId,
            final Long groupId) throws DeletionException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SupervisorMappingService supervisorService = serviceAccessor.getSupervisorService();

        try {
            final List<SProcessSupervisor> sProcessSupervisors = searchSProcessSupervisors(processDefinitionId, userId,
                    groupId, roleId);

            if (!sProcessSupervisors.isEmpty()) {
                // Then, delete it
                supervisorService.deleteProcessSupervisor(sProcessSupervisors.get(0));
                log.info("The process manager has been deleted with process definition id = <"
                        + processDefinitionId + ">, user id = <" + userId + ">, group id = <" + groupId
                        + ">, and role id = <" + roleId + ">.");
            } else {
                throw new DeletionException(new SupervisorNotFoundException(
                        "The process manager was not found with process definition id = <"
                                + processDefinitionId + ">, user id = <" + userId + ">, group id = <" + groupId
                                + ">, and role id = <" + roleId + ">."));
            }
        } catch (final SBonitaException e) {
            throw new DeletionException(e);
        }
    }

    private void deleteAllSupervisorsOfProcess(final long processDefinitionId) throws DeletionException {
        final SearchOptionsBuilder builder = new SearchOptionsBuilder(0, Integer.MAX_VALUE);
        builder.filter(ProcessSupervisorSearchDescriptor.PROCESS_DEFINITION_ID, processDefinitionId);
        try {
            SearchResult<ProcessSupervisor> processSupervisors = searchProcessSupervisors(builder.done());

            List<Long> supervisorIds = processSupervisors.getResult().stream().map(ProcessSupervisor::getSupervisorId)
                    .collect(Collectors.toList());

            for (Long id : supervisorIds) {
                deleteSupervisor(id);
            }
        } catch (SearchException e) {
            throw new DeletionException(e);
        }
    }

    @Override
    public ProcessSupervisor createProcessSupervisorForUser(final long processDefinitionId, final long userId)
            throws CreationException {
        return createSupervisor(SProcessSupervisor.builder().processDefId(processDefinitionId).userId(userId).build());
    }

    @Override
    public ProcessSupervisor createProcessSupervisorForRole(final long processDefinitionId, final long roleId)
            throws CreationException {
        return createSupervisor(SProcessSupervisor.builder().processDefId(processDefinitionId).roleId(roleId).build());
    }

    @Override
    public ProcessSupervisor createProcessSupervisorForGroup(final long processDefinitionId, final long groupId)
            throws CreationException {
        return createSupervisor(
                SProcessSupervisor.builder().processDefId(processDefinitionId).groupId(groupId).build());
    }

    @Override
    public ProcessSupervisor createProcessSupervisorForMembership(final long processDefinitionId, final long groupId,
            final long roleId) throws CreationException {
        return createSupervisor(
                SProcessSupervisor.builder().processDefId(processDefinitionId).groupId(groupId).roleId(roleId).build());
    }

    private ProcessSupervisor createSupervisor(final SProcessSupervisor sProcessSupervisor) throws CreationException {
        final ServiceAccessor ServiceAccessor = getServiceAccessor();
        final SupervisorMappingService supervisorService = ServiceAccessor.getSupervisorService();

        try {
            checkIfProcessSupervisorAlreadyExists(sProcessSupervisor.getProcessDefId(), sProcessSupervisor.getUserId(),
                    sProcessSupervisor.getGroupId(),
                    sProcessSupervisor.getRoleId());

            final SProcessSupervisor supervisor = supervisorService.createProcessSupervisor(sProcessSupervisor);
            log.info("The process manager has been created with process definition id = <"
                    + sProcessSupervisor.getProcessDefId() + ">, user id = <"
                    + sProcessSupervisor.getUserId() + ">, group id = <"
                    + sProcessSupervisor.getGroupId() + ">, and role id = <"
                    + sProcessSupervisor.getRoleId() + ">");
            return ModelConvertor.toProcessSupervisor(supervisor);
        } catch (final SBonitaException e) {
            throw new CreationException(e);
        }
    }

    private void checkIfProcessSupervisorAlreadyExists(final long processDefinitionId, final Long userId,
            final Long groupId, final Long roleId)
            throws AlreadyExistsException {
        try {
            final List<SProcessSupervisor> processSupervisors = searchSProcessSupervisors(processDefinitionId, userId,
                    groupId, roleId);
            if (!processSupervisors.isEmpty()) {
                throw new AlreadyExistsException("This supervisor already exists for process definition id = <"
                        + processDefinitionId + ">, user id = <"
                        + userId + ">, group id = <" + groupId + ">, role id = <" + roleId + ">");
            }
        } catch (final SBonitaReadException e1) {
            // Nothing to do
        }
    }

    protected List<SProcessSupervisor> searchSProcessSupervisors(final Long processDefinitionId, final Long userId,
            final Long groupId, final Long roleId)
            throws SBonitaReadException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SupervisorMappingService supervisorService = serviceAccessor.getSupervisorService();

        final List<OrderByOption> oderByOptions = Collections.singletonList(
                new OrderByOption(SProcessSupervisor.class, SProcessSupervisor.USER_ID_KEY, OrderByType.DESC));
        final List<FilterOption> filterOptions = new ArrayList<>();
        filterOptions.add(new FilterOption(SProcessSupervisor.class, SProcessSupervisor.PROCESS_DEF_ID_KEY,
                processDefinitionId == null ? -1 : processDefinitionId));
        filterOptions.add(new FilterOption(SProcessSupervisor.class, SProcessSupervisor.USER_ID_KEY,
                userId == null ? -1 : userId));
        filterOptions.add(new FilterOption(SProcessSupervisor.class, SProcessSupervisor.GROUP_ID_KEY,
                groupId == null ? -1 : groupId));
        filterOptions.add(new FilterOption(SProcessSupervisor.class, SProcessSupervisor.ROLE_ID_KEY,
                roleId == null ? -1 : roleId));

        return supervisorService.searchProcessSupervisors(new QueryOptions(0, 1, oderByOptions, filterOptions, null));
    }

    @Override
    public SearchResult<ProcessDeploymentInfo> searchUncategorizedProcessDeploymentInfosCanBeStartedBy(
            final long userId, final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchProcessDefinitionsDescriptor searchDescriptor = searchEntitiesDescriptor
                .getSearchProcessDefinitionsDescriptor();
        final SearchUncategorizedProcessDeploymentInfosCanBeStartedBy transactionSearch = new SearchUncategorizedProcessDeploymentInfosCanBeStartedBy(
                processDefinitionService, searchDescriptor, searchOptions, userId);
        try {
            transactionSearch.execute();
        } catch (final SBonitaException e) {
            throw new SearchException("Error while retrieving process definitions", e);
        }
        return transactionSearch.getResult();
    }

    @Override
    public SearchResult<ArchivedHumanTaskInstance> searchArchivedHumanTasksManagedBy(final long managerUserId,
            final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchArchivedTasksManagedBy searchTransaction = new SearchArchivedTasksManagedBy(managerUserId,
                searchOptions, activityInstanceService,
                flowNodeStateManager, searchEntitiesDescriptor.getSearchArchivedHumanTaskInstanceDescriptor());
        try {
            searchTransaction.execute();
        } catch (final SBonitaException e) {
            throw new SearchException(e);
        }
        return searchTransaction.getResult();
    }

    @Override
    public SearchResult<ProcessInstance> searchOpenProcessInstancesInvolvingUser(final long userId,
            final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchOpenProcessInstancesInvolvingUser searchOpenProcessInstances = new SearchOpenProcessInstancesInvolvingUser(
                processInstanceService,
                searchEntitiesDescriptor.getSearchProcessInstanceDescriptor(), userId, searchOptions,
                processDefinitionService);
        try {
            searchOpenProcessInstances.execute();
            return searchOpenProcessInstances.getResult();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
    }

    @Override
    public SearchResult<ProcessInstance> searchOpenProcessInstancesInvolvingUsersManagedBy(final long managerUserId,
            final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchOpenProcessInstancesInvolvingUsersManagedBy searchOpenProcessInstances = new SearchOpenProcessInstancesInvolvingUsersManagedBy(
                processInstanceService, searchEntitiesDescriptor.getSearchProcessInstanceDescriptor(), managerUserId,
                searchOptions, processDefinitionService);
        try {
            searchOpenProcessInstances.execute();
            return searchOpenProcessInstances.getResult();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
    }

    @Override
    public SearchResult<ArchivedHumanTaskInstance> searchArchivedHumanTasks(final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();

        final SearchArchivedTasks searchArchivedTasks = new SearchArchivedTasks(activityInstanceService,
                flowNodeStateManager,
                searchEntitiesDescriptor.getSearchArchivedHumanTaskInstanceDescriptor(), searchOptions);
        try {
            searchArchivedTasks.execute();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
        return searchArchivedTasks.getResult();
    }

    @Override
    public SearchResult<HumanTaskInstance> searchAssignedTasksManagedBy(final long managerUserId,
            final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        return AbstractHumanTaskInstanceSearchEntity.searchHumanTaskInstance(
                searchEntitiesDescriptor.getSearchHumanTaskInstanceDescriptor(),
                searchOptions,
                flowNodeStateManager,
                (queryOptions) -> activityInstanceService.getNumberOfAssignedTasksManagedBy(managerUserId,
                        queryOptions),
                (queryOptions) -> activityInstanceService.searchAssignedTasksManagedBy(managerUserId, queryOptions))
                .search();
    }

    @Override
    public SearchResult<ArchivedProcessInstance> searchArchivedProcessInstancesSupervisedBy(final long userId,
            final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchArchivedProcessInstancesSupervisedBy searchArchivedProcessInstances = new SearchArchivedProcessInstancesSupervisedBy(
                userId,
                processInstanceService, serviceAccessor.getProcessDefinitionService(),
                searchEntitiesDescriptor.getSearchArchivedProcessInstanceDescriptor(),
                searchOptions);
        try {
            searchArchivedProcessInstances.execute();
            return searchArchivedProcessInstances.getResult();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
    }

    @Override
    public SearchResult<ArchivedProcessInstance> searchArchivedProcessInstancesInvolvingUser(final long userId,
            final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchArchivedProcessInstancesInvolvingUser searchArchivedProcessInstances = new SearchArchivedProcessInstancesInvolvingUser(
                userId,
                processInstanceService, serviceAccessor.getProcessDefinitionService(),
                searchEntitiesDescriptor.getSearchArchivedProcessInstanceDescriptor(),
                searchOptions);
        try {
            searchArchivedProcessInstances.execute();
            return searchArchivedProcessInstances.getResult();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
    }

    @Override
    public SearchResult<HumanTaskInstance> searchPendingTasksForUser(final long userId,
            final SearchOptions searchOptions) throws SearchException {
        return searchTasksForUser(userId, searchOptions, "");
    }

    /**
     * @param queryType can be :
     *        PendingOrAssignedOrAssignedToOthers if we also want to retrieve tasks directly assigned to this user or
     *        tasks assigned
     *        to other users.
     *        PendingOrAssigned if we also want to retrieve tasks directly assigned to this user.
     *        empty for the default behaviour; retrieve pending tasks for user.
     */
    private SearchResult<HumanTaskInstance> searchTasksForUser(final long userId, final SearchOptions searchOptions,
            final String queryType) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();

        switch (queryType) {
            case PENDING_OR_ASSIGNED_OR_ASSIGNED_TO_OTHERS:
                return AbstractHumanTaskInstanceSearchEntity.searchHumanTaskInstance(
                        searchEntitiesDescriptor.getSearchHumanTaskInstanceDescriptor(),
                        searchOptions,
                        flowNodeStateManager,
                        (queryOptions) -> activityInstanceService.getNumberOfPendingOrAssignedOrAssignedToOthersTasks(
                                userId,
                                queryOptions),
                        (queryOptions) -> activityInstanceService.searchPendingOrAssignedOrAssignedToOthersTasks(userId,
                                queryOptions))
                        .search();
            case PENDING_OR_ASSIGNED:
                return AbstractHumanTaskInstanceSearchEntity.searchHumanTaskInstance(
                        searchEntitiesDescriptor.getSearchHumanTaskInstanceDescriptor(),
                        searchOptions,
                        flowNodeStateManager,
                        (queryOptions) -> activityInstanceService.getNumberOfPendingOrAssignedTasks(userId,
                                queryOptions),
                        (queryOptions) -> activityInstanceService.searchPendingOrAssignedTasks(userId, queryOptions))
                        .search();
            default:
                return AbstractHumanTaskInstanceSearchEntity
                        .searchHumanTaskInstance(searchEntitiesDescriptor.getSearchHumanTaskInstanceDescriptor(),
                                searchOptions,
                                flowNodeStateManager,
                                (queryOptions) -> activityInstanceService.getNumberOfPendingTasksForUser(userId,
                                        queryOptions),
                                (queryOptions) -> activityInstanceService.searchPendingTasksForUser(userId,
                                        queryOptions))
                        .search();
        }
    }

    @Override
    public SearchResult<HumanTaskInstance> searchPendingTasksAssignedToUser(final long userId,
            final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        return AbstractHumanTaskInstanceSearchEntity
                .searchHumanTaskInstance(searchEntitiesDescriptor.getSearchHumanTaskInstanceDescriptor(),
                        searchOptions,
                        flowNodeStateManager,
                        (queryOptions) -> activityInstanceService.getNumberOfPendingTasksAssignedTo(userId,
                                queryOptions),
                        (queryOptions) -> activityInstanceService.searchPendingTasksAssignedTo(userId, queryOptions))
                .search();
    }

    @Override
    public SearchResult<HumanTaskInstance> searchMyAvailableHumanTasks(final long userId,
            final SearchOptions searchOptions) throws SearchException {
        return searchTasksForUser(userId, searchOptions, PENDING_OR_ASSIGNED);
    }

    @Override
    public SearchResult<HumanTaskInstance> searchPendingOrAssignedToUserOrAssignedToOthersTasks(final long userId,
            final SearchOptions searchOptions) throws SearchException {
        return searchTasksForUser(userId, searchOptions, PENDING_OR_ASSIGNED_OR_ASSIGNED_TO_OTHERS);
    }

    @Override
    public SearchResult<HumanTaskInstance> searchPendingTasksSupervisedBy(final long userId,
            final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        return AbstractHumanTaskInstanceSearchEntity
                .searchHumanTaskInstance(searchEntitiesDescriptor.getSearchHumanTaskInstanceDescriptor(),
                        searchOptions,
                        flowNodeStateManager,
                        (queryOptions) -> activityInstanceService.getNumberOfPendingTasksSupervisedBy(userId,
                                queryOptions),
                        (queryOptions) -> activityInstanceService.searchPendingTasksSupervisedBy(userId, queryOptions))
                .search();
    }

    @Override
    public SearchResult<Comment> searchComments(final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final SCommentService commentService = serviceAccessor.getCommentService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchComments searchComments = new SearchComments(searchEntitiesDescriptor.getSearchCommentDescriptor(),
                searchOptions, commentService);
        try {
            searchComments.execute();
            return searchComments.getResult();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
    }

    @Override
    public Comment addProcessComment(final long processInstanceId, final String comment) throws CreationException {
        return addProcessCommentOnBehalfOfUser(processInstanceId, comment, getUserId());
    }

    @Override
    public Comment addProcessCommentOnBehalfOfUser(final long processInstanceId, final String comment, long userId)
            throws CreationException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        try {
            serviceAccessor.getProcessInstanceService().getProcessInstance(processInstanceId);
        } catch (final SProcessInstanceReadException | SProcessInstanceNotFoundException e) {
            throw new RetrieveException(buildCantAddCommentOnProcessInstance(), e); // FIXME: should be another exception
        }
        final SCommentService commentService = serviceAccessor.getCommentService();
        try {
            SComment sComment = commentService.addComment(processInstanceId, comment, userId);
            return ModelConvertor.toComment(sComment);
        } catch (final SBonitaException e) {
            throw new CreationException(buildCantAddCommentOnProcessInstance(), e.getCause());
        }
    }

    private String buildCantAddCommentOnProcessInstance() {
        return "Cannot add a comment on a finished or inexistant process instance";
    }

    @Override
    public Document attachDocument(final long processInstanceId, final String documentName, final String fileName,
            final String mimeType, final String url)
            throws DocumentAttachmentException, ProcessInstanceNotFoundException {
        return documentAPI.attachDocument(processInstanceId, documentName, fileName, mimeType, url);
    }

    @Override
    public Document attachDocument(final long processInstanceId, final String documentName, final String fileName,
            final String mimeType,
            final byte[] documentContent) throws DocumentAttachmentException, ProcessInstanceNotFoundException {
        return documentAPI.attachDocument(processInstanceId, documentName, fileName, mimeType, documentContent);
    }

    @Override
    public Document attachNewDocumentVersion(final long processInstanceId, final String documentName,
            final String fileName, final String mimeType,
            final String url) throws DocumentAttachmentException {
        return documentAPI.attachNewDocumentVersion(processInstanceId, documentName, fileName, mimeType, url);
    }

    @Override
    public Document attachNewDocumentVersion(final long processInstanceId, final String documentName,
            final String contentFileName,
            final String contentMimeType, final byte[] documentContent) throws DocumentAttachmentException {
        return documentAPI.attachNewDocumentVersion(processInstanceId, documentName, contentFileName, contentMimeType,
                documentContent);
    }

    @Override
    public Document getDocument(final long documentId) throws DocumentNotFoundException {
        return documentAPI.getDocument(documentId);
    }

    @Override
    public List<Document> getLastVersionOfDocuments(final long processInstanceId, final int pageIndex,
            final int numberPerPage,
            final DocumentCriterion pagingCriterion) throws DocumentException, ProcessInstanceNotFoundException {
        return documentAPI.getLastVersionOfDocuments(processInstanceId, pageIndex, numberPerPage, pagingCriterion);
    }

    @Override
    public byte[] getDocumentContent(final String documentStorageId) throws DocumentNotFoundException {
        return documentAPI.getDocumentContent(documentStorageId);
    }

    @Override
    public Document getLastDocument(final long processInstanceId, final String documentName)
            throws DocumentNotFoundException {
        return documentAPI.getLastDocument(processInstanceId, documentName);
    }

    @Override
    public long getNumberOfDocuments(final long processInstanceId) throws DocumentException {
        return documentAPI.getNumberOfDocuments(processInstanceId);
    }

    @Override
    public Document getDocumentAtProcessInstantiation(final long processInstanceId, final String documentName)
            throws DocumentNotFoundException {

        return documentAPI.getDocumentAtProcessInstantiation(processInstanceId, documentName);
    }

    @Override
    public Document getDocumentAtActivityInstanceCompletion(final long activityInstanceId, final String documentName)
            throws DocumentNotFoundException {
        return documentAPI.getDocumentAtActivityInstanceCompletion(activityInstanceId, documentName);
    }

    @Override
    public SearchResult<HumanTaskInstance> searchPendingTasksManagedBy(final long managerUserId,
            final SearchOptions searchOptions) throws SearchException {
        return taskInvolvementDelegate.searchPendingTasksManagedBy(managerUserId, searchOptions);
    }

    @Override
    public Map<Long, Long> getNumberOfOverdueOpenTasks(final List<Long> userIds) throws RetrieveException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();

        try {
            return activityInstanceService.getNumberOfOverdueOpenTasksForUsers(userIds);
        } catch (final SBonitaException e) {
            logError(e);
            throw new RetrieveException(e.getMessage());
        }
    }

    @Override
    public SearchResult<ProcessDeploymentInfo> searchUncategorizedProcessDeploymentInfos(
            final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchProcessDefinitionsDescriptor searchDescriptor = searchEntitiesDescriptor
                .getSearchProcessDefinitionsDescriptor();
        final SearchUncategorizedProcessDeploymentInfos transactionSearch = new SearchUncategorizedProcessDeploymentInfos(
                processDefinitionService,
                searchDescriptor, searchOptions);
        try {
            transactionSearch.execute();
        } catch (final SBonitaException e) {
            throw new SearchException("Problem encountered while searching for Uncategorized Process Definitions", e);
        }
        return transactionSearch.getResult();
    }

    @Override
    public SearchResult<Comment> searchCommentsManagedBy(final long managerUserId, final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final SCommentService commentService = serviceAccessor.getCommentService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchCommentsManagedBy searchComments = new SearchCommentsManagedBy(
                searchEntitiesDescriptor.getSearchCommentDescriptor(), searchOptions,
                commentService, managerUserId);
        try {
            searchComments.execute();
            return searchComments.getResult();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
    }

    @Override
    public SearchResult<Comment> searchCommentsInvolvingUser(final long userId, final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final SCommentService commentService = serviceAccessor.getCommentService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchCommentsInvolvingUser searchComments = new SearchCommentsInvolvingUser(
                searchEntitiesDescriptor.getSearchCommentDescriptor(),
                searchOptions, commentService, userId);
        try {
            searchComments.execute();
            return searchComments.getResult();
        } catch (final SBonitaException sbe) {
            throw new SearchException(sbe);
        }
    }

    @Override
    public List<Long> getChildrenInstanceIdsOfProcessInstance(final long processInstanceId, final int startIndex,
            final int maxResults,
            final ProcessInstanceCriterion criterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();

        long totalNumber;
        try {
            totalNumber = processInstanceService.getNumberOfChildInstancesOfProcessInstance(processInstanceId);
            if (totalNumber == 0) {
                return Collections.emptyList();
            }
            final OrderAndField orderAndField = OrderAndFields.getOrderAndFieldForProcessInstance(criterion);
            return processInstanceService.getChildInstanceIdsOfProcessInstance(processInstanceId, startIndex,
                    maxResults, orderAndField.getField(),
                    orderAndField.getOrder());
        } catch (final SProcessInstanceReadException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public SearchResult<ProcessDeploymentInfo> searchUncategorizedProcessDeploymentInfosSupervisedBy(final long userId,
            final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchProcessDefinitionsDescriptor searchDescriptor = searchEntitiesDescriptor
                .getSearchProcessDefinitionsDescriptor();
        final SearchUncategorizedProcessDeploymentInfosSupervisedBy transactionSearch = new SearchUncategorizedProcessDeploymentInfosSupervisedBy(
                processDefinitionService, searchDescriptor, searchOptions, userId);
        try {
            transactionSearch.execute();
        } catch (final SBonitaException e) {
            throw new SearchException(
                    "Problem encountered while searching for Uncategorized Process Definitions for a supervisor", e);
        }
        return transactionSearch.getResult();
    }

    @Override
    public Map<Long, ProcessDeploymentInfo> getProcessDeploymentInfosFromIds(final List<Long> processDefinitionIds) {
        return ProcessDeploymentAPIDelegate.getInstance().getProcessDeploymentInfosFromIds(processDefinitionIds);
    }

    @Override
    public List<ConnectorImplementationDescriptor> getConnectorImplementations(final long processDefinitionId,
            final int startIndex, final int maxsResults,
            final ConnectorCriterion sortingCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ConnectorService connectorService = serviceAccessor.getConnectorService();
        final OrderAndField orderAndField = OrderAndFields.getOrderAndFieldForConnectorImplementation(sortingCriterion);
        try {
            final List<SConnectorImplementationDescriptor> sConnectorImplementationDescriptors = connectorService
                    .getConnectorImplementations(processDefinitionId, startIndex, maxsResults, orderAndField.getField(),
                            orderAndField.getOrder());
            return ModelConvertor.toConnectorImplementationDescriptors(sConnectorImplementationDescriptors);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public long getNumberOfConnectorImplementations(final long processDefinitionId) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ConnectorService connectorService = serviceAccessor.getConnectorService();
        try {
            return connectorService.getNumberOfConnectorImplementations(processDefinitionId);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public SearchResult<ActivityInstance> searchActivities(final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final SearchActivityInstances searchActivityInstancesTransaction;
        try {
            searchActivityInstancesTransaction = new SearchActivityInstances(activityInstanceService,
                    flowNodeStateManager,
                    searchEntitiesDescriptor.getSearchActivityInstanceDescriptor(), searchOptions);
            searchActivityInstancesTransaction.execute();
        } catch (final SBonitaException e) {
            throw new SearchException(e);
        }
        return searchActivityInstancesTransaction.getResult();
    }

    @Override
    public SearchResult<ArchivedFlowNodeInstance> searchArchivedFlowNodeInstances(final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final SearchArchivedFlowNodeInstances searchTransaction = new SearchArchivedFlowNodeInstances(
                activityInstanceService, flowNodeStateManager,
                searchEntitiesDescriptor.getSearchArchivedFlowNodeInstanceDescriptor(), searchOptions);
        try {
            searchTransaction.execute();
        } catch (final SBonitaException e) {
            throw new SearchException(e);
        }
        return searchTransaction.getResult();
    }

    @Override
    public SearchResult<FlowNodeInstance> searchFlowNodeInstances(final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final SearchFlowNodeInstances searchFlowNodeInstancesTransaction = new SearchFlowNodeInstances(
                activityInstanceService, flowNodeStateManager,
                searchEntitiesDescriptor.getSearchFlowNodeInstanceDescriptor(), searchOptions);
        try {
            searchFlowNodeInstancesTransaction.execute();
        } catch (final SBonitaException e) {
            throw new SearchException(e);
        }
        return searchFlowNodeInstancesTransaction.getResult();
    }

    @Override
    public SearchResult<TimerEventTriggerInstance> searchTimerEventTriggerInstances(final long processInstanceId,
            final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final EventInstanceService eventInstanceService = serviceAccessor.getEventInstanceService();
        final SearchTimerEventTriggerInstances transaction = new SearchTimerEventTriggerInstances(eventInstanceService,
                searchEntitiesDescriptor.getSearchEventTriggerInstanceDescriptor(), processInstanceId, searchOptions);
        try {
            transaction.execute();
        } catch (final SBonitaException e) {
            throw new SearchException(e);
        }
        return transaction.getResult();
    }

    @Override
    public Date updateExecutionDateOfTimerEventTriggerInstance(final long timerEventTriggerInstanceId,
            final Date executionDate)
            throws TimerEventTriggerInstanceNotFoundException, UpdateException {
        if (executionDate == null) {
            throw new UpdateException("The date must be not null !!");
        }
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final EventInstanceService eventInstanceService = serviceAccessor.getEventInstanceService();
        final SchedulerService schedulerService = serviceAccessor.getSchedulerService();

        final EntityUpdateDescriptor descriptor = new EntityUpdateDescriptor();
        descriptor.addField(EXECUTION_DATE, executionDate.getTime());

        try {
            final STimerEventTriggerInstance sTimerEventTriggerInstance = eventInstanceService.getEventTriggerInstance(
                    STimerEventTriggerInstance.class,
                    timerEventTriggerInstanceId);
            if (sTimerEventTriggerInstance == null) {
                throw new TimerEventTriggerInstanceNotFoundException(timerEventTriggerInstanceId);
            }
            eventInstanceService.updateEventTriggerInstance(sTimerEventTriggerInstance, descriptor);
            return schedulerService
                    .rescheduleJob(sTimerEventTriggerInstance.getJobTriggerName(),
                            String.valueOf(getServiceAccessor().getTenantId()), executionDate);
        } catch (final SBonitaException sbe) {
            throw new UpdateException(sbe);
        }
    }

    @Override
    public SearchResult<ArchivedActivityInstance> searchArchivedActivities(final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final SearchArchivedActivityInstances searchActivityInstancesTransaction = new SearchArchivedActivityInstances(
                activityInstanceService,
                flowNodeStateManager, searchEntitiesDescriptor.getSearchArchivedActivityInstanceDescriptor(),
                searchOptions);
        try {
            searchActivityInstancesTransaction.execute();
        } catch (final SBonitaException e) {
            throw new SearchException(e);
        }
        return searchActivityInstancesTransaction.getResult();
    }

    @Override
    public ConnectorImplementationDescriptor getConnectorImplementation(final long processDefinitionId,
            final String connectorId, final String connectorVersion)
            throws ConnectorNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ConnectorService connectorService = serviceAccessor.getConnectorService();
        final GetConnectorImplementation transactionContent = new GetConnectorImplementation(connectorService,
                processDefinitionId, connectorId,
                connectorVersion);
        try {
            transactionContent.execute();
            final SConnectorImplementationDescriptor sConnectorImplementationDescriptor = transactionContent
                    .getResult();
            return ModelConvertor.toConnectorImplementationDescriptor(sConnectorImplementationDescriptor);
        } catch (final SBonitaException e) {
            throw new ConnectorNotFoundException(e);
        }
    }

    @Override
    @CustomTransactions
    public void cancelProcessInstance(final long processInstanceId)
            throws ProcessInstanceNotFoundException, UpdateException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final LockService lockService = serviceAccessor.getLockService();
        final ProcessInstanceInterruptor processInstanceInterruptor = serviceAccessor.getProcessInstanceInterruptor();
        BonitaLock lock = null;
        try {
            // lock process execution
            lock = lockService.lock(processInstanceId, SFlowElementsContainerType.PROCESS.name(),
                    serviceAccessor.getTenantId());
            inTx(() -> {
                try {
                    return processInstanceInterruptor.interruptProcessInstance(processInstanceId,
                            SStateCategory.CANCELLING);
                } catch (final SProcessInstanceNotFoundException spinfe) {
                    throw new ProcessInstanceNotFoundException(processInstanceId);
                } catch (final SBonitaException e) {
                    throw new UpdateException(e);
                }
            });
        } catch (ProcessInstanceNotFoundException | UpdateException e) {
            throw e;
        } catch (Exception e) {
            throw new BonitaRuntimeException(String.format("Failed to cancel process instance %s", processInstanceId),
                    e);
        } finally {
            // unlock process execution
            try {
                lockService.unlock(lock, serviceAccessor.getTenantId());
            } catch (final SLockException e) {
                // ignore it
            }
        }
    }

    @Override
    public void setProcessInstanceState(final ProcessInstance processInstance, final String state)
            throws UpdateException {
        // NOW, is only available for COMPLETED, ABORTED, CANCELLED, STARTED
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        try {
            final ProcessInstanceState processInstanceState = ModelConvertor.getProcessInstanceState(state);
            final SetProcessInstanceState transactionContent = new SetProcessInstanceState(processInstanceService,
                    processInstance.getId(),
                    processInstanceState);
            transactionContent.execute();
        } catch (final IllegalArgumentException | SBonitaException e) {
            throw new UpdateException(e.getMessage());
        }
    }

    @Override
    public Map<Long, ProcessDeploymentInfo> getProcessDeploymentInfosFromProcessInstanceIds(
            final List<Long> processInstanceIds) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();

        try {
            final Map<Long, SProcessDefinitionDeployInfo> sProcessDeploymentInfos = processDefinitionService
                    .getProcessDeploymentInfosFromProcessInstanceIds(processInstanceIds);
            return ModelConvertor.toProcessDeploymentInfos(sProcessDeploymentInfos);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public Map<Long, ProcessDeploymentInfo> getProcessDeploymentInfosFromArchivedProcessInstanceIds(
            final List<Long> archivedProcessInstantsIds) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();

        try {
            final Map<Long, SProcessDefinitionDeployInfo> sProcessDeploymentInfos = processDefinitionService
                    .getProcessDeploymentInfosFromArchivedProcessInstanceIds(archivedProcessInstantsIds);
            return ModelConvertor.toProcessDeploymentInfos(sProcessDeploymentInfos);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public SearchResult<Document> searchDocuments(final SearchOptions searchOptions) throws SearchException {

        return documentAPI.searchDocuments(searchOptions);
    }

    @Override
    public SearchResult<Document> searchDocumentsSupervisedBy(final long userId, final SearchOptions searchOptions)
            throws SearchException,
            UserNotFoundException {
        return documentAPI.searchDocumentsSupervisedBy(userId, searchOptions);
    }

    @Override
    public SearchResult<ArchivedDocument> searchArchivedDocuments(final SearchOptions searchOptions)
            throws SearchException {

        return documentAPI.searchArchivedDocuments(searchOptions);
    }

    @Override
    public SearchResult<ArchivedDocument> searchArchivedDocumentsSupervisedBy(final long userId,
            final SearchOptions searchOptions) throws SearchException,
            UserNotFoundException {
        return documentAPI.searchArchivedDocumentsSupervisedBy(userId, searchOptions);
    }

    @Override
    public void retryTask(final long activityInstanceId)
            throws ActivityExecutionException, ActivityInstanceNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        ConnectorInstanceService connectorInstanceService = serviceAccessor.getConnectorInstanceService();
        ResetAllFailedConnectorStrategy strategy = new ResetAllFailedConnectorStrategy(connectorInstanceService,
                new ConnectorReseter(connectorInstanceService), BATCH_SIZE);
        FlowNodeRetrier flowNodeRetrier = new FlowNodeRetrier(serviceAccessor.getContainerRegistry(),
                serviceAccessor.getFlowNodeExecutor(),
                serviceAccessor.getActivityInstanceService(), serviceAccessor.getFlowNodeStateManager(), strategy);
        flowNodeRetrier.retry(activityInstanceId);
    }

    @Override
    public void executeMessageCouple(final long messageInstanceId, final long waitingMessageId)
            throws ExecutionException {
        MessagesHandlingService messagesHandlingService = getServiceAccessor().getMessagesHandlingService();
        try {
            messagesHandlingService.resetMessageCouple(messageInstanceId, waitingMessageId);
            messagesHandlingService.triggerMatchingOfMessages();
        } catch (SBonitaException e) {
            throw new ExecutionException(
                    "Failed to execute Event Message couple: messageInstanceId=" + messageInstanceId
                            + ", waitingMessageId=" + waitingMessageId,
                    e);
        }
    }

    @Override
    public ArchivedDocument getArchivedVersionOfProcessDocument(final long sourceObjectId)
            throws ArchivedDocumentNotFoundException {
        return documentAPI.getArchivedVersionOfProcessDocument(sourceObjectId);
    }

    @Override
    public ArchivedDocument getArchivedProcessDocument(final long archivedProcessDocumentId)
            throws ArchivedDocumentNotFoundException {
        return documentAPI.getArchivedProcessDocument(archivedProcessDocumentId);
    }

    @Override
    public SearchResult<ArchivedComment> searchArchivedComments(final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SCommentService sCommentService = serviceAccessor.getCommentService();
        final SearchArchivedComments searchArchivedComments = new SearchArchivedComments(sCommentService,
                searchEntitiesDescriptor.getSearchArchivedCommentsDescriptor(), searchOptions);
        try {
            searchArchivedComments.execute();
        } catch (final SBonitaException e) {
            throw new SearchException(e);
        }
        return searchArchivedComments.getResult();
    }

    @Override
    public ArchivedComment getArchivedComment(final long archivedCommentId)
            throws RetrieveException, NotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final SCommentService sCommentService = serviceAccessor.getCommentService();
        try {
            final SAComment archivedComment = sCommentService.getArchivedComment(archivedCommentId);
            return ModelConvertor.toArchivedComment(archivedComment);
        } catch (final SCommentNotFoundException e) {
            throw new NotFoundException(e);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public Map<Long, ActorInstance> getActorsFromActorIds(final List<Long> actorIds) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final Map<Long, ActorInstance> res = new HashMap<>();
        final ActorMappingService actormappingService = serviceAccessor.getActorMappingService();
        final GetActorsByActorIds getActorsByActorIds = new GetActorsByActorIds(actormappingService, actorIds);
        try {
            getActorsByActorIds.execute();
        } catch (final SBonitaException e1) {
            throw new RetrieveException(e1);
        }
        final List<SActor> actors = getActorsByActorIds.getResult();
        for (final SActor actor : actors) {
            res.put(actor.getId(), ModelConvertor.toActorInstance(actor));
        }
        return res;
    }

    @Override
    public Serializable evaluateExpressionOnProcessDefinition(final Expression expression,
            final Map<String, Serializable> context,
            final long processDefinitionId) throws ExpressionEvaluationException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ExpressionResolverService expressionResolverService = serviceAccessor.getExpressionResolverService();

        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SExpression sExpression = ModelConvertor.constructSExpression(expression);
        final SExpressionContext expcontext = new SExpressionContext();
        expcontext.setProcessDefinitionId(processDefinitionId);
        SProcessDefinition processDef;
        try {
            processDef = processDefinitionService.getProcessDefinition(processDefinitionId);
            if (processDef != null) {
                expcontext.setProcessDefinition(processDef);
            }
            final HashMap<String, Object> hashMap = new HashMap<>(context);
            expcontext.setInputValues(hashMap);
            return (Serializable) expressionResolverService.evaluate(sExpression, expcontext);
        } catch (final SExpressionEvaluationException e) {
            throw new ExpressionEvaluationException(e, e.getExpressionName());
        } catch (final SInvalidExpressionException e) {
            throw new ExpressionEvaluationException(e, e.getExpressionName());
        } catch (final SBonitaException e) {
            throw new ExpressionEvaluationException(e, null);
        }
    }

    @Override
    public void updateDueDateOfTask(final long userTaskId, final Date dueDate) throws UpdateException {
        final ActivityInstanceService activityInstanceService = getServiceAccessor().getActivityInstanceService();
        try {
            final SetExpectedEndDate updateProcessInstance = new SetExpectedEndDate(activityInstanceService, userTaskId,
                    dueDate);
            updateProcessInstance.execute();
        } catch (final SBonitaException e) {
            throw new UpdateException(e);
        }
    }

    @Override
    public long countComments(final SearchOptions searchOptions) throws SearchException {
        final SearchOptionsBuilder searchOptionsBuilder = new SearchOptionsBuilder(0, 0)
                .setFilters(searchOptions.getFilters()).searchTerm(
                        searchOptions.getSearchTerm());
        final SearchResult<Comment> searchResult = searchComments(searchOptionsBuilder.done());
        return searchResult.getCount();
    }

    @Override
    public long countAttachments(final SearchOptions searchOptions) throws SearchException {
        final SearchOptionsBuilder searchOptionsBuilder = new SearchOptionsBuilder(0, 0)
                .setFilters(searchOptions.getFilters()).searchTerm(
                        searchOptions.getSearchTerm());
        final SearchResult<Document> searchResult = documentAPI.searchDocuments(searchOptionsBuilder.done());
        return searchResult.getCount();
    }

    @Override
    public void sendSignal(final String signalName) throws SendEventException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final EventsHandler eventsHandler = serviceAccessor.getEventsHandler();
        final SThrowSignalEventTriggerDefinition signalEventTriggerDefinition = BuilderFactory
                .get(SThrowSignalEventTriggerDefinitionBuilderFactory.class)
                .createNewInstance(signalName).done();
        try {
            eventsHandler.handleThrowEvent(signalEventTriggerDefinition);
        } catch (final SBonitaException e) {
            throw new SendEventException(e);
        }
    }

    @Override
    public void sendMessage(final String messageName, final Expression targetProcess, final Expression targetFlowNode,
            final Map<Expression, Expression> messageContent) throws SendEventException {
        sendMessage(messageName, targetProcess, targetFlowNode, messageContent, null);
    }

    @Override
    public void sendMessage(final String messageName, final Expression targetProcess, final Expression targetFlowNode,
            final Map<Expression, Expression> messageContent, final Map<Expression, Expression> correlations)
            throws SendEventException {
        if (correlations != null && correlations.size() > 5) {
            throw new SendEventException("Too many correlations: a message can not have more than 5 correlations.");
        }
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final EventsHandler eventsHandler = serviceAccessor.getEventsHandler();
        final ExpressionResolverService expressionResolverService = serviceAccessor.getExpressionResolverService();

        final SExpression targetProcessNameExp = ModelConvertor.constructSExpression(targetProcess);
        SExpression targetFlowNodeNameExp = null;
        if (targetFlowNode != null) {
            targetFlowNodeNameExp = ModelConvertor.constructSExpression(targetFlowNode);
        }
        final SThrowMessageEventTriggerDefinitionBuilder builder = BuilderFactory
                .get(SThrowMessageEventTriggerDefinitionBuilderFactory.class)
                .createNewInstance(messageName, targetProcessNameExp, targetFlowNodeNameExp);
        if (correlations != null && !correlations.isEmpty()) {
            addMessageCorrelations(builder, correlations);
        }
        try {
            if (messageContent != null && !messageContent.isEmpty()) {
                addMessageContent(builder, expressionResolverService, messageContent);
            }
            final SThrowMessageEventTriggerDefinition messageEventTriggerDefinition = builder.done();
            eventsHandler.handleThrowEvent(messageEventTriggerDefinition);
        } catch (final SBonitaException e) {
            throw new SendEventException(e);
        }

    }

    @Override
    public int deleteMessageByCreationDate(final long creationDate, SearchOptions searchOptions)
            throws ExecutionException {
        try {
            return getServiceAccessor().getEventInstanceService()
                    .deleteMessageAndDataInstanceOlderThanCreationDate(creationDate, buildQueryOptions(searchOptions,
                            getServiceAccessor().getSearchEntitiesDescriptor().getSearchMessageInstanceDescriptor()));
        } catch (SBonitaException e) {
            throw new ExecutionException(e);
        }
    }

    /**
     * Utility method to convert a SearchOptions into a QueryOptions
     */
    private QueryOptions buildQueryOptions(SearchOptions searchOptions,
            SearchEntityDescriptor searchEntityDescriptor) {
        if (searchOptions != null) {
            final List<SearchFilter> filters = searchOptions.getFilters();

            final List<FilterOption> filterOptions = new ArrayList<>(filters.size());
            for (final SearchFilter filter : filters) {
                final FilterOption option = searchEntityDescriptor.getEntityFilter(filter);
                if (option != null) {// in case of a unknown filter on state
                    filterOptions.add(option);
                }
            }

            return new QueryOptions(searchOptions.getStartIndex(), searchOptions.getMaxResults(),
                    Collections.emptyList(), filterOptions, null);
        } else {
            return QueryOptions.ALL_RESULTS;
        }
    }

    private void addMessageContent(
            final SThrowMessageEventTriggerDefinitionBuilder messageEventTriggerDefinitionBuilder,
            final ExpressionResolverService expressionResolverService, final Map<Expression, Expression> messageContent)
            throws SBonitaException {
        for (final Entry<Expression, Expression> entry : messageContent.entrySet()) {
            expressionResolverService.evaluate(ModelConvertor.constructSExpression(entry.getKey()));
            final SDataDefinitionBuilder dataDefinitionBuilder = BuilderFactory.get(SDataDefinitionBuilderFactory.class)
                    .createNewInstance(
                            entry.getKey().getContent(), entry.getValue().getReturnType());
            dataDefinitionBuilder.setDefaultValue(ModelConvertor.constructSExpression(entry.getValue()));
            messageEventTriggerDefinitionBuilder.addData(dataDefinitionBuilder.done());
        }

    }

    private void addMessageCorrelations(
            final SThrowMessageEventTriggerDefinitionBuilder messageEventTriggerDefinitionBuilder,
            final Map<Expression, Expression> messageCorrelations) {
        for (final Entry<Expression, Expression> entry : messageCorrelations.entrySet()) {
            messageEventTriggerDefinitionBuilder.addCorrelation(ModelConvertor.constructSExpression(entry.getKey()),
                    ModelConvertor.constructSExpression(entry.getValue()));
        }
    }

    @Override
    public List<Problem> getProcessResolutionProblems(final long processDefinitionId)
            throws ProcessDefinitionNotFoundException {
        return processDeploymentAPIDelegate.getProcessResolutionProblems(processDefinitionId);
    }

    @Override
    public List<ProcessDeploymentInfo> getProcessDeploymentInfos(final int startIndex, final int maxResults,
            final ProcessDeploymentInfoCriterion pagingCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();

        final SearchProcessDefinitionsDescriptor processDefinitionsDescriptor = serviceAccessor
                .getSearchEntitiesDescriptor()
                .getSearchProcessDefinitionsDescriptor();
        final GetProcessDefinitionDeployInfos transactionContentWithResult = new GetProcessDefinitionDeployInfos(
                processDefinitionService,
                processDefinitionsDescriptor, startIndex, maxResults, pagingCriterion);
        try {
            transactionContentWithResult.execute();
            return transactionContentWithResult.getResult();
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public List<ProcessDeploymentInfo> getProcessDeploymentInfosWithActorOnlyForGroup(final long groupId,
            final int startIndex, final int maxResults,
            final ProcessDeploymentInfoCriterion sortingCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();

        final SearchProcessDefinitionsDescriptor processDefinitionsDescriptor = serviceAccessor
                .getSearchEntitiesDescriptor()
                .getSearchProcessDefinitionsDescriptor();
        final GetProcessDefinitionDeployInfosWithActorOnlyForGroup transactionContentWithResult = new GetProcessDefinitionDeployInfosWithActorOnlyForGroup(
                processDefinitionService, processDefinitionsDescriptor, startIndex, maxResults, sortingCriterion,
                groupId);
        try {
            transactionContentWithResult.execute();
            return transactionContentWithResult.getResult();
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public List<ProcessDeploymentInfo> getProcessDeploymentInfosWithActorOnlyForGroups(final List<Long> groupIds,
            final int startIndex, final int maxResults,
            final ProcessDeploymentInfoCriterion sortingCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();

        final SearchProcessDefinitionsDescriptor processDefinitionsDescriptor = serviceAccessor
                .getSearchEntitiesDescriptor()
                .getSearchProcessDefinitionsDescriptor();
        final GetProcessDefinitionDeployInfosWithActorOnlyForGroups transactionContentWithResult = new GetProcessDefinitionDeployInfosWithActorOnlyForGroups(
                processDefinitionService, processDefinitionsDescriptor, startIndex, maxResults, sortingCriterion,
                groupIds);
        try {
            transactionContentWithResult.execute();
            return transactionContentWithResult.getResult();
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public List<ProcessDeploymentInfo> getProcessDeploymentInfosWithActorOnlyForRole(final long roleId,
            final int startIndex, final int maxResults,
            final ProcessDeploymentInfoCriterion sortingCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();

        final SearchProcessDefinitionsDescriptor processDefinitionsDescriptor = serviceAccessor
                .getSearchEntitiesDescriptor()
                .getSearchProcessDefinitionsDescriptor();
        final GetProcessDefinitionDeployInfosWithActorOnlyForRole transactionContentWithResult = new GetProcessDefinitionDeployInfosWithActorOnlyForRole(
                processDefinitionService, processDefinitionsDescriptor, startIndex, maxResults, sortingCriterion,
                roleId);
        try {

            transactionContentWithResult.execute();
            return transactionContentWithResult.getResult();
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public List<ProcessDeploymentInfo> getProcessDeploymentInfosWithActorOnlyForRoles(final List<Long> roleIds,
            final int startIndex, final int maxResults,
            final ProcessDeploymentInfoCriterion sortingCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();

        final SearchProcessDefinitionsDescriptor processDefinitionsDescriptor = serviceAccessor
                .getSearchEntitiesDescriptor()
                .getSearchProcessDefinitionsDescriptor();
        final GetProcessDefinitionDeployInfosWithActorOnlyForRoles transactionContentWithResult = new GetProcessDefinitionDeployInfosWithActorOnlyForRoles(
                processDefinitionService, processDefinitionsDescriptor, startIndex, maxResults, sortingCriterion,
                roleIds);
        try {
            transactionContentWithResult.execute();
            return transactionContentWithResult.getResult();
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public List<ProcessDeploymentInfo> getProcessDeploymentInfosWithActorOnlyForUser(final long userId,
            final int startIndex, final int maxResults,
            final ProcessDeploymentInfoCriterion sortingCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchProcessDefinitionsDescriptor processDefinitionsDescriptor = serviceAccessor
                .getSearchEntitiesDescriptor()
                .getSearchProcessDefinitionsDescriptor();
        final GetProcessDefinitionDeployInfosWithActorOnlyForUser transactionContentWithResult = new GetProcessDefinitionDeployInfosWithActorOnlyForUser(
                processDefinitionService, processDefinitionsDescriptor, startIndex, maxResults, sortingCriterion,
                userId);
        try {
            transactionContentWithResult.execute();
            return transactionContentWithResult.getResult();
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public List<ProcessDeploymentInfo> getProcessDeploymentInfosWithActorOnlyForUsers(final List<Long> userIds,
            final int startIndex, final int maxResults,
            final ProcessDeploymentInfoCriterion sortingCriterion) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchProcessDefinitionsDescriptor processDefinitionsDescriptor = serviceAccessor
                .getSearchEntitiesDescriptor()
                .getSearchProcessDefinitionsDescriptor();
        final GetProcessDefinitionDeployInfosWithActorOnlyForUsers transactionContentWithResult = new GetProcessDefinitionDeployInfosWithActorOnlyForUsers(
                processDefinitionService, processDefinitionsDescriptor, startIndex, maxResults, sortingCriterion,
                userIds);
        try {
            transactionContentWithResult.execute();
            return transactionContentWithResult.getResult();
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public SearchResult<ConnectorInstance> searchConnectorInstances(final SearchOptions searchOptions)
            throws RetrieveException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ConnectorInstanceService connectorInstanceService = serviceAccessor.getConnectorInstanceService();
        if (searchOptions.getSorts().isEmpty()) {
            searchOptions.getSorts().add(new Sort(Order.ASC, ConnectorInstancesSearchDescriptor.EXECUTION_ORDER));
        }
        try {
            return search(searchEntitiesDescriptor.getSearchConnectorInstanceDescriptor(),
                    searchOptions,
                    ModelConvertor::toConnectorInstances,
                    connectorInstanceService::getNumberOfConnectorInstances,
                    connectorInstanceService::searchConnectorInstances);
        } catch (SearchException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public SearchResult<ArchivedConnectorInstance> searchArchivedConnectorInstances(final SearchOptions searchOptions)
            throws RetrieveException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ConnectorInstanceService connectorInstanceService = serviceAccessor.getConnectorInstanceService();
        final ArchiveService archiveService = serviceAccessor.getArchiveService();
        final ReadPersistenceService persistenceService = archiveService.getDefinitiveArchiveReadPersistenceService();
        final SearchArchivedConnectorInstance searchArchivedConnectorInstance = new SearchArchivedConnectorInstance(
                connectorInstanceService,
                searchEntitiesDescriptor.getSearchArchivedConnectorInstanceDescriptor(), searchOptions,
                persistenceService);
        try {
            searchArchivedConnectorInstance.execute();
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
        return searchArchivedConnectorInstance.getResult();
    }

    @Override
    public List<HumanTaskInstance> getHumanTaskInstances(final long rootProcessInstanceId, final String taskName,
            final int startIndex, final int maxResults) {
        try {
            final List<HumanTaskInstance> humanTaskInstances = getHumanTaskInstances(rootProcessInstanceId, taskName,
                    startIndex, maxResults,
                    HumanTaskInstanceSearchDescriptor.PROCESS_INSTANCE_ID, Order.ASC);
            return Objects.requireNonNullElse(humanTaskInstances, Collections.emptyList());
        } catch (final SearchException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public HumanTaskInstance getLastStateHumanTaskInstance(final long rootProcessInstanceId, final String taskName)
            throws NotFoundException {
        try {
            final List<HumanTaskInstance> humanTaskInstances = getHumanTaskInstances(rootProcessInstanceId, taskName, 0,
                    1,
                    HumanTaskInstanceSearchDescriptor.REACHED_STATE_DATE, Order.DESC);
            if (humanTaskInstances == null || humanTaskInstances.isEmpty()) {
                throw new NotFoundException("Task '" + taskName + "' not found");
            }
            return humanTaskInstances.get(0);
        } catch (final SearchException e) {
            throw new RetrieveException(e);
        }
    }

    private List<HumanTaskInstance> getHumanTaskInstances(final long processInstanceId, final String taskName,
            final int startIndex, final int maxResults,
            final String field, final Order order) throws SearchException {
        final SearchOptionsBuilder builder = new SearchOptionsBuilder(startIndex, maxResults);
        builder.filter(HumanTaskInstanceSearchDescriptor.PROCESS_INSTANCE_ID, processInstanceId)
                .filter(HumanTaskInstanceSearchDescriptor.NAME, taskName);
        builder.sort(field, order);
        final SearchResult<HumanTaskInstance> searchHumanTasks = searchHumanTaskInstances(builder.done());
        return searchHumanTasks.getResult();
    }

    @Override
    public SearchResult<User> searchUsersWhoCanStartProcessDefinition(final long processDefinitionId,
            final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();

        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchUserDescriptor searchDescriptor = searchEntitiesDescriptor.getSearchUserDescriptor();
        final SearchUsersWhoCanStartProcessDeploymentInfo transactionSearch = new SearchUsersWhoCanStartProcessDeploymentInfo(
                processDefinitionService,
                searchDescriptor, processDefinitionId, searchOptions);
        try {
            transactionSearch.execute();
        } catch (final SBonitaException e) {
            throw new SearchException(e);
        }
        return transactionSearch.getResult();
    }

    @Override
    public Map<String, Serializable> evaluateExpressionsAtProcessInstanciation(final long processInstanceId,
            final Map<Expression, Map<String, Serializable>> expressions) throws ExpressionEvaluationException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        try {
            try {
                final SProcessInstance processInstance = processInstanceService.getProcessInstance(processInstanceId);
                // if it exists and is initializing or started
                final int stateId = processInstance.getStateId();
                if (stateId == 0/* initializing */ || stateId == 1/* started */) {
                    // the evaluation date is either now (initializing) or the start date if available
                    final long evaluationDate = stateId == 0 ? System.currentTimeMillis()
                            : processInstance.getStartDate();
                    return evaluateExpressionsInstanceLevelAndArchived(expressions, processInstanceId,
                            CONTAINER_TYPE_PROCESS_INSTANCE,
                            processInstance.getProcessDefinitionId(), evaluationDate);
                }
            } catch (final SProcessInstanceNotFoundException spinfe) {
                // get it in the archive
            }
            final ArchivedProcessInstance archiveProcessInstance = getStartedArchivedProcessInstance(processInstanceId);
            return evaluateExpressionsInstanceLevelAndArchived(expressions, processInstanceId,
                    CONTAINER_TYPE_PROCESS_INSTANCE, archiveProcessInstance.getProcessDefinitionId(),
                    archiveProcessInstance.getStartDate().getTime());
        } catch (final SExpressionEvaluationException e) {
            throw new ExpressionEvaluationException(e, e.getExpressionName());
        } catch (final SInvalidExpressionException e) {
            throw new ExpressionEvaluationException(e, e.getExpressionName());
        } catch (final SBonitaException e) {
            throw new ExpressionEvaluationException(e, null);
        }
    }

    @Override
    public Map<String, Serializable> evaluateExpressionOnCompletedProcessInstance(final long processInstanceId,
            final Map<Expression, Map<String, Serializable>> expressions) throws ExpressionEvaluationException {
        try {
            final ArchivedProcessInstance lastArchivedProcessInstance = getLastArchivedProcessInstance(
                    processInstanceId);
            return evaluateExpressionsInstanceLevelAndArchived(expressions, processInstanceId,
                    CONTAINER_TYPE_PROCESS_INSTANCE,
                    lastArchivedProcessInstance.getProcessDefinitionId(),
                    lastArchivedProcessInstance.getArchiveDate().getTime());
        } catch (final SExpressionEvaluationException e) {
            throw new ExpressionEvaluationException(e, e.getExpressionName());
        } catch (final SInvalidExpressionException e) {
            throw new ExpressionEvaluationException(e, e.getExpressionName());
        } catch (final SBonitaException e) {
            throw new ExpressionEvaluationException(e, null);
        }
    }

    @Override
    public Map<String, Serializable> evaluateExpressionsOnProcessInstance(final long processInstanceId,
            final Map<Expression, Map<String, Serializable>> expressions) throws ExpressionEvaluationException {
        try {
            final SProcessInstance sProcessInstance = getSProcessInstance(processInstanceId);
            return evaluateExpressionsInstanceLevel(expressions, processInstanceId, CONTAINER_TYPE_PROCESS_INSTANCE,
                    sProcessInstance.getProcessDefinitionId());
        } catch (final SExpressionEvaluationException e) {
            throw new ExpressionEvaluationException(e, e.getExpressionName());
        } catch (final SInvalidExpressionException e) {
            throw new ExpressionEvaluationException(e, e.getExpressionName());
        } catch (final SBonitaException e) {
            throw new ExpressionEvaluationException(e, null);
        }
    }

    @Override
    public Map<String, Serializable> evaluateExpressionsOnProcessDefinition(final long processDefinitionId,
            final Map<Expression, Map<String, Serializable>> expressions) throws ExpressionEvaluationException {
        try {
            return evaluateExpressionsDefinitionLevel(expressions, processDefinitionId);
        } catch (final SExpressionEvaluationException e) {
            throw new ExpressionEvaluationException(e, e.getExpressionName());
        } catch (final SInvalidExpressionException e) {
            throw new ExpressionEvaluationException(e, e.getExpressionName());
        } catch (final SBonitaException e) {
            throw new ExpressionEvaluationException(e, null);
        }
    }

    @Override
    public Map<String, Serializable> evaluateExpressionsOnActivityInstance(final long activityInstanceId,
            final Map<Expression, Map<String, Serializable>> expressions) throws ExpressionEvaluationException {
        try {
            final SActivityInstance sActivityInstance = getSActivityInstance(activityInstanceId);
            final SProcessInstance sProcessInstance = getSProcessInstance(
                    sActivityInstance.getParentProcessInstanceId());
            return evaluateExpressionsInstanceLevel(expressions, activityInstanceId, CONTAINER_TYPE_ACTIVITY_INSTANCE,
                    sProcessInstance.getProcessDefinitionId());
        } catch (final SExpressionEvaluationException e) {
            throw new ExpressionEvaluationException(e, e.getExpressionName());
        } catch (final SInvalidExpressionException e) {
            throw new ExpressionEvaluationException(e, e.getExpressionName());
        } catch (final SBonitaException e) {
            throw new ExpressionEvaluationException(e, null);
        }
    }

    @Override
    public Map<String, Serializable> evaluateExpressionsOnCompletedActivityInstance(final long activityInstanceId,
            final Map<Expression, Map<String, Serializable>> expressions) throws ExpressionEvaluationException {
        try {
            final ArchivedActivityInstance activityInstance = getArchivedActivityInstance(activityInstanceId);
            // same archive time to process even if there're many activities in the process
            final ArchivedProcessInstance lastArchivedProcessInstance = getLastArchivedProcessInstance(
                    activityInstance.getProcessInstanceId());

            return evaluateExpressionsInstanceLevelAndArchived(expressions, activityInstanceId,
                    CONTAINER_TYPE_ACTIVITY_INSTANCE,
                    lastArchivedProcessInstance.getProcessDefinitionId(), activityInstance.getArchiveDate().getTime());
        } catch (final SExpressionEvaluationException e) {
            throw new ExpressionEvaluationException(e, e.getExpressionName());
        } catch (final SInvalidExpressionException e) {
            throw new ExpressionEvaluationException(e, e.getExpressionName());
        } catch (final ActivityInstanceNotFoundException | SBonitaException e) {
            throw new ExpressionEvaluationException(e, null);
        }
    }

    private Map<String, Serializable> evaluateExpressionsDefinitionLevel(
            final Map<Expression, Map<String, Serializable>> expressionsAndTheirPartialContext,
            final long processDefinitionId) throws SBonitaException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ExpressionResolverService expressionResolverService = serviceAccessor.getExpressionResolverService();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        final EvaluateExpressionsDefinitionLevel evaluations = createDefinitionLevelExpressionEvaluator(
                expressionsAndTheirPartialContext, processDefinitionId,
                expressionResolverService, processDefinitionService);
        evaluations.execute();
        return evaluations.getResult();
    }

    protected EvaluateExpressionsDefinitionLevel createDefinitionLevelExpressionEvaluator(
            final Map<Expression, Map<String, Serializable>> expressionsAndTheirPartialContext,
            final long processDefinitionId,
            final ExpressionResolverService expressionResolverService,
            final ProcessDefinitionService processDefinitionService) {
        return new EvaluateExpressionsDefinitionLevel(expressionsAndTheirPartialContext, processDefinitionId,
                expressionResolverService, processDefinitionService, getServiceAccessor().getBusinessDataRepository());
    }

    private Map<String, Serializable> evaluateExpressionsInstanceLevel(
            final Map<Expression, Map<String, Serializable>> expressions, final long containerId,
            final String containerType, final long processDefinitionId) throws SBonitaException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ExpressionResolverService expressionService = serviceAccessor.getExpressionResolverService();

        final EvaluateExpressionsInstanceLevel evaluations = createInstanceLevelExpressionEvaluator(expressions,
                containerId, containerType,
                processDefinitionId, expressionService);
        evaluations.execute();
        return evaluations.getResult();
    }

    protected EvaluateExpressionsInstanceLevel createInstanceLevelExpressionEvaluator(
            final Map<Expression, Map<String, Serializable>> expressions,
            final long containerId, final String containerType, final long processDefinitionId,
            final ExpressionResolverService expressionService) {
        return new EvaluateExpressionsInstanceLevel(expressions, containerId, containerType, processDefinitionId,
                expressionService, getServiceAccessor().getBusinessDataRepository());
    }

    private Map<String, Serializable> evaluateExpressionsInstanceLevelAndArchived(
            final Map<Expression, Map<String, Serializable>> expressions,
            final long containerId, final String containerType, final long processDefinitionId, final long time)
            throws SBonitaException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ExpressionResolverService expressionService = serviceAccessor.getExpressionResolverService();
        final EvaluateExpressionsInstanceLevelAndArchived evaluations = createInstanceAndArchivedLevelExpressionEvaluator(
                expressions, containerId,
                containerType, processDefinitionId, time, expressionService);
        evaluations.execute();
        return evaluations.getResult();
    }

    protected EvaluateExpressionsInstanceLevelAndArchived createInstanceAndArchivedLevelExpressionEvaluator(
            final Map<Expression, Map<String, Serializable>> expressions, final long containerId,
            final String containerType, final long processDefinitionId,
            final long time, final ExpressionResolverService expressionService) {
        return new EvaluateExpressionsInstanceLevelAndArchived(expressions, containerId, containerType,
                processDefinitionId, time, expressionService,
                getServiceAccessor().getBusinessDataRepository());
    }

    private ArchivedProcessInstance getStartedArchivedProcessInstance(final long processInstanceId)
            throws SBonitaException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();

        final SearchOptionsBuilder searchOptionsBuilder = new SearchOptionsBuilder(0, 2);
        searchOptionsBuilder.sort(ArchivedProcessInstancesSearchDescriptor.ARCHIVE_DATE, Order.ASC);
        searchOptionsBuilder.filter(ArchivedProcessInstancesSearchDescriptor.SOURCE_OBJECT_ID, processInstanceId);
        searchOptionsBuilder.filter(ArchivedProcessInstancesSearchDescriptor.STATE_ID,
                ProcessInstanceState.STARTED.getId());
        final SearchArchivedProcessInstances searchArchivedProcessInstances = new SearchArchivedProcessInstances(
                processInstanceService,
                serviceAccessor.getProcessDefinitionService(),
                searchEntitiesDescriptor.getSearchArchivedProcessInstanceDescriptor(),
                searchOptionsBuilder.done());
        searchArchivedProcessInstances.execute();

        try {
            return searchArchivedProcessInstances.getResult().getResult().get(0);
        } catch (final IndexOutOfBoundsException e) {
            throw new SAProcessInstanceNotFoundException(processInstanceId, ProcessInstanceState.STARTED.name());
        }

    }

    protected ArchivedProcessInstance getLastArchivedProcessInstance(final long processInstanceId)
            throws SBonitaException {
        return processInvolvementDelegate.getLastArchivedProcessInstance(processInstanceId);
    }

    @Override
    public List<FailedJob> getFailedJobs(final int startIndex, final int maxResults) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final JobService jobService = serviceAccessor.getJobService();
        try {
            final List<SFailedJob> failedJobs = jobService.getFailedJobs(startIndex, maxResults);
            return ModelConvertor.toFailedJobs(failedJobs);
        } catch (final SSchedulerException sse) {
            throw new RetrieveException(sse);
        }
    }

    @Override
    public void replayFailedJob(final long jobDescriptorId) throws ExecutionException {
        replayFailedJob(jobDescriptorId, null);
    }

    @Override
    public void replayFailedJob(final long jobDescriptorId, final Map<String, Serializable> parameters)
            throws ExecutionException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SchedulerService schedulerService = serviceAccessor.getSchedulerService();
        try {
            if (parameters == null || parameters.isEmpty()) {
                schedulerService.retryJobThatFailed(jobDescriptorId);
            } else {
                final List<SJobParameter> jobParameters = buildJobParametersFromMap(parameters);
                schedulerService.retryJobThatFailed(jobDescriptorId, jobParameters);
            }
        } catch (final SSchedulerException sse) {
            throw new ExecutionException(sse);
        }
    }

    protected List<SJobParameter> buildJobParametersFromMap(final Map<String, Serializable> parameters) {
        final List<SJobParameter> jobParameters = new ArrayList<>();
        for (final Entry<String, Serializable> parameter : parameters.entrySet()) {
            jobParameters.add(buildSJobParameter(parameter.getKey(), parameter.getValue()));
        }
        return jobParameters;
    }

    protected SJobParameter buildSJobParameter(final String parameterKey, final Serializable parameterValue) {
        return SJobParameter.builder()
                .key(parameterKey)
                .value(parameterValue).build();
    }

    @Override
    public ArchivedDataInstance getArchivedProcessDataInstance(final String dataName,
            final long sourceProcessInstanceId) throws ArchivedDataNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ParentContainerResolver parentContainerResolver = serviceAccessor.getParentContainerResolver();
        final DataInstanceService dataInstanceService = serviceAccessor.getDataInstanceService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final SAProcessInstance lastArchivedProcessInstance = processInstanceService
                    .getLastArchivedProcessInstance(sourceProcessInstanceId);
            if (lastArchivedProcessInstance == null) {
                throw new ArchivedDataNotFoundException(
                        "Archived process instance not found: " + sourceProcessInstanceId);
            }
            final long processDefinitionId = lastArchivedProcessInstance.getProcessDefinitionId();
            final ClassLoader processClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, processDefinitionId));
            Thread.currentThread().setContextClassLoader(processClassLoader);
            final SADataInstance dataInstance = dataInstanceService.getLastSADataInstance(dataName,
                    sourceProcessInstanceId,
                    DataInstanceContainer.PROCESS_INSTANCE.toString(), parentContainerResolver);
            return ModelConvertor.toArchivedDataInstance(dataInstance);
        } catch (final SDataInstanceException e) {
            throw new ArchivedDataNotFoundException(e);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Override
    public ArchivedDataInstance getArchivedActivityDataInstance(final String dataName,
            final long sourceActivityInstanceId)
            throws ArchivedDataNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final DataInstanceService dataInstanceService = serviceAccessor.getDataInstanceService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ParentContainerResolver parentContainerResolver = serviceAccessor.getParentContainerResolver();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final int processDefinitionIndex = BuilderFactory.get(SAutomaticTaskInstanceBuilderFactory.class)
                .getProcessDefinitionIndex();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final long parentProcessInstanceId = activityInstanceService
                    .getLastArchivedFlowNodeInstance(SAFlowNodeInstance.class, sourceActivityInstanceId)
                    .getLogicalGroup(processDefinitionIndex);
            final ClassLoader processClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, parentProcessInstanceId));
            Thread.currentThread().setContextClassLoader(processClassLoader);
            final SADataInstance dataInstance = dataInstanceService.getLastSADataInstance(dataName,
                    sourceActivityInstanceId,
                    DataInstanceContainer.ACTIVITY_INSTANCE.toString(), parentContainerResolver);
            return ModelConvertor.toArchivedDataInstance(dataInstance);
        } catch (final SDataInstanceException e) {
            throw new ArchivedDataNotFoundException(e);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Override
    public List<ArchivedDataInstance> getArchivedProcessDataInstances(final long sourceProcessInstanceId,
            final int startIndex, final int maxResults) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final DataInstanceService dataInstanceService = serviceAccessor.getDataInstanceService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final SAProcessInstance lastArchivedProcessInstance = processInstanceService
                    .getLastArchivedProcessInstance(sourceProcessInstanceId);
            if (lastArchivedProcessInstance == null) {
                throw new RetrieveException("Archived process instance not found: " + sourceProcessInstanceId);
            }
            final long processDefinitionId = lastArchivedProcessInstance.getProcessDefinitionId();
            final ClassLoader processClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, processDefinitionId));
            Thread.currentThread().setContextClassLoader(processClassLoader);
            final List<SADataInstance> dataInstances = dataInstanceService.getLastLocalSADataInstances(
                    sourceProcessInstanceId,
                    DataInstanceContainer.PROCESS_INSTANCE.toString(), startIndex, maxResults);
            return ModelConvertor.toArchivedDataInstances(dataInstances);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Override
    public List<ArchivedDataInstance> getArchivedActivityDataInstances(final long sourceActivityInstanceId,
            final int startIndex, final int maxResults) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final DataInstanceService dataInstanceService = serviceAccessor.getDataInstanceService();
        final ClassLoaderService classLoaderService = serviceAccessor.getClassLoaderService();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final int processDefinitionIndex = BuilderFactory.get(SAutomaticTaskInstanceBuilderFactory.class)
                .getProcessDefinitionIndex();
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            final long parentProcessInstanceId = activityInstanceService
                    .getLastArchivedFlowNodeInstance(SAFlowNodeInstance.class, sourceActivityInstanceId)
                    .getLogicalGroup(processDefinitionIndex);
            final ClassLoader processClassLoader = classLoaderService.getClassLoader(
                    identifier(ScopeType.PROCESS, parentProcessInstanceId));
            Thread.currentThread().setContextClassLoader(processClassLoader);

            final List<SADataInstance> dataInstances = dataInstanceService.getLastLocalSADataInstances(
                    sourceActivityInstanceId,
                    DataInstanceContainer.ACTIVITY_INSTANCE.toString(), startIndex, maxResults);
            return ModelConvertor.toArchivedDataInstances(dataInstances);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Override
    public List<User> getPossibleUsersOfPendingHumanTask(final long humanTaskInstanceId, final int startIndex,
            final int maxResults) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        try {
            // pagination of this method is based on order by username:
            final List<Long> userIds = activityInstanceService.getPossibleUserIdsOfPendingTasks(humanTaskInstanceId,
                    startIndex, maxResults);
            final IdentityService identityService = getServiceAccessor().getIdentityService();
            // This method below is also ordered by username, so the order is preserved:
            final List<SUser> sUsers = identityService.getUsers(userIds);
            return ModelConvertor.toUsers(sUsers);
        } catch (final SBonitaException sbe) {
            throw new RetrieveException(sbe);
        }
    }

    @Override
    public List<User> getPossibleUsersOfHumanTask(final long processDefinitionId, final String humanTaskName,
            final int startIndex, final int maxResults) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        try {
            final SProcessDefinition processDefinition = processDefinitionService
                    .getProcessDefinition(processDefinitionId);
            final SFlowNodeDefinition flowNode = processDefinition.getProcessContainer().getFlowNode(humanTaskName);
            if (!(flowNode instanceof SHumanTaskDefinition)) {
                return Collections.emptyList();
            }
            final SHumanTaskDefinition humanTask = (SHumanTaskDefinition) flowNode;
            final String actorName = humanTask.getActorName();
            final List<Long> userIds = getUserIdsForActor(serviceAccessor, processDefinitionId, actorName, startIndex,
                    maxResults);
            final List<SUser> users = serviceAccessor.getIdentityService().getUsers(userIds);
            return ModelConvertor.toUsers(users);
        } catch (final SProcessDefinitionNotFoundException spdnfe) {
            return Collections.emptyList();
        } catch (final SBonitaException sbe) {
            throw new RetrieveException(sbe);
        }
    }

    private List<Long> getUserIdsForActor(final ServiceAccessor serviceAccessor, final long processDefinitionId,
            final String actorName,
            final int startIndex, final int maxResults) throws SActorNotFoundException, SBonitaReadException {
        final ActorMappingService actorMappingService = serviceAccessor.getActorMappingService();
        final SActor actor = actorMappingService.getActor(actorName, processDefinitionId);
        return actorMappingService.getPossibleUserIdsOfActorId(actor.getId(), startIndex, maxResults);
    }

    @Override
    public List<Long> getUserIdsForActor(final long processDefinitionId, final String actorName, final int startIndex,
            final int maxResults) {
        try {
            return getUserIdsForActor(getServiceAccessor(), processDefinitionId, actorName, startIndex, maxResults);
        } catch (final SBonitaException e) {
            throw new RetrieveException(e);
        }
    }

    @Override
    public SearchResult<User> searchUsersWhoCanExecutePendingHumanTask(final long humanTaskInstanceId,
            final SearchOptions searchOptions) {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final SearchUserDescriptor searchDescriptor = searchEntitiesDescriptor.getSearchUserDescriptor();
        final SearchUsersWhoCanExecutePendingHumanTaskDeploymentInfo searcher = new SearchUsersWhoCanExecutePendingHumanTaskDeploymentInfo(
                humanTaskInstanceId, activityInstanceService, searchDescriptor, searchOptions);
        try {
            searcher.execute();
        } catch (final SBonitaException sbe) {
            throw new RetrieveException(sbe);
        }
        return searcher.getResult();
    }

    @Override
    public SearchResult<HumanTaskInstance> searchAssignedAndPendingHumanTasksFor(final long rootProcessDefinitionId,
            final long userId,
            final SearchOptions searchOptions) throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final SearchHumanTaskInstanceDescriptor searchDescriptor = searchEntitiesDescriptor
                .getSearchHumanTaskInstanceDescriptor();
        return AbstractHumanTaskInstanceSearchEntity.searchHumanTaskInstance(searchDescriptor,
                searchOptions,
                flowNodeStateManager,
                (queryOptions) -> activityInstanceService
                        .getNumberOfAssignedAndPendingHumanTasksFor(rootProcessDefinitionId, userId, queryOptions),
                (queryOptions) -> activityInstanceService.searchAssignedAndPendingHumanTasksFor(rootProcessDefinitionId,
                        userId, queryOptions))
                .search();
    }

    @Override
    public SearchResult<HumanTaskInstance> searchAssignedAndPendingHumanTasks(final long rootProcessDefinitionId,
            final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final SearchHumanTaskInstanceDescriptor searchDescriptor = searchEntitiesDescriptor
                .getSearchHumanTaskInstanceDescriptor();
        return AbstractHumanTaskInstanceSearchEntity.searchHumanTaskInstance(searchDescriptor,
                searchOptions,
                flowNodeStateManager,
                (queryOptions) -> activityInstanceService
                        .getNumberOfAssignedAndPendingHumanTasks(rootProcessDefinitionId, queryOptions),
                (queryOptions) -> activityInstanceService.searchAssignedAndPendingHumanTasks(rootProcessDefinitionId,
                        queryOptions))
                .search();
    }

    @Override
    public SearchResult<HumanTaskInstance> searchAssignedAndPendingHumanTasks(final SearchOptions searchOptions)
            throws SearchException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final SearchEntitiesDescriptor searchEntitiesDescriptor = serviceAccessor.getSearchEntitiesDescriptor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        final FlowNodeStateManager flowNodeStateManager = serviceAccessor.getFlowNodeStateManager();
        final SearchHumanTaskInstanceDescriptor searchDescriptor = searchEntitiesDescriptor
                .getSearchHumanTaskInstanceDescriptor();
        return AbstractHumanTaskInstanceSearchEntity.searchHumanTaskInstance(searchDescriptor,
                searchOptions,
                flowNodeStateManager,
                activityInstanceService::getNumberOfAssignedAndPendingHumanTasks,
                activityInstanceService::searchAssignedAndPendingHumanTasks).search();
    }

    @Override
    public ContractDefinition getUserTaskContract(final long userTaskId) throws UserTaskNotFoundException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        try {
            final SHumanTaskInstance taskInstance = activityInstanceService.getHumanTaskInstance(userTaskId);
            if (!(taskInstance instanceof SUserTaskInstance)) {
                throw new UserTaskNotFoundException("Impossible to find a user task with id: " + userTaskId);
            }
            final SProcessDefinition processDefinition = getServiceAccessor().getProcessDefinitionService()
                    .getProcessDefinition(
                            taskInstance.getProcessDefinitionId());
            final SUserTaskDefinition userTask = (SUserTaskDefinition) processDefinition.getProcessContainer()
                    .getFlowNode(
                            taskInstance.getFlowNodeDefinitionId());
            return ModelConvertor.toContract(userTask.getContract());
        } catch (final SActivityInstanceNotFoundException | SProcessDefinitionNotFoundException | SActivityReadException
                | SBonitaReadException e) {
            throw new UserTaskNotFoundException(e.getMessage());
        }
    }

    @Override
    public ContractDefinition getProcessContract(final long processDefinitionId)
            throws ProcessDefinitionNotFoundException {
        try {
            final SProcessDefinition processDefinition = getServiceAccessor().getProcessDefinitionService()
                    .getProcessDefinition(processDefinitionId);
            return ModelConvertor.toContract(processDefinition.getContract());
        } catch (final SProcessDefinitionNotFoundException | SBonitaReadException e) {
            throw new ProcessDefinitionNotFoundException(e.getMessage());
        }
    }

    @Override
    @CustomTransactions
    public void executeUserTask(final long flownodeInstanceId, final Map<String, Serializable> inputs)
            throws FlowNodeExecutionException,
            ContractViolationException,
            UserTaskNotFoundException {
        executeUserTask(0, flownodeInstanceId, inputs);
    }

    @Override
    @CustomTransactions
    public void executeUserTask(final long userId, final long flownodeInstanceId,
            final Map<String, Serializable> inputs) throws FlowNodeExecutionException,
            ContractViolationException, UserTaskNotFoundException {
        try {
            inTx(() -> {
                executeFlowNode(userId, flownodeInstanceId, inputs, true);
                return null;
            });
        } catch (final ContractViolationException e) {
            throw e;
        } catch (final SFlowNodeNotFoundException e) {
            throw new UserTaskNotFoundException(
                    String.format("User task %s is not found, it might already be executed", flownodeInstanceId));
        } catch (final Exception e) {
            verifyIfTheActivityWasInTheCorrectStateAndThrowException(flownodeInstanceId, e);
        }
    }

    private void verifyIfTheActivityWasInTheCorrectStateAndThrowException(long flownodeInstanceId, Exception e)
            throws UserTaskNotFoundException, FlowNodeExecutionException {
        SFlowNodeInstance flowNodeInstance;
        try {
            flowNodeInstance = inTx(
                    () -> getServiceAccessor().getActivityInstanceService().getFlowNodeInstance(flownodeInstanceId));
        } catch (SActivityInstanceNotFoundException e1) {
            throw new UserTaskNotFoundException(
                    String.format("User task %s is not found, it might already be executed", flownodeInstanceId));
        } catch (Exception e1) {
            throw new FlowNodeExecutionException(e);
        }
        if (flowNodeInstance.getStateId() != FlowNodeState.ID_ACTIVITY_READY || flowNodeInstance.isStateExecuting()) {
            //this in a not found because that task was not visible anymore
            throw new UserTaskNotFoundException(
                    String.format(
                            "User task is not executable (currently in state '%s'), this might be because someone else already executed it.",
                            (flowNodeInstance.isStateExecuting() ? "executing " : "")
                                    + flowNodeInstance.getStateName()));
        }
        throw new FlowNodeExecutionException(e);
    }

    private <T> T inTx(Callable<T> booleanCallable) throws Exception {
        return getServiceAccessor().getUserTransactionService().executeInTransaction(booleanCallable);
    }

    private void checkIsHumanTaskInReadyState(SFlowNodeInstance flowNodeInstance) throws SFlowNodeExecutionException {
        if (!(flowNodeInstance instanceof SHumanTaskInstance)) {
            throw new SFlowNodeExecutionException(
                    "Unable to execute flownode " + flowNodeInstance.getId() + " because is not a user task");
        }
        if (flowNodeInstance.getStateId() != FlowNodeState.ID_ACTIVITY_READY || flowNodeInstance.isStateExecuting()) {
            throw new SFlowNodeExecutionException(
                    "Unable to execute flow node " + flowNodeInstance.getId()
                            + " because it is in an incompatible state ("
                            + (flowNodeInstance.isStateExecuting() ? "transitioning from state " : "on state ")
                            + flowNodeInstance.getStateName() + "). Someone probably already called execute on it.");
        }
    }

    /**
     * Execute a flow node. All methods that executes flow nodes and human tasks uses this one.
     *
     * @param userId the id of the user executing the task
     * @param shouldBeReadyTask if true the method will only accept to execute human task in ready state
     */
    protected void executeFlowNode(final long userId, final long flowNodeInstanceId,
            final Map<String, Serializable> inputs, boolean shouldBeReadyTask)
            throws ContractViolationException, SBonitaException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        ActivityInstanceService activityInstanceService = serviceAccessor.getActivityInstanceService();
        ContractDataService contractDataService = serviceAccessor.getContractDataService();
        IdentityService identityService = serviceAccessor.getIdentityService();
        SCommentService commentService = serviceAccessor.getCommentService();
        WorkService workService = serviceAccessor.getWorkService();
        BPMWorkFactory workFactory = serviceAccessor.getBPMWorkFactory();

        SFlowNodeInstance flowNodeInstance = activityInstanceService.getFlowNodeInstance(flowNodeInstanceId);
        if (shouldBeReadyTask) {
            /*
             * this is to protect from concurrent execution of the task when 2 users call execute user task at the same
             * time
             * it still might have concurrency issue but:
             * - if the second client call execute with contract inputs, on commit there will be a constraint violation
             * + rollback
             * - if there is no contract input, the work will check that the activity is in ready state before calling
             * execute.
             * The only left issue is that on this last case the executor will change to the last one.
             */
            checkIsHumanTaskInReadyState(flowNodeInstance);
        }
        if (flowNodeInstance instanceof SUserTaskInstance) {
            try {
                throwContractViolationExceptionIfContractIsInvalid(inputs, serviceAccessor, flowNodeInstance);
            } catch (SContractViolationException e) {
                throw new ContractViolationException(e.getSimpleMessage(), e.getMessage(), e.getExplanations(),
                        e.getCause());
            }
        }
        if (flowNodeInstance instanceof SHumanTaskInstance
                && ((SHumanTaskInstance) flowNodeInstance).getAssigneeId() <= 0) {
            throw new SFlowNodeExecutionException("The user task " + flowNodeInstanceId + " is not assigned");
        }
        final SSession session = getSession();
        if (session != null) {
            final long executerSubstituteUserId = session.getUserId();
            final long executerUserId;
            if (userId == 0) {
                executerUserId = executerSubstituteUserId;
            } else {
                executerUserId = userId;
            }
            final boolean isFirstState = flowNodeInstance.getStateId() == 0;

            if (flowNodeInstance instanceof SUserTaskInstance) {
                contractDataService.addUserTaskData(flowNodeInstance.getId(), inputs);
            }
            // TODO: the following 4 instructions seem to be redundant with stepForward:
            // Cannot we factorize this?
            serviceAccessor.getBPMArchiverService().archiveFlowNodeInstance(flowNodeInstance);
            // flag as executing
            activityInstanceService.setExecuting(flowNodeInstance);
            activityInstanceService.setExecutedBy(flowNodeInstance, executerUserId);
            activityInstanceService.setExecutedBySubstitute(flowNodeInstance, executerSubstituteUserId);
            WorkDescriptor work = workFactory.createExecuteFlowNodeWorkDescriptor(flowNodeInstance);
            workService.registerWork(work);
            if (log.isInfoEnabled() && !isFirstState /*
                                                      * don't log when create
                                                      * subtask
                                                      */) {
                final String message = LogMessageBuilder.buildExecuteTaskContextMessage(flowNodeInstance,
                        session.getUserName(), executerUserId,
                        executerSubstituteUserId, inputs);
                log.info(message);
            } else if (log.isDebugEnabled()) {
                log.debug("Executing state " + flowNodeInstance.getStateName() + " (" + flowNodeInstance.getStateId()
                        + ") for flownode " + LogMessageBuilder.buildFlowNodeContextMessage(flowNodeInstance));
            }
            if (executerUserId != executerSubstituteUserId) {
                try {
                    final SUser executorUser = identityService.getUser(executerUserId);
                    String stb = "The user " + session.getUserName() + " " + "acting as delegate of the user "
                            + executorUser.getUserName() + " "
                            + "has done the task \"" + flowNodeInstance.getDisplayName() + "\".";
                    commentService.addSystemComment(flowNodeInstance.getParentProcessInstanceId(), stb);
                } catch (final SBonitaException e) {
                    log.error(
                            "Error when adding a comment on the process instance.", e);
                }
            }
        }
    }

    private void throwContractViolationExceptionIfContractIsInvalid(final Map<String, Serializable> inputs,
            final ServiceAccessor serviceAccessor, final SFlowNodeInstance flowNodeInstance)
            throws SBonitaReadException, SProcessDefinitionNotFoundException, SContractViolationException {
        final SProcessDefinition processDefinition = serviceAccessor.getProcessDefinitionService()
                .getProcessDefinition(flowNodeInstance.getProcessDefinitionId());
        final SUserTaskDefinition userTaskDefinition = (SUserTaskDefinition) processDefinition.getProcessContainer()
                .getFlowNode(
                        flowNodeInstance.getFlowNodeDefinitionId());
        final SContractDefinition contractDefinition = userTaskDefinition.getContract();
        final ContractValidator validator = new ContractValidatorFactory()
                .createContractValidator(serviceAccessor.getExpressionService());
        validator.validate(flowNodeInstance.getProcessDefinitionId(), contractDefinition, inputs);

    }

    @Override
    public Document removeDocument(final long documentId) throws DocumentNotFoundException, DeletionException {
        return documentAPI.removeDocument(documentId);
    }

    @Override
    public List<Document> getDocumentList(final long processInstanceId, final String name, final int from,
            final int numberOfResult)
            throws DocumentNotFoundException {
        return documentAPI.getDocumentList(processInstanceId, name, from, numberOfResult);
    }

    @Override
    public void setDocumentList(final long processInstanceId, final String name,
            final List<DocumentValue> documentsValues) throws DocumentException,
            DocumentNotFoundException {
        documentAPI.setDocumentList(processInstanceId, name, documentsValues);
    }

    @Override
    public void deleteContentOfArchivedDocument(final long archivedDocumentId)
            throws DocumentException, DocumentNotFoundException {
        documentAPI.deleteContentOfArchivedDocument(archivedDocumentId);
    }

    protected ServiceAccessor getServiceAccessor() {
        return ServiceAccessorSingleton.getInstance();
    }

    long getUserId() {
        return APIUtils.getUserId();
    }

    @Override
    public Document addDocument(final long processInstanceId, final String documentName, final String description,
            final DocumentValue documentValue)
            throws ProcessInstanceNotFoundException, DocumentAttachmentException, AlreadyExistsException {
        return documentAPI.addDocument(processInstanceId, documentName, description, documentValue);
    }

    @Override
    public Document updateDocument(final long documentId, final DocumentValue documentValue)
            throws ProcessInstanceNotFoundException,
            DocumentAttachmentException,
            AlreadyExistsException {
        return documentAPI.updateDocument(documentId, documentValue);
    }

    @Override
    public void purgeClassLoader(final long processDefinitionId)
            throws ProcessDefinitionNotFoundException, UpdateException {
        processManagementAPIImplDelegate.purgeClassLoader(processDefinitionId);
    }

    @Override
    public Serializable getUserTaskContractVariableValue(final long userTaskInstanceId, final String name)
            throws ContractDataNotFoundException {
        final ContractDataService contractDataService = getServiceAccessor().getContractDataService();
        try {
            return contractDataService.getArchivedUserTaskDataValue(userTaskInstanceId, name);
        } catch (final SContractDataNotFoundException scdnfe) {
            throw new ContractDataNotFoundException(scdnfe);
        } catch (final SBonitaReadException sbe) {
            throw new RetrieveException(sbe);
        }
    }

    @Override
    public Serializable getProcessInputValueDuringInitialization(long processInstanceId, String name)
            throws ContractDataNotFoundException {
        try {
            return getServiceAccessor().getContractDataService().getProcessDataValue(processInstanceId, name);
        } catch (SContractDataNotFoundException | SBonitaReadException e) {
            throw new ContractDataNotFoundException(e);
        }
    }

    @Override
    public Serializable getProcessInputValueAfterInitialization(long processInstanceId, String name)
            throws ContractDataNotFoundException {
        try {
            return getServiceAccessor().getContractDataService().getArchivedProcessDataValue(processInstanceId, name);
        } catch (SContractDataNotFoundException | SBonitaReadException e) {
            throw new ContractDataNotFoundException(e);
        }
    }

    @Override
    public int getNumberOfParameterInstances(final long processDefinitionId) {
        return processManagementAPIImplDelegate.getNumberOfParameterInstances(processDefinitionId);
    }

    @Override
    public ParameterInstance getParameterInstance(final long processDefinitionId, final String parameterName)
            throws NotFoundException {
        return processManagementAPIImplDelegate.getParameterInstance(processDefinitionId, parameterName);
    }

    @Override
    public List<ParameterInstance> getParameterInstances(final long processDefinitionId, final int startIndex,
            final int maxResults,
            final ParameterCriterion sort) {
        return processManagementAPIImplDelegate.getParameterInstances(processDefinitionId, startIndex, maxResults,
                sort);
    }

    @Override
    public Map<String, Serializable> getUserTaskExecutionContext(long userTaskInstanceId)
            throws UserTaskNotFoundException, ExpressionEvaluationException {
        ServiceAccessor serviceAccessor = getServiceAccessor();
        try {
            SFlowNodeInstance activityInstance = serviceAccessor.getActivityInstanceService()
                    .getFlowNodeInstance(userTaskInstanceId);
            SProcessDefinition processDefinition = serviceAccessor.getProcessDefinitionService()
                    .getProcessDefinition(activityInstance.getProcessDefinitionId());
            final SExpressionContext expressionContext = createExpressionContext(userTaskInstanceId, processDefinition,
                    CONTAINER_TYPE_ACTIVITY_INSTANCE, null);
            SFlowNodeDefinition flowNode = processDefinition.getProcessContainer()
                    .getFlowNode(activityInstance.getFlowNodeDefinitionId());
            return evaluateContext(serviceAccessor.getExpressionResolverService(), expressionContext,
                    ((SUserTaskDefinition) flowNode).getContext());
        } catch (SFlowNodeNotFoundException | SBonitaReadException | SFlowNodeReadException
                | SProcessDefinitionNotFoundException e) {
            throw new UserTaskNotFoundException(e);
        } catch (SInvalidExpressionException | SExpressionEvaluationException | SExpressionDependencyMissingException
                | SExpressionTypeUnknownException e) {
            throw new ExpressionEvaluationException(e);
        }
    }

    @Override
    public Map<String, Serializable> getArchivedUserTaskExecutionContext(long archivedUserTaskInstanceId)
            throws UserTaskNotFoundException,
            ExpressionEvaluationException {
        ServiceAccessor serviceAccessor = getServiceAccessor();
        try {
            SAFlowNodeInstance archivedActivityInstance = serviceAccessor.getActivityInstanceService()
                    .getArchivedFlowNodeInstance(archivedUserTaskInstanceId);
            SProcessDefinition processDefinition = serviceAccessor.getProcessDefinitionService()
                    .getProcessDefinition(archivedActivityInstance.getProcessDefinitionId());
            final SExpressionContext expressionContext = createExpressionContext(
                    archivedActivityInstance.getSourceObjectId(), processDefinition,
                    CONTAINER_TYPE_ACTIVITY_INSTANCE, archivedActivityInstance.getArchiveDate());
            SFlowNodeDefinition flowNode = processDefinition.getProcessContainer()
                    .getFlowNode(archivedActivityInstance.getFlowNodeDefinitionId());
            return evaluateContext(serviceAccessor.getExpressionResolverService(), expressionContext,
                    ((SUserTaskDefinition) flowNode).getContext());
        } catch (SFlowNodeNotFoundException | SBonitaReadException | SFlowNodeReadException
                | SProcessDefinitionNotFoundException e) {
            throw new UserTaskNotFoundException(e);
        } catch (SInvalidExpressionException | SExpressionEvaluationException | SExpressionDependencyMissingException
                | SExpressionTypeUnknownException e) {
            throw new ExpressionEvaluationException(e);
        }
    }

    @Override
    public Map<String, Serializable> getProcessInstanceExecutionContext(long processInstanceId)
            throws ProcessInstanceNotFoundException,
            ExpressionEvaluationException {
        ServiceAccessor serviceAccessor = getServiceAccessor();
        try {
            SProcessInstance processInstance = getProcessInstanceService(serviceAccessor)
                    .getProcessInstance(processInstanceId);
            if (processInstance == null) {
                throw new ProcessInstanceNotFoundException("Process Instance not found " + processInstanceId);
            }
            SProcessDefinition processDefinition = serviceAccessor.getProcessDefinitionService()
                    .getProcessDefinition(processInstance.getProcessDefinitionId());
            final SExpressionContext expressionContext = createExpressionContext(processInstanceId, processDefinition,
                    CONTAINER_TYPE_PROCESS_INSTANCE, null);
            return evaluateContext(serviceAccessor.getExpressionResolverService(), expressionContext,
                    processDefinition.getContext());
        } catch (SProcessInstanceNotFoundException | SBonitaReadException | SProcessInstanceReadException
                | SProcessDefinitionNotFoundException e) {
            throw new ProcessInstanceNotFoundException(e);
        } catch (SInvalidExpressionException | SExpressionEvaluationException | SExpressionDependencyMissingException
                | SExpressionTypeUnknownException e) {
            throw new ExpressionEvaluationException(e);
        }
    }

    @Override
    public Map<String, Serializable> getArchivedProcessInstanceExecutionContext(long archivedProcessInstanceId)
            throws ProcessInstanceNotFoundException,
            ExpressionEvaluationException {
        ServiceAccessor serviceAccessor = getServiceAccessor();
        try {
            SAProcessInstance processInstance = getProcessInstanceService(serviceAccessor)
                    .getArchivedProcessInstance(archivedProcessInstanceId);
            if (processInstance == null) {
                throw new ProcessInstanceNotFoundException(
                        "Archived Process Instance not found " + archivedProcessInstanceId);
            }
            SProcessDefinition processDefinition = serviceAccessor.getProcessDefinitionService()
                    .getProcessDefinition(processInstance.getProcessDefinitionId());
            final SExpressionContext expressionContext = createExpressionContext(processInstance.getSourceObjectId(),
                    processDefinition,
                    CONTAINER_TYPE_PROCESS_INSTANCE, processInstance.getArchiveDate());
            return evaluateContext(serviceAccessor.getExpressionResolverService(), expressionContext,
                    processDefinition.getContext());
        } catch (SBonitaReadException | SProcessInstanceReadException | SProcessDefinitionNotFoundException e) {
            throw new ProcessInstanceNotFoundException(e);
        } catch (SInvalidExpressionException | SExpressionEvaluationException | SExpressionDependencyMissingException
                | SExpressionTypeUnknownException e) {
            throw new ExpressionEvaluationException(e);
        }
    }

    Map<String, Serializable> evaluateContext(ExpressionResolverService expressionResolverService,
            SExpressionContext expressionContext,
            List<SContextEntry> context) throws SExpressionTypeUnknownException, SExpressionEvaluationException,
            SExpressionDependencyMissingException,
            SInvalidExpressionException {
        if (context.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SExpression> expressions = toExpressionList(context);
        List<Object> evaluate = expressionResolverService.evaluate(expressions, expressionContext);
        return toResultMap(context, evaluate);
    }

    List<SExpression> toExpressionList(List<SContextEntry> context) {
        List<SExpression> expressions = new ArrayList<>();
        for (SContextEntry sContextEntry : context) {
            expressions.add(sContextEntry.getExpression());
        }
        return expressions;
    }

    HashMap<String, Serializable> toResultMap(List<SContextEntry> context, List<Object> evaluate) {
        HashMap<String, Serializable> result = new HashMap<>(evaluate.size());
        for (int i = 0; i < evaluate.size(); i++) {
            result.put(context.get(i).getKey(), (Serializable) evaluate.get(i));
        }
        return result;
    }

    ProcessInstanceService getProcessInstanceService(ServiceAccessor serviceAccessor) {
        return serviceAccessor.getProcessInstanceService();
    }

    SExpressionContext createExpressionContext(long processInstanceId, SProcessDefinition processDefinition,
            String type, Long time) {
        final SExpressionContext expressionContext = new SExpressionContext();
        expressionContext.setContainerId(processInstanceId);
        expressionContext.setContainerType(type);
        expressionContext.setProcessDefinitionId(processDefinition.getId());
        if (time != null) {
            expressionContext.setTime(time);
        }
        return expressionContext;
    }

    @Override
    public SearchResult<FormMapping> searchFormMappings(SearchOptions searchOptions) throws SearchException {
        return processConfigurationAPI.searchFormMappings(searchOptions);
    }

    @Override
    public FormMapping getFormMapping(long formMappingId) throws FormMappingNotFoundException {
        return processConfigurationAPI.getFormMapping(formMappingId);
    }

    @Override
    public ProcessInstance updateProcessInstance(final long processInstanceId, final ProcessInstanceUpdater updater)
            throws ProcessInstanceNotFoundException, UpdateException {
        if (updater == null || updater.getFields().isEmpty()) {
            throw new UpdateException("The update descriptor does not contain field updates");
        }
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        try {
            final SProcessInstance processInstance = processInstanceService.getProcessInstance(processInstanceId);
            EntityUpdateDescriptor entityUpdateDescriptor = new EntityUpdateDescriptor();
            final Map<org.bonitasoft.engine.bpm.process.impl.ProcessInstanceUpdater.ProcessInstanceField, Serializable> fields = updater
                    .getFields();
            for (final Entry<org.bonitasoft.engine.bpm.process.impl.ProcessInstanceUpdater.ProcessInstanceField, Serializable> field : fields
                    .entrySet()) {
                entityUpdateDescriptor.addField(SProcessInstance.STRING_INDEX_KEY + (field.getKey().ordinal() + 1),
                        field.getValue());
            }
            processInstanceService.updateProcess(processInstance, entityUpdateDescriptor);
            return getProcessInstance(processInstanceId);
        } catch (final SProcessInstanceNotFoundException spinfe) {
            throw new ProcessInstanceNotFoundException(spinfe);
        } catch (final SBonitaException | RetrieveException sbe) {
            throw new UpdateException(sbe);
        }
    }

    @Override
    public ProcessInstance updateProcessInstanceIndex(final long processInstanceId, final Index index,
            final String value) throws ProcessInstanceNotFoundException, UpdateException {
        final ServiceAccessor serviceAccessor = getServiceAccessor();
        final ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        try {
            final SProcessInstance processInstance = processInstanceService.getProcessInstance(processInstanceId);
            EntityUpdateDescriptor entityUpdateDescriptor = new EntityUpdateDescriptor();
            entityUpdateDescriptor.addField(SProcessInstance.STRING_INDEX_KEY + (index.ordinal() + 1), value);
            processInstanceService.updateProcess(processInstance, entityUpdateDescriptor);
            return getProcessInstance(processInstanceId);
        } catch (final SProcessInstanceNotFoundException notFound) {
            throw new ProcessInstanceNotFoundException(notFound);
        } catch (final SBonitaException | RetrieveException sbe) {
            throw new UpdateException(sbe);
        }
    }

}
