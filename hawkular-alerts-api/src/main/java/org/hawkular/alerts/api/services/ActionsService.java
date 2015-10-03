/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
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
package org.hawkular.alerts.api.services;

import org.hawkular.alerts.api.model.action.Action;
import org.hawkular.alerts.api.model.paging.Page;
import org.hawkular.alerts.api.model.paging.Pager;

/**
 * A interface used to send actions.
 *
 * Actions will be created inside of the alerts engine and will be delivered using a chain of ActionListener listeners.
 *
 * @author Jay Shaughnessy
 * @author Lucas Ponce
 */
public interface ActionsService {

    /**
     * Send an action to be processed by the plugins architecture.
     *
     * @param action Action to send
     */
    void send(Action action);

    /**
     * Update the result of an action.
     *
     * @param action Action
     */
    void updateResult(Action action);

    /**
     * @param tenantId Tenant where actions are stored
     * @param criteria If null returns all actions (not recommended)
     * @param pager Paging requirement for fetching actions. Optional. Return all if null.
     * @return NotNull, can be empty.
     * @throws Exception
     */
    Page<Action> getActions(String tenantId, ActionsCriteria criteria, Pager pager) throws Exception;

    /**
     * Delete the requested Actions from the history, as described by the provided criteria.
     *
     * @param tenantId
     * @param criteria
     * @return
     * @throws Exception
     */
    int deleteActions(String tenantId, ActionsCriteria criteria) throws Exception;

    /**
     * Register a listener that will process actions.
     * ActionListeners are responsible to connect Actions with plugins.
     *
     * @param listener the listener
     */
    void addListener(ActionListener listener);

}
