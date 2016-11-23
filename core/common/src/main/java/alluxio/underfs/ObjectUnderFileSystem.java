/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.underfs;

import alluxio.AlluxioURI;
import alluxio.Configuration;
import alluxio.Constants;
import alluxio.PropertyKey;
import alluxio.underfs.options.CreateOptions;
import alluxio.underfs.options.DeleteOptions;
import alluxio.underfs.options.MkdirsOptions;
import alluxio.util.CommonUtils;
import alluxio.util.io.PathUtils;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.concurrent.ThreadSafe;

/**
 * A object based abstract {@link UnderFileSystem}.
 */
@ThreadSafe
public abstract class ObjectUnderFileSystem extends BaseUnderFileSystem {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  /** Maximum length for a single listing query. */
  private static final int LISTING_LENGTH_MAX = 1000;

  /** Length of each list request. */
  protected static final int LISTING_LENGTH =
      Configuration.getInt(PropertyKey.UNDERFS_LISTING_LENGTH) > LISTING_LENGTH_MAX
          ? LISTING_LENGTH_MAX : Configuration.getInt(PropertyKey.UNDERFS_LISTING_LENGTH);

  /** Value used to indicate nested structure. */
  protected static final char PATH_SEPARATOR_CHAR = '/';

  /** Value used to indicate nested structure. */
  protected static final String PATH_SEPARATOR = String.valueOf(PATH_SEPARATOR_CHAR);

  /**
   * Constructs an {@link ObjectUnderFileSystem}.
   *
   * @param uri the {@link AlluxioURI} used to create this ufs
   */
  protected ObjectUnderFileSystem(AlluxioURI uri) {
    super(uri);
  }

  /**
   * Information about a single object in object UFS.
   */
  protected class ObjectStatus {
    long mFileSize;
    long mLastModifiedTimeMs;

    public ObjectStatus(long fileSize, long lastModifiedTimeMs) {
      mFileSize = fileSize;
      mLastModifiedTimeMs = lastModifiedTimeMs;
    }

    /**
     * Gets the file size in bytes.
     *
     * @return the file size in bytes
     */
    public long getContentLength() {
      return mFileSize;
    }

    /**
     * Gets the UTC last modified time in ms.
     *
     * @return modification time in milliseconds
     */
    public long getLastModifiedTimeMs() {
      return mLastModifiedTimeMs;
    }
  }

  /**
   * A chunk of listing results.
   */
  public interface ObjectListingResult {
    /**
     * Objects in a pseudo-directory which may be a file or a directory.
     *
     * @return a list of objects
     */
    String[] getObjectNames();

    /**
     * Use common prefixes to infer pseudo-directories in object store.
     *
     * @return a list of common prefixes
     */
    String[] getCommonPrefixes();

    /**
     * Get next chunk of object listings.
     *
     * @return null if done with listing, otherwise return next chunk
     * @throws IOException if a non-alluxio error occurs
     */
    ObjectListingResult getNextChunk() throws IOException;
  }

  @Override
  public void close() throws IOException {
  }

  @Override
  public void connectFromMaster(String hostname) {
  }

  @Override
  public void connectFromWorker(String hostname) {
  }

  @Override
  public OutputStream create(String path, CreateOptions options) throws IOException {
    return createDirect(path, options);
  }

  @Override
  public OutputStream createDirect(String path, CreateOptions options) throws IOException {
    if (mkdirs(getParentPath(path), true)) {
      return createObject(stripPrefixIfPresent(path));
    }
    return null;
  }

  @Override
  public boolean deleteFile(String path) throws IOException {
    return deleteObject(stripPrefixIfPresent(path));
  }

  @Override
  public boolean deleteDirectory(String path, DeleteOptions options) throws IOException {
    if (!options.isRecursive()) {
      UnderFileStatus[] children = listInternal(path, false);
      if (children == null) {
        LOG.error("Unable to delete {} because listInternal returns null", path);
        return false;
      }
      if (children.length != 0) {
        LOG.error("Unable to delete {} because it is a non empty directory. Specify "
                + "recursive as true in order to delete non empty directories.", path);
        return false;
      }
    } else {
      // Delete children
      UnderFileStatus[] pathsToDelete = listInternal(path, true);
      if (pathsToDelete == null) {
        LOG.error("Unable to delete {} because listInternal returns null", path);
        return false;
      }
      for (UnderFileStatus pathToDelete : pathsToDelete) {
        // If we fail to deleteObject one file, stop
        String pathKey = stripPrefixIfPresent(PathUtils.concatPath(path, pathToDelete.getName()));
        boolean success;
        if (pathToDelete.isDirectory()) {
          success = deleteObject(convertToFolderName(pathKey));
        } else {
          success = deleteObject(pathKey);
        }
        if (!success) {
          LOG.error("Failed to delete path {}, aborting delete.", pathToDelete.getName());
          return false;
        }
      }
    }
    // Delete the directory itself
    return deleteObject(stripPrefixIfPresent(convertToFolderName(path)));
  }

  /**
   * Gets the block size in bytes. This method defaults to the default user block size in Alluxio.
   *
   * @param path the file name
   * @return the default Alluxio user block size
   * @throws IOException this implementation will not throw this exception, but subclasses may
   */
  @Override
  public long getBlockSizeByte(String path) throws IOException {
    return Configuration.getBytes(PropertyKey.USER_BLOCK_SIZE_BYTES_DEFAULT);
  }

  // Not supported
  @Override
  public Object getConf() {
    LOG.debug("getConf is not supported when using default ObjectUnderFileSystem.");
    return null;
  }

  // Not supported
  @Override
  public List<String> getFileLocations(String path) throws IOException {
    LOG.debug("getFileLocations is not supported when using default ObjectUnderFileSystem.");
    return null;
  }

  // Not supported
  @Override
  public List<String> getFileLocations(String path, long offset) throws IOException {
    LOG.debug("getFileLocations is not supported when using default ObjectUnderFileSystem.");
    return null;
  }

  // This call is currently only used for the web ui, where a negative value implies unknown.
  @Override
  public long getSpace(String path, SpaceType type) throws IOException {
    return -1;
  }

  @Override
  public long getFileSize(String path) throws IOException {
    ObjectStatus details = getObjectStatus(stripPrefixIfPresent(path));
    if (details != null) {
      return details.getContentLength();
    } else {
      LOG.error("Error fetching file size, assuming file does not exist");
      throw new FileNotFoundException(path);
    }
  }

  @Override
  public long getModificationTimeMs(String path) throws IOException {
    ObjectStatus details = getObjectStatus(stripPrefixIfPresent(path));
    if (details != null) {
      return details.getLastModifiedTimeMs();
    } else {
      throw new FileNotFoundException(path);
    }
  }

  @Override
  public boolean isDirectory(String path) throws IOException {
    // Root is always a folder
    return isRoot(path) || getFolderMetadata(path) != null;
  }

  @Override
  public boolean isFile(String path) throws IOException {
    // Directly try to get the file metadata, if we fail it either is a folder or does not exist
    return getObjectStatus(stripPrefixIfPresent(path)) != null;
  }

  @Override
  public String[] listRecursive(String path) throws IOException {
    return UnderFileStatus.toListingResult(listInternal(path, true));
  }

  @Override
  public String[] list(String path) throws IOException {
    return UnderFileStatus.toListingResult(listInternal(path, false));
  }

  @Override
  public boolean mkdirs(String path, MkdirsOptions options) throws IOException {
    if (path == null) {
      return false;
    }
    if (isDirectory(path)) {
      return true;
    }
    if (isFile(path)) {
      LOG.error("Cannot create directory {} because it is already a file.", path);
      return false;
    }
    if (!options.getCreateParent()) {
      if (parentExists(path)) {
        // Parent directory exists
        return mkdirsInternal(path);
      } else {
        LOG.error("Cannot create directory {} because parent does not exist", path);
        return false;
      }
    }
    // Parent directories should be created
    if (parentExists(path)) {
      // Parent directory exists
      return mkdirsInternal(path);
    } else {
      String parentKey = getParentPath(path);
      // Recursively make the parent folders
      return mkdirs(parentKey, true) && mkdirsInternal(path);
    }
  }

  @Override
  public boolean renameDirectory(String src, String dst) throws IOException {
    UnderFileStatus[] children = listInternal(src, false);
    if (children == null) {
      LOG.error("Failed to list directory {}, aborting rename.", src);
      return false;
    }
    if (isFile(dst) || isDirectory(dst)) {
      LOG.error("Unable to rename {} to {} because destination already exists.", src, dst);
      return false;
    }
    // Source exists and is a file, and destination does not exist
    // Rename the source folder first
    if (!copyObject(stripPrefixIfPresent(convertToFolderName(src)),
        stripPrefixIfPresent(convertToFolderName(dst)))) {
      return false;
    }
    // Rename each child in the src folder to destination/child
    for (UnderFileStatus child : children) {
      String childSrcPath = PathUtils.concatPath(src, child);
      String childDstPath = PathUtils.concatPath(dst, child);
      boolean success;
      if (child.isDirectory()) {
        // Recursive call
        success = renameDirectory(childSrcPath, childDstPath);
      } else {
        success = renameFile(childSrcPath, childDstPath);
      }
      if (!success) {
        LOG.error("Failed to rename path {}, aborting rename.", child);
        return false;
      }
    }
    // Delete src and everything under src
    return deleteDirectory(src, DeleteOptions.defaults().setRecursive(true));
  }

  @Override
  public boolean renameFile(String src, String dst) throws IOException {
    if (!isFile(src)) {
      LOG.error("Unable to rename {} to {} because source does not exist or is a directory.",
          src, dst);
      return false;
    }
    if (isFile(dst) || isDirectory(dst)) {
      LOG.error("Unable to rename {} to {} because destination already exists.", src, dst);
      return false;
    }
    // Source is a file and Destination does not exist
    return copyObject(stripPrefixIfPresent(src), stripPrefixIfPresent(dst))
        && deleteObject(stripPrefixIfPresent(src));
  }

  // Default object UFS does not provide a mechanism for updating the configuration, no-op
  @Override
  public void setConf(Object conf) {}

  @Override
  public boolean supportsFlush() {
    return false;
  }

  /**
   * Create a zero-byte object used to encode a directory.
   *
   * @param key the key to create
   * @return true if the operation was successful
   */
  protected abstract boolean createEmptyObject(String key);

  /**
   * Creates an {@link OutputStream} for object uploads.
   *
   * @param key ufs key including scheme and bucket
   * @throws IOException if failed to create stream
   * @return new OutputStream
   */
  protected abstract OutputStream createObject(String key) throws IOException;

  /**
   * Appends the directory suffix to the key.
   *
   * @param key the key to convert
   * @return key as a directory path
   */
  protected String convertToFolderName(String key) {
    // Strips the slash if it is the end of the key string. This is because the slash at
    // the end of the string is not part of the Object key.
    key = CommonUtils.stripSuffixIfPresent(key, PATH_SEPARATOR);
    return key + getFolderSuffix();
  }

  /**
   * Copies an object to another key.
   *
   * @param src the source key to copy
   * @param dst the destination key to copy to
   * @return true if the operation was successful, false otherwise
   */
  protected abstract boolean copyObject(String src, String dst) throws IOException;

  /**
   * Internal function to delete a key.
   *
   * @param key the key to delete
   * @return true if successful, false if an exception is thrown
   */
  protected abstract boolean deleteObject(String key) throws IOException;

  /**
   * Gets the metadata associated with a non-root path if it represents a folder. This method will
   * return null if the path is not a folder. If the path exists as a prefix but does not have the
   * folder dummy file, a folder dummy file will be created.
   *
   * @param path the path to get the metadata for
   * @return the metadata of the folder, or null if the path does not represent a folder
   */
  protected ObjectStatus getFolderMetadata(String path) {
    Preconditions.checkArgument(!isRoot(path));
    String keyAsFolder = convertToFolderName(stripPrefixIfPresent(path));
    ObjectStatus meta = getObjectStatus(keyAsFolder);
    if (meta == null) {
      String dir = stripPrefixIfPresent(path);
      String dirPrefix = PathUtils.normalizePath(dir, PATH_SEPARATOR);
      // Check if anything begins with <folder_path>/
      try {
        ObjectListingResult objs = getObjectListing(dirPrefix, true);
        // If there are, this is a folder and we can create the necessary metadata
        if (objs != null && objs.getObjectNames() != null && objs.getObjectNames().length > 0) {
          mkdirsInternal(dir);
          meta = getObjectStatus(keyAsFolder);
        }
      } catch (IOException e) {
        meta = null;
      }
    }
    return meta;
  }

  /**
   * Get metadata information about object.
   *
   * @param key ufs key to get metadata for
   * @return {@link ObjectStatus} if key exists and successful, otherwise null
   */
  protected abstract ObjectStatus getObjectStatus(String key);

  /**
   * Get parent path.
   *
   * @param path ufs path including scheme and bucket
   * @return the parent path, or null if the parent does not exist
   */
  protected String getParentPath(String path) {
    // Root does not have a parent.
    if (isRoot(path)) {
      return null;
    }
    int separatorIndex = path.lastIndexOf(PATH_SEPARATOR);
    if (separatorIndex < 0) {
      return null;
    }
    return path.substring(0, separatorIndex);
  }

  /**
   * Checks if the key is the root.
   *
   * @param key ufs path including scheme and bucket
   * @return true if the key is the root, false otherwise
   */
  protected boolean isRoot(String key) {
    return PathUtils.normalizePath(key, PATH_SEPARATOR).equals(
        PathUtils.normalizePath(getRootKey(), PATH_SEPARATOR));
  }

  /**
   * Gets the child name based on the parent name.
   *
   * @param child the key of the child
   * @param parent the key of the parent
   * @return the child key with the parent prefix removed
   * @throws IOException if parent prefix is invalid
   */
  protected String getChildName(String child, String parent) throws IOException {
    if (child.startsWith(parent)) {
      return child.substring(parent.length());
    }
    throw new IOException(String.format("Invalid prefix. Parent: %s Child: %s", parent, child));
  }

  /**
   * Get suffix used to encode a directory.
   *
   * @return folder suffix
   */
  protected abstract String getFolderSuffix();

  /**
   * Get object listing for given path.
   *
   * @param path pseudo-directory path excluding header and bucket
   * @param recursive whether to request immediate children only, or all descendants
   * @return chunked object listing
   * @throws IOException if a non-alluxio error occurs
   */
  protected abstract ObjectListingResult getObjectListing(String path, boolean recursive)
      throws IOException;

  /**
   * Get full path of root in object store.
   *
   * @return full path including scheme and bucket
   */
  protected abstract String getRootKey();

  /**
   * Lists the files in the given path, the paths will be their logical names and not contain the
   * folder suffix. Note that, the list results are unsorted.
   *
   * @param path the key to list
   * @param recursive if true will list children directories as well
   * @return an array of the file and folder names in this directory
   * @throws IOException if an I/O error occurs
   */
  protected UnderFileStatus[] listInternal(String path, boolean recursive) throws IOException {
    // if the path not exists, or it is a file, then should return null
    if (!isDirectory(path)) {
      return null;
    }
    path = stripPrefixIfPresent(path);
    path = PathUtils.normalizePath(path, PATH_SEPARATOR);
    path = path.equals(PATH_SEPARATOR) ? "" : path;
    Map<String, Boolean> children = new HashMap<>();
    ObjectListingResult chunk = getObjectListing(path, recursive);
    chunk = chunk.getNextChunk();
    if (chunk == null) {
      return null;
    }
    while (chunk != null) {
      // Directories in UFS can be possibly encoded in two different ways:
      // (1) as file objects with FOLDER_SUFFIX for directories created through Alluxio or
      // (2) as "common prefixes" of other files objects for directories not created through
      // Alluxio
      //
      // Case (1) (and file objects) is accounted for by iterating over chunk.getObjects() while
      // case (2) is accounted for by iterating over chunk.getCommonPrefixes().
      //
      // An example, with prefix="ufs" and delimiter="/" and LISTING_LENGTH=5
      // - objects.key = ufs/, child =
      // - objects.key = ufs/dir1<FOLDER_SUFFIX>, child = dir1
      // - objects.key = ufs/file, child = file
      // - commonPrefix = ufs/dir1/, child = dir1
      // - commonPrefix = ufs/dir2/, child = dir2

      // Handle case (1)
      for (String obj : chunk.getObjectNames()) {
        // Remove parent portion of the key
        String child = getChildName(obj, path);
        // Prune the special folder suffix
        boolean isDir = child.endsWith(getFolderSuffix());
        child = CommonUtils.stripSuffixIfPresent(child, getFolderSuffix());
        // Only add if the path is not empty (removes results equal to the path)
        if (!child.isEmpty()) {
          children.put(child, isDir);
        }
      }
      // Handle case (2)
      for (String commonPrefix : chunk.getCommonPrefixes()) {
        // Remove parent portion of the key
        String child = getChildName(commonPrefix, path);

        if (child != null) {
          // Remove any portion after the last path delimiter
          int childNameIndex = child.lastIndexOf(PATH_SEPARATOR);
          child = childNameIndex != -1 ? child.substring(0, childNameIndex) : child;
          if (!child.isEmpty() && !children.containsKey(child)) {
            // This directory has not been created through Alluxio.
            mkdirsInternal(commonPrefix);
            // If both a file and a directory existed with the same name, the path will be
            // treated as a directory
            children.put(child, true);
          }
        }
      }
      chunk = chunk.getNextChunk();
    }
    UnderFileStatus[] ret = new UnderFileStatus[children.size()];
    int pos = 0;
    for (Map.Entry<String, Boolean> entry : children.entrySet()) {
      ret[pos++] = new UnderFileStatus(entry.getKey(), entry.getValue());
    }
    return ret;
  }

  /**
   * Creates a directory flagged file with the key and folder suffix.
   *
   * @param key the key to create a folder
   * @return true if the operation was successful, false otherwise
   */
  protected boolean mkdirsInternal(String key) {
    return createEmptyObject(convertToFolderName(stripPrefixIfPresent(key)));
  }

  /**
   * Treating the object store as a file system, checks if the parent directory exists.
   *
   * @param path the path to check
   * @return true if the parent exists or if the path is root, false otherwise
   */
  protected boolean parentExists(String path) throws IOException {
    // Assume root always has a parent
    if (isRoot(path)) {
      return true;
    }
    String parentKey = getParentPath(path);
    return parentKey != null && isDirectory(parentKey);
  }

  /**
   * Strips the bucket prefix or the preceding path separator from the path if it is present. For
   * example, for input path ufs://my-bucket-name/my-path/file, the output would be my-path/file. If
   * path is an absolute path like /my-path/file, the output would be my-path/file. This method will
   * leave keys without a prefix unaltered, ie. my-path/file returns my-path/file.
   *
   * @param path the path to strip
   * @return the path without the bucket prefix
   */
  protected String stripPrefixIfPresent(String path) {
    String stripedKey = CommonUtils.stripPrefixIfPresent(path,
        PathUtils.normalizePath(getRootKey(), PATH_SEPARATOR));
    if (!stripedKey.equals(path)) {
      return stripedKey;
    }
    return CommonUtils.stripPrefixIfPresent(path, PATH_SEPARATOR);
  }
}
