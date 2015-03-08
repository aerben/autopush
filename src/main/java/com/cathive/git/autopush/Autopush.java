package com.cathive.git.autopush;

import com.google.common.base.Throwables;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE;

/**
 * Autopush is an application that performs scheduled commits of a repository and pushes all changes to a remote
 * repository. It is meant to be a convenient cross-platform-application for backup purposes using git.
 *
 * @author Alexander Erben
 */
@SpringBootApplication
@EnableScheduling
public class Autopush {

    private static Logger LOG = Logger.getLogger(Autopush.class.getCanonicalName());

    /**
     * This value has no default. Provide it as a command line parameter.
     */
    @Value("${repository.path}")
    private String repositoryPath;

    /**
     * The default of this value is "origin". Set it in the CLI parameter if this default is not suitable.
     */
    @Value("${remote.name}")
    private String remoteName;

    /**
     * The default of this value is "master". Set it in the CLI parameter if this default is not suitable.
     */
    @Value("${remote.branch}")
    private String branchName;

    /**
     * The cron expression for the interval of autopush attempts. Defaults to * * *\3 * *, thus every three hours.
     * The expression consists of 6 components starting with seconds.
     */
    @Value("${interval.cron}")
    private String intervalCron;

    /**
     * Reference to the git repository to autopush
     */
    private Git repository;

    public static void main(String[] args) {
        SpringApplication.run(Autopush.class, args);
    }

    /**
     * Setup the {@link org.eclipse.jgit.api.Git}-repository.
     * Preconditions: the configured repository.path points to an existing directory containing a non-bare
     * git repository. A remote repository by the name given in the value remote.name must exist and a remote tracking
     * branch by the name of the value remote.branch must exist.
     * @throws IOException
     * @throws GitAPIException
     */
    @PostConstruct
    public void setupRepository() throws IOException, GitAPIException {
        final Path path = Paths.get(this.repositoryPath);
        checkArgument(exists(path) && isDirectory(path), "Directory to push must exist! Was: " + this.repositoryPath);
        this.repository = Git.open(path.toFile());
        checkState(this.repository
                        .branchList()
                        .setListMode(REMOTE)
                        .call().stream()
                        .allMatch((ref) -> ref.getName().contains(this.remoteName + "/" + this.branchName)),
                format("Repository does not contain a remote \"%s\" with branch \"%s\"",
                        this.remoteName, this.branchName));
        autopush(); // test setup and perform one push
    }

    /**
     * Check if the working copy of the specified repository contains changes.
     * Add them to the index if present, perform a commit and perform a push to the remote repository.
     */
    @Scheduled(cron = "${interval.cron}")
    public void autopush() {
        try {
            LOG.info("Validating access to remote repository.");
            this.repository.fetch().call();
            if (repositoryChanged()) {
                LOG.info("Changes detected in repository.");
                addAll();
                commit();
                push();
                LOG.info("Successfully updated remote repository.");
            } else {
                LOG.info("Remote repository is up to date!");
            }
        } catch (final GitAPIException e) {
            LOG.severe(Throwables.getStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }

    /**
     * Add all files to the index
     */
    private void addAll() throws GitAPIException {
        this.repository.add()
                .addFilepattern(".")
                .call();
    }

    /**
     * Perform a git commit with default author and message strings
     */
    private void commit() throws GitAPIException {
        this.repository.commit()
                .setMessage("Commit by autopush")
                .setAuthor("autopush", "autopush@github.com")
                .call();
    }

    /**
     * Push the master to the remote repository
     */
    private void push() throws GitAPIException {
        this.repository.push().call();
    }

    /**
     * Check if the working copy is not clean, meaning that changes happened to the working copy
     * making an update of the remote branch necessary.
     * @return {@link true} if the working copy contains changes, {@link false} if not.
     */
    private boolean repositoryChanged() throws GitAPIException {
        return !this.repository.status().call().isClean();
    }
}