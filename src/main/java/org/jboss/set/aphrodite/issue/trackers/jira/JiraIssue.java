package org.jboss.set.aphrodite.issue.trackers.jira;

import org.jboss.set.aphrodite.domain.Issue;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Ryan Emerson
 */
public class JiraIssue extends Issue {

    private List<String> fixVersions;

    public JiraIssue(URL url) {
        super(url);
        fixVersions = new ArrayList<>();
    }

    public List<String> getFixVersions() {
        return fixVersions;
    }

    public void setFixVersions(List<String> fixVersions) {
        this.fixVersions = fixVersions;
    }
}
