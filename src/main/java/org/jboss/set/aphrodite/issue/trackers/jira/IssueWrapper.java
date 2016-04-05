/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.set.aphrodite.issue.trackers.jira;

import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.BROWSE_ISSUE_PATH;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.DEV_ACK;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.JSON_CUSTOM_FIELD;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.PM_ACK;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.QE_ACK;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.FLAG_MAP;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.TARGET_RELEASE;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.getAphroditeStatus;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.jboss.set.aphrodite.domain.Comment;
import org.jboss.set.aphrodite.domain.Flag;
import org.jboss.set.aphrodite.domain.FlagStatus;
import org.jboss.set.aphrodite.domain.Issue;
import org.jboss.set.aphrodite.domain.IssueEstimation;
import org.jboss.set.aphrodite.domain.IssueType;
import org.jboss.set.aphrodite.domain.Stage;

import com.atlassian.jira.rest.client.api.domain.BasicComponent;
import com.atlassian.jira.rest.client.api.domain.BasicProject;
import com.atlassian.jira.rest.client.api.domain.IssueField;
import com.atlassian.jira.rest.client.api.domain.IssueFieldId;
import com.atlassian.jira.rest.client.api.domain.IssueLinkType.Direction;
import com.atlassian.jira.rest.client.api.domain.Project;
import com.atlassian.jira.rest.client.api.domain.TimeTracking;
import com.atlassian.jira.rest.client.api.domain.User;
import com.atlassian.jira.rest.client.api.domain.Version;
import com.atlassian.jira.rest.client.api.domain.input.ComplexIssueInputFieldValue;
import com.atlassian.jira.rest.client.api.domain.input.FieldInput;
import com.atlassian.jira.rest.client.api.domain.input.IssueInput;
import com.atlassian.jira.rest.client.api.domain.input.IssueInputBuilder;
import org.jboss.set.aphrodite.spi.NotFoundException;

/**
 * @author Ryan Emerson
 */
class IssueWrapper {

    private static final Log LOG = LogFactory.getLog(JiraIssueTracker.class);

    Issue jiraSearchIssueToIssue(URL baseURL, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        URL url = trackerIdToBrowsableUrl(baseURL, jiraIssue.getKey());
        return jiraIssueToIssue(url, jiraIssue);
    }

    private void setCreationTime(JiraIssue issue, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        issue.setCreationTime(jiraIssue.getCreationDate().toDate());
    }

    private void setLastUpdated(JiraIssue issue, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        issue.setLastUpdated(jiraIssue.getUpdateDate().toDate());
    }

    Issue jiraIssueToIssue(URL url, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        JiraIssue issue = new JiraIssue(url);

        issue.setTrackerId(jiraIssue.getKey());
        issue.setSummary(jiraIssue.getSummary());
        issue.setDescription(jiraIssue.getDescription());
        issue.setStatus(getAphroditeStatus(jiraIssue.getStatus().getName()));

        TimeTracking timeTracking = jiraIssue.getTimeTracking();
        if(timeTracking != null) {
            int estimate = (timeTracking.getOriginalEstimateMinutes() == null) ? 0 : timeTracking.getOriginalEstimateMinutes();
            int spent = (timeTracking.getTimeSpentMinutes() == null) ? 0 : timeTracking.getTimeSpentMinutes();
            issue.setEstimation(new IssueEstimation(estimate / 60d, spent / 60d));
        }

        setIssueStream(issue, jiraIssue);
        setIssueProject(issue, jiraIssue);
        setIssueComponent(issue, jiraIssue);
        setIssueAssignee(issue, jiraIssue);
        setIssueReporter(issue, jiraIssue);

        setIssueStage(issue, jiraIssue);
        setIssueType(issue, jiraIssue);
        setFixVersions(issue, jiraIssue);
        setIssueDependencies(url, issue, jiraIssue.getIssueLinks());
        setIssueComments(issue, jiraIssue);
        setCreationTime(issue, jiraIssue);
        setLastUpdated(issue, jiraIssue);

        return issue;
    }

    // TODO find a solution for updating time estimates, see https://github.com/jboss-set/aphrodite/issues/23
    IssueInput issueToFluentUpdate(JiraIssue issue, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue,
                                   Project project) throws NotFoundException {
        checkUnsupportedUpdateFields(issue);
        IssueInputBuilder inputBuilder = new IssueInputBuilder(jiraIssue.getProject().getKey(), jiraIssue.getIssueType().getId());

        issue.getSummary().ifPresent(inputBuilder::setSummary);
        inputBuilder.setFieldInput(new FieldInput(IssueFieldId.COMPONENTS_FIELD,
                issue.getComponents().stream().map(e -> ComplexIssueInputFieldValue.with("name", e)).collect(Collectors.toList()))
        );
        issue.getDescription().ifPresent(inputBuilder::setDescription);
        issue.getAssignee().ifPresent(
            assignee -> inputBuilder.setFieldInput(new FieldInput(IssueFieldId.ASSIGNEE_FIELD, ComplexIssueInputFieldValue.with("name", assignee)))
        );

        issue.getStreamStatus()

        // this is ok but does nothing if there is no permissions.
        issue.getStage().getStateMap().entrySet()
            .stream().filter(entry -> entry.getValue() != FlagStatus.NO_SET)
            .forEach(entry -> inputBuilder.setFieldInput(new FieldInput(JSON_CUSTOM_FIELD + FLAG_MAP.get(entry.getKey()), entry.getValue().getSymbol())));

        return updateFixVersions(issue, project, inputBuilder).build();
    }

    private void updateStreamStatus(JiraIssue issue, Project project, IssueInputBuilder inputBuilder) {
        Map<String, Version> versionsMap = StreamSupport.stream(project.getVersions().spliterator(), false)
                .collect(Collectors.toMap(Version::getName, Function.identity()));

        for (Map.Entry<String, FlagStatus> entry : issue.getStreamStatus().entrySet()) {
            if (entry.getValue() != FlagStatus.ACCEPTED) {
                Version version = versionsMap.get(entry.getKey());
                if (version != null) {
//                    projectVersions.add(versionsMap.get(en));
                } else {
                    throw new NotFoundException("No version exists for this project with name : " + versionName);
                }
            }
        }
    }

    private IssueInputBuilder updateFixVersions(JiraIssue issue, Project project, IssueInputBuilder inputBuilder) throws NotFoundException {
        Map<String, Version> versionsMap = StreamSupport.stream(project.getVersions().spliterator(), false)
                .collect(Collectors.toMap(Version::getName, Function.identity()));

        List<Version> projectVersions = new ArrayList<>();
        for (String versionName : issue.getFixVersions()) {
            Version version = versionsMap.get(versionName);
            if (version != null) {
                projectVersions.add(version);
            } else {
                throw new NotFoundException("No version exists for this project with name : " + versionName);
            }
        }

        inputBuilder.setFixVersions(projectVersions);
        return inputBuilder;
    }

    private void checkUnsupportedUpdateFields(Issue issue) {
        if (issue.getReporter().isPresent() && LOG.isDebugEnabled())
            LOG.debug("JIRA does not support updating the reporter field, field ignored.");
    }

    private void setIssueProject(JiraIssue issue, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        BasicProject project = jiraIssue.getProject();
        if (project != null)
            issue.setProduct(project.getName());
    }

    private void setIssueComponent(JiraIssue issue, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        Iterable<BasicComponent> components = jiraIssue.getComponents();
        List<String> tmp = new ArrayList<>();
        for(BasicComponent component : components) {
            tmp.add(component.getName());
        }
        issue.setComponents(tmp);
    }

    private void setIssueAssignee(JiraIssue issue, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        User assignee = jiraIssue.getAssignee();
        if (assignee != null)
            issue.setAssignee(assignee.getName());
    }

    private void setIssueReporter(JiraIssue issue, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        User reporter = jiraIssue.getReporter();
        if (reporter != null)
            issue.setReporter(reporter.getName());
    }

    private void setIssueStage(JiraIssue issue, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        Stage stage = new Stage();
        stage.setStatus(Flag.PM, FlagStatus.getMatchingFlag((String) jiraIssue.getField(JSON_CUSTOM_FIELD + PM_ACK).getValue()));
        stage.setStatus(Flag.DEV, FlagStatus.getMatchingFlag((String) jiraIssue.getField(JSON_CUSTOM_FIELD + DEV_ACK).getValue()));
        stage.setStatus(Flag.QE, FlagStatus.getMatchingFlag((String) jiraIssue.getField(JSON_CUSTOM_FIELD + QE_ACK).getValue()));
        issue.setStage(stage);
    }

    private void setIssueType(JiraIssue issue, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        String type = jiraIssue.getIssueType().getName();
        try {
            issue.setType(IssueType.valueOf(type.toUpperCase()));
        } catch (IllegalArgumentException e) {
            issue.setType(IssueType.UNDEFINED);
        }
    }

    private void setIssueStream(JiraIssue issue, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        try {
            IssueField jsonField = jiraIssue.getField(JSON_CUSTOM_FIELD + TARGET_RELEASE);
            if (jsonField == null || jsonField.getValue() == null) {
                return;
            }
            JSONObject value = (JSONObject) jsonField.getValue();
            Map<String, FlagStatus> streamStatus;
            streamStatus = Collections.singletonMap(value.getString("name"), FlagStatus.ACCEPTED);
            issue.setStreamStatus(streamStatus);
        } catch (JSONException e) {
            LOG.error("error setting the stream in " + jiraIssue.getKey(), e);
        }

    }

    private void setFixVersions(JiraIssue issue, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        Iterable<Version> versions = jiraIssue.getFixVersions();
        if (versions != null) {
            List<String> strings = StreamSupport.stream(jiraIssue.getFixVersions().spliterator(), false)
                    .map(Version::getName)
                    .collect(Collectors.toList());
            issue.setFixVersions(strings);
        }
    }

    private void setIssueDependencies(URL originalUrl, JiraIssue issue, Iterable<com.atlassian.jira.rest.client.api.domain.IssueLink> links) {
        if (links == null)
            return;

        for (com.atlassian.jira.rest.client.api.domain.IssueLink il : links) {
            if (il.getIssueLinkType().getDirection().equals(Direction.INBOUND)) {
                URL url = trackerIdToBrowsableUrl(originalUrl, il.getTargetIssueKey());
                issue.getBlocks().add(url);
            } else {
                URL url = trackerIdToBrowsableUrl(originalUrl, il.getTargetIssueKey());
                issue.getDependsOn().add(url);
            }
        }
    }

    private void setIssueComments(JiraIssue issue, com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        List<Comment> comments = new ArrayList<>();
        jiraIssue.getComments()
                .forEach(c -> comments.add(new Comment(issue.getTrackerId().get(), Long.toString(c.getId()), c.getBody(), false)));
        issue.getComments().addAll(comments);
    }

    private URL trackerIdToBrowsableUrl(URL url, String trackerId) {
        try {
            String link = url.getProtocol() + "://" + url.getHost() + BROWSE_ISSUE_PATH + trackerId;
            return new URL(link);
        } catch (MalformedURLException e) {
            return null;
        }
    }
}
