package com.cathive.git.autopush;

import org.eclipse.jgit.api.Git;
import org.hamcrest.CoreMatchers;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static com.google.common.collect.Iterables.size;
import static org.junit.Assert.*;

public class AutopushTest {
    @Test
    public void testSimpleCommit() throws Exception {
        final Path upstreamDirectory = Files.createTempDirectory(null);
        final Git upstream =
                Git.init()
                        .setDirectory(upstreamDirectory.toFile())
                        .call();
        Files.createFile(upstreamDirectory.resolve("test.txt"));
        upstream.add().addFilepattern("*").call();
        upstream.commit().setMessage("Initial").call();
        final int oldLogSize = size(upstream.log().call());

        final Path cloneDirectory = Files.createTempDirectory(null);
        Git.cloneRepository()
                .setBare(false)
                .setDirectory(cloneDirectory.toFile())
                .setURI(upstreamDirectory.toString())
                .call();
        Files.createFile(cloneDirectory.resolve("test2.txt"));

        final Autopush autopush = new Autopush(Duration.ofMinutes(5), cloneDirectory);
        autopush.shutdown(true);

        final int newLogSize = size(upstream.log().call());
        upstream.log().call().forEach((commit) -> System.out.println(commit.getFullMessage()));
        assertThat(newLogSize, CoreMatchers.equalTo(oldLogSize + 1));
    }
}