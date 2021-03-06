/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spiller;

import com.facebook.presto.execution.buffer.PagesSerde;
import com.facebook.presto.execution.buffer.PagesSerdeUtil;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PrestoException;
import com.google.common.io.Closer;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import io.airlift.slice.InputStreamSliceInput;
import io.airlift.slice.OutputStreamSliceOutput;
import io.airlift.slice.SliceOutput;

import javax.annotation.concurrent.NotThreadSafe;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.facebook.presto.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static com.facebook.presto.util.ImmutableCollectors.toImmutableList;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

@NotThreadSafe
public class BinaryFileSpiller
        implements Spiller
{
    private final Path targetDirectory;
    private final Closer closer = Closer.create();
    private final PagesSerde serde;
    private final AtomicLong totalSpilledDataSize;

    private final ListeningExecutorService executor;

    private int spillsCount;
    private ListenableFuture<?> previousSpill = immediateFuture(null);

    public BinaryFileSpiller(
            PagesSerde serde,
            ListeningExecutorService executor,
            Path spillPath,
            AtomicLong totalSpilledDataSize)
    {
        this.serde = requireNonNull(serde, "serde is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.totalSpilledDataSize = requireNonNull(totalSpilledDataSize, "totalSpilledDataSize is null");
        try {
            this.targetDirectory = Files.createTempDirectory(spillPath, "presto-spill");
        }
        catch (IOException e) {
            throw new PrestoException(GENERIC_INTERNAL_ERROR, "Failed to create spill directory", e);
        }
    }

    @Override
    public ListenableFuture<?> spill(Iterator<Page> pageIterator)
    {
        checkState(previousSpill.isDone());
        Path spillPath = getPath(spillsCount++);

        previousSpill = executor.submit(() -> writePages(pageIterator, spillPath));
        return previousSpill;
    }

    private void writePages(Iterator<Page> pageIterator, Path spillPath)
    {
        try (SliceOutput output = new OutputStreamSliceOutput(new BufferedOutputStream(new FileOutputStream(spillPath.toFile())))) {
            totalSpilledDataSize.addAndGet(PagesSerdeUtil.writePages(serde, output, pageIterator));
        }
        catch (UncheckedIOException | IOException e) {
            throw new PrestoException(GENERIC_INTERNAL_ERROR, "Failed to spill pages", e);
        }
    }

    @Override
    public List<Iterator<Page>> getSpills()
    {
        checkState(previousSpill.isDone());
        return IntStream.range(0, spillsCount)
                .mapToObj(i -> readPages(getPath(i)))
                .collect(toImmutableList());
    }

    private Iterator<Page> readPages(Path spillPath)
    {
        try {
            InputStream input = new BufferedInputStream(new FileInputStream(spillPath.toFile()));
            closer.register(input);
            return PagesSerdeUtil.readPages(serde, new InputStreamSliceInput(input));
        }
        catch (IOException e) {
            throw new PrestoException(GENERIC_INTERNAL_ERROR, "Failed to read spilled pages", e);
        }
    }

    @Override
    public void close()
    {
        try (Stream<Path> list = Files.list(targetDirectory)) {
            closer.close();
            for (Path path : list.collect(toList())) {
                Files.delete(path);
            }
            Files.delete(targetDirectory);
        }
        catch (IOException e) {
            throw new PrestoException(
                    GENERIC_INTERNAL_ERROR,
                    String.format("Failed to delete directory [%s]", targetDirectory),
                    e);
        }
    }

    private Path getPath(int spillNumber)
    {
        return Paths.get(targetDirectory.toAbsolutePath().toString(), String.format("%d.bin", spillNumber));
    }
}
