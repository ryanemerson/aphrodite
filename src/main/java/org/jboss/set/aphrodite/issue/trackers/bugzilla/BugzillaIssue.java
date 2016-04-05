package org.jboss.set.aphrodite.issue.trackers.bugzilla;

import org.jboss.set.aphrodite.domain.Issue;

import java.net.URL;
import java.util.Optional;

/**
 * @author Ryan Emerson
 */
public class BugzillaIssue extends Issue {

    private String targetRelease;

    private String targetMilestone;

    private String version;

    public BugzillaIssue(URL url) {
        super(url);
    }

    public Optional<String> getTargetRelease() {
        return Optional.of(targetRelease);
    }

    public void setTargetRelease(String targetRelease) {
        this.targetRelease = targetRelease;
    }

    public Optional<String> getTargetMilestone() {
        return Optional.of(targetMilestone);
    }

    public void setTargetMilestone(String targetMilestone) {
        this.targetMilestone = targetMilestone;
    }

    public Optional<String> getVersion() {
        return Optional.of(version);
    }

    public void setVersion(String version) {
        this.version = version;
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() +
                "{" + super.toString() +
                ", targetRelease='" + targetRelease + '\'' +
                ", targetMilestone='" + targetMilestone + '\'' +
                ", version='" + version + '\'' +
                "}\n";
    }
}
