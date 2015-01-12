package com.cathive.git.autopush;

import com.google.common.base.Throwables;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.Iterator;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.time.Instant.now;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Created by erben on 12.01.15.
 */
public class Autopush {

    private final Duration delta;
    private final Git repository;
    private final ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;

    public Autopush(final Duration delta, final Path directory) {
        requireNonNull(delta, "Delta must not be null!");
        checkArgument(delta.toMinutes() < 1, "Delta must be at least one minute!");

        requireNonNull(directory, "Directory to push must not be null!");
        checkArgument(exists(directory), "Directory to push must exist!");
        checkArgument(isDirectory(directory), "Path to directory to push must point to directory!");

        try {
            this.repository = Git.open(directory.toFile());
            final Iterator<RevCommit> log = this.repository.log().call().iterator();
            checkState(log.hasNext(), "Repository does not have a commit!");
        } catch (final IOException e) {
            throw new IllegalArgumentException("Could not open repository from path "+directory.toString(),e);
        } catch (final GitAPIException e) {
            throw Throwables.propagate(e);
        }

        this.delta = delta;

        this.scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1);
        this.scheduledThreadPoolExecutor.scheduleAtFixedRate(this::push, 0, delta.toMillis(), MILLISECONDS);
    }

    public void shutdown() {
        this.scheduledThreadPoolExecutor.shutdown();
    }

    private void push() {
        try {
            this.repository.fetch().call();

            final RevCommit lastCommit =
                    this.repository
                            .log()
                            .call()
                            .iterator()
                            .next();

            final Instant lastCommitTime = Instant.ofEpochMilli(lastCommit.getCommitTime());
            final Duration durationSince = Duration.between(lastCommitTime, now());

            if (durationSince.compareTo(this.delta) > 0) {
                this.repository.add()
                        .addFilepattern("*")
                        .call();
                this.repository.commit()
                        .setMessage("Commit by autopush")
                        .setAuthor("autopush", "autopush@github.com")
                        .call();
                this.repository.push().call();
            }
        } catch (final Exception e) {
            Throwables.propagate(e);
        }
    }
}
