/*
 * Copyright (C) 2022-2023 Hedera Hashgraph, LLC
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

package com.swirlds.merkledb;

import static com.swirlds.common.io.utility.FileUtils.hardLinkTree;
import static com.swirlds.logging.LogMarker.MERKLE_DB;

import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import com.swirlds.common.io.utility.TemporaryFileBuilder;
import com.swirlds.merkledb.files.DataFileCommon;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A virtual database instance is a set of data sources (sometimes referenced to as tables, see more
 * on it below) that share a single folder on disk to store data. So far individual data sources are
 * independent, but in the future there will be shared resources at the database level used by all
 * data sources. The database holds information about all table serialization configs (key and value
 * serializer classes). It also tracks opened data sources.
 *
 * By default, when a new virtual data source is created by {@link MerkleDbDataSourceBuilder}, it
 * uses a database in a temporary folder. When multiple data sources are created, they all share the
 * same database instance. This temporary location can be overridden using {@link
 * #setDefaultPath(Path)} method.
 *
 * When virtual data source builder creates a data source snapshot in a specified folder (which
 * is the folder where platform state file is written), it uses a {@code MerkleDb} instance that
 * corresponds to that folder. When a data source is copied, it uses yet another database instance
 * in a different temp folder.
 *
 * Tables vs data sources. Sometimes these two terms are used interchangeably, but they are
 * slightly different. Tables are about configs: how keys and values are serialized, whether disk
 * based indexes are preferred, and so on. Tables are also data on disk, whether it's currently used
 * by any virtual maps or not. Data sources are runtime objects, they provide methods to work with
 * table data. Data sources can be opened or closed, but tables still exists in the database and on
 * the disk.
 */
public final class MerkleDb {

    /** MerkleDb logger. */
    private static final Logger logger = LogManager.getLogger(MerkleDb.class);

    /**
     * Max number of tables in a single database instance. A table is created, when a new
     * data source is opened, or when an existing data source is copied. Copies are removed
     * automatically on close
     */
    private static final int MAX_TABLES = 1024;

    /** Sub-folder name for shared database data. Relative to database storage dir */
    private static final String SHARED_DIRNAME = "shared";
    /**
     * Sub-folder name for table data. Relative to database storage dir. This sub-folder will
     * contain other sub-folders, one per table
     */
    private static final String TABLES_DIRNAME = "tables";
    /** Metadata file name. Relative to database storage dir */
    private static final String METADATA_FILENAME = "metadata.mdb";

    /** Label for database component used in logging, stats, etc. */
    public static final String MERKLEDB_COMPONENT = "merkledb";

    /**
     * All virtual database instances in a process. Once we have something like "application
     * context" to share a single JVM between multiple nodes, this should be changed to be global to
     * context rather than global to the whole process
     */
    private static final ConcurrentHashMap<Path, MerkleDb> instances = new ConcurrentHashMap<>();

    /** A path for a database where new or restored data sources are created by default */
    private static final AtomicReference<Path> defaultInstancePath = new AtomicReference<>();

    /**
     * The base directory in which the database directory will be created. By default, a temporary
     * location provided by {@link com.swirlds.common.io.utility.TemporaryFileBuilder}.
     */
    private final Path storageDir;

    /**
     * When a new data source is created, it gets an ID within the database. This field is used
     * to generate an ID. The database starts from the value of the field and checks if there
     * is a table with the corresponding ID. If so, it increments the field and checks again.
     */
    private final AtomicInteger nextTableId = new AtomicInteger(0);

    /**
     * All table configurations. This array is loaded when a database instance is created, even if
     * no data sources are created by virtual data source builders in this instance. Array is
     * indexed by table IDs.
     */
    private final AtomicReferenceArray<TableMetadata> tableConfigs;

    /**
     * All currently opened data sources. When a data source is closed, it gets removed from this
     * array. Array is indexed by table IDs.
     */
    @SuppressWarnings("rawtypes")
    private final AtomicReferenceArray<MerkleDbDataSource> dataSources = new AtomicReferenceArray<>(MAX_TABLES);

    /**
     * For every table name (data source label) there may be multiple tables in a single
     * database. One of them is "primary", it's used by some virtual map in the merkle tree. All
     * others are "secondary", for example, they are used during reconnects (both on the teacher
     * and the learner sides). Secondary tables are deleted, both metadata and data, when the
     * corresponding data source is closed. When a primary data source is closed, its data is
     * preserved on disk.
     *
     * Secondary tables are not included to snapshots and aren't written to DB metadata.
     */
    @SuppressWarnings("rawtypes")
    private final Set<Integer> primaryTables = ConcurrentHashMap.newKeySet();

    /**
     * Returns a virtual database instance for a given path. If the instance doesn't exist, it gets
     * created first. If the path is {@code null}, the default MerkleDb path is used instead.
     *
     * @param path Database storage dir. If {@code null}, the default MerkleDb path is used
     * @return Virtual database instance that stores its data in the specified path
     */
    public static MerkleDb getInstance(final Path path) {
        return instances.computeIfAbsent(path != null ? path : getDefaultPath(), MerkleDb::new);
    }

    /**
     * A database path (storage dir) to use for new or restored data sources
     *
     * @return Default instance path
     */
    private static Path getDefaultPath() {
        return defaultInstancePath.updateAndGet(p -> {
            if (p == null) {
                try {
                    p = TemporaryFileBuilder.buildTemporaryFile("merkledb");
                } catch (IOException z) {
                    throw new UncheckedIOException(z);
                }
            }
            return p;
        });
    }

    /**
     * Sets the default database path (storage dir) to use for new or restored data sources. This
     * path can be overridden at any moment, but in this case data sources created with the old
     * default path and new data sources created with the new path will not share any database
     * resources.
     *
     * @param value The new default database path
     */
    public static void setDefaultPath(Path value) {
        // It probably makes sense to let change default instance path only before the first call
        // to getDefaultInstance(). Update: in the tests, this method may be called multiple times,
        // if a test needs to create multiple maps with the same name
        defaultInstancePath.set(value);
    }

    /**
     * Gets a default database instance. Used by virtual data source builder to create new data
     * sources or restore data sources from snapshots.
     *
     * @return Default database instance
     */
    public static MerkleDb getDefaultInstance() {
        return getInstance(getDefaultPath());
    }

    /**
     * Creates a new database instance with the given path as the storage dir. If database metadata
     * file exists in the specified folder, it gets loaded into the tables map.
     *
     * @param storageDir A folder to store database files in
     */
    private MerkleDb(final Path storageDir) {
        if (storageDir == null) {
            throw new IllegalArgumentException("Cannot create a MerkleDatabase instance with null storageDir");
        }
        this.storageDir = storageDir;
        this.tableConfigs = loadMetadata();
        try {
            final Path sharedDir = getSharedDir();
            if (!Files.exists(sharedDir)) {
                Files.createDirectories(sharedDir);
            }
            final Path tablesDir = getTablesDir();
            if (!Files.exists(tablesDir)) {
                Files.createDirectories(tablesDir);
            }
        } catch (IOException z) {
            throw new UncheckedIOException(z);
        }
        // If this is a new database, create the metadata file
        if (!Files.exists(storageDir.resolve(METADATA_FILENAME))) {
            storeMetadata();
        }
        logger.info(MERKLE_DB.getMarker(), "New MerkleDb instance is created, storageDir={}", storageDir);
    }

    /**
     * Iterates over the list of table metadata starting from index from {@link #nextTableId} until
     * it finds an ID that isn't used by a table.
     *
     * @return The next available table ID
     */
    int getNextTableId() {
        for (int tablesCount = 0; tablesCount < MAX_TABLES; tablesCount++) {
            final int id = Math.abs(nextTableId.getAndIncrement() % MAX_TABLES);
            if (tableConfigs.get(id) == null) {
                return id;
            }
        }
        throw new IllegalStateException("Tables limit is reached");
    }

    /**
     * Base database storage dir.
     *
     * @return Database storage dir
     */
    public Path getStorageDir() {
        return storageDir;
    }

    /**
     * Database storage dir to store data shared between all data sources.
     *
     * @return Database storage dir shared between data sources
     */
    public Path getSharedDir() {
        return getSharedDir(storageDir);
    }

    private static Path getSharedDir(final Path baseDir) {
        return baseDir.resolve(SHARED_DIRNAME);
    }

    /**
     * Database storage dir to store data specific to individual data sources. Each data source
     * (table) will have a sub-folder in this dir with the name equal to the table name
     *
     * @return Database storage dir for tables data
     */
    public Path getTablesDir() {
        return getTablesDir(storageDir);
    }

    private static Path getTablesDir(final Path baseDir) {
        return baseDir.resolve(TABLES_DIRNAME);
    }

    /**
     * Database storage dir to store data for a data source with the specified name and table ID.
     * This is a sub-folder in {@link #getTablesDir()}
     *
     * @param tableName Table name
     * @param tableId Table ID
     * @return Database storage dir to store data for the specified table
     */
    public Path getTableDir(final String tableName, final int tableId) {
        return getTableDir(storageDir, tableName, tableId);
    }

    private static Path getTableDir(final Path baseDir, final String tableName, final int tableId) {
        return getTablesDir(baseDir).resolve(tableName + "-" + tableId);
    }

    /**
     * Creates a new data source (table) in this database instance with the given name.
     *
     * @param label Table name. Used in logs and stats and also as a folder name to store table data
     *     in the tables storage dir
     * @param tableConfig Table serialization config
     * @param dbCompactionEnabled Whether background compaction process needs to be enabled for this
     *     data source
     * @return A created data source
     * @param <K> Virtual key type
     * @param <V> Virtual value type
     * @throws IOException If an I/O error happened while creating a new data source
     * @throws IllegalStateException If a data source (table) with the specified name already exists
     *     in the database instance
     */
    public <K extends VirtualKey, V extends VirtualValue> MerkleDbDataSource<K, V> createDataSource(
            final String label, final MerkleDbTableConfig<K, V> tableConfig, final boolean dbCompactionEnabled)
            throws IOException {
        // This method should be synchronized, as between tableExists() and tableConfigs.set()
        // a new data source can be created in a parallel thread. However, the current assumption
        // is no threads are creating a data source with the same name at the same time. From
        // Java memory perspective, neither of methods in this class need to be synchronized, as
        // tableConfigs and dataSources are both AtomicReferenceArrays and thread safe
        if (tableExists(label)) {
            throw new IllegalStateException("Table already exists: " + label);
        }
        final int tableId = getNextTableId();
        tableConfigs.set(tableId, new TableMetadata(tableId, label, tableConfig));
        MerkleDbDataSource<K, V> dataSource =
                new MerkleDbDataSource<>(this, label, tableId, tableConfig, dbCompactionEnabled);
        dataSources.set(tableId, dataSource);
        // New tables are always primary
        primaryTables.add(tableId);
        storeMetadata();
        return dataSource;
    }

    /**
     * Make a data source copy. The copied data source has the same metadata and label (table name),
     * but a different table ID.
     *
     * Only one data source for any given label can be active at a time, that is used by a virtual
     * map in the merkle tree. If makeCopyPrimary is {@code true}, the copy is marked as active, and
     * the original data source is marked as secondary. This happens when a learner creates a copy
     * of a virtual root during reconnects. If makeCopyPrimary is {@code false}, the copy is not
     * marked as active, and the status of the original data source is not changed. This mode is used
     * during reconnects by teachers.
     *
     * @param dataSource Data source to copy
     * @param makeCopyPrimary Whether to make the copy primary
     * @return A copied data source
     * @param <K> Virtual key type
     * @param <V> Virtual value type
     * @throws IOException If an I/O error occurs
     */
    public <K extends VirtualKey, V extends VirtualValue> MerkleDbDataSource<K, V> copyDataSource(
            final MerkleDbDataSource<K, V> dataSource, final boolean makeCopyPrimary) throws IOException {
        final String label = dataSource.getTableName();
        final int tableId = getNextTableId();
        importDataSource(dataSource, tableId, !makeCopyPrimary, makeCopyPrimary); // import to itself == copy
        return getDataSource(tableId, label, makeCopyPrimary);
    }

    private <K extends VirtualKey, V extends VirtualValue> void importDataSource(
            final MerkleDbDataSource<K, V> dataSource,
            final int tableId,
            final boolean leaveSourcePrimary,
            final boolean makeCopyPrimary)
            throws IOException {
        final String label = dataSource.getTableName();
        final MerkleDbTableConfig<K, V> tableConfig =
                dataSource.getTableConfig().copy();
        if (tableConfigs.get(tableId) != null) {
            throw new IllegalStateException("Table with ID " + tableId + " already exists");
        }
        tableConfigs.set(tableId, new TableMetadata(tableId, label, tableConfig));
        try {
            dataSource.pauseMerging();
            dataSource.snapshot(getTableDir(label, tableId));
        } finally {
            dataSource.resumeMerging();
        }
        if (!leaveSourcePrimary) {
            dataSource.stopBackgroundCompaction();
            primaryTables.remove(dataSource.getTableId());
        }
        if (makeCopyPrimary) {
            primaryTables.add(tableId);
        }
        storeMetadata();
    }

    /**
     * Returns a data source with the given name. If the data source isn't opened yet (e.g. on
     * restore from a snapshot), opens it first. If there is no table configuration for the given
     * table name, throws an exception.
     *
     * @param name Table name
     * @param dbCompactionEnabled Whether background compaction process needs to be enabled for this
     *     data source. If the data source was previously opened, this flag is ignored
     * @return The datasource
     * @param <K> Virtual key type
     * @param <V> Virtual value type
     */
    public <K extends VirtualKey, V extends VirtualValue> MerkleDbDataSource<K, V> getDataSource(
            final String name, final boolean dbCompactionEnabled) throws IOException {
        final TableMetadata metadata = getTableMetadata(name);
        if (metadata == null) {
            throw new IllegalStateException("Unknown table: " + name);
        }
        final int tableId = metadata.tableId();
        return getDataSource(tableId, name, dbCompactionEnabled);
    }

    @SuppressWarnings({"unchecked"})
    private <K extends VirtualKey, V extends VirtualValue> MerkleDbDataSource<K, V> getDataSource(
            final int tableId, final String tableName, final boolean dbCompactionEnabled) throws IOException {
        final MerkleDbTableConfig<K, V> tableConfig = getTableConfig(tableId);
        final AtomicReference<IOException> rethrowIO = new AtomicReference<>(null);
        final MerkleDbDataSource<K, V> dataSource = dataSources.updateAndGet(tableId, ds -> {
            if (ds != null) {
                return ds;
            }
            try {
                return new MerkleDbDataSource<>(this, tableName, tableId, tableConfig, dbCompactionEnabled);
            } catch (final IOException z) {
                rethrowIO.set(z);
                return null;
            }
        });
        if (rethrowIO.get() != null) {
            throw rethrowIO.get();
        }
        return dataSource;
    }

    /**
     * Marks the data source as closed in this database instance. The corresponding table
     * configuration and table files are preserved, so the data source can be re-opened later using
     * {@link #getDataSource(String, boolean)} method.
     *
     * @param dataSource The closed data source
     * @param <K> Virtual key type
     * @param <V> Virtual value type
     */
    public <K extends VirtualKey, V extends VirtualValue> void closeDataSource(
            final MerkleDbDataSource<K, V> dataSource) {
        if (this != dataSource.getDatabase()) {
            throw new IllegalStateException("Can't close table in a different database");
        }
        final int tableId = dataSource.getTableId();
        assert dataSources.get(tableId) != null;
        dataSources.set(tableId, null);
        if (!primaryTables.contains(tableId)) {
            // Delete data, if the table is secondary
            removeTable(tableId);
        }
    }

    /**
     * For testing purpose only.
     *
     * Removes the table. Table config and table data files are deleted.
     *
     * @param tableId ID of the table to remove
     */
    void removeTable(final int tableId) {
        final TableMetadata metadata = tableConfigs.get(tableId);
        if (metadata == null) {
            throw new IllegalArgumentException("Unknown table ID: " + tableId);
        }
        assert dataSources.get(tableId) == null; // data source must have been already closed
        final String label = metadata.tableName();
        tableConfigs.set(tableId, null);
        DataFileCommon.deleteDirectoryAndContents(getTableDir(label, tableId));
        storeMetadata();
    }

    /**
     * Returns table serialization config for the specified table ID.
     *
     * Implementation notes: this method should be very fast and lock free, as it is / will be
     * used in multi-table stores to find the right serialization config during merges, so it will
     * be called very often
     *
     * @param tableId Table ID
     * @return Table serialization config
     * @param <K> Virtual key type
     * @param <V> Virtual value type
     */
    public <K extends VirtualKey, V extends VirtualValue> MerkleDbTableConfig<K, V> getTableConfig(final int tableId) {
        if ((tableId < 0) || (tableId >= MAX_TABLES)) {
            // Throw an exception instead? Perhaps, not
            return null;
        }
        final TableMetadata metadata = tableConfigs.get(tableId);
        @SuppressWarnings("unchecked")
        final MerkleDbTableConfig<K, V> tableConfig = metadata != null ? metadata.tableConfig() : null;
        return tableConfig;
    }

    /**
     * Takes a snapshot of the database source into the specified folder.
     *
     * @param destination Destination folder
     * @throws IOException If an I/O error occurred
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void snapshot(final Path destination, final MerkleDbDataSource dataSource) throws IOException {
        if (this != dataSource.getDatabase()) {
            logger.error(
                    MERKLE_DB.getMarker(),
                    "Trying to snapshot a data source from a different"
                            + " database. This storageDir={}, other storageDir={}",
                    getStorageDir(),
                    dataSource.getDatabase().getStorageDir());
            throw new IllegalArgumentException("Cannot snapshot a data source from a different database");
        }
        final String tableName = dataSource.getTableName();
        final boolean isPrimary = primaryTables.contains(dataSource.getTableId());
        if (!isPrimary) {
            logger.info(
                    MERKLE_DB.getMarker(),
                    "A snapshot is taken for a secondary table, it may happen"
                            + " during reconnect or ISS reporting. Table name={}",
                    tableName);
        }
        final MerkleDb targetDb = getInstance(destination);
        if (targetDb.tableExists(tableName)) {
            throw new IllegalStateException("Table already exists in the target database, " + tableName);
        }
        targetDb.importDataSource(dataSource, dataSource.getTableId(), true, true);
        targetDb.storeMetadata();
    }

    /**
     * Creates a database instance from a database snapshot in the specified folder. The instance is
     * created in the specified target folder, if not {@code null}, or in the default MerkleDb
     * folder otherwise.
     *
     * This method must be called before the database instance is created in the target folder.
     *
     * @param source Source folder
     * @param target Target folder, optional. If {@code null}, the default MerkleDb folder is used
     * @return Default database instance
     * @throws IOException If an I/O error occurs
     * @throws IllegalStateException If the default database instance is already created
     */
    public static MerkleDb restore(final Path source, final Path target) throws IOException {
        final Path defaultInstancePath = (target != null) ? target : getDefaultPath();
        if (!Files.exists(defaultInstancePath.resolve(METADATA_FILENAME))) {
            Files.createDirectories(defaultInstancePath);
            // For all data files, it's enough to create hard-links from the source dir to the
            // target dir. However, hard-linking the metadata file wouldn't work. The target
            // MerkleDb instance is mutable, e.g. new tables can be created in it and stored
            // in DB metadata. With hard links, changing target metadata would also change the
            // source metadata, which is strictly prohibited as existing saved states must
            // never be changed. So just copy the metadata file
            Files.copy(source.resolve(METADATA_FILENAME), defaultInstancePath.resolve(METADATA_FILENAME));
            final Path sharedDirPath = source.resolve(SHARED_DIRNAME);
            // No shared data yet, so the folder may be empty or even may not exist
            if (Files.exists(sharedDirPath)) {
                hardLinkTree(sharedDirPath, defaultInstancePath.resolve(SHARED_DIRNAME));
            }
            hardLinkTree(source.resolve(TABLES_DIRNAME), defaultInstancePath.resolve(TABLES_DIRNAME));
        } else {
            // Check the target database:
            //   * if it has the same set of tables as in the source, restore is a no-op
            //   * if tables are different, throw an error: can't restore into an existing database
        }
        return getInstance(defaultInstancePath);
    }

    private void storeMetadata() {
        storeMetadata(storageDir, getPrimaryTables());
    }

    /**
     * Writes database metadata file to the specified dir. Only table configs from the given list of
     * tables are included.
     *
     * Metadata file contains the following data:
     *
     * <ul>
     *   <li>number of tables
     *   <li>(for every table) table ID, table Name, and table serialization config
     * </ul>
     *
     * @param dir Folder to write metadata file to
     * @param tables List of tables to include to the metadata file
     */
    @SuppressWarnings("rawtypes")
    private void storeMetadata(final Path dir, final Collection<TableMetadata> tables) {
        final Path tableConfigFile = dir.resolve(METADATA_FILENAME);
        try (OutputStream fileOut =
                        Files.newOutputStream(tableConfigFile, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
                SerializableDataOutputStream out = new SerializableDataOutputStream(fileOut)) {
            out.writeInt(tables.size());
            for (TableMetadata metadata : tables) {
                final int tableId = metadata.tableId();
                out.writeInt(tableId);
                out.writeNormalisedString(metadata.tableName());
                out.writeSerializable(metadata.tableConfig(), false);
            }
        } catch (final IOException z) {
            throw new UncheckedIOException(z);
        }
    }

    private AtomicReferenceArray<TableMetadata> loadMetadata() {
        final AtomicReferenceArray<TableMetadata> metadata = loadMetadata(storageDir);
        // All tables loaded from disk are primary
        for (int i = 0; i < MAX_TABLES; i++) {
            if (metadata.get(i) != null) {
                primaryTables.add(i);
            }
        }
        return metadata;
    }

    /**
     * Loads metadata file from the specified folder and returns the list of loaded tables. If the
     * metadata file doesn't exist, an empty list is returned as if the database in the specified
     * folder didn't exist or didn't have any tables.
     *
     * @param dir Folder to read metadata file from
     * @return List of loaded tables
     */
    @SuppressWarnings("rawtypes")
    private static AtomicReferenceArray<TableMetadata> loadMetadata(final Path dir) {
        final AtomicReferenceArray<TableMetadata> tableConfigs = new AtomicReferenceArray<>(MAX_TABLES);
        final Path tableConfigFile = dir.resolve(METADATA_FILENAME);
        if (Files.exists(tableConfigFile)) {
            try (InputStream fileIn = Files.newInputStream(tableConfigFile, StandardOpenOption.READ);
                    SerializableDataInputStream in = new SerializableDataInputStream(fileIn)) {
                final int size = in.readInt();
                for (int i = 0; i < size; i++) {
                    final int tableId = in.readInt();
                    if ((tableId < 0) || (tableId >= MAX_TABLES)) {
                        throw new IllegalStateException("Corrupted MerkleDb metadata: wrong table ID");
                    }
                    final String name = in.readNormalisedString(256);
                    final MerkleDbTableConfig config = in.readSerializable(false, MerkleDbTableConfig::new);
                    tableConfigs.set(tableId, new TableMetadata(tableId, name, config));
                }
            } catch (final IOException z) {
                throw new UncheckedIOException(z);
            }
        }
        return tableConfigs;
    }

    /**
     * Checks if a table with the specified name exists in this database.
     *
     * @param tableName Table name to check
     * @return {@code true} if the table with this name exist, {@code false} otherwise
     */
    private boolean tableExists(final String tableName) {
        // I wish there was AtomicReferenceArray.stream()
        for (int i = 0; i < tableConfigs.length(); i++) {
            final TableMetadata tableMetadata = tableConfigs.get(i);
            if ((tableMetadata != null) && tableName.equals(tableMetadata.tableName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns table metadata for the primary table with the specified name. If there is no table
     * with the name, returns {@code null}.
     *
     * @param tableName Table name
     * @return Table metadata or {@code null} if the table with the specified name doesn't exist
     */
    private TableMetadata getTableMetadata(final String tableName) {
        // I wish there was AtomicReferenceArray.stream()
        for (int i = 0; i < tableConfigs.length(); i++) {
            final TableMetadata metadata = tableConfigs.get(i);
            if ((metadata != null) && tableName.equals(metadata.tableName()) && primaryTables.contains(i)) {
                return metadata;
            }
        }
        return null;
    }

    /**
     * Returns the list of primary tables in this database. The corresponding data source may
     * or may not be opened.
     *
     * @return List of all tables in the database
     */
    private Set<TableMetadata> getPrimaryTables() {
        // I wish there was AtomicReferenceArray.stream()
        final Set<TableMetadata> tables = new HashSet<>();
        for (int i = 0; i < tableConfigs.length(); i++) {
            final TableMetadata tableMetadata = tableConfigs.get(i);
            if ((tableMetadata != null) && primaryTables.contains(i)) {
                tables.add(tableConfigs.get(i));
            }
        }
        // If this method is ever used outside this class, change it to return an
        // unmodifiable set instead
        return tables;
    }

    /**
     * A simple record to store table metadata: ID, name, and serialization config. Table metadata
     * exist for all tables in the database regardless of whether the corresponding data sources are
     * not opened yet, opened, or already closed.
     *
     * @param tableId Table ID
     * @param tableName Table name
     * @param tableConfig Table serialization config
     */
    @SuppressWarnings("rawtypes")
    private record TableMetadata(int tableId, String tableName, MerkleDbTableConfig tableConfig) {
        public TableMetadata {
            if (tableId < 0) {
                throw new IllegalArgumentException("Table ID < 0");
            }
            if (tableId >= MAX_TABLES) {
                throw new IllegalArgumentException("Table ID >= MAX_TABLES");
            }
            if (tableName == null) {
                throw new IllegalArgumentException("Table name is null");
            }
            if (tableConfig == null) {
                throw new IllegalArgumentException("Table config is null");
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return storageDir.hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MerkleDb other)) {
            return false;
        }
        return Objects.equals(storageDir, other.storageDir);
    }
}
