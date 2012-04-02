/**
 * Licensed to the Austrian Association for Software Tool Integration (AASTI)
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The AASTI licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openengsb.opencit.core.projectmanager.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.openengsb.core.api.ConnectorValidationFailedException;
import org.openengsb.core.api.context.Context;
import org.openengsb.core.api.context.ContextCurrentService;
import org.openengsb.core.api.context.ContextHolder;
import org.openengsb.core.api.model.ConnectorId;
import org.openengsb.core.api.persistence.PersistenceException;
import org.openengsb.core.api.persistence.PersistenceManager;
import org.openengsb.core.api.persistence.PersistenceService;
import org.openengsb.core.api.workflow.WorkflowService;
import org.openengsb.core.common.OpenEngSBCoreServices;
import org.openengsb.core.common.util.ModelUtils;
import org.openengsb.domain.notification.Notification;
import org.openengsb.domain.report.ReportDomain;
import org.openengsb.opencit.core.projectmanager.NoSuchProjectException;
import org.openengsb.opencit.core.projectmanager.ProjectAlreadyExistsException;
import org.openengsb.opencit.core.projectmanager.ProjectManager;
import org.openengsb.opencit.core.projectmanager.model.ConnectorConfig;
import org.openengsb.opencit.core.projectmanager.model.Project;
import org.openengsb.opencit.core.projectmanager.model.Project.State;
import org.openengsb.opencit.core.projectmanager.util.ConnectorUtil;
import org.osgi.framework.BundleContext;

public class ProjectManagerImpl implements ProjectManager {

    private PersistenceManager persistenceManager;

    private PersistenceService persistence;

    private ContextCurrentService contextService;

    private BundleContext bundleContext;

    private ConnectorUtil connectorUtil;

    private WorkflowService workflowService;

    private HashMap<String, Integer> buildCounts = new HashMap<String, Integer>();

    public void init() {
        persistence = persistenceManager.getPersistenceForBundle(bundleContext.getBundle());
    }

    @Override
    public void createProject(Project project) 
        throws ProjectAlreadyExistsException, ConnectorValidationFailedException {
        checkId(project.getId());
        try {
            persistence.create(project);
            setupProject(project);
        } catch (PersistenceException e) {
            throw new RuntimeException(e);
        }
    }

    private void createConnectors(Project project) throws ConnectorValidationFailedException {
        Map<String, ConnectorConfig> connectorConfigs = project.getConnectorConfigs();

        /* Mainly for the tests */
        if (connectorConfigs == null) {
            return;
        }

        for (Entry<String, ConnectorConfig> e : connectorConfigs.entrySet()) {
            String domain = e.getKey();
            ConnectorConfig cfg = e.getValue();
            ConnectorId id = getConnectorUtil().createConnector(project, domain, cfg.getConnector(),
                cfg.getAttributeValues());
            project.addService(domain, id);
        }
    }

    private void setupProject(Project project) throws ConnectorValidationFailedException {
        createAndSetContext(project);
        createConnectors(project);
        setDefaultConnectors(project);
    }

    private void createAndSetContext(Project project) {
        try {
            contextService.createContext(project.getId());
        } catch (IllegalArgumentException iae) {
            // ignore - means that context already exists
        }
        ContextHolder.get().setCurrentContextId(project.getId());
    }

    private void setDefaultConnectors(Project project) {
        Map<String, ConnectorId> services = project.getServices();
        if (services == null) {
            return;
        }
        String oldCtx = ContextHolder.get().getCurrentContextId();
        ContextHolder.get().setCurrentContextId(project.getId());
        Context context = contextService.getContext();

        for (Entry<String, ConnectorId> entry : services.entrySet()) {
            String domain = entry.getKey();
            String id = entry.getValue().getInstanceId();
            context.put(domain, id);
        }
        context.put("AuditingDomain", "auditing");

        ContextHolder.get().setCurrentContextId(oldCtx);
    }

    private void checkId(String id) throws ProjectAlreadyExistsException {
        List<Project> projects = persistence.query(new Project(id));
        if (!projects.isEmpty()) {
            throw new ProjectAlreadyExistsException("Project with id '" + id + "' already exists.");
        }
    }

    @Override
    public List<Project> getAllProjects() {
        return persistence.query(new Project(null));
    }

    @Override
    public Project getProject(String projectId) throws NoSuchProjectException {
        List<Project> projects = persistence.query(new Project(projectId));
        if (projects.isEmpty()) {
            throw new NoSuchProjectException("No project with id '" + projectId + "' found.");
        }
        return projects.get(0);
    }

    @Override
    public void updateProject(Project project) throws NoSuchProjectException {
        getProject(project.getId());
        try {
            persistence.update(new Project(project.getId()), project);
            setDefaultConnectors(project);
        } catch (PersistenceException e) {
            throw new RuntimeException("Could not update project", e);
        }
    }

    @Override
    public void updateCurrentContextProjectState(State state) throws NoSuchProjectException {
        String projectId = contextService.getContext().getId();
        Project project = getProject(projectId);
        project.setState(state);
        updateProject(project);
    }

    @Override
    public Project getCurrentContextProject() throws NoSuchProjectException {
        String projectId = ContextHolder.get().getCurrentContextId();
        return getProject(projectId);
    }

    @Override
    public void deleteProject(String projectId) throws NoSuchProjectException {
        Project project = getProject(projectId);
        ReportDomain reportDomain;

        reportDomain = OpenEngSBCoreServices.getWiringService().getDomainEndpoint(ReportDomain.class, "report");
        reportDomain.removeCategory(projectId);
        try {
            persistence.delete(project);
        } catch (PersistenceException e) {
            throw new RuntimeException("Could not delete project " + projectId, e);
        }
    }

    public void setPersistenceManager(PersistenceManager persistenceManager) {
        this.persistenceManager = persistenceManager;
    }

    public void setBundleContext(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    public void setContextService(ContextCurrentService contextService) {
        this.contextService = contextService;
    }

    public void setConnectorUtil(ConnectorUtil connectorUtil) {
        this.connectorUtil = connectorUtil;
    }

    public ConnectorUtil getConnectorUtil() {
        return connectorUtil;
    }

    @Override
    public Notification createNotification() {
        return ModelUtils.createEmptyModelObject(Notification.class);
    }

    @Override
    public synchronized void endProjectBuild(Project project) {
        Integer count;
        count = buildCounts.get(project.getId());
        count--;
        if (count.equals(new Integer(0))) {
            buildCounts.remove(project.getId());
        } else {
            buildCounts.put(project.getId(), count);
        }
    }

    @Override
    public synchronized boolean isProjectBuilding(Project project) {
        return buildCounts.containsKey(project.getId());
    }

    @Override
    public synchronized void startProjectBuild(Project project) {
        Integer count;
        count = buildCounts.get(project.getId());
        if (count == null) {
            count = new Integer(0);
        }
        count++;
        buildCounts.put(project.getId(), count);
    }

    @Override
    public void buildProject(Project project) {
        String oldctx = ContextHolder.get().getCurrentContextId();
        if (!oldctx.equals(project.getId())) {
            ContextHolder.get().setCurrentContextId(project.getId());
        }

        workflowService.startFlow("ci");

        if (!oldctx.equals(project.getId())) {
            ContextHolder.get().setCurrentContextId(oldctx);
        }
    }

    public void setWorkflowService(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }
}
