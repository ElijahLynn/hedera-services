/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.common.scratchpad;

import static com.swirlds.common.io.utility.TemporaryFileBuilder.buildTemporaryFile;
import static com.swirlds.logging.LogMarker.STARTUP;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

import com.swirlds.common.config.StateConfig;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.formatting.TextTable;
import com.swirlds.common.io.SelfSerializable;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.system.NodeId;
import com.swirlds.common.threading.locks.AutoClosableLock;
import com.swirlds.common.threading.locks.Locks;
import com.swirlds.common.threading.locks.locked.Locked;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A utility for "taking notes" that are preserved across restart boundaries.
 * <p>
 * A scratchpad instance is thread safe. All read operations and write operations against a scratchpad are atomic. Any
 * write that has completed is guaranteed to be visible to all subsequent reads, regardless of crashes/restarts.
 *
 * @param <K> the enum type that defines the scratchpad fields
 */
public class Scratchpad<K extends Enum<K> & ScratchpadType> {

    private static final Logger logger = LogManager.getLogger(Scratchpad.class);

    /**
     * The directory where scratchpad files are written. Files are written with the format "0.scr", "1.scr", etc., with
     * the number increasing by one each time a file is written. If multiple files are present, the file with the
     * highest number is the most recent and is always used. Multiple files will only be present if the platform crashes
     * after writing a file but before it has the opportunity to delete the previous file.
     */
    public static final String SCRATCHPAD_DIRECTORY_NAME = "scratchpad";

    /**
     * The file extension used for scratchpad files.
     */
    public static final String SCRATCHPAD_FILE_EXTENSION = ".scr";

    private final Set<K> fields;
    private final Map<Integer, K> indexToFieldMap;
    private final String id;

    private final Map<K, SelfSerializable> data = new HashMap<>();
    private final AutoClosableLock lock = Locks.createAutoLock();
    private final Path scratchpadDirectory;
    private long nextScratchpadIndex;

    private final int fileVersion = 1;

    /**
     * Create a new scratchpad.
     *
     * @param platformContext the platform context
     * @param selfId          the ID of this node
     * @param clazz           the enum class that defines the scratchpad fields
     * @param id              the unique ID of this scratchpad (creating multiple scratchpad instances on the same node
     *                        with the same unique ID has undefined (and possibly undesirable) behavior. Must not
     *                        contain any non-alphanumeric characters, with the exception of the following characters:
     *                        "_", "-", and ".". Must not be empty.
     */
    public Scratchpad(
            @NonNull final PlatformContext platformContext,
            @NonNull final NodeId selfId,
            @NonNull final Class<K> clazz,
            @NonNull final String id) {

        final StateConfig stateConfig = platformContext.getConfiguration().getConfigData(StateConfig.class);
        scratchpadDirectory = stateConfig
                .savedStateDirectory()
                .resolve(SCRATCHPAD_DIRECTORY_NAME)
                .resolve(Long.toString(selfId.id()))
                .resolve(id);

        if (id.isEmpty()) {
            throw new IllegalArgumentException("scratchpad ID must not be empty");
        }
        if (!id.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException(
                    "scratchpad ID must only contain alphanumeric characters, '_', '-', " + "and '.'");
        }

        this.id = id;
        fields = EnumSet.allOf(clazz);

        indexToFieldMap = new HashMap<>();
        for (final K key : fields) {
            final K previous = indexToFieldMap.put(key.getFieldId(), key);
            if (previous != null) {
                throw new RuntimeException("duplicate scratchpad field ID: " + key.getFieldId());
            }
        }

        loadFromDisk();
    }

    /**
     * Log the contents of the scratchpad.
     */
    public void logContents() {
        final TextTable table = new TextTable().setBordersEnabled(false);

        for (final K field : fields) {
            final SelfSerializable value = data.get(field);
            if (value == null) {
                table.addToRow(field.name(), "null");
            } else {
                table.addRow(field.name(), value.toString());
            }
        }

        logger.info(
                STARTUP.getMarker(),
                """
                        Scratchpad {} contents:
                        {}""",
                id,
                table.render());
    }

    /**
     * Get a value from the scratchpad.
     * <p>
     * The object returned by this method should be treated as if it is immutable. Modifying this object in any way may
     * cause the scratchpad to become corrupted.
     *
     * @param key the field to get
     * @param <V> the type of the value
     * @return the value, or null if the field is not present
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public <V extends SelfSerializable> V get(final K key) {
        try (final Locked ignored = lock.lock()) {
            return (V) data.get(key);
        }
    }

    /**
     * Set a field in the scratchpad. The scratchpad file is updated atomically. When this method returns, the data
     * written to the scratchpad will be present the next time the scratchpad is checked, even if that is after a
     * restart boundary.
     * <p>
     * The object set via this method should be treated as if it is immutable after this function is called. Modifying
     * this object in any way may cause the scratchpad to become corrupted.
     *
     * @param key   the field to set
     * @param value the value to set, may be null
     * @param <V>   the type of the value
     * @return the previous value
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public <V extends SelfSerializable> V set(@NonNull final K key, @Nullable final V value) {
        logger.info(STARTUP.getMarker(), "Setting scratchpad field {}:{} to {}", id, key, value);
        try (final Locked ignored = lock.lock()) {
            final V previous = (V) data.put(key, value);
            flush();
            return previous;
        }
    }

    /**
     * Perform an arbitrary atomic operation on the scratchpad. This operation is atomic with respect to reads, writes,
     * and other calls to this method.
     * <p>
     * The map provided to the operation should not be accessed after the operation returns. Doing so is not thread safe
     * and may result in undefined behavior.
     * <p>
     * It is safe to keep references to the objects in the map after the operation returns as long as these objects are
     * treated as if they are immutable. Modifying these objects in any way may cause the scratchpad to become
     * corrupted.
     *
     * @param operation the operation to perform, is provided a map of all scratchpad fields to their current values. If
     *                  a field is not present in the map, then it should be considered to have the value null.
     */
    public void atomicOperation(@NonNull final Consumer<Map<K, SelfSerializable>> operation) {
        try (final Locked ignored = lock.lock()) {
            operation.accept(data);
            flush();
        }
    }

    /**
     * Clear the scratchpad. Deletes all files on disk. Useful if a scratchpad type is being removed and the intention
     * is to delete all data associated with it.
     */
    public void clear() {
        logger.info(STARTUP.getMarker(), "Clearing scratchpad {}", id);
        try (final Locked ignored = lock.lock()) {
            data.clear();
            FileUtils.deleteDirectory(scratchpadDirectory);
        } catch (final IOException e) {
            throw new UncheckedIOException("unable to clear scratchpad", e);
        }
    }

    /**
     * Parse the scratchpad file from disk.
     */
    private void loadFromDisk() {
        try {
            final List<Path> files = getScratchpadFiles();
            if (files.isEmpty()) {
                return;
            }

            // Delete all files except for the most recent one
            for (int index = 0; index < files.size() - 1; index++) {
                Files.delete(files.get(index));
            }

            final Path scratchpadFile = files.get(files.size() - 1);
            nextScratchpadIndex = getFileIndex(scratchpadFile) + 1;

            try (final SerializableDataInputStream in = new SerializableDataInputStream(
                    new BufferedInputStream(new FileInputStream(scratchpadFile.toFile())))) {

                final int fileVersion = in.readInt();
                if (fileVersion != this.fileVersion) {
                    throw new RuntimeException("scratchpad file version mismatch");
                }

                int fieldCount = in.readInt();

                for (int index = 0; index < fieldCount; index++) {
                    final int fieldId = in.readInt();
                    final K key = indexToFieldMap.get(fieldId);
                    if (key == null) {
                        throw new IOException("scratchpad file contains unknown field " + fieldId);
                    }

                    final SelfSerializable value = in.readSerializable();
                    data.put(key, value);
                }
            }
        } catch (final IOException e) {
            throw new RuntimeException("unable to load scratchpad", e);
        }
    }

    /**
     * Write the data in the scratchpad to a temporary file.
     *
     * @return the path to the temporary file that was written
     */
    @NonNull
    private Path flushToTemporaryFile() throws IOException {
        final Path temporaryFile = buildTemporaryFile();
        try (final SerializableDataOutputStream out = new SerializableDataOutputStream(
                new BufferedOutputStream(new FileOutputStream(temporaryFile.toFile(), false)))) {

            out.writeInt(fileVersion);

            int fieldCount = 0;
            for (final K keys : fields) {
                if (data.get(keys) != null) {
                    fieldCount++;
                }
            }
            out.writeInt(fieldCount);

            for (final K key : fields) {
                final SelfSerializable value = data.get(key);
                if (value != null) {
                    out.writeInt(key.getFieldId());
                    out.writeSerializable(value, true);
                }
            }
        }

        return temporaryFile;
    }

    /**
     * Generate the path to the next scratchpad file.
     *
     * @return the path to the next scratchpad file
     */
    @NonNull
    private Path generateNextFilePath() {
        return scratchpadDirectory.resolve((nextScratchpadIndex++) + SCRATCHPAD_FILE_EXTENSION);
    }

    /**
     * Get the file index of a scratchpad file.
     *
     * @param path the path to the scratchpad file
     * @return the file index
     */
    private long getFileIndex(@NonNull final Path path) {
        final String fileName = path.getFileName().toString();
        return Long.parseLong(fileName.substring(0, fileName.indexOf('.')));
    }

    /**
     * Get a list of all scratchpad files currently on disk sorted from lowest index to highest index.
     */
    @NonNull
    private List<Path> getScratchpadFiles() {
        if (!Files.exists(scratchpadDirectory)) {
            return List.of();
        }

        final List<Path> files = new ArrayList<>();

        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(scratchpadDirectory)) {
            for (final Path path : stream) {
                if (path.toString().endsWith(SCRATCHPAD_FILE_EXTENSION)) {
                    files.add(path);
                }
            }
        } catch (final IOException e) {
            throw new RuntimeException("unable to list scratchpad files", e);
        }

        files.sort((a, b) -> Long.compare(getFileIndex(a), getFileIndex(b)));

        return files;
    }

    /**
     * Flush the scratchpad to disk atomically. Blocks until complete.
     */
    private void flush() {
        try {
            final List<Path> scratchpadFiles = getScratchpadFiles();

            final Path temporaryFile = flushToTemporaryFile();

            if (!Files.exists(scratchpadDirectory)) {
                Files.createDirectories(scratchpadDirectory);
            }
            Files.move(temporaryFile, generateNextFilePath(), ATOMIC_MOVE);

            for (final Path scratchpadFile : scratchpadFiles) {
                Files.delete(scratchpadFile);
            }
        } catch (final IOException e) {
            throw new RuntimeException("unable to flush scratchpad", e);
        }
    }

    // FUTURE WORK:
    //  - pcli command to look inside a scratchpad file
    //  - pcli command to edit a scratchpad file
}
