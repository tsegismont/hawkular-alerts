/*
 * Copyright 2015-2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.alerts.engine.impl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.AccessTimeout;
import javax.ejb.EJB;
import javax.ejb.Local;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.enterprise.concurrent.ManagedExecutorService;

import org.hawkular.alerts.api.json.JsonImport.FullAction;
import org.hawkular.alerts.api.json.JsonImport.FullTrigger;
import org.hawkular.alerts.api.json.JsonUtil;
import org.hawkular.alerts.api.model.Severity;
import org.hawkular.alerts.api.model.action.ActionDefinition;
import org.hawkular.alerts.api.model.condition.AvailabilityCondition;
import org.hawkular.alerts.api.model.condition.CompareCondition;
import org.hawkular.alerts.api.model.condition.Condition;
import org.hawkular.alerts.api.model.condition.EventCondition;
import org.hawkular.alerts.api.model.condition.ExternalCondition;
import org.hawkular.alerts.api.model.condition.RateCondition;
import org.hawkular.alerts.api.model.condition.StringCondition;
import org.hawkular.alerts.api.model.condition.ThresholdCondition;
import org.hawkular.alerts.api.model.condition.ThresholdRangeCondition;
import org.hawkular.alerts.api.model.dampening.Dampening;
import org.hawkular.alerts.api.model.data.Data;
import org.hawkular.alerts.api.model.event.EventType;
import org.hawkular.alerts.api.model.paging.Order;
import org.hawkular.alerts.api.model.paging.Page;
import org.hawkular.alerts.api.model.paging.Pager;
import org.hawkular.alerts.api.model.paging.TriggerComparator;
import org.hawkular.alerts.api.model.trigger.Match;
import org.hawkular.alerts.api.model.trigger.Mode;
import org.hawkular.alerts.api.model.trigger.Trigger;
import org.hawkular.alerts.api.model.trigger.TriggerAction;
import org.hawkular.alerts.api.model.trigger.TriggerType;
import org.hawkular.alerts.api.services.DefinitionsEvent;
import org.hawkular.alerts.api.services.DefinitionsEvent.Type;
import org.hawkular.alerts.api.services.DefinitionsListener;
import org.hawkular.alerts.api.services.DefinitionsService;
import org.hawkular.alerts.api.services.TriggersCriteria;
import org.hawkular.alerts.engine.exception.NotFoundApplicationException;
import org.hawkular.alerts.engine.log.MsgLogger;
import org.hawkular.alerts.engine.service.AlertsEngine;
import org.jboss.logging.Logger;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.util.concurrent.Futures;

/**
 * A Cassandra implementation of {@link org.hawkular.alerts.api.services.DefinitionsService}.
 *
 * @author Lucas Ponce
 */
@Local(DefinitionsService.class)
@Stateless
@TransactionAttribute(value = TransactionAttributeType.NOT_SUPPORTED)
public class CassDefinitionsServiceImpl implements DefinitionsService {
    /**
     * Used on distributed environments.
     * If present, the initial data are not loaded on this node.
     */
    public static final String SKIP_INIT_DATA = "hawkular-alerts.skip-init-data";
    private static final String JBOSS_DATA_DIR = "jboss.server.data.dir";
    private static final String INIT_FOLDER = "hawkular-alerts";
    private final MsgLogger msgLog = MsgLogger.LOGGER;
    private final Logger log = Logger.getLogger(CassDefinitionsServiceImpl.class);

    @EJB
    AlertsEngine alertsEngine;

    @EJB
    AlertsContext alertsContext;

    @Resource
    private ManagedExecutorService executor;

    public CassDefinitionsServiceImpl() {
    }

    public void setAlertsEngine(AlertsEngine alertsEngine) {
        this.alertsEngine = alertsEngine;
    }

    public void setAlertsContext(AlertsContext alertsContext) {
        this.alertsContext = alertsContext;
    }

    public void setExecutor(ManagedExecutorService executor) {
        this.executor = executor;
    }

    @PostConstruct
    public void init() {
        if (!alertsContext.isInitialized()) {
            executor.submit(() -> {
                try {
                    initialData();
                } catch (IOException e) {
                    msgLog.errorProcessInitialData(e.getMessage());
                }
            });
        }
    }

    private void initialData() throws IOException {
        if (!System.getProperties().containsKey(SKIP_INIT_DATA)) {
            String data = System.getProperty(JBOSS_DATA_DIR);
            if (data == null || data.isEmpty()) {
                msgLog.errorFolderNotFound(data);
                return;
            }
            String folder = data + "/" + INIT_FOLDER;
            initFiles(folder);
        }
        alertsContext.setInitialized(true);
    }

    private void initFiles(String folder) {
        if (folder == null) {
            msgLog.errorFolderMustBeNotNull();
            return;
        }

        File fFolder = new File(folder);
        if (!fFolder.exists()) {
            log.debug("Data folder doesn't exits. Skipping initialization.");
            return;
        }

        File fAlerts = new File(fFolder, "alerts-data.json");
        if (!fAlerts.exists()) {
            if (log.isDebugEnabled()) {
                log.debug(fAlerts.getAbsolutePath() + " doesn't exits. Skipping initialization.");
            }
            return;
        }

        try {
            AlertsImportManager importManager = new AlertsImportManager(fAlerts);
            for (FullTrigger fTrigger : importManager.getFullTriggers()) {
                Trigger trigger = fTrigger.getTrigger();
                addTrigger(trigger);
                for (Dampening dampening : fTrigger.getDampenings()) {
                    addDampening(dampening);
                }
                for (Condition condition : fTrigger.getConditions()) {
                    initCondition(condition);
                }
            }
            for (FullAction fAction : importManager.getFullActions()) {
                ActionDefinition actionDefinition = new ActionDefinition(fAction.getTenantId(),
                        fAction.getActionPlugin(), fAction.getActionId(), fAction.getProperties());
                addActionDefinition(actionDefinition.getTenantId(), actionDefinition);
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                e.printStackTrace();
            }
            msgLog.errorDatabaseException("Error initializing files. Msg: " + e);
        }

    }

    private void initCondition(Condition condition) throws Exception {
        Collection<Condition> conditions = getTriggerConditions(condition.getTenantId(), condition.getTriggerId(),
                condition.getTriggerMode());
        conditions.add(condition);
        setConditions(condition.getTenantId(), condition.getTriggerId(), condition.getTriggerMode(), conditions);
    }

    @Override
    public void addActionDefinition(String tenantId, ActionDefinition actionDefinition)
            throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (actionDefinition == null) {
            throw new IllegalArgumentException("ActionDefinition must be not null");
        }
        actionDefinition.setTenantId(tenantId);
        if (isEmpty(actionDefinition.getActionPlugin())) {
            throw new IllegalArgumentException("ActionPlugin must be not null");
        }
        if (isEmpty(actionDefinition.getActionId())) {
            throw new IllegalArgumentException("ActionId must be not null");
        }
        if (isEmpty(actionDefinition.getProperties())) {
            throw new IllegalArgumentException("Properties must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement insertAction = CassStatement.get(session, CassStatement.INSERT_ACTION_DEFINITION);
        if (insertAction == null) {
            throw new RuntimeException("insertAction PreparedStatement is null");
        }

        try {
            session.execute(insertAction.bind(tenantId, actionDefinition.getActionPlugin(),
                    actionDefinition.getActionId(), JsonUtil.toJson(actionDefinition)));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public void addTrigger(String tenantId, Trigger trigger) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(trigger)) {
            throw new IllegalArgumentException("Trigger must be not null");
        }

        checkTenantId(tenantId, trigger);
        trigger.setType(TriggerType.STANDARD);

        addTrigger(trigger);
    }

    @Override
    public void addGroupTrigger(String tenantId, Trigger groupTrigger) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(groupTrigger)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }

        checkTenantId(tenantId, groupTrigger);
        if (!groupTrigger.isGroup()) {
            groupTrigger.setType(TriggerType.GROUP);
        }

        addTrigger(groupTrigger);
    }

    private void addTrigger(Trigger trigger) throws Exception {
        Session session = CassCluster.getSession();
        PreparedStatement insertTrigger = CassStatement.get(session, CassStatement.INSERT_TRIGGER);
        if (insertTrigger == null) {
            throw new RuntimeException("insertTrigger PreparedStatement is null");
        }

        try {
            session.execute(insertTrigger.bind(trigger.getTenantId(), trigger.getId(), trigger.isAutoDisable(),
                    trigger.isAutoEnable(), trigger.isAutoResolve(), trigger.isAutoResolveAlerts(),
                    trigger.getAutoResolveMatch().name(), trigger.getContext(), trigger.getDescription(),
                    trigger.isEnabled(), trigger.getEventCategory(), trigger.getEventText(), trigger.getEventType(),
                    trigger.getFiringMatch().name(), trigger.getMemberOf(), trigger.getName(),
                    trigger.getSeverity().name(), trigger.getSource(), trigger.getTags(), trigger.getType().name()));

            insertTriggerActions(trigger);
            insertTags(trigger.getTenantId(), TagType.TRIGGER, trigger.getId(), trigger.getTags());

        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        if (alertsContext.isInitialized() && null != alertsEngine) {
            alertsEngine.addTrigger(trigger.getTenantId(), trigger.getId());
        }

        notifyListeners(DefinitionsEvent.Type.TRIGGER_CREATE);
    }

    private void insertTriggerActions(Trigger trigger) throws Exception {
        Session session = CassCluster.getSession();
        PreparedStatement insertTriggerActions = CassStatement.get(session, CassStatement.INSERT_TRIGGER_ACTIONS);
        if (insertTriggerActions == null) {
            throw new RuntimeException("insertTriggerActions PreparedStatement is null");
        }
        if (trigger.getActions() != null) {
            trigger.getActions().forEach(triggerAction -> {
                triggerAction.setTenantId(trigger.getTenantId());
            });
            List<ResultSetFuture> futures = trigger.getActions().stream().map(triggerAction -> session.
            executeAsync(insertTriggerActions.bind(trigger.getTenantId(), trigger.getId(),
                    triggerAction.getActionPlugin(), triggerAction.getActionId(),
                    JsonUtil.toJson(triggerAction)))).collect(Collectors.toList());
            Futures.allAsList(futures).get();
        }
    }

    @Override
    public void removeTrigger(String tenantId, String triggerId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }

        Trigger doomedTrigger = getTrigger(tenantId, triggerId);
        if (null == doomedTrigger) {
            throw new NotFoundApplicationException(Trigger.class.getName(), tenantId, triggerId);
        }
        if (doomedTrigger.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] is a group trigger.");
        }
        if (doomedTrigger.isMember()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId
                    + "] is a member trigger and must be removed via the group.");
        }

        removeTrigger(doomedTrigger);
    }

    @Override
    public void removeGroupTrigger(String tenantId, String groupId, boolean keepNonOrphans, boolean keepOrphans)
            throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(groupId)) {
            throw new IllegalArgumentException("GroupId must be not null");
        }

        Trigger doomedTrigger = getTrigger(tenantId, groupId);
        if (null == doomedTrigger) {
            throw new NotFoundApplicationException(Trigger.class.getName(), tenantId, groupId);
        }
        if (!doomedTrigger.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + groupId + "] is not a group trigger");
        }

        Collection<Trigger> memberTriggers = getMemberTriggers(tenantId, groupId, true);

        for (Trigger member : memberTriggers) {
            if ((keepNonOrphans && !member.isOrphan()) || (keepOrphans && member.isOrphan())) {
                member.setMemberOf(null);
                member.setType(TriggerType.STANDARD);
                updateTrigger(member, member.getActions(), member.getTags());
                continue;
            }

            removeTrigger(member);
        }

        removeTrigger(doomedTrigger);
    }

    private void removeTrigger(Trigger trigger) throws Exception {
        Session session = CassCluster.getSession();

        String tenantId = trigger.getTenantId();
        String triggerId = trigger.getId();

        PreparedStatement deleteDampenings = CassStatement.get(session, CassStatement.DELETE_DAMPENINGS);
        PreparedStatement deleteConditions = CassStatement.get(session, CassStatement.DELETE_CONDITIONS);
        PreparedStatement deleteActions = CassStatement.get(session, CassStatement.DELETE_TRIGGER_ACTIONS);
        PreparedStatement deleteTrigger = CassStatement.get(session, CassStatement.DELETE_TRIGGER);
        session.execute(deleteActions.bind(trigger.getTenantId(), trigger.getId()));
        if (deleteDampenings == null || deleteConditions == null || deleteActions == null || deleteTrigger == null) {
            throw new RuntimeException("delete*Triggers PreparedStatement is null");
        }

        try {
            deleteTags(tenantId, TagType.TRIGGER, triggerId, trigger.getTags());
            deleteTriggerActions(tenantId, triggerId);
            List<ResultSetFuture> futures = new ArrayList<>();
            futures.add(session.executeAsync(deleteDampenings.bind(tenantId, triggerId)));
            futures.add(session.executeAsync(deleteConditions.bind(tenantId, triggerId)));
            futures.add(session.executeAsync(deleteActions.bind(tenantId, triggerId)));
            futures.add(session.executeAsync(deleteTrigger.bind(tenantId, triggerId)));
            Futures.allAsList(futures).get();
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        /*
            Trigger should be removed from the alerts engine.
         */
        if (alertsContext.isInitialized() && null != alertsEngine) {
            alertsEngine.removeTrigger(tenantId, triggerId);
        }

        notifyListeners(DefinitionsEvent.Type.TRIGGER_REMOVE);
    }

    @Override
    public Trigger updateTrigger(String tenantId, Trigger trigger) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(trigger)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }

        checkTenantId(tenantId, trigger);
        String triggerId = trigger.getId();
        Trigger existingTrigger = getTrigger(tenantId, trigger.getId());
        if (null == existingTrigger) {
            throw new NotFoundApplicationException(Trigger.class.getName(), tenantId, trigger.getId());
        }
        if (existingTrigger.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] is a group trigger.");
        }
        if (existingTrigger.isMember()) {
            if (!existingTrigger.isOrphan()) {
                throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId
                        + "] is a member trigger and must be updated via the group.");
            }
            if (!existingTrigger.getMemberOf().equals(trigger.getMemberOf())) {
                throw new IllegalArgumentException("A member trigger can not change groups.");
            }
            if (existingTrigger.isOrphan() != trigger.isOrphan()) {
                throw new IllegalArgumentException("Orphan status can not be changed by this method.");
            }
        }

        return updateTrigger(trigger, existingTrigger.getActions(), existingTrigger.getTags());
    }

    @Override
    public Trigger updateGroupTrigger(String tenantId, Trigger groupTrigger) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(groupTrigger)) {
            throw new IllegalArgumentException("Trigger must be not null");
        }

        checkTenantId(tenantId, groupTrigger);
        String groupId = groupTrigger.getId();

        Trigger existingGroupTrigger = getTrigger(tenantId, groupId);
        if (null == existingGroupTrigger) {
            throw new NotFoundApplicationException(Trigger.class.getName(), tenantId, groupTrigger.getId());
        }
        if (!existingGroupTrigger.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + groupId + "] is not a group trigger");
        }

        // trigger type can not be updated
        groupTrigger.setType(existingGroupTrigger.getType());

        Collection<Trigger> memberTriggers = getMemberTriggers(tenantId, groupId, false);

        // This works for the existing members as well, because they are all the same as the existing group trigger
        Set<TriggerAction> existingActions = existingGroupTrigger.getActions();
        Map<String, String> existingTags = existingGroupTrigger.getTags();

        for (Trigger member : memberTriggers) {
            copyGroupTrigger(groupTrigger, member);
            updateTrigger(member, existingActions, existingTags);
        }

        return updateTrigger(groupTrigger, existingActions, existingTags);
    }

    @Override
    public void updateTriggerEnablement(String tenantId, String triggerId, boolean enabled) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }

        Trigger existingTrigger = getTrigger(tenantId, triggerId);
        if (null == existingTrigger) {
            throw new NotFoundApplicationException(Trigger.class.getName(), tenantId, triggerId);
        }
        if (existingTrigger.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] is a group trigger.");
        }
        if (enabled == existingTrigger.isEnabled()) {
            log.debugf("Ignoring enable/disable request. Trigger %s is already set enabled=%s", triggerId, enabled);
            return;
        }

        Session session = CassCluster.getSession();
        PreparedStatement updateTriggerEnabled = CassStatement.get(session, CassStatement.UPDATE_TRIGGER_ENABLED);
        if (updateTriggerEnabled == null) {
            throw new RuntimeException("updateTriggerEnabled PreparedStatement is null");
        }
        try {
            session.execute(updateTriggerEnabled.bind(enabled, tenantId, triggerId));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        if (alertsContext.isInitialized() && null != alertsEngine) {
            alertsEngine.reloadTrigger(tenantId, triggerId);
        }

        notifyListeners(DefinitionsEvent.Type.TRIGGER_UPDATE);
    }

    private Trigger copyGroupTrigger(Trigger group, Trigger member) {
        member.setActions(group.getActions());
        member.setAutoDisable(group.isAutoDisable());
        member.setAutoEnable(group.isAutoEnable());
        member.setAutoResolve(group.isAutoResolve());
        member.setAutoResolveAlerts(group.isAutoResolveAlerts());
        member.setAutoResolveMatch(group.getAutoResolveMatch());
        member.setContext(group.getContext());
        member.setDescription(group.getDescription());
        member.setEnabled(group.isEnabled());
        member.setEventType(group.getEventType());
        member.setFiringMatch(group.getFiringMatch());
        member.setMemberOf(group.getId());
        member.setSeverity(group.getSeverity());
        member.setTags(group.getTags());
        member.setType(TriggerType.MEMBER);

        return member;
    }

    private Trigger updateTrigger(Trigger trigger, Set<TriggerAction> existingActions,
            Map<String, String> existingTags) throws Exception {
        Session session = CassCluster.getSession();
        PreparedStatement updateTrigger = CassStatement.get(session, CassStatement.UPDATE_TRIGGER);
        if (updateTrigger == null) {
            throw new RuntimeException("updateTrigger PreparedStatement is null");
        }
        try {
            session.execute(updateTrigger.bind(trigger.isAutoDisable(), trigger.isAutoEnable(),
                    trigger.isAutoResolve(), trigger.isAutoResolveAlerts(), trigger.getAutoResolveMatch().name(),
                    trigger.getContext(), trigger.getDescription(), trigger.isEnabled(), trigger.getEventCategory(),
                    trigger.getEventText(), trigger.getFiringMatch().name(), trigger.getMemberOf(), trigger.getName(),
                    trigger.getSeverity().name(), trigger.getSource(), trigger.getTags(), trigger.getType().name(),
                    trigger.getTenantId(), trigger.getId()));
            if (!trigger.getActions().equals(existingActions)) {
                deleteTriggerActions(trigger.getTenantId(), trigger.getId());
                insertTriggerActions(trigger);
            }
            if (!trigger.getTags().equals(existingTags)) {
                deleteTags(trigger.getTenantId(), TagType.TRIGGER, trigger.getId(), existingTags);
                insertTags(trigger.getTenantId(), TagType.TRIGGER, trigger.getId(), trigger.getTags());
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        if (alertsContext.isInitialized() && null != alertsEngine) {
            alertsEngine.reloadTrigger(trigger.getTenantId(), trigger.getId());
        }

        notifyListeners(DefinitionsEvent.Type.TRIGGER_UPDATE);

        return trigger;
    }

    @Override
    public Trigger orphanMemberTrigger(String tenantId, String memberId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(memberId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }

        Trigger member = getTrigger(tenantId, memberId);
        if (null == member) {
            throw new NotFoundApplicationException(Trigger.class.getName(), tenantId, memberId);
        }
        if (!member.isMember()) {
            throw new IllegalArgumentException("Trigger is not a member trigger: [" + tenantId + "/" + memberId + "]");
        }
        if (member.isOrphan()) {
            throw new IllegalArgumentException("Trigger is already an orphan: [" + tenantId + "/" + memberId + "]");
        }

        member.setType(TriggerType.ORPHAN);
        return updateTrigger(member, member.getActions(), member.getTags());
    }

    @Override
    public Trigger unorphanMemberTrigger(String tenantId, String memberId, Map<String, String> memberContext,
            Map<String, String> memberTags, Map<String, String> dataIdMap) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(memberId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }

        Trigger orphanMember = getTrigger(tenantId, memberId);
        if (null == orphanMember) {
            throw new NotFoundApplicationException(Trigger.class.getName(), tenantId, memberId);
        }
        if (!orphanMember.isMember()) {
            throw new IllegalArgumentException("Trigger is not a member trigger: [" + tenantId + "/" + memberId + "]");
        }
        if (!orphanMember.isOrphan()) {
            throw new IllegalArgumentException("Trigger is not an orphan: [" + tenantId + "/" + memberId + "]");
        }

        String groupId = orphanMember.getMemberOf();
        String memberName = orphanMember.getName();
        String memberDescription = orphanMember.getDescription();

        removeTrigger(orphanMember);
        Trigger member = addMemberTrigger(tenantId, groupId, memberId, memberName, memberDescription, memberContext,
                memberTags, dataIdMap);

        return member;
    }

    private void deleteTriggerActions(String tenantId, String triggerId) throws Exception {
        Session session = CassCluster.getSession();
        PreparedStatement deleteTriggerActions = CassStatement.get(session, CassStatement.DELETE_TRIGGER_ACTIONS);
        if (deleteTriggerActions == null) {
            throw new RuntimeException("updateTrigger PreparedStatement is null");
        }
        session.execute(deleteTriggerActions.bind(tenantId, triggerId));
    }

    @Override
    public Trigger getTrigger(String tenantId, String triggerId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement selectTrigger = CassStatement.get(session, CassStatement.SELECT_TRIGGER);
        if (selectTrigger == null) {
            throw new RuntimeException("selectTrigger PreparedStatement is null");
        }
        Trigger trigger = null;
        try {
            ResultSet rsTrigger = session.execute(selectTrigger.bind(tenantId, triggerId));
            Iterator<Row> itTrigger = rsTrigger.iterator();
            if (itTrigger.hasNext()) {
                Row row = itTrigger.next();
                trigger = mapTrigger(row);
                selectTriggerActions(trigger);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return trigger;
    }

    // TODO: This fetch perform cross-tenant fetch and may be inefficient at scale
    //       Added timeout to prevent slow startup on embedded Cassandra scenarios
    @Override
    @AccessTimeout(value = 60, unit = TimeUnit.SECONDS)
    public Collection<Trigger> getAllTriggers() throws Exception {
        return selectTriggers(null);
    }

    // TODO (jshaughn) The DB-Level filtering approach implemented below is a best-practice for dealing
    // with Cassandra.  It's basically a series of queries, one for each filter, with a progressive
    // intersection of the resulting ID set.
    @Override
    public Page<Trigger> getTriggers(String tenantId, TriggersCriteria criteria, Pager pager) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        Session session = CassCluster.getSession();
        boolean filter = (null != criteria && criteria.hasCriteria());
        boolean thin = (null != criteria && criteria.isThin()); // currently ignored, triggers have no thinned data

        if (filter && log.isDebugEnabled()) {
            log.debug("getTriggers criteria: " + criteria.toString());
        }

        List<Trigger> triggers = new ArrayList<>();
        Set<String> triggerIds = new HashSet<>();
        boolean activeFilter = false;

        try {
            if (filter) {
                /*
                    Get triggerIds explicitly added into the criteria. Start with these as there is no query involved
                */
                if (criteria.hasTriggerIdCriteria()) {
                    Set<String> idsFilteredByTriggers = filterByTriggers(criteria);
                    if (activeFilter) {
                        triggerIds.retainAll(idsFilteredByTriggers);
                        if (triggerIds.isEmpty()) {
                            return new Page<>(triggers, pager, 0);
                        }
                    } else {
                        triggerIds.addAll(idsFilteredByTriggers);
                    }
                    activeFilter = true;
                }

                /*
                    Get triggerIds via tags
                */
                if (criteria.hasTagCriteria()) {
                    Set<String> idsFilteredByTags = getIdsByTags(tenantId, TagType.TRIGGER, criteria.getTags());
                    if (activeFilter) {
                        triggerIds.retainAll(idsFilteredByTags);
                        if (triggerIds.isEmpty()) {
                            return new Page<>(triggers, pager, 0);
                        }
                    } else {
                        triggerIds.addAll(idsFilteredByTags);
                    }
                    activeFilter = true;
                }

                /*
                    If we have reached this point then we have at least 1 filtered triggerId, so now
                    get the resulting Triggers...
                 */
                PreparedStatement selectTrigger = CassStatement
                        .get(session, CassStatement.SELECT_TRIGGER);
                List<ResultSetFuture> futures = triggerIds.stream()
                        .map(id -> session.executeAsync(selectTrigger.bind(tenantId, id)))
                        .collect(Collectors.toList());
                List<ResultSet> rsTriggers = Futures.allAsList(futures).get();
                for (ResultSet rs : rsTriggers) {
                    for (Row row : rs) {
                        Trigger trigger = mapTrigger(row);
                        selectTriggerActions(trigger);
                        triggers.add(trigger);
                    }
                }
            } else {
                triggers.addAll(selectTriggers(tenantId));

            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        return prepareTriggersPage(triggers, pager);
    }

    private Set<String> filterByTriggers(TriggersCriteria criteria) {
        Set<String> result = Collections.emptySet();
        if (isEmpty(criteria.getTriggerIds())) {
            if (!isEmpty(criteria.getTriggerId())) {
                result = new HashSet<>(1);
                result.add(criteria.getTriggerId());
            }
        } else {
            result = new HashSet<>();
            result.addAll(criteria.getTriggerIds());
        }
        return result;
    }

    private Set<String> getIdsByTags(String tenantId, TagType tagType, Map<String, String> tags)
            throws Exception {
        Set<String> ids = new HashSet<>();
        List<ResultSetFuture> futures = new ArrayList<>();
        Session session = CassCluster.getSession();
        PreparedStatement selectTagsByName = CassStatement.get(session, CassStatement.SELECT_TAGS_BY_NAME);
        PreparedStatement selectTagsByNameAndValue = CassStatement.get(session,
                CassStatement.SELECT_TAGS_BY_NAME_AND_VALUE);

        for (Map.Entry<String, String> tag : tags.entrySet()) {
            boolean nameOnly = "*".equals(tag.getValue());
            BoundStatement bs = nameOnly ?
                    selectTagsByName.bind(tenantId, tagType.name(), tag.getKey()) :
                    selectTagsByNameAndValue.bind(tenantId, tagType.name(), tag.getKey(), tag.getValue());
            futures.add(session.executeAsync(bs));
        }
        List<ResultSet> rsTags = Futures.allAsList(futures).get();
        rsTags.stream().forEach(r -> {
            for (Row row : r) {
                ids.add(row.getString("id"));
            }
        });
        return ids;
    }

    private Collection<Trigger> selectTriggers(String tenantId) throws Exception {
        Session session = CassCluster.getSession();
        PreparedStatement selectTriggers = isEmpty(tenantId)
                ? CassStatement.get(session, CassStatement.SELECT_TRIGGERS_ALL)
                : CassStatement.get(session, CassStatement.SELECT_TRIGGERS_TENANT);
        if (null == selectTriggers) {
            throw new RuntimeException("selectTriggersTenant PreparedStatement is null");
        }

        List<Trigger> triggers = new ArrayList<>();
        try {
            ResultSet rsTriggers = session.execute(isEmpty(tenantId)
                    ? selectTriggers.bind()
                    : selectTriggers.bind(tenantId));
            for (Row row : rsTriggers) {
                Trigger trigger = mapTrigger(row);
                selectTriggerActions(trigger);
                triggers.add(trigger);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return triggers;
    }

    private Page<Trigger> prepareTriggersPage(List<Trigger> triggers, Pager pager) {
        if (pager != null) {
            if (pager.getOrder() != null
                    && !pager.getOrder().isEmpty()
                    && pager.getOrder().get(0).getField() == null) {
                pager = Pager.builder()
                        .withPageSize(pager.getPageSize())
                        .withStartPage(pager.getPageNumber())
                        .orderBy(TriggerComparator.Field.NAME.getName(), Order.Direction.DESCENDING).build();
            }
            List<Trigger> ordered = triggers;
            if (pager.getOrder() != null) {
                pager.getOrder()
                        .stream()
                        .filter(o -> o.getField() != null && o.getDirection() != null)
                        .forEach(o -> {
                            TriggerComparator comparator = new TriggerComparator(o.getField(), o.getDirection());
                            Collections.sort(ordered, comparator);
                        });
            }
            if (!pager.isLimited() || ordered.size() < pager.getStart()) {
                pager = new Pager(0, ordered.size(), pager.getOrder());
                return new Page<>(ordered, pager, ordered.size());
            }
            if (pager.getEnd() >= ordered.size()) {
                return new Page<>(ordered.subList(pager.getStart(), ordered.size()), pager, ordered.size());
            }
            return new Page<>(ordered.subList(pager.getStart(), pager.getEnd()), pager, ordered.size());
        } else {
            pager = Pager.builder().withPageSize(triggers.size()).orderBy(TriggerComparator.Field.ID.getName(),
                    Order.Direction.ASCENDING).build();
            return new Page<>(triggers, pager, triggers.size());
        }
    }

    // TODO: This performs a cross-tenant fetch and may be inefficient at scale
    @Override
    public Collection<Trigger> getAllTriggersByTag(String name, String value) throws Exception {
        if (isEmpty(name)) {
            throw new IllegalArgumentException("name must be not null");
        }
        if (isEmpty(value)) {
            throw new IllegalArgumentException("value must be not null (use '*' for all");
        }

        Session session = CassCluster.getSession();

        try {
            // first, get all the partitions (i.e. tenants) for triggers
            BoundStatement bs = CassStatement.get(session, CassStatement.SELECT_PARTITIONS_TRIGGERS).bind();
            Set<String> tenants = new HashSet<>();
            for (Row row : session.execute(bs)) {
                tenants.add(row.getString("tenantId"));
            }

            // next, get all of the tagged triggerIds
            boolean nameOnly = "*".equals(value);
            PreparedStatement selectTags = nameOnly
                    ? CassStatement.get(session, CassStatement.SELECT_TAGS_BY_NAME)
                    : CassStatement.get(session, CassStatement.SELECT_TAGS_BY_NAME_AND_VALUE);
            if (selectTags == null) {
                throw new RuntimeException("selectTags PreparedStatement is null");
            }

            Map<String, Set<String>> tenantTriggerIdsMap = new HashMap<>();
            List<ResultSetFuture> futures = nameOnly ? tenants.stream()
                    .map(tenantId -> session.executeAsync(selectTags.bind(tenantId, TagType.TRIGGER, name)))
                    .collect(Collectors.toList()) : tenants.stream()
                            .map(tenantId -> session.executeAsync(selectTags.bind(tenantId, TagType.TRIGGER, name,
                                    value)))
                            .collect(Collectors.toList());
            List<ResultSet> rsTriggerIds = Futures.allAsList(futures).get();
            rsTriggerIds.stream().forEach(rs -> {
                for (Row row : rs) {
                    String tenantId = row.getString("tenantId");
                    String triggerId = row.getString("id");
                    Set<String> storedTriggerIds = tenantTriggerIdsMap.get(tenantId);
                    if (null == storedTriggerIds) {
                        storedTriggerIds = new HashSet<>();
                    }
                    storedTriggerIds.add(triggerId);
                    tenantTriggerIdsMap.put(tenantId, storedTriggerIds);
                }
            });

            // Now, generate a cross-tenant result set if Triggers using the tenantIds and triggerIds
            List<Trigger> triggers = new ArrayList<>();
            PreparedStatement selectTrigger = CassStatement.get(session, CassStatement.SELECT_TRIGGER);
            for (Map.Entry<String, Set<String>> entry : tenantTriggerIdsMap.entrySet()) {
                String tenantId = entry.getKey();
                Set<String> triggerIds = entry.getValue();
                futures = triggerIds.stream()
                        .map(triggerId -> session.executeAsync(selectTrigger.bind(tenantId, triggerId)))
                        .collect(Collectors.toList());
                List<ResultSet> rsTriggers = Futures.allAsList(futures).get();
                for (ResultSet rs : rsTriggers) {
                    for (Row row : rs) {
                        Trigger trigger = mapTrigger(row);
                        selectTriggerActions(trigger);
                        triggers.add(trigger);
                    }
                }
            }

            return triggers;

        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    // This impl is not efficient. It actually pulls all triggers for the tenant and then filters out the
    // triggers that are not the requested members.  To make this more efficient we'd likely need to
    // create a new member_triggers table which duplicated the triggers table, and where we would maintain
    // a replica entry for each member trigger.  We'd need a primary key like ((tenantId,groupId),memberId) and
    // then we could query for member triggers directly.  Because this method is expected to be called
    // rarely, and because the trigger population is not expected to be huge (likely maxing out in the thousands),
    // we may just get away with this dumb impl.
    @Override
    public Collection<Trigger> getMemberTriggers(String tenantId, String groupId, boolean includeOrphans)
            throws Exception {

        Session session = CassCluster.getSession();
        PreparedStatement selectTriggersTenant = CassStatement.get(session, CassStatement.SELECT_TRIGGERS_TENANT);
        if (null == selectTriggersTenant) {
            throw new RuntimeException("selectTriggersMemberOf PreparedStatement is null");
        }

        List<Trigger> triggers = new ArrayList<>();
        try {
            ResultSet rsTriggers = session.execute(selectTriggersTenant.bind(tenantId));
            for (Row row : rsTriggers) {
                if (groupId.equals(row.getString("memberOf")) &&
                        (includeOrphans || TriggerType.MEMBER == TriggerType.valueOf(row.getString("type")))) {
                    Trigger trigger = mapTrigger(row);
                    selectTriggerActions(trigger);
                    triggers.add(trigger);
                }
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return triggers;
    }

    private void selectTriggerActions(Trigger trigger) throws Exception {
        if (trigger == null) {
            throw new IllegalArgumentException("Trigger must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement selectTriggerActions = CassStatement.get(session, CassStatement.SELECT_TRIGGER_ACTIONS);
        if (selectTriggerActions == null) {
            throw new RuntimeException("selectTriggerActions PreparedStatement is null");
        }
        ResultSet rsTriggerActions = session
                .execute(selectTriggerActions.bind(trigger.getTenantId(), trigger.getId()));
        Set<TriggerAction> actions = new HashSet<>();
        for (Row row : rsTriggerActions) {
            TriggerAction action = JsonUtil.fromJson(row.getString("payload"), TriggerAction.class);
            actions.add(action);
        }
        trigger.setActions(actions);
    }

    private Trigger mapTrigger(Row row) {
        Trigger trigger = new Trigger();

        trigger.setTenantId(row.getString("tenantId"));
        trigger.setId(row.getString("id"));
        trigger.setAutoDisable(row.getBool("autoDisable"));
        trigger.setAutoEnable(row.getBool("autoEnable"));
        trigger.setAutoResolve(row.getBool("autoResolve"));
        trigger.setAutoResolveAlerts(row.getBool("autoResolveAlerts"));
        trigger.setAutoResolveMatch(Match.valueOf(row.getString("autoResolveMatch")));
        trigger.setContext(row.getMap("context", String.class, String.class));
        trigger.setDescription(row.getString("description"));
        trigger.setEnabled(row.getBool("enabled"));
        trigger.setEventCategory(row.getString("eventCategory"));
        trigger.setEventText(row.getString("eventText"));
        trigger.setEventType(EventType.valueOf(row.getString("eventType")));
        trigger.setFiringMatch(Match.valueOf(row.getString("firingMatch")));
        trigger.setMemberOf(row.getString("memberOf"));
        trigger.setName(row.getString("name"));
        trigger.setSource(row.getString("source"));
        trigger.setSeverity(Severity.valueOf(row.getString("severity")));
        trigger.setType(TriggerType.valueOf(row.getString("type")));
        trigger.setTags(row.getMap("tags", String.class, String.class));

        return trigger;
    }

    @Override
    public Trigger addMemberTrigger(String tenantId, String groupId, String memberId, String memberName,
            String memberDescription, Map<String, String> memberContext, Map<String, String> memberTags,
            Map<String, String> dataIdMap) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(groupId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        if (isEmpty(dataIdMap)) {
            throw new IllegalArgumentException("DataIdMap must be not null");
        }

        // fetch the group trigger
        Trigger group = getTrigger(tenantId, groupId);
        if (group == null) {
            throw new IllegalArgumentException("Trigger not found for tenantId/triggerId [ " + tenantId + "]/[" +
                    groupId + "]");
        }

        // fetch the group conditions
        // ensure we have a 1-1 mapping for the dataId substitution
        Set<String> dataIdTokens = new HashSet<>();
        Collection<Condition> conditions = getTriggerConditions(tenantId, groupId, null);
        for (Condition c : conditions) {
            if (Condition.Type.COMPARE == c.getType()) {
                dataIdTokens.add(c.getDataId());
                dataIdTokens.add(((CompareCondition) c).getData2Id());
            } else {
                dataIdTokens.add(c.getDataId());
            }
        }
        if (!dataIdTokens.equals(dataIdMap.keySet())) {
            throw new IllegalArgumentException(
                    "DataIdMap must contain the exact dataIds (keyset) expected by the condition set. Expected: "
                            + dataIdTokens + ", dataIdMap: " + dataIdMap.keySet());
        }

        // create a member trigger like the group trigger
        memberId = isEmpty(memberId) ? Trigger.generateId() : memberId;
        memberName = isEmpty(memberName) ? group.getName() : memberName;
        Trigger member = new Trigger(tenantId, memberId, memberName);

        copyGroupTrigger(group, member);

        if (!isEmpty(memberDescription)) {
            member.setDescription(memberDescription);
        }
        if (null != memberContext) {
            // add additional or override existing context
            Map<String, String> combinedContext = new HashMap<>();
            combinedContext.putAll(member.getContext());
            combinedContext.putAll(memberContext);
            member.setContext(combinedContext);
        }
        if (null != memberTags) {
            // add additional or override existing tags
            Map<String, String> combinedTags = new HashMap<>();
            combinedTags.putAll(member.getTags());
            combinedTags.putAll(memberTags);
            member.setTags(combinedTags);
        }

        addTrigger(member);

        // add any conditions
        for (Condition c : conditions) {
            Condition newCondition = getMemberCondition(member, c, dataIdMap);
            if (newCondition != null) {
                addCondition(newCondition);
            }
        }

        // add any dampening
        Collection<Dampening> dampenings = getTriggerDampenings(tenantId, groupId, null);

        for (Dampening d : dampenings) {
            Dampening newDampening = new Dampening(member.getTenantId(), member.getId(), d.getTriggerMode(),
                    d.getType(), d.getEvalTrueSetting(), d.getEvalTotalSetting(), d.getEvalTimeSetting());
            addDampening(newDampening);
        }

        // add any tags
        insertTags(tenantId, TagType.TRIGGER, member.getId(), member.getTags());

        return member;
    }

    private Condition getMemberCondition(Trigger member, Condition groupCondition, Map<String, String> dataIdMap) {
        Condition newCondition = null;
        switch (groupCondition.getType()) {
            case AVAILABILITY:
                newCondition = new AvailabilityCondition(member.getTenantId(), member.getId(),
                        groupCondition.getTriggerMode(),
                        groupCondition.getConditionSetSize(), groupCondition.getConditionSetIndex(),
                        dataIdMap.get(groupCondition.getDataId()),
                        ((AvailabilityCondition) groupCondition).getOperator());
                break;
            case COMPARE:
                newCondition = new CompareCondition(member.getTenantId(), member.getId(),
                        groupCondition.getTriggerMode(),
                        groupCondition.getConditionSetSize(), groupCondition.getConditionSetIndex(),
                        dataIdMap.get(groupCondition.getDataId()),
                        ((CompareCondition) groupCondition).getOperator(),
                        ((CompareCondition) groupCondition).getData2Multiplier(),
                        dataIdMap.get(((CompareCondition) groupCondition).getData2Id()));
                break;
            case EVENT:
                newCondition = new EventCondition(member.getTenantId(), member.getId(),
                        groupCondition.getTriggerMode(),
                        groupCondition.getConditionSetSize(), groupCondition.getConditionSetIndex(),
                        dataIdMap.get(groupCondition.getDataId()),
                        ((EventCondition) groupCondition).getExpression());
                break;
            case EXTERNAL:
                String tokenDataId = groupCondition.getDataId();
                String memberDataId = dataIdMap.get(tokenDataId);
                String tokenExpression = ((ExternalCondition) groupCondition).getExpression();
                String memberExpression = isEmpty(tokenExpression) ? tokenExpression
                        : tokenExpression.replace(tokenDataId, memberDataId);
                newCondition = new ExternalCondition(member.getTenantId(), member.getId(),
                        groupCondition.getTriggerMode(),
                        groupCondition.getConditionSetSize(), groupCondition.getConditionSetIndex(),
                        memberDataId,
                        ((ExternalCondition) groupCondition).getSystemId(),
                        memberExpression);
                break;
            case RANGE:
                newCondition = new ThresholdRangeCondition(member.getTenantId(), member.getId(),
                        groupCondition.getTriggerMode(),
                        groupCondition.getConditionSetSize(), groupCondition.getConditionSetIndex(),
                        dataIdMap.get(groupCondition.getDataId()),
                        ((ThresholdRangeCondition) groupCondition).getOperatorLow(),
                        ((ThresholdRangeCondition) groupCondition).getOperatorHigh(),
                        ((ThresholdRangeCondition) groupCondition).getThresholdLow(),
                        ((ThresholdRangeCondition) groupCondition).getThresholdHigh(),
                        ((ThresholdRangeCondition) groupCondition).isInRange());
                break;
            case RATE:
                newCondition = new RateCondition(member.getTenantId(), member.getId(),
                        groupCondition.getTriggerMode(),
                        groupCondition.getConditionSetSize(), groupCondition.getConditionSetIndex(),
                        dataIdMap.get(groupCondition.getDataId()),
                        ((RateCondition) groupCondition).getDirection(),
                        ((RateCondition) groupCondition).getPeriod(),
                        ((RateCondition) groupCondition).getOperator(),
                        ((RateCondition) groupCondition).getThreshold());
                break;
            case STRING:
                newCondition = new StringCondition(member.getTenantId(), member.getId(),
                        groupCondition.getTriggerMode(),
                        groupCondition.getConditionSetSize(), groupCondition.getConditionSetIndex(),
                        dataIdMap.get(groupCondition.getDataId()),
                        ((StringCondition) groupCondition).getOperator(),
                        ((StringCondition) groupCondition).getPattern(),
                        ((StringCondition) groupCondition).isIgnoreCase());
                break;
            case THRESHOLD:
                newCondition = new ThresholdCondition(member.getTenantId(), member.getId(),
                        groupCondition.getTriggerMode(),
                        groupCondition.getConditionSetSize(), groupCondition.getConditionSetIndex(),
                        dataIdMap.get(groupCondition.getDataId()),
                        ((ThresholdCondition) groupCondition).getOperator(),
                        ((ThresholdCondition) groupCondition).getThreshold());
                break;
            default:
                throw new IllegalArgumentException("Unexpected Condition type: " + groupCondition.getType().name());
        }

        newCondition.setContext(groupCondition.getContext());
        return newCondition;
    }

    @Override
    public Trigger addDataDrivenMemberTrigger(String tenantId, String groupId, String source) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(groupId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        if (isEmpty(source)) {
            throw new IllegalArgumentException("source must be not null");
        }
        if (Data.SOURCE_NONE.equals(source)) {
            throw new IllegalArgumentException("source is required (can not be none)");
        }

        // fetch the group trigger
        Trigger group = getTrigger(tenantId, groupId);
        if (group == null) {
            throw new IllegalArgumentException("Trigger not found for tenantId/triggerId [ " + tenantId + "]/[" +
                    groupId + "]");
        }

        // fetch the group conditions and generate a dataIdMap that just uses the same tokens as found in the
        // group conditions. That is what we want in this use case, the source provides the differentiator
        Map<String, String> dataIdMap = new HashMap<>();
        Collection<Condition> conditions = getTriggerConditions(tenantId, groupId, null);
        for (Condition c : conditions) {
            dataIdMap.put(c.getDataId(), c.getDataId());
            if (Condition.Type.COMPARE == c.getType()) {
                dataIdMap.put(((CompareCondition) c).getData2Id(), ((CompareCondition) c).getData2Id());
            }
        }

        // create a member trigger like the group trigger
        String memberId = group.getId() + "_" + source;
        Trigger member = new Trigger(tenantId, memberId, group.getName());

        copyGroupTrigger(group, member);
        member.setSource(source);

        addTrigger(member);

        // add any conditions
        for (Condition c : conditions) {
            Condition newCondition = getMemberCondition(member, c, dataIdMap);
            if (newCondition != null) {
                addCondition(newCondition);
            }
        }

        // add any dampening
        Collection<Dampening> dampenings = getTriggerDampenings(tenantId, groupId, null);

        for (Dampening d : dampenings) {
            Dampening newDampening = new Dampening(member.getTenantId(), member.getId(), d.getTriggerMode(),
                    d.getType(), d.getEvalTrueSetting(), d.getEvalTotalSetting(), d.getEvalTimeSetting());
            addDampening(newDampening);
        }

        // add any tags
        Map<String, String> updatedTags = new HashMap<>(member.getTags());
        updatedTags.put("source", source); //TODO do we need this? Should we index source instead?
        insertTags(tenantId, TagType.TRIGGER, member.getId(), member.getTags());

        return member;
    }

    @Override
    public Dampening addDampening(String tenantId, Dampening dampening) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(dampening)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }

        checkTenantId(tenantId, dampening);

        String triggerId = dampening.getTriggerId();
        Trigger trigger = getTrigger(tenantId, triggerId);
        if (null == trigger) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] does not exist.");
        }
        if (trigger.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] is a group trigger.");
        }
        if (trigger.isMember() && !trigger.isOrphan()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId
                    + "] is a member trigger and must be managed via the group.");
        }

        return addDampening(dampening);
    }

    @Override
    public Dampening addGroupDampening(String tenantId, Dampening dampening) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(dampening)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }

        checkTenantId(tenantId, dampening);

        String groupId = dampening.getTriggerId();
        Trigger groupTrigger = getTrigger(tenantId, groupId);
        if (null == groupTrigger) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + groupId + "] does not exist.");
        }
        if (!groupTrigger.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + groupId + "] is not a group trigger.");
        }

        Collection<Trigger> memberTriggers = getMemberTriggers(tenantId, groupId, false);

        for (Trigger member : memberTriggers) {
            dampening.setTriggerId(member.getId());
            addDampening(dampening);
        }

        dampening.setTriggerId(groupTrigger.getId());
        return addDampening(dampening);
    }

    private Dampening addDampening(Dampening dampening) throws Exception {
        Session session = CassCluster.getSession();
        PreparedStatement insertDampening = CassStatement.get(session, CassStatement.INSERT_DAMPENING);
        if (insertDampening == null) {
            throw new RuntimeException("insertDampening PreparedStatement is null");
        }

        try {
            session.execute(insertDampening.bind(dampening.getTenantId(), dampening.getTriggerId(),
                    dampening.getTriggerMode().name(), dampening.getType().name(), dampening.getEvalTrueSetting(),
                    dampening.getEvalTotalSetting(), dampening.getEvalTimeSetting(), dampening.getDampeningId()));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        if (alertsContext.isInitialized() && null != alertsEngine) {
            alertsEngine.reloadTrigger(dampening.getTenantId(), dampening.getTriggerId());
        }

        notifyListeners(DefinitionsEvent.Type.DAMPENING_CHANGE);

        return dampening;
    }

    @Override
    public void removeDampening(String tenantId, String dampeningId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(dampeningId)) {
            throw new IllegalArgumentException("dampeningId must be not null");
        }

        Dampening dampening = getDampening(tenantId, dampeningId);
        if (null == dampening) {
            if (log.isDebugEnabled()) {
                log.debug("Ignoring removeDampening(" + dampeningId + "), the Dampening does not exist.");
            }
            return;
        }

        String triggerId = dampening.getTriggerId();
        Trigger trigger = getTrigger(tenantId, triggerId);
        if (null == trigger) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] does not exist.");
        }
        if (trigger.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] is a group trigger.");
        }
        if (trigger.isMember() && !trigger.isOrphan()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId
                    + "] is a member trigger and must be managed via the group.");
        }

        removeDampening(dampening);
    }

    @Override
    public void removeGroupDampening(String tenantId, String dampeningId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(dampeningId)) {
            throw new IllegalArgumentException("dampeningId must be not null");
        }

        Dampening dampening = getDampening(tenantId, dampeningId);
        if (null == dampening) {
            if (log.isDebugEnabled()) {
                log.debug("Ignoring removeDampening(" + dampeningId + "), the Dampening does not exist.");
            }
            return;
        }

        String groupId = dampening.getTriggerId();
        Trigger groupTrigger = getTrigger(tenantId, groupId);
        if (null == groupTrigger) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + groupId + "] does not exist.");
        }
        if (!groupTrigger.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + groupId + "] is not a group trigger.");
        }

        Collection<Trigger> memberTriggers = getMemberTriggers(tenantId, groupId, false);

        for (Trigger member : memberTriggers) {
            Collection<Dampening> dampenings = getTriggerDampenings(tenantId, member.getId(),
                    dampening.getTriggerMode());
            if (dampenings.isEmpty()) {
                continue;
            }
            removeDampening(dampenings.iterator().next());
        }

        removeDampening(dampening);
    }

    private void removeDampening(Dampening dampening) throws Exception {
        Session session = CassCluster.getSession();
        PreparedStatement deleteDampeningId = CassStatement.get(session, CassStatement.DELETE_DAMPENING_ID);
        if (deleteDampeningId == null) {
            throw new RuntimeException("deleteDampeningId PreparedStatement is null");
        }

        try {
            session.execute(deleteDampeningId.bind(dampening.getTenantId(), dampening.getTriggerId(),
                    dampening.getTriggerMode().name(), dampening.getDampeningId()));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        if (alertsContext.isInitialized() && null != alertsEngine) {
            alertsEngine.reloadTrigger(dampening.getTenantId(), dampening.getTriggerId());
        }

        notifyListeners(DefinitionsEvent.Type.DAMPENING_CHANGE);
    }

    @Override
    public Dampening updateDampening(String tenantId, Dampening dampening) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(dampening)) {
            throw new IllegalArgumentException("DampeningId must be not null");
        }

        checkTenantId(tenantId, dampening);

        String triggerId = dampening.getTriggerId();
        Trigger trigger = getTrigger(tenantId, triggerId);
        if (null == trigger) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] does not exist.");
        }
        if (trigger.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] is a group trigger.");
        }
        if (trigger.isMember() && !trigger.isOrphan()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId
                    + "] is a member trigger and must be managed via the group.");
        }

        return updateDampening(dampening);
    }

    @Override
    public Dampening updateGroupDampening(String tenantId, Dampening dampening) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(dampening)) {
            throw new IllegalArgumentException("DampeningId must be not null");
        }

        checkTenantId(tenantId, dampening);

        String groupId = dampening.getTriggerId();
        Trigger groupTrigger = getTrigger(tenantId, groupId);
        if (null == groupTrigger) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + groupId + "] does not exist.");
        }
        if (!groupTrigger.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + groupId + "] is not a group trigger.");
        }

        Collection<Trigger> memberTriggers = getMemberTriggers(tenantId, groupId, false);

        for (Trigger member : memberTriggers) {
            dampening.setTriggerId(member.getId());
            updateDampening(dampening);
        }

        dampening.setTriggerId(groupTrigger.getId());
        return updateDampening(dampening);
    }

    private Dampening updateDampening(Dampening dampening) throws Exception {
        Session session = CassCluster.getSession();
        PreparedStatement updateDampeningId = CassStatement.get(session, CassStatement.UPDATE_DAMPENING_ID);
        if (updateDampeningId == null) {
            throw new RuntimeException("updateDampeningId PreparedStatement is null");
        }

        try {
            session.execute(updateDampeningId.bind(dampening.getType().name(), dampening.getEvalTrueSetting(),
                    dampening.getEvalTotalSetting(), dampening.getEvalTimeSetting(), dampening.getTenantId(),
                    dampening.getTriggerId(), dampening.getTriggerMode().name(), dampening.getDampeningId()));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        if (alertsContext.isInitialized() && null != alertsEngine) {
            alertsEngine.reloadTrigger(dampening.getTenantId(), dampening.getTriggerId());
        }

        notifyListeners(DefinitionsEvent.Type.DAMPENING_CHANGE);

        return dampening;
    }

    @Override
    public Dampening getDampening(String tenantId, String dampeningId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(dampeningId)) {
            throw new IllegalArgumentException("DampeningId must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement selectDampeningId = CassStatement.get(session, CassStatement.SELECT_DAMPENING_ID);
        if (selectDampeningId == null) {
            throw new RuntimeException("selectDampeningId PreparedStatement is null");
        }

        Dampening dampening = null;
        try {
            ResultSet rsDampening = session.execute(selectDampeningId.bind(tenantId, dampeningId));
            Iterator<Row> itDampening = rsDampening.iterator();
            if (itDampening.hasNext()) {
                Row row = itDampening.next();
                dampening = mapDampening(row);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return dampening;
    }

    @Override
    public Collection<Dampening> getTriggerDampenings(String tenantId, String triggerId, Mode triggerMode)
            throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement selectTriggerDampenings = CassStatement
                .get(session, CassStatement.SELECT_TRIGGER_DAMPENINGS);
        PreparedStatement selectTriggerDampeningsMode = CassStatement.get(session,
                CassStatement.SELECT_TRIGGER_DAMPENINGS_MODE);
        if (selectTriggerDampenings == null || selectTriggerDampeningsMode == null) {
            throw new RuntimeException("selectTriggerDampenings* PreparedStatement is null");
        }
        List<Dampening> dampenings = new ArrayList<>();
        try {
            ResultSet rsDampenings;
            if (triggerMode == null) {
                rsDampenings = session.execute(selectTriggerDampenings.bind(tenantId, triggerId));
            } else {
                rsDampenings = session.execute(selectTriggerDampeningsMode.bind(tenantId, triggerId,
                        triggerMode.name()));
            }
            mapDampenings(rsDampenings, dampenings);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return dampenings;
    }

    // TODO: This getAll* fetches are cross-tenant fetch and may be inefficient at scale
    @Override
    public Collection<Dampening> getAllDampenings() throws Exception {
        Session session = CassCluster.getSession();
        PreparedStatement selectDampeningsAll = CassStatement.get(session, CassStatement.SELECT_DAMPENINGS_ALL);
        if (selectDampeningsAll == null) {
            throw new RuntimeException("selectDampeningsAll PreparedStatement is null");
        }
        List<Dampening> dampenings = new ArrayList<>();
        try {
            ResultSet rsDampenings = session.execute(selectDampeningsAll.bind());
            mapDampenings(rsDampenings, dampenings);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return dampenings;
    }

    @Override
    public Collection<Dampening> getDampenings(String tenantId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement selectDampeningsByTenant = CassStatement.get(session,
                CassStatement.SELECT_DAMPENINGS_BY_TENANT);
        if (selectDampeningsByTenant == null) {
            throw new RuntimeException("selectDampeningsByTenant PreparedStatement is null");
        }
        List<Dampening> dampenings = new ArrayList<>();
        try {
            ResultSet rsDampenings = session.execute(selectDampeningsByTenant.bind(tenantId));
            mapDampenings(rsDampenings, dampenings);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return dampenings;
    }

    private void mapDampenings(ResultSet rsDampenings, List<Dampening> dampenings) throws Exception {
        for (Row row : rsDampenings) {
            Dampening dampening = mapDampening(row);
            dampenings.add(dampening);
        }
    }

    private Dampening mapDampening(Row row) {
        Dampening dampening = new Dampening();
        dampening.setTenantId(row.getString("tenantId"));
        dampening.setTriggerId(row.getString("triggerId"));
        dampening.setTriggerMode(Mode.valueOf(row.getString("triggerMode")));
        dampening.setType(Dampening.Type.valueOf(row.getString("type")));
        dampening.setEvalTrueSetting(row.getInt("evalTrueSetting"));
        dampening.setEvalTotalSetting(row.getInt("evalTotalSetting"));
        dampening.setEvalTimeSetting(row.getLong("evalTimeSetting"));
        return dampening;
    }

    @Override
    @Deprecated
    public Collection<Condition> addCondition(String tenantId, String triggerId, Mode triggerMode,
            Condition condition) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        if (triggerMode == null) {
            throw new IllegalArgumentException("TriggerMode must be not null");
        }
        if (condition == null) {
            throw new IllegalArgumentException("Condition must be not null");
        }

        Trigger trigger = getTrigger(tenantId, triggerId);
        if (null == trigger) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] does not exist.");
        }
        if (trigger.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] is a group trigger.");
        }
        if (trigger.isMember() && !trigger.isOrphan()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId
                    + "] is a member trigger and must be managed via the group.");
        }

        condition.setTenantId(tenantId);
        condition.setTriggerId(triggerId);
        condition.setTriggerMode(triggerMode);

        return addCondition(condition);
    }

    @Override
    public Collection<Condition> setGroupConditions(String tenantId, String groupId, Mode triggerMode,
            Collection<Condition> groupConditions, Map<String, Map<String, String>> dataIdMemberMap) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(groupId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        if (triggerMode == null) {
            throw new IllegalArgumentException("TriggerMode must be not null");
        }
        if (groupConditions == null) {
            throw new IllegalArgumentException("GroupConditions must be not null");
        }

        Trigger group = getTrigger(tenantId, groupId);
        if (null == group) {
            throw new NotFoundApplicationException(Trigger.class.getName(), tenantId, groupId);
        }
        if (!group.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + groupId + "] is not a group trigger.");
        }

        Collection<Trigger> memberTriggers = getMemberTriggers(tenantId, groupId, false);

        // for data-driven groups a change to group conditions invalidates the previously generated members
        // Note: if the new set of conditions uses the same set of dataIds we probably don't need to invalidate
        // the current members but the work of maintaining them may not add much, if any, benefit.
        if (TriggerType.DATA_DRIVEN_GROUP == group.getType()) {
            for (Trigger member : memberTriggers) {
                removeTrigger(member);
            }
            memberTriggers.clear();
        }

        // allow the dataIdMap to be empty when there are no member triggers
        if (!memberTriggers.isEmpty()) {
            if (dataIdMemberMap == null) {
                throw new IllegalArgumentException("DataIdMemberMap must be not null when member triggers exist.");
            }

            // first, validate the dataIdMap
            for (Condition groupCondition : groupConditions) {
                if (!dataIdMemberMap.containsKey(groupCondition.getDataId())) {
                    throw new IllegalArgumentException("Missing dataIdMap entry for dataId token ["
                            + groupCondition.getDataId() + "]");
                }
                if (Condition.Type.COMPARE == groupCondition.getType()) {
                    CompareCondition cc = (CompareCondition) groupCondition;
                    if (!dataIdMemberMap.containsKey(cc.getData2Id())) {
                        throw new IllegalArgumentException(
                                "Missing dataIdMap entry for CompareCondition data2Id token ["
                                        + cc.getData2Id() + "]");
                    }
                }
                for (Map.Entry<String, Map<String, String>> entry : dataIdMemberMap.entrySet()) {
                    String dataId = entry.getKey();
                    Map<String, String> memberMap = entry.getValue();
                    if (memberMap.size() != memberTriggers.size()) {
                        throw new IllegalArgumentException("memberMap size [" + memberMap.size() + "] for dataId ["
                                + dataId + "] must equal number of member triggers [" + memberTriggers.size() + "]");
                    }
                    for (Trigger member : memberTriggers) {
                        String value = memberMap.get(member.getId());
                        if (isEmpty(value)) {
                            throw new IllegalArgumentException(
                                    "Invalid mapping. DataId=[" + dataId + "], Member=[" + member.getId()
                                            + "], value=[" + value + "]");
                        }
                    }
                }
            }
        }

        // ensure conditions are set properly
        for (Condition groupCondition : groupConditions) {
            groupCondition.setTenantId(group.getTenantId());
            groupCondition.setTriggerId(group.getId());
            groupCondition.setTriggerMode(triggerMode);
        }

        // set conditions on the members
        Map<String, String> dataIdMap = new HashMap<>();
        Collection<Condition> memberConditions = new ArrayList<>(groupConditions.size());
        for (Trigger member : memberTriggers) {
            dataIdMap.clear();
            memberConditions.clear();
            for (Map.Entry<String, Map<String, String>> entry : dataIdMemberMap.entrySet()) {
                dataIdMap.put(entry.getKey(), entry.getValue().get(member.getId()));
            }

            for (Condition groupCondition : groupConditions) {
                Condition memberCondition = getMemberCondition(member, groupCondition, dataIdMap);
                memberConditions.add(memberCondition);
            }
            Collection<Condition> memberConditionSet = setConditions(tenantId, member.getId(), triggerMode,
                    memberConditions);
            if (log.isDebugEnabled()) {
                log.debug("Member condition set: " + memberConditionSet);
            }
        }

        // set conditions on the group trigger
        return setConditions(tenantId, groupId, triggerMode, groupConditions);
    }

    private Collection<Condition> addCondition(Condition condition) throws Exception {
        String tenantId = condition.getTenantId();
        String triggerId = condition.getTriggerId();
        Mode triggerMode = condition.getTriggerMode();

        Collection<Condition> conditions = getTriggerConditions(tenantId, triggerId, triggerMode);
        conditions.add(condition);
        int i = 0;
        for (Condition c : conditions) {
            c.setConditionSetSize(conditions.size());
            c.setConditionSetIndex(++i);
        }

        return setConditions(tenantId, triggerId, triggerMode, conditions);
    }

    @Override
    @Deprecated
    public Collection<Condition> removeCondition(String tenantId, String conditionId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(conditionId)) {
            throw new IllegalArgumentException("ConditionId must be not null");
        }

        Condition condition = getCondition(tenantId, conditionId);
        if (null == condition) {
            if (log.isDebugEnabled()) {
                log.debug("Ignoring removeCondition [" + conditionId + "], the condition does not exist.");
            }
            return null;
        }

        String triggerId = condition.getTriggerId();
        Trigger trigger = getTrigger(tenantId, triggerId);
        if (null == trigger) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] does not exist.");
        }
        if (trigger.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] is a group trigger.");
        }
        if (trigger.isMember() && !trigger.isOrphan()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId
                    + "] is a member trigger and must be managed via the group.");
        }

        return removeCondition(condition);
    }

    private Collection<Condition> removeCondition(Condition condition) throws Exception {
        String tenantId = condition.getTenantId();
        String triggerId = condition.getTriggerId();
        Mode triggerMode = condition.getTriggerMode();
        String conditionId = condition.getConditionId();
        Collection<Condition> conditions = getTriggerConditions(tenantId, triggerId, triggerMode);

        int i = 0;
        int size = conditions.size() - 1;
        Collection<Condition> newConditions = new ArrayList<>(size);
        for (Condition c : conditions) {
            if (!c.getConditionId().equals(conditionId)) {
                c.setConditionSetSize(conditions.size());
                c.setConditionSetIndex(++i);
                newConditions.add(c);
            }
        }

        return setConditions(tenantId, triggerId, triggerMode, newConditions);
    }

    @Override
    @Deprecated
    public Collection<Condition> updateCondition(String tenantId, Condition condition) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (condition == null) {
            throw new IllegalArgumentException("Condition must be not null");
        }

        String conditionId = condition.getConditionId();
        if (isEmpty(conditionId)) {
            throw new IllegalArgumentException("ConditionId must be not null");
        }

        Condition existingCondition = getCondition(tenantId, conditionId);
        if (null == existingCondition) {
            throw new IllegalArgumentException("ConditionId [" + conditionId + "] on tenant " + tenantId +
                    " does not exist.");
        }
        if (existingCondition.getTriggerMode() != condition.getTriggerMode()) {
            throw new IllegalArgumentException("The condition trigger mode ["
                    + existingCondition.getTriggerMode().name() + "] can not be changed.");
        }

        String triggerId = existingCondition.getTriggerId();
        Trigger existingTrigger = getTrigger(tenantId, triggerId);
        if (null == existingTrigger) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] does not exist.");
        }
        if (existingTrigger.isGroup()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId + "] is a group trigger.");
        }
        if (existingTrigger.isMember() && !existingTrigger.isOrphan()) {
            throw new IllegalArgumentException("Trigger [" + tenantId + "/" + triggerId
                    + "] is a member trigger and must be managed via the group.");
        }

        return updateCondition(existingTrigger, condition);
    }

    private Collection<Condition> updateCondition(Trigger trigger, Condition condition) throws Exception {

        String tenantId = trigger.getTenantId();
        String triggerId = trigger.getId();
        Mode triggerMode = condition.getTriggerMode();
        Collection<Condition> conditions = getTriggerConditions(tenantId, triggerId, triggerMode);

        int size = conditions.size();
        Collection<Condition> newConditions = new ArrayList<>(size);
        for (Condition c : conditions) {
            if (c.getConditionId().equals(condition.getConditionId())) {
                newConditions.add(condition);
            } else {
                newConditions.add(c);
            }
        }

        return setConditions(tenantId, triggerId, triggerMode, newConditions);
    }

    @Override
    public Collection<Condition> setConditions(String tenantId, String triggerId, Mode triggerMode,
            Collection<Condition> conditions) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        if (triggerMode == null) {
            throw new IllegalArgumentException("TriggerMode must be not null");
        }
        if (conditions == null) {
            throw new IllegalArgumentException("Conditions must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement insertConditionAvailability = CassStatement.get(session,
                CassStatement.INSERT_CONDITION_AVAILABILITY);
        PreparedStatement insertConditionCompare = CassStatement.get(session, CassStatement.INSERT_CONDITION_COMPARE);
        PreparedStatement insertConditionEvent = CassStatement
                .get(session, CassStatement.INSERT_CONDITION_EVENT);
        PreparedStatement insertConditionExternal = CassStatement
                .get(session, CassStatement.INSERT_CONDITION_EXTERNAL);
        PreparedStatement insertConditionRate = CassStatement
                .get(session, CassStatement.INSERT_CONDITION_RATE);
        PreparedStatement insertConditionString = CassStatement.get(session, CassStatement.INSERT_CONDITION_STRING);
        PreparedStatement insertConditionThreshold = CassStatement.get(session,
                CassStatement.INSERT_CONDITION_THRESHOLD);
        PreparedStatement insertConditionThresholdRange = CassStatement.get(session,
                CassStatement.INSERT_CONDITION_THRESHOLD_RANGE);
        if (insertConditionAvailability == null
                || insertConditionCompare == null
                || insertConditionEvent == null
                || insertConditionExternal == null
                || insertConditionRate == null
                || insertConditionString == null
                || insertConditionThreshold == null
                || insertConditionThresholdRange == null) {
            throw new RuntimeException("insert*Condition PreparedStatement is null");
        }
        // Get rid of the prior condition set
        removeConditions(tenantId, triggerId, triggerMode);

        // Now add the new condition set
        try {
            List<ResultSetFuture> futures = new ArrayList<>();

            int i = 0;
            for (Condition cond : conditions) {
                cond.setTenantId(tenantId);
                cond.setTriggerId(triggerId);
                cond.setTriggerMode(triggerMode);
                cond.setConditionSetSize(conditions.size());
                cond.setConditionSetIndex(++i);

                switch (cond.getType()) {
                    case AVAILABILITY:
                        AvailabilityCondition aCond = (AvailabilityCondition) cond;
                        futures.add(session.executeAsync(insertConditionAvailability.bind(aCond.getTenantId(),
                                aCond.getTriggerId(), aCond.getTriggerMode().name(), aCond.getContext(),
                                aCond.getConditionSetSize(), aCond.getConditionSetIndex(),
                                aCond.getConditionId(), aCond.getDataId(), aCond.getOperator().name())));
                        break;
                    case COMPARE:
                        CompareCondition cCond = (CompareCondition) cond;
                        futures.add(session.executeAsync(insertConditionCompare.bind(cCond.getTenantId(),
                                cCond.getTriggerId(), cCond.getTriggerMode().name(), cCond.getContext(),
                                cCond.getConditionSetSize(), cCond.getConditionSetIndex(),
                                cCond.getConditionId(), cCond.getDataId(), cCond.getOperator().name(),
                                cCond.getData2Id(),
                                cCond.getData2Multiplier())));
                        break;
                    case EVENT:
                        EventCondition evCond = (EventCondition) cond;
                        futures.add(session.executeAsync(insertConditionEvent.bind(evCond.getTenantId(),
                                evCond.getTriggerId(), evCond.getTriggerMode().name(), evCond.getContext(),
                                evCond.getConditionSetSize(), evCond.getConditionSetIndex(), evCond.getConditionId(),
                                evCond.getDataId(), evCond.getExpression())));
                        break;
                    case EXTERNAL:
                        ExternalCondition eCond = (ExternalCondition) cond;
                        futures.add(session.executeAsync(insertConditionExternal.bind(eCond.getTenantId(),
                                eCond.getTriggerId(), eCond.getTriggerMode().name(), eCond.getContext(),
                                eCond.getConditionSetSize(), eCond.getConditionSetIndex(), eCond.getConditionId(),
                                eCond.getDataId(), eCond.getSystemId(), eCond.getExpression())));
                        break;
                    case RANGE:
                        ThresholdRangeCondition rCond = (ThresholdRangeCondition) cond;
                        futures.add(session.executeAsync(insertConditionThresholdRange.bind(rCond.getTenantId(),
                                rCond.getTriggerId(), rCond.getTriggerMode().name(), rCond.getContext(),
                                rCond.getConditionSetSize(), rCond.getConditionSetIndex(), rCond.getConditionId(),
                                rCond.getDataId(), rCond.getOperatorLow().name(), rCond.getOperatorHigh().name(),
                                rCond.getThresholdLow(), rCond.getThresholdHigh(), rCond.isInRange())));
                        break;
                    case RATE:
                        RateCondition rateCond = (RateCondition) cond;
                        futures.add(session.executeAsync(insertConditionRate.bind(rateCond.getTenantId(),
                                rateCond.getTriggerId(), rateCond.getTriggerMode().name(), rateCond.getContext(),
                                rateCond.getConditionSetSize(), rateCond.getConditionSetIndex(),
                                rateCond.getConditionId(), rateCond.getDataId(), rateCond.getDirection(),
                                rateCond.getPeriod(), rateCond.getOperator().name(), rateCond.getThreshold())));
                        break;
                    case STRING:
                        StringCondition sCond = (StringCondition) cond;
                        futures.add(session.executeAsync(insertConditionString.bind(sCond.getTenantId(),
                                sCond.getTriggerId(), sCond.getTriggerMode().name(), sCond.getContext(),
                                sCond.getConditionSetSize(), sCond.getConditionSetIndex(), sCond.getConditionId(),
                                sCond.getDataId(), sCond.getOperator().name(), sCond.getPattern(),
                                sCond.isIgnoreCase())));
                        break;
                    case THRESHOLD:
                        ThresholdCondition tCond = (ThresholdCondition) cond;
                        futures.add(session.executeAsync(insertConditionThreshold.bind(tCond.getTenantId(),
                                tCond.getTriggerId(), tCond.getTriggerMode().name(), tCond.getContext(),
                                tCond.getConditionSetSize(), tCond.getConditionSetIndex(),
                                tCond.getConditionId(), tCond.getDataId(), tCond.getOperator().name(),
                                tCond.getThreshold())));
                        break;
                    default:
                        throw new IllegalArgumentException("Unexpected ConditionType: " + cond);
                }
            }
            Futures.allAsList(futures).get();

        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        if (alertsContext.isInitialized() && alertsEngine != null) {
            alertsEngine.reloadTrigger(tenantId, triggerId);
        }

        notifyListeners(DefinitionsEvent.Type.CONDITION_CHANGE);

        return conditions;
    }

    private void insertTags(String tenantId, TagType type, String id, Map<String, String> tags)
            throws Exception {
        if (isEmpty(tags)) {
            return;
        }
        Session session = CassCluster.getSession();
        PreparedStatement insertTag = CassStatement.get(session, CassStatement.INSERT_TAG);
        if (insertTag == null) {
            throw new RuntimeException("insertTag PreparedStatement is null");
        }

        List<ResultSetFuture> futures = new ArrayList<>(tags.size());
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            futures.add(session.executeAsync(insertTag.bind(tenantId, type.name(), tag.getKey(), tag.getValue(), id)));
        }
        Futures.allAsList(futures).get();
    }

    private void removeConditions(String tenantId, String triggerId, Mode triggerMode) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must not be null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must not be null");
        }
        if (triggerMode == null) {
            throw new IllegalArgumentException("TriggerMode must not be null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement deleteConditionsMode = CassStatement.get(session, CassStatement.DELETE_CONDITIONS_MODE);
        if (deleteConditionsMode == null) {
            throw new RuntimeException("deleteConditionsMode PreparedStatement is null");
        }
        try {
            session.execute(deleteConditionsMode.bind(tenantId, triggerId, triggerMode.name()));

        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    private void deleteTags(String tenantId, TagType type, String id, Map<String, String> tags) throws Exception {
        if (isEmpty(tags)) {
            return;
        }

        Session session = CassCluster.getSession();
        PreparedStatement deleteTag = CassStatement.get(session, CassStatement.DELETE_TAG);

        List<ResultSetFuture> futures = new ArrayList<>(tags.size());
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            futures.add(session.executeAsync(deleteTag.bind(tenantId, type.name(), tag.getKey(), tag.getValue(), id)));
        }
        Futures.allAsList(futures).get();
    }

    @Override
    @Deprecated
    public Condition getCondition(String tenantId, String conditionId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(conditionId)) {
            throw new IllegalArgumentException("conditionId must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement selectConditionId = CassStatement.get(session, CassStatement.SELECT_CONDITION_ID);
        if (selectConditionId == null) {
            throw new RuntimeException("selectConditionId PreparedStatement is null");
        }
        Condition condition = null;
        try {
            ResultSet rsCondition = session.execute(selectConditionId.bind(tenantId, conditionId));
            Iterator<Row> itCondition = rsCondition.iterator();
            if (itCondition.hasNext()) {
                Row row = itCondition.next();
                condition = mapCondition(row);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        return condition;
    }

    @Override
    public Collection<Condition> getTriggerConditions(String tenantId, String triggerId, Mode triggerMode)
            throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("triggerId must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement selectTriggerConditions = CassStatement
                .get(session, CassStatement.SELECT_TRIGGER_CONDITIONS);
        PreparedStatement selectTriggerConditionsTriggerMode = CassStatement.get(session,
                CassStatement.SELECT_TRIGGER_CONDITIONS_TRIGGER_MODE);
        if (selectTriggerConditions == null || selectTriggerConditionsTriggerMode == null) {
            throw new RuntimeException("selectTriggerConditions* PreparedStatement is null");
        }
        List<Condition> conditions = new ArrayList<>();
        try {
            ResultSet rsConditions;
            if (triggerMode == null) {
                rsConditions = session.execute(selectTriggerConditions.bind(tenantId, triggerId));
            } else {
                rsConditions = session.execute(selectTriggerConditionsTriggerMode.bind(tenantId, triggerId,
                        triggerMode.name()));
            }
            mapConditions(rsConditions, conditions);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        return conditions;
    }

    // TODO: This getAll* fetches are cross-tenant fetch and may be inefficient at scale
    @Override
    public Collection<Condition> getAllConditions() throws Exception {
        Session session = CassCluster.getSession();
        PreparedStatement selectConditionsAll = CassStatement.get(session, CassStatement.SELECT_CONDITIONS_ALL);
        if (selectConditionsAll == null) {
            throw new RuntimeException("selectConditionsAll PreparedStatement is null");
        }
        List<Condition> conditions = new ArrayList<>();
        try {
            ResultSet rsConditions = session.execute(selectConditionsAll.bind());
            mapConditions(rsConditions, conditions);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return conditions;
    }

    @Override
    public Collection<Condition> getConditions(String tenantId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement selectConditionsByTenant = CassStatement.get(session,
                CassStatement.SELECT_CONDITIONS_BY_TENANT);
        if (selectConditionsByTenant == null) {
            throw new RuntimeException("selectConditionsByTenant PreparedStatement is null");
        }
        List<Condition> conditions = new ArrayList<>();
        try {
            ResultSet rsConditions = session.execute(selectConditionsByTenant.bind(tenantId));
            mapConditions(rsConditions, conditions);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return conditions;
    }

    private void mapConditions(ResultSet rsConditions, List<Condition> conditions) throws Exception {
        for (Row row : rsConditions) {
            Condition condition = mapCondition(row);
            if (condition != null) {
                conditions.add(condition);
            }
        }
    }

    private Condition mapCondition(Row row) throws Exception {
        Condition condition = null;
        String type = row.getString("type");
        if (type != null && !type.isEmpty()) {
            switch (Condition.Type.valueOf(type)) {
                case AVAILABILITY:
                    AvailabilityCondition aCondition = new AvailabilityCondition();
                    aCondition.setTenantId(row.getString("tenantId"));
                    aCondition.setTriggerId(row.getString("triggerId"));
                    aCondition.setTriggerMode(Mode.valueOf(row.getString("triggerMode")));
                    aCondition.setConditionSetSize(row.getInt("conditionSetSize"));
                    aCondition.setConditionSetIndex(row.getInt("conditionSetIndex"));
                    aCondition.setDataId(row.getString("dataId"));
                    aCondition.setOperator(AvailabilityCondition.Operator.valueOf(row.getString("operator")));
                    aCondition.setContext(row.getMap("context", String.class, String.class));
                    condition = aCondition;
                    break;
                case COMPARE:
                    CompareCondition cCondition = new CompareCondition();
                    cCondition.setTenantId(row.getString("tenantId"));
                    cCondition.setTriggerId(row.getString("triggerId"));
                    cCondition.setTriggerMode(Mode.valueOf(row.getString("triggerMode")));
                    cCondition.setConditionSetSize(row.getInt("conditionSetSize"));
                    cCondition.setConditionSetIndex(row.getInt("conditionSetIndex"));
                    cCondition.setDataId(row.getString("dataId"));
                    cCondition.setOperator(CompareCondition.Operator.valueOf(row.getString("operator")));
                    cCondition.setData2Id(row.getString("data2Id"));
                    cCondition.setData2Multiplier(row.getDouble("data2Multiplier"));
                    cCondition.setContext(row.getMap("context", String.class, String.class));
                    condition = cCondition;
                    break;
                case EXTERNAL:
                    ExternalCondition eCondition = new ExternalCondition();
                    eCondition.setTenantId(row.getString("tenantId"));
                    eCondition.setTriggerId(row.getString("triggerId"));
                    eCondition.setTriggerMode(Mode.valueOf(row.getString("triggerMode")));
                    eCondition.setConditionSetSize(row.getInt("conditionSetSize"));
                    eCondition.setConditionSetIndex(row.getInt("conditionSetIndex"));
                    eCondition.setDataId(row.getString("dataId"));
                    eCondition.setSystemId(row.getString("operator"));
                    eCondition.setExpression(row.getString("pattern"));
                    eCondition.setContext(row.getMap("context", String.class, String.class));
                    condition = eCondition;
                    break;
                case EVENT:
                    EventCondition evCondition = new EventCondition();
                    evCondition.setTenantId(row.getString("tenantId"));
                    evCondition.setTriggerId(row.getString("triggerId"));
                    evCondition.setTriggerMode(Mode.valueOf(row.getString("triggerMode")));
                    evCondition.setConditionSetSize(row.getInt("conditionSetSize"));
                    evCondition.setConditionSetIndex(row.getInt("conditionSetIndex"));
                    evCondition.setDataId(row.getString("dataId"));
                    evCondition.setExpression(row.getString("pattern"));
                    evCondition.setContext(row.getMap("context", String.class, String.class));
                    condition = evCondition;
                    break;
                case RANGE:
                    ThresholdRangeCondition rCondition = new ThresholdRangeCondition();
                    rCondition.setTenantId(row.getString("tenantId"));
                    rCondition.setTriggerId(row.getString("triggerId"));
                    rCondition.setTriggerMode(Mode.valueOf(row.getString("triggerMode")));
                    rCondition.setConditionSetSize(row.getInt("conditionSetSize"));
                    rCondition.setConditionSetIndex(row.getInt("conditionSetIndex"));
                    rCondition.setDataId(row.getString("dataId"));
                    rCondition.setOperatorLow(ThresholdRangeCondition.Operator.valueOf(row.getString("operatorLow")));
                    rCondition.setOperatorHigh(ThresholdRangeCondition.Operator.valueOf(row.getString("operatorHigh")));
                    rCondition.setThresholdLow(row.getDouble("thresholdLow"));
                    rCondition.setThresholdHigh(row.getDouble("thresholdHigh"));
                    rCondition.setInRange(row.getBool("inRange"));
                    rCondition.setContext(row.getMap("context", String.class, String.class));
                    condition = rCondition;
                    break;
                case RATE:
                    RateCondition rateCondition = new RateCondition();
                    rateCondition.setTenantId(row.getString("tenantId"));
                    rateCondition.setTriggerId(row.getString("triggerId"));
                    rateCondition.setTriggerMode(Mode.valueOf(row.getString("triggerMode")));
                    rateCondition.setConditionSetSize(row.getInt("conditionSetSize"));
                    rateCondition.setConditionSetIndex(row.getInt("conditionSetIndex"));
                    rateCondition.setDataId(row.getString("dataId"));
                    rateCondition.setDirection(RateCondition.Direction.valueOf(row.getString("direction")));
                    rateCondition.setPeriod(RateCondition.Period.valueOf(row.getString("period")));
                    rateCondition.setOperator(RateCondition.Operator.valueOf(row.getString("operator")));
                    rateCondition.setThreshold(row.getDouble("threshold"));
                    rateCondition.setContext(row.getMap("context", String.class, String.class));
                    condition = rateCondition;
                    break;
                case STRING:
                    StringCondition sCondition = new StringCondition();
                    sCondition.setTenantId(row.getString("tenantId"));
                    sCondition.setTriggerId(row.getString("triggerId"));
                    sCondition.setTriggerMode(Mode.valueOf(row.getString("triggerMode")));
                    sCondition.setConditionSetSize(row.getInt("conditionSetSize"));
                    sCondition.setConditionSetIndex(row.getInt("conditionSetIndex"));
                    sCondition.setDataId(row.getString("dataId"));
                    sCondition.setOperator(StringCondition.Operator.valueOf(row.getString("operator")));
                    sCondition.setPattern(row.getString("pattern"));
                    sCondition.setIgnoreCase(row.getBool("ignoreCase"));
                    sCondition.setContext(row.getMap("context", String.class, String.class));
                    condition = sCondition;
                    break;
                case THRESHOLD:
                    ThresholdCondition tCondition = new ThresholdCondition();
                    tCondition.setTenantId(row.getString("tenantId"));
                    tCondition.setTriggerId(row.getString("triggerId"));
                    tCondition.setTriggerMode(Mode.valueOf(row.getString("triggerMode")));
                    tCondition.setConditionSetSize(row.getInt("conditionSetSize"));
                    tCondition.setConditionSetIndex(row.getInt("conditionSetIndex"));
                    tCondition.setDataId(row.getString("dataId"));
                    tCondition.setOperator(ThresholdCondition.Operator.valueOf(row.getString("operator")));
                    tCondition.setThreshold(row.getDouble("threshold"));
                    tCondition.setContext(row.getMap("context", String.class, String.class));
                    condition = tCondition;
                    break;
                default:
                    if (log.isDebugEnabled()) {
                        log.debug("Unexpected condition type found: " + type);
                    }
                    break;
            }
        } else {
            log.debug("Invalid condition type: null or empty");
        }
        return condition;
    }

    @Override
    public void addActionPlugin(String actionPlugin, Set<String> properties) throws Exception {
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("actionPlugin must be not null");
        }
        if (properties == null || properties.isEmpty()) {
            throw new IllegalArgumentException("properties must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement insertActionPlugin = CassStatement.get(session, CassStatement.INSERT_ACTION_PLUGIN);
        if (insertActionPlugin == null) {
            throw new RuntimeException("insertActionPlugin PreparedStatement is null");
        }
        try {
            session.execute(insertActionPlugin.bind(actionPlugin, properties));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public void addActionPlugin(String actionPlugin, Map<String, String> defaultProperties) throws Exception {
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("actionPlugin must be not null");
        }
        if (defaultProperties == null || defaultProperties.isEmpty()) {
            throw new IllegalArgumentException("defaultProperties must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement insertActionPluginDefaulProperties = CassStatement.get(session,
                CassStatement.INSERT_ACTION_PLUGIN_DEFAULT_PROPERTIES);
        if (insertActionPluginDefaulProperties == null) {
            throw new RuntimeException("insertDefaulPropertiesActionPlugin PreparedStatement is null");
        }
        try {
            Set<String> properties = defaultProperties.keySet();
            session.execute(insertActionPluginDefaulProperties.bind(actionPlugin, properties, defaultProperties));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public void removeActionPlugin(String actionPlugin) throws Exception {
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("actionPlugin must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement deleteActionPlugin = CassStatement.get(session, CassStatement.DELETE_ACTION_PLUGIN);
        if (deleteActionPlugin == null) {
            throw new RuntimeException("deleteActionPlugin PreparedStatement is null");
        }
        try {
            session.execute(deleteActionPlugin.bind(actionPlugin));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public void updateActionPlugin(String actionPlugin, Set<String> properties) throws Exception {
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("actionPlugin must be not null");
        }
        if (properties == null || properties.isEmpty()) {
            throw new IllegalArgumentException("properties must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement updateActionPlugin = CassStatement.get(session, CassStatement.UPDATE_ACTION_PLUGIN);
        if (updateActionPlugin == null) {
            throw new RuntimeException("updateActionPlugin PreparedStatement is null");
        }
        try {
            session.execute(updateActionPlugin.bind(properties, actionPlugin));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public void updateActionPlugin(String actionPlugin, Map<String, String> defaultProperties) throws Exception {
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("actionPlugin must be not null");
        }
        if (defaultProperties == null || defaultProperties.isEmpty()) {
            throw new IllegalArgumentException("defaultProperties must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement updateDefaultPropertiesActionPlugin = CassStatement.get(session,
                CassStatement.UPDATE_ACTION_PLUGIN_DEFAULT_PROPERTIES);
        if (updateDefaultPropertiesActionPlugin == null) {
            throw new RuntimeException("updateDefaultPropertiesActionPlugin PreparedStatement is null");
        }
        try {
            Set<String> properties = defaultProperties.keySet();
            session.execute(updateDefaultPropertiesActionPlugin.bind(properties, defaultProperties, actionPlugin));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public Collection<String> getActionPlugins() throws Exception {
        Session session = CassCluster.getSession();
        PreparedStatement selectActionPlugins = CassStatement.get(session, CassStatement.SELECT_ACTION_PLUGINS);
        if (selectActionPlugins == null) {
            throw new RuntimeException("selectActionPlugins PreparedStatement is null");
        }
        ArrayList<String> actionPlugins = new ArrayList<>();
        try {
            ResultSet rsActionPlugins = session.execute(selectActionPlugins.bind());
            for (Row row : rsActionPlugins) {
                actionPlugins.add(row.getString("actionPlugin"));
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return actionPlugins;
    }

    @Override
    @AccessTimeout(value = 60, unit = TimeUnit.SECONDS)
    public Set<String> getActionPlugin(String actionPlugin) throws Exception {
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("actionPlugin must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement selectActionPlugin = CassStatement.get(session, CassStatement.SELECT_ACTION_PLUGIN);
        if (selectActionPlugin == null) {
            throw new RuntimeException("selectActionPlugin PreparedStatement is null");
        }
        Set<String> properties = null;
        try {
            ResultSet rsActionPlugin = session.execute(selectActionPlugin.bind(actionPlugin));
            Iterator<Row> itActionPlugin = rsActionPlugin.iterator();
            if (itActionPlugin.hasNext()) {
                Row row = itActionPlugin.next();
                properties = row.getSet("properties", String.class);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return properties;
    }

    @Override
    public Map<String, String> getDefaultActionPlugin(String actionPlugin) throws Exception {
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("actionPlugin must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement selectActionPluginDefaultProperties = CassStatement.get(session,
                CassStatement.SELECT_ACTION_PLUGIN_DEFAULT_PROPERTIES);
        if (selectActionPluginDefaultProperties == null) {
            throw new RuntimeException("selectDefaultPropertiesActionPlugin PreparedStatement is null");
        }
        Map<String, String> defaultProperties = null;
        try {
            ResultSet rsActionPlugin = session.execute(selectActionPluginDefaultProperties.bind(actionPlugin));
            Iterator<Row> itActionPlugin = rsActionPlugin.iterator();
            if (itActionPlugin.hasNext()) {
                Row row = itActionPlugin.next();
                defaultProperties = row.getMap("defaultProperties", String.class, String.class);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return defaultProperties;
    }

    @Override
    public void removeActionDefinition(String tenantId, String actionPlugin, String actionId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("ActionPlugin must be not null");
        }
        if (isEmpty(actionId)) {
            throw new IllegalArgumentException("ActionId must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement deleteAction = CassStatement.get(session, CassStatement.DELETE_ACTION_DEFINITION);
        if (deleteAction == null) {
            throw new RuntimeException("deleteAction PreparedStatement is null");
        }
        try {
            session.execute(deleteAction.bind(tenantId, actionPlugin, actionId));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public void updateActionDefinition(String tenantId, ActionDefinition actionDefinition) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (actionDefinition == null) {
            throw new IllegalArgumentException("actionDefinition must be not null");
        }
        if (isEmpty(actionDefinition.getActionPlugin())) {
            throw new IllegalArgumentException("ActionPlugin must be not null");
        }
        if (isEmpty(actionDefinition.getActionId())) {
            throw new IllegalArgumentException("ActionId must be not null");
        }
        if (isEmpty(actionDefinition.getProperties())) {
            throw new IllegalArgumentException("Properties must be not null");
        }

        Session session = CassCluster.getSession();
        PreparedStatement updateAction = CassStatement.get(session, CassStatement.UPDATE_ACTION_DEFINITION);
        if (updateAction == null) {
            throw new RuntimeException("updateAction PreparedStatement is null");
        }
        try {
            session.execute(updateAction.bind(JsonUtil.toJson(actionDefinition), tenantId,
                    actionDefinition.getActionPlugin(), actionDefinition.getActionId()));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    // TODO: This getAll* fetches are cross-tenant fetch and may be inefficient at scale
    @Override
    public Map<String, Map<String, Set<String>>> getAllActionDefinitionIds() throws Exception {
        Session session = CassCluster.getSession();
        PreparedStatement selectActionsAll = CassStatement.get(session, CassStatement.SELECT_ACTION_ID_ALL);
        if (selectActionsAll == null) {
            throw new RuntimeException("selectActionsAll PreparedStatement is null");
        }
        Map<String, Map<String, Set<String>>> actions = new HashMap<>();
        try {
            ResultSet rsActions = session.execute(selectActionsAll.bind());
            for (Row row : rsActions) {
                String tenantId = row.getString("tenantId");
                String actionPlugin = row.getString("actionPlugin");
                String actionId = row.getString("actionId");
                if (actions.get(tenantId) == null) {
                    actions.put(tenantId, new HashMap<>());
                }
                if (actions.get(tenantId).get(actionPlugin) == null) {
                    actions.get(tenantId).put(actionPlugin, new HashSet<>());
                }
                actions.get(tenantId).get(actionPlugin).add(actionId);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return actions;
    }

    @Override
    public Map<String, Set<String>> getActionDefinitionIds(String tenantId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement selectActionsByTenant = CassStatement.get(session,
                CassStatement.SELECT_ACTION_ID_BY_TENANT);
        if (selectActionsByTenant == null) {
            throw new RuntimeException("selectActionsByTenant PreparedStatement is null");
        }
        Map<String, Set<String>> actions = new HashMap<>();
        try {
            ResultSet rsActions = session.execute(selectActionsByTenant.bind(tenantId));
            for (Row row : rsActions) {
                String actionPlugin = row.getString("actionPlugin");
                String actionId = row.getString("actionId");
                if (actions.get(actionPlugin) == null) {
                    actions.put(actionPlugin, new HashSet<>());
                }
                actions.get(actionPlugin).add(actionId);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return actions;
    }

    @Override
    public Collection<String> getActionDefinitionIds(String tenantId, String actionPlugin) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("actionPlugin must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement selectActionsPlugin = CassStatement.get(session, CassStatement.SELECT_ACTION_ID_BY_PLUGIN);
        if (selectActionsPlugin == null) {
            throw new RuntimeException("selectActionsPlugin PreparedStatement is null");
        }
        ArrayList<String> actions = new ArrayList<>();
        try {
            ResultSet rsActions = session.execute(selectActionsPlugin.bind(tenantId, actionPlugin));
            for (Row row : rsActions) {
                actions.add(row.getString("actionId"));
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return actions;
    }

    @Override
    public ActionDefinition getActionDefinition(String tenantId, String actionPlugin, String actionId)
            throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("ActionPlugin must be not null");
        }
        if (isEmpty(actionId)) {
            throw new IllegalArgumentException("actionId must be not null");
        }
        Session session = CassCluster.getSession();
        PreparedStatement selectAction = CassStatement.get(session, CassStatement.SELECT_ACTION_DEFINITION);
        if (selectAction == null) {
            throw new RuntimeException("selectAction PreparedStatement is null");
        }
        ActionDefinition actionDefinition = null;
        try {
            ResultSet rsAction = session.execute(selectAction.bind(tenantId, actionPlugin, actionId));
            Iterator<Row> itAction = rsAction.iterator();
            if (itAction.hasNext()) {
                Row row = itAction.next();
                actionDefinition = JsonUtil.fromJson(row.getString("payload"), ActionDefinition.class);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return actionDefinition;
    }

    @Override
    public void registerListener(DefinitionsListener listener, Type eventType, Type... eventTypes) {
        alertsContext.registerDefinitionListener(listener, eventType, eventTypes);
    }

    private void notifyListeners(Type eventType) {
        DefinitionsEvent de = new DefinitionsEvent(eventType);
        if (log.isDebugEnabled()) {
            log.debug("Notifying applicable listeners " + alertsContext.getDefinitionListeners() +
                    " of event " + eventType.name());
        }
        for (Map.Entry<DefinitionsListener, Set<Type>> me : alertsContext.getDefinitionListeners().entrySet()) {
            if (me.getValue().contains(eventType)) {
                if (log.isDebugEnabled()) {
                    log.debug("Notified Listener " + eventType.name());
                }
                me.getKey().onChange(de);
            }
        }
    }

    private boolean isEmpty(Trigger trigger) {
        return trigger == null || trigger.getId() == null || trigger.getId().trim().isEmpty();
    }

    private boolean isEmpty(Dampening dampening) {
        return dampening == null || dampening.getTriggerId() == null || dampening.getTriggerId().trim().isEmpty() ||
                dampening.getDampeningId() == null || dampening.getDampeningId().trim().isEmpty();
    }

    private boolean isEmpty(String id) {
        return id == null || id.trim().isEmpty();
    }

    public boolean isEmpty(Map<String, String> map) {
        return map == null || map.isEmpty();
    }

    private boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    /*
        The attribute tenantId is part of the Trigger object.
        But it is also important to have it specifically on the services calls.
        So, in case that a tenantId parameter does not match with trigger.tenantId attribute,
        this last one will be overwritten with the parameter.
     */
    private void checkTenantId(String tenantId, Object obj) {
        if (isEmpty(tenantId)) {
            return;
        }
        if (obj == null) {
            return;
        }
        if (obj instanceof Trigger) {
            Trigger trigger = (Trigger) obj;
            if (trigger.getTenantId() == null || !trigger.getTenantId().equals(tenantId)) {
                trigger.setTenantId(tenantId);
            }
        } else if (obj instanceof Dampening) {
            Dampening dampening = (Dampening) obj;
            if (dampening.getTenantId() == null || !dampening.getTenantId().equals(tenantId)) {
                dampening.setTenantId(tenantId);
            }
        }
    }

}