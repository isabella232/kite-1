/*
 * Copyright 2013 Cloudera.
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

package org.kitesdk.data.filesystem;

import com.google.common.collect.Iterators;
import java.util.Iterator;
import org.kitesdk.data.DatasetException;
import org.kitesdk.data.DatasetIOException;
import org.kitesdk.data.DatasetReader;
import org.kitesdk.data.DatasetWriter;
import org.kitesdk.data.spi.AbstractRefineableView;
import org.kitesdk.data.spi.Constraints;
import org.kitesdk.data.spi.Pair;
import org.kitesdk.data.spi.StorageKey;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import javax.annotation.concurrent.Immutable;
import java.io.IOException;

/**
 * FileSystem implementation of a {@link org.kitesdk.data.spi.Constraints}-based
 * {@link org.kitesdk.data.RefineableView}.
 *
 * @param <E> The type of records read and written by this view.
 */
@Immutable
class FileSystemView<E> extends AbstractRefineableView<E> {

  private final FileSystem fs;
  private final Path root;

  FileSystemView(FileSystemDataset<E> dataset) {
    super(dataset);
    this.fs = dataset.getFileSystem();
    this.root = dataset.getDirectory();
  }

  private FileSystemView(FileSystemView<E> view, Constraints c) {
    super(view, c);
    this.fs = view.fs;
    this.root = view.root;
  }

  @Override
  protected FileSystemView<E> filter(Constraints c) {
    return new FileSystemView<E>(this, c);
  }

  @Override
  public DatasetReader<E> newReader() {
    return new MultiFileDatasetReader<E>(
        fs, pathIterator(), dataset.getDescriptor(), constraints);
  }

  @Override
  public DatasetWriter<E> newWriter() {
    if (dataset.getDescriptor().isPartitioned()) {
      return new PartitionedDatasetWriter<E>(this);
    } else {
      return FileSystemWriters.newFileWriter(fs, root, dataset.getDescriptor());
    }
  }

  @Override
  public boolean deleteAll() {
    boolean deleted = false;
    for (Pair<StorageKey, Path> partition : partitionIterator()) {
      deleted = cleanlyDelete(fs, root, partition.second()) || deleted;
    }
    return deleted;
  }

  PathIterator pathIterator() {
    Iterator<Pair<StorageKey, Path>> directories;
    if (dataset.getDescriptor().isPartitioned()) {
      directories = partitionIterator();
    } else {
      directories = Iterators.singletonIterator(
          Pair.of((StorageKey) null, root));
    }
    return new PathIterator(fs, root, directories);
  }

  private FileSystemPartitionIterator partitionIterator() {
    try {
      return new FileSystemPartitionIterator(
          fs, root,
          dataset.getDescriptor().getPartitionStrategy(), constraints);
    } catch (IOException ex) {
      throw new DatasetException("Cannot list partitions in view:" + this, ex);
    }
  }

  private static boolean cleanlyDelete(FileSystem fs, Path root, Path dir) {
    try {
      boolean deleted;
      if (dir.isAbsolute()) {
        deleted = fs.delete(dir, true /* include any files */ );
      } else {
        // the path should be treated as relative to the root path
        Path absolute = new Path(root, dir);
        deleted = fs.delete(absolute, true /* include any files */ );
        // iterate up to the root, removing empty directories
        for (Path current = absolute.getParent();
             !current.equals(root) && !(current.getParent() == null);
             current = current.getParent()) {
          final FileStatus[] stats = fs.listStatus(current);
          if (stats == null || stats.length == 0) {
            // dir is empty and should be removed
            deleted = fs.delete(current, true) || deleted;
          } else {
            // all parent directories will be non-empty
            break;
          }
        }
      }
      return deleted;
    } catch (IOException ex) {
      throw new DatasetIOException("Could not cleanly delete path:" + dir, ex);
    }
  }
}
