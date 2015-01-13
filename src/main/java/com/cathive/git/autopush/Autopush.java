package com.cathive.git.autopush;

import com.google.common.base.Throwables;
import com.google.common.collect.Sets;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.isEmpty;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.time.Instant.now;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class Autopush {

    /**
     * Keeps references to running autopushs to prevent garbage collection
     */
    private final static Set<Autopush> INSTANCES = Sets.newConcurrentHashSet();

    private final Duration period;
    private final Git repository;
    private final ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;

    /**
     * Create a new autopush instance.
     * Preconditions:
     * <ul>
     *     <li>The given duration must not be null</li>
     *     <li>The given directory path must not be null and point to an existing directory.
     *     The directory must contain a git repository with at least one commit.</li>
     * </ul>
     *
     * Autopush will perform one initial add, commit and push if changes have been detected and
     * the remote repository has not been updated for longer than the passed period.
     * Afterwards, autopush will repeat the automatic add, commit and push after the passed period until
     * {@link #shutdown(boolean)} is called.
     *
     * @param period time that has to pass after the last commit of the upstream repository until a new commit and push is performed
     * @param directory directory containing the git repository clone
     */
    public Autopush(final Duration period, final Path directory) {
        // Preconditions
        requireNonNull(period, "Delta must not be null!");
        checkArgument(period.toMinutes() > 1, "Delta must be at least one minute!");
        requireNonNull(directory, "Directory to push must not be null!");
        checkArgument(exists(directory), "Directory to push must exist!");
        checkArgument(isDirectory(directory), "Path to directory to push must point to directory!");

        this.repository = verifyRepository(directory);
        this.period = period;
        this.scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1);

        push(); // initial push to assert at least one execution before shutdown

        this.scheduledThreadPoolExecutor.scheduleAtFixedRate(this::push, period.toMillis(), period.toMillis(), MILLISECONDS);
        INSTANCES.add(this);
    }

    /**
     * Perform an orderly shutdown of this autopush, finishing a possibly running push operation.
     * @param awaitTermination should the execution block until the last push completes?
     * @throws InterruptedException if the termination is interrupted
     */
    public void shutdown(final boolean awaitTermination) throws InterruptedException {
        this.scheduledThreadPoolExecutor.shutdown();
        if (awaitTermination) this.scheduledThreadPoolExecutor.awaitTermination(5, TimeUnit.MINUTES);
        INSTANCES.remove(this);
    }

    /**
     * Precondition check to assert that the given directory contains a valid git repository with at least one commit.
     * @param directory that should contain the git repository
     * @return instance derived from the path
     */
    private Git verifyRepository(final Path directory) {
        try {
            final Git repository = Git.open(directory.toFile());
            checkState(!isEmpty(repository.log().call()), "Repository does not have a commit!");
            return repository;
        } catch (final IOException e) {
            throw new IllegalArgumentException("Could not open repository from path " + directory.toString(), e);
        } catch (final GitAPIException e) {
            throw Throwables.propagate(e);
        }
    }

    private void push() {
        try {
            this.repository.fetch().call();
            if (repositoryChanged()) {
                if (durationSinceLastCommit().compareTo(this.period) > 0) {
                    this.repository.add()
                            .addFilepattern("*")
                            .call();
                    this.repository.commit()
                            .setMessage("Commit by autopush")
                            .setAuthor("autopush", "autopush@github.com")
                            .call();
                    this.repository.push().call();
                }
            }
        } catch (final Exception e) {
            Throwables.propagate(e);
        }
    }

    private boolean repositoryChanged() throws GitAPIException {
        return !this.repository.status().call().isClean();
    }

    private Duration durationSinceLastCommit() throws GitAPIException {
        final RevCommit lastCommit =
                this.repository
                        .log()
                        .call()
                        .iterator()
                        .next();

        final Instant lastCommitTime = Instant.ofEpochMilli(lastCommit.getCommitTime());
        return Duration.between(lastCommitTime, now());
    }
}