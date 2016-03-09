/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.syncope.client.console.wizards.any;

import de.agilecoders.wicket.extensions.markup.html.bootstrap.form.checkbox.bootstraptoggle.BootstrapToggle;
import de.agilecoders.wicket.extensions.markup.html.bootstrap.form.checkbox.bootstraptoggle.BootstrapToggleConfig;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.syncope.client.console.commons.Constants;
import org.apache.syncope.client.console.panels.search.AnySelectionSearchResultPanel;
import org.apache.syncope.client.console.panels.search.GroupSearchPanel;
import org.apache.syncope.client.console.panels.search.GroupSelectionSearchResultPanel;
import org.apache.syncope.client.console.panels.search.SearchClause;
import org.apache.syncope.client.console.panels.search.SearchClausePanel;
import org.apache.syncope.client.console.panels.search.SearchUtils;
import org.apache.syncope.client.console.panels.search.UserSearchPanel;
import org.apache.syncope.client.console.panels.search.UserSelectionSearchResultPanel;
import org.apache.syncope.client.console.rest.AnyTypeClassRestClient;
import org.apache.syncope.client.console.rest.AnyTypeRestClient;
import org.apache.syncope.client.console.rest.GroupRestClient;
import org.apache.syncope.client.console.rest.UserRestClient;
import org.apache.syncope.client.console.wicket.markup.html.form.AjaxTextFieldPanel;
import org.apache.syncope.client.lib.SyncopeClient;
import org.apache.syncope.common.lib.to.AnyTO;
import org.apache.syncope.common.lib.to.AnyTypeTO;
import org.apache.syncope.common.lib.to.GroupTO;
import org.apache.syncope.common.lib.to.UserTO;
import org.apache.syncope.common.lib.types.AnyTypeKind;
import org.apache.wicket.PageReference;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.form.AjaxFormComponentUpdatingBehavior;
import org.apache.wicket.event.Broadcast;
import org.apache.wicket.event.IEvent;
import org.apache.wicket.extensions.ajax.markup.html.IndicatingAjaxLink;
import org.apache.wicket.extensions.wizard.WizardStep;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.form.CheckBox;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.model.PropertyModel;
import org.apache.wicket.model.util.ListModel;

public class Ownership extends WizardStep {

    private static final long serialVersionUID = 855618618337931784L;

    private final Pattern owner = Pattern.compile("\\[\\(\\d+\\)\\] .*");

    private final GroupHandler handler;

    private final WebMarkupContainer ownerContainer;

    private final AnyTypeRestClient anyTypeRestClient = new AnyTypeRestClient();

    private final AnyTypeClassRestClient anyTypeClassRestClient = new AnyTypeClassRestClient();

    private final GroupSearchPanel groupSearchPanel;

    private final GroupRestClient groupRestClient = new GroupRestClient();

    private final Fragment groupSearchFragment;

    private final GroupSelectionSearchResultPanel groupSearchResultPanel;

    private final UserSearchPanel userSearchPanel;

    private final UserRestClient userRestClient = new UserRestClient();

    private final Fragment userSearchFragment;

    private final UserSelectionSearchResultPanel userSearchResultPanel;

    private final Model<Boolean> isGroupOwnership;

    public Ownership(final GroupHandler groupHandler, final PageReference pageRef) {
        super();
        this.handler = groupHandler;

        isGroupOwnership = Model.of(groupHandler.getInnerObject().getGroupOwner() != null);

        final BootstrapToggleConfig config = new BootstrapToggleConfig();
        config
                .withOnStyle(BootstrapToggleConfig.Style.info).withOffStyle(BootstrapToggleConfig.Style.warning)
                .withSize(BootstrapToggleConfig.Size.mini)
                .withOnLabel(AnyTypeKind.GROUP.name())
                .withOffLabel(AnyTypeKind.USER.name());

        add(new BootstrapToggle("ownership", new Model<Boolean>() {

            private static final long serialVersionUID = 1L;

            @Override
            public Boolean getObject() {
                return isGroupOwnership.getObject();
            }
        }, config) {

            private static final long serialVersionUID = 1L;

            @Override
            protected IModel<String> getOffLabel() {
                return Model.of(getString("Off", null, "USER Owner"));
            }

            @Override
            protected IModel<String> getOnLabel() {
                return Model.of(getString("On", null, "GROUP Owner"));
            }

            @Override
            protected CheckBox newCheckBox(final String id, final IModel<Boolean> model) {
                final CheckBox checkBox = super.newCheckBox(id, model);
                checkBox.add(new AjaxFormComponentUpdatingBehavior(Constants.ON_CHANGE) {

                    private static final long serialVersionUID = 1L;

                    @Override
                    protected void onUpdate(final AjaxRequestTarget target) {
                        isGroupOwnership.setObject(!isGroupOwnership.getObject());
                        if (isGroupOwnership.getObject()) {
                            ownerContainer.addOrReplace(groupSearchFragment);
                            groupSearchResultPanel.search(null, target);
                        } else {
                            ownerContainer.addOrReplace(userSearchFragment);
                            userSearchResultPanel.search(null, target);
                        }
                        target.add(ownerContainer);
                    }
                });
                return checkBox;
            }
        });

        ownerContainer = new WebMarkupContainer("ownerContainer");
        ownerContainer.setOutputMarkupId(true);
        this.add(ownerContainer);

        groupSearchFragment = new Fragment("search", "groupSearchFragment", this);
        groupSearchPanel = new GroupSearchPanel.Builder(
                new ListModel<>(new ArrayList<SearchClause>())).required(false).enableSearch().build("groupsearch");
        groupSearchFragment.add(groupSearchPanel.setRenderBodyOnly(true));

        AnyTypeTO anyTypeTO = anyTypeRestClient.get(AnyTypeKind.GROUP.name());

        groupSearchResultPanel = GroupSelectionSearchResultPanel.class.cast(new GroupSelectionSearchResultPanel.Builder(
                anyTypeClassRestClient.list(anyTypeTO.getClasses()),
                anyTypeTO.getKey(),
                pageRef).build("searchResult"));

        groupSearchFragment.add(groupSearchResultPanel);

        userSearchFragment = new Fragment("search", "userSearchFragment", this);
        userSearchPanel = UserSearchPanel.class.cast(new UserSearchPanel.Builder(
                new ListModel<>(new ArrayList<SearchClause>())).required(false).enableSearch().build("usersearch"));
        userSearchFragment.add(userSearchPanel.setRenderBodyOnly(true));

        anyTypeTO = anyTypeRestClient.get(AnyTypeKind.USER.name());

        userSearchResultPanel = UserSelectionSearchResultPanel.class.cast(new UserSelectionSearchResultPanel.Builder(
                anyTypeClassRestClient.list(anyTypeTO.getClasses()),
                anyTypeTO.getKey(),
                pageRef).build("searchResult"));

        userSearchFragment.add(userSearchResultPanel);

        if (isGroupOwnership.getObject()) {
            ownerContainer.add(groupSearchFragment);
        } else {
            ownerContainer.add(userSearchFragment);
        }

        final AjaxTextFieldPanel userOwner = new AjaxTextFieldPanel(
                "userOwner", "userOwner", new PropertyModel<String>(groupHandler.getInnerObject(), "userOwner") {

            private static final long serialVersionUID = -3743432456095828573L;

            @Override
            public String getObject() {
                if (groupHandler.getInnerObject().getUserOwner() == null) {
                    return StringUtils.EMPTY;
                } else {
                    UserTO userTO = userRestClient.read(groupHandler.getInnerObject().getUserOwner());
                    if (userTO == null) {
                        return StringUtils.EMPTY;
                    } else {
                        return String.format("[%d] %s", userTO.getKey(), userTO.getUsername());
                    }
                }
            }

            @Override
            public void setObject(final String object) {
                if (StringUtils.isBlank(object)) {
                    groupHandler.getInnerObject().setUserOwner(null);
                } else {
                    final Matcher matcher = owner.matcher(object);
                    if (matcher.matches()) {
                        groupHandler.getInnerObject().setUserOwner(Long.parseLong(matcher.group(1)));
                    }
                }
            }
        }, false);
        userOwner.setPlaceholder("userOwner");
        userOwner.hideLabel();
        userOwner.setReadOnly(true).setOutputMarkupId(true);
        userSearchFragment.add(userOwner);

        final IndicatingAjaxLink<Void> userOwnerReset = new IndicatingAjaxLink<Void>("userOwnerReset") {

            private static final long serialVersionUID = -7978723352517770644L;

            @Override
            public void onClick(final AjaxRequestTarget target) {
                send(Ownership.this, Broadcast.EXACT,
                        new GroupSelectionSearchResultPanel.ItemSelection<GroupTO>(target, null));
            }
        };
        userSearchFragment.add(userOwnerReset);

        final AjaxTextFieldPanel groupOwner = new AjaxTextFieldPanel(
                "groupOwner", "groupOwner", new PropertyModel<String>(groupHandler.getInnerObject(), "groupOwner") {

            private static final long serialVersionUID = -3743432456095828573L;

            @Override
            public String getObject() {
                if (groupHandler.getInnerObject().getGroupOwner() == null) {
                    return StringUtils.EMPTY;
                } else {
                    GroupTO groupTO = groupRestClient.read(groupHandler.getInnerObject().getGroupOwner());
                    if (groupTO == null) {
                        return StringUtils.EMPTY;
                    } else {
                        return String.format("[%d] %s", groupTO.getKey(), groupTO.getName());
                    }
                }
            }

            @Override
            public void setObject(final String object) {
                if (StringUtils.isBlank(object)) {
                    groupHandler.getInnerObject().setGroupOwner(null);
                } else {
                    final Matcher matcher = owner.matcher(object);
                    if (matcher.matches()) {
                        groupHandler.getInnerObject().setGroupOwner(Long.parseLong(matcher.group(1)));
                    }
                }
            }
        }, false);
        groupOwner.setPlaceholder("groupOwner");
        groupOwner.hideLabel();
        groupOwner.setReadOnly(true).setOutputMarkupId(true);
        groupSearchFragment.add(groupOwner);

        final IndicatingAjaxLink<Void> groupOwnerReset = new IndicatingAjaxLink<Void>("groupOwnerReset") {

            private static final long serialVersionUID = -7978723352517770644L;

            @Override
            public void onClick(final AjaxRequestTarget target) {
                send(Ownership.this, Broadcast.EXACT,
                        new GroupSelectionSearchResultPanel.ItemSelection<GroupTO>(target, null));
            }
        };
        groupSearchFragment.add(groupOwnerReset);
    }

    @Override
    public void onEvent(final IEvent<?> event) {
        if (event.getPayload() instanceof SearchClausePanel.SearchEvent) {
            final AjaxRequestTarget target = SearchClausePanel.SearchEvent.class.cast(event.getPayload()).getTarget();
            if (Ownership.this.isGroupOwnership.getObject()) {
                final String fiql = SearchUtils.buildFIQL(
                        groupSearchPanel.getModel().getObject(), SyncopeClient.getGroupSearchConditionBuilder());
                groupSearchResultPanel.search(fiql, target);
            } else {
                final String fiql = SearchUtils.buildFIQL(
                        userSearchPanel.getModel().getObject(), SyncopeClient.getUserSearchConditionBuilder());
                userSearchResultPanel.search(fiql, target);
            }
        } else if (event.getPayload() instanceof AnySelectionSearchResultPanel.ItemSelection) {
            final AnyTO sel = ((AnySelectionSearchResultPanel.ItemSelection) event.getPayload()).getSelection();
            if (sel == null) {
                handler.getInnerObject().setUserOwner(null);
                handler.getInnerObject().setGroupOwner(null);
            } else if (sel instanceof UserTO) {
                handler.getInnerObject().setUserOwner(sel.getKey());
                handler.getInnerObject().setGroupOwner(null);
                ((UserSelectionSearchResultPanel.ItemSelection) event.getPayload()).getTarget().add(ownerContainer);
            } else if (sel instanceof GroupTO) {
                handler.getInnerObject().setGroupOwner(sel.getKey());
                handler.getInnerObject().setUserOwner(null);
                ((GroupSelectionSearchResultPanel.ItemSelection) event.getPayload()).getTarget().add(ownerContainer);
            }
        } else {
            super.onEvent(event);
        }
    }
}
