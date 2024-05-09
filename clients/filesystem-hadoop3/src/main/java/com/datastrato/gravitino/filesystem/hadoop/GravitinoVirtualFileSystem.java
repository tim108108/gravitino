/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.filesystem.hadoop;

import static com.datastrato.gravitino.filesystem.hadoop.GravitinoVirtualFileSystemConfiguration.DOT;
import static com.datastrato.gravitino.filesystem.hadoop.GravitinoVirtualFileSystemConfiguration.SLASH;
import static com.datastrato.gravitino.filesystem.hadoop.GravitinoVirtualFileSystemConfiguration.UNDER_SCORE;

import com.datastrato.gravitino.Catalog;
import com.datastrato.gravitino.NameIdentifier;
import com.datastrato.gravitino.auth.AuthConstants;
import com.datastrato.gravitino.client.DefaultOAuth2TokenProvider;
import com.datastrato.gravitino.client.GravitinoClient;
import com.datastrato.gravitino.client.GravitinoClientBase;
import com.datastrato.gravitino.client.TokenAuthProvider;
import com.datastrato.gravitino.enums.FilesetPrefixPattern;
import com.datastrato.gravitino.file.Fileset;
import com.datastrato.gravitino.properties.FilesetProperties;
import com.datastrato.gravitino.shaded.com.google.common.annotations.VisibleForTesting;
import com.datastrato.gravitino.shaded.com.google.common.base.Preconditions;
import com.datastrato.gravitino.shaded.com.google.common.collect.ImmutableMap;
import com.datastrato.gravitino.shaded.com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.datastrato.gravitino.shaded.org.apache.commons.lang3.StringUtils;
import com.datastrato.gravitino.shaded.org.apache.commons.lang3.tuple.Pair;
import com.datastrato.gravitino.utils.FilesetPrefixPatternUtils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.InvalidPathException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Progressable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GravitinoVirtualFileSystem} is a virtual file system which users can access `fileset` and
 * other resources. It obtains the actual storage location corresponding to the resource from the
 * Gravitino server, and creates an independent file system for it to act as an agent for users to
 * access the underlying storage.
 */
public class GravitinoVirtualFileSystem extends FileSystem {
  private static final Logger Logger = LoggerFactory.getLogger(GravitinoVirtualFileSystem.class);
  private Path workingDirectory;
  private URI uri;
  private GravitinoClient client;
  private String metalakeName;
  private Cache<NameIdentifier, Pair<Fileset, FileSystem>> filesetCache;
  private ScheduledThreadPoolExecutor scheduler;

  // The pattern is used to match gvfs path. The scheme prefix (gvfs://fileset) is optional.
  // The following path can be match:
  //     gvfs://fileset/fileset_catalog/fileset_schema/fileset1/file.txt
  //     /fileset_catalog/fileset_schema/fileset1/sub_dir/
  private static final Pattern IDENTIFIER_PATTERN =
      Pattern.compile("^(?:gvfs://fileset)?/([^/]+)/([^/]+)/([^/]+)(?:/[^/]+)*/?$");

  @Override
  public void initialize(URI name, Configuration configuration) throws IOException {
    if (!name.toString().startsWith(GravitinoVirtualFileSystemConfiguration.GVFS_FILESET_PREFIX)) {
      throw new IllegalArgumentException(
          String.format(
              "Unsupported file system scheme: %s for %s.",
              name.getScheme(), GravitinoVirtualFileSystemConfiguration.GVFS_SCHEME));
    }

    int maxCapacity =
        configuration.getInt(
            GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_FILESET_CACHE_MAX_CAPACITY_KEY,
            GravitinoVirtualFileSystemConfiguration
                .FS_GRAVITINO_FILESET_CACHE_MAX_CAPACITY_DEFAULT);
    Preconditions.checkArgument(
        maxCapacity > 0,
        "'%s' should be greater than 0",
        GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_FILESET_CACHE_MAX_CAPACITY_KEY);

    long evictionMillsAfterAccess =
        configuration.getLong(
            GravitinoVirtualFileSystemConfiguration
                .FS_GRAVITINO_FILESET_CACHE_EVICTION_MILLS_AFTER_ACCESS_KEY,
            GravitinoVirtualFileSystemConfiguration
                .FS_GRAVITINO_FILESET_CACHE_EVICTION_MILLS_AFTER_ACCESS_DEFAULT);
    Preconditions.checkArgument(
        evictionMillsAfterAccess > 0,
        "'%s' should be greater than 0",
        GravitinoVirtualFileSystemConfiguration
            .FS_GRAVITINO_FILESET_CACHE_EVICTION_MILLS_AFTER_ACCESS_KEY);

    initializeCache(maxCapacity, evictionMillsAfterAccess);

    this.metalakeName =
        configuration.get(GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_METALAKE_KEY);
    Preconditions.checkArgument(
        StringUtils.isNotBlank(metalakeName),
        "'%s' is not set in the configuration",
        GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_METALAKE_KEY);

    initializeClient(configuration);

    this.workingDirectory = new Path(name);
    this.uri = URI.create(name.getScheme() + "://" + name.getAuthority());

    setConf(configuration);
    super.initialize(uri, getConf());
  }

  @VisibleForTesting
  Cache<NameIdentifier, Pair<Fileset, FileSystem>> getFilesetCache() {
    return filesetCache;
  }

  private void initializeCache(int maxCapacity, long expireAfterAccess) {
    // Since Caffeine does not ensure that removalListener will be involved after expiration
    // We use a scheduler with one thread to clean up expired clients.
    this.scheduler = new ScheduledThreadPoolExecutor(1, newDaemonThreadFactory());

    this.filesetCache =
        Caffeine.newBuilder()
            .maximumSize(maxCapacity)
            .expireAfterAccess(expireAfterAccess, TimeUnit.MILLISECONDS)
            .scheduler(Scheduler.forScheduledExecutorService(scheduler))
            .removalListener(
                (key, value, cause) -> {
                  try {
                    Pair<Fileset, FileSystem> pair = (Pair<Fileset, FileSystem>) value;
                    if (pair != null && pair.getRight() != null) pair.getRight().close();
                  } catch (IOException e) {
                    Logger.error("Cannot close the file system for fileset: {}", key, e);
                  }
                })
            .build();
  }

  private ThreadFactory newDaemonThreadFactory() {
    return new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat("gvfs-cache-cleaner" + "-%d")
        .build();
  }

  private void initializeClient(Configuration configuration) {
    // initialize the Gravitino client
    String serverUri =
        configuration.get(GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_SERVER_URI_KEY);
    Preconditions.checkArgument(
        StringUtils.isNotBlank(serverUri),
        "'%s' is not set in the configuration",
        GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_SERVER_URI_KEY);

    String authType =
        configuration.get(
            GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_AUTH_TYPE_KEY,
            GravitinoVirtualFileSystemConfiguration.SIMPLE_AUTH_TYPE);
    if (authType.equalsIgnoreCase(GravitinoVirtualFileSystemConfiguration.SIMPLE_AUTH_TYPE)) {
      String superUser =
          configuration.get(
              GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_SIMPLE_SUPER_USER_KEY);
      checkAuthConfig(
          GravitinoVirtualFileSystemConfiguration.SIMPLE_AUTH_TYPE,
          GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_SIMPLE_SUPER_USER_KEY,
          superUser);
      String proxyUser =
          configuration.get(
              GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_SIMPLE_PROXY_USER_KEY);
      GravitinoClientBase.Builder<GravitinoClient> builder =
          GravitinoClient.builder(serverUri).withMetalake(metalakeName).withSimpleAuth(superUser);
      if (StringUtils.isNotBlank(proxyUser)) {
        builder.withHeaders(ImmutableMap.of(AuthConstants.PROXY_USER, proxyUser));
      }
      this.client = builder.build();
    } else if (authType.equalsIgnoreCase(
        GravitinoVirtualFileSystemConfiguration.OAUTH2_AUTH_TYPE)) {
      String authServerUri =
          configuration.get(
              GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_OAUTH2_SERVER_URI_KEY);
      checkAuthConfig(
          GravitinoVirtualFileSystemConfiguration.OAUTH2_AUTH_TYPE,
          GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_OAUTH2_SERVER_URI_KEY,
          authServerUri);

      String credential =
          configuration.get(
              GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_OAUTH2_CREDENTIAL_KEY);
      checkAuthConfig(
          GravitinoVirtualFileSystemConfiguration.OAUTH2_AUTH_TYPE,
          GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_OAUTH2_CREDENTIAL_KEY,
          credential);

      String path =
          configuration.get(
              GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_OAUTH2_PATH_KEY);
      checkAuthConfig(
          GravitinoVirtualFileSystemConfiguration.OAUTH2_AUTH_TYPE,
          GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_OAUTH2_PATH_KEY,
          path);

      String scope =
          configuration.get(
              GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_OAUTH2_SCOPE_KEY);
      checkAuthConfig(
          GravitinoVirtualFileSystemConfiguration.OAUTH2_AUTH_TYPE,
          GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_OAUTH2_SCOPE_KEY,
          scope);

      DefaultOAuth2TokenProvider authDataProvider =
          DefaultOAuth2TokenProvider.builder()
              .withUri(authServerUri)
              .withCredential(credential)
              .withPath(path)
              .withScope(scope)
              .build();

      this.client =
          GravitinoClient.builder(serverUri)
              .withMetalake(metalakeName)
              .withOAuth(authDataProvider)
              .build();
    } else if (authType.equalsIgnoreCase(GravitinoVirtualFileSystemConfiguration.TOKEN_AUTH_TYPE)) {
      String token =
          configuration.get(
              GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_AUTH_TOKEN_KEY);
      checkAuthConfig(
          GravitinoVirtualFileSystemConfiguration.TOKEN_AUTH_TYPE,
          GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_AUTH_TOKEN_KEY,
          token);
      TokenAuthProvider tokenAuthProvider = new TokenAuthProvider(token);
      this.client =
          GravitinoClient.builder(serverUri)
              .withMetalake(metalakeName)
              .withTokenAuth(tokenAuthProvider)
              .build();
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Unsupported authentication type: %s for %s.",
              authType, GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_AUTH_TYPE_KEY));
    }
  }

  private void checkAuthConfig(String authType, String configKey, String configValue) {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(configValue),
        "%s should not be null if %s is set to %s.",
        configKey,
        GravitinoVirtualFileSystemConfiguration.FS_GRAVITINO_CLIENT_AUTH_TYPE_KEY,
        authType);
  }

  private String getVirtualLocation(NameIdentifier identifier, boolean withScheme) {
    return String.format(
        "%s/%s/%s/%s",
        withScheme ? GravitinoVirtualFileSystemConfiguration.GVFS_FILESET_PREFIX : "",
        identifier.namespace().level(1),
        identifier.namespace().level(2),
        identifier.name());
  }

  private Path getActualPathByIdentifier(
      NameIdentifier identifier, Pair<Fileset, FileSystem> filesetPair, Path path) {
    String virtualPath = path.toString();
    boolean withScheme =
        virtualPath.startsWith(GravitinoVirtualFileSystemConfiguration.GVFS_FILESET_PREFIX);
    String virtualLocation = getVirtualLocation(identifier, withScheme);
    String storageLocation = filesetPair.getLeft().storageLocation();
    try {
      if (checkMountsSingleFile(filesetPair)) {
        Preconditions.checkArgument(
            virtualPath.equals(virtualLocation),
            "Path: %s should be same with the virtual prefix: %s, because the fileset only mounts a single file.",
            virtualPath,
            virtualLocation);

        return new Path(storageLocation);
      } else {
        return new Path(virtualPath.replaceFirst(virtualLocation, storageLocation));
      }
    } catch (Exception e) {
      throw new RuntimeException(
          String.format("Cannot resolve path: %s to actual storage path, exception:", path), e);
    }
  }

  private boolean checkMountsSingleFile(Pair<Fileset, FileSystem> filesetPair) {
    try {
      return filesetPair
          .getRight()
          .getFileStatus(new Path(filesetPair.getLeft().storageLocation()))
          .isFile();
    } catch (FileNotFoundException e) {
      // We should always return false here, same with the logic in `FileSystem.isFile(Path f)`.
      return false;
    } catch (IOException e) {
      throw new RuntimeException(
          String.format(
              "Cannot check whether the fileset: %s mounts a single file, exception: %s",
              filesetPair.getLeft().name(), e.getMessage()),
          e);
    }
  }

  private FileStatus convertFileStatusPathPrefix(
      FileStatus fileStatus, String actualPrefix, String virtualPrefix) {
    String filePath = fileStatus.getPath().toString();
    Preconditions.checkArgument(
        filePath.startsWith(actualPrefix),
        "Path %s doesn't start with prefix \"%s\".",
        filePath,
        actualPrefix);
    Path path = new Path(filePath.replaceFirst(actualPrefix, virtualPrefix));
    fileStatus.setPath(path);

    return fileStatus;
  }

  @VisibleForTesting
  NameIdentifier extractIdentifier(URI virtualUri) {
    String virtualPath = virtualUri.toString();
    Preconditions.checkArgument(
        StringUtils.isNotBlank(virtualPath),
        "Uri which need be extracted cannot be null or empty.");

    Matcher matcher = IDENTIFIER_PATTERN.matcher(virtualPath);
    Preconditions.checkArgument(
        matcher.matches() && matcher.groupCount() == 3,
        "URI %s doesn't contains valid identifier",
        virtualPath);

    return NameIdentifier.ofFileset(
        metalakeName, matcher.group(1), matcher.group(2), matcher.group(3));
  }

  private FilesetContext getFilesetContext(Path virtualPath) {
    NameIdentifier identifier = extractIdentifier(virtualPath.toUri());
    Pair<Fileset, FileSystem> pair = filesetCache.get(identifier, this::constructNewFilesetPair);
    Preconditions.checkState(
        pair != null,
        "Cannot get the pair of fileset instance and actual file system for %s",
        identifier);
    Path actualPath = getActualPathByIdentifier(identifier, pair, virtualPath);
    return FilesetContext.builder()
        .withIdentifier(identifier)
        .withFileset(pair.getLeft())
        .withFileSystem(pair.getRight())
        .withActualPath(actualPath)
        .withVirtualPath(virtualPath)
        .build();
  }

  private Pair<Fileset, FileSystem> constructNewFilesetPair(NameIdentifier identifier) {
    // Always create a new file system instance for the fileset.
    // Therefore, users cannot bypass gvfs and use `FileSystem.get()` to directly obtain the
    // FileSystem
    try {
      Fileset fileset = loadFileset(identifier);
      URI storageUri = URI.create(fileset.storageLocation());
      FileSystem actualFileSystem = FileSystem.newInstance(storageUri, getConf());
      Preconditions.checkState(actualFileSystem != null, "Cannot get the actual file system");
      return Pair.of(fileset, actualFileSystem);
    } catch (IOException e) {
      throw new RuntimeException(
          String.format(
              "Cannot create file system for fileset: %s, exception: %s",
              identifier, e.getMessage()),
          e);
    } catch (RuntimeException e) {
      throw new RuntimeException(
          String.format(
              "Cannot load fileset: %s from the server. exception: %s",
              identifier, e.getMessage()));
    }
  }

  private Fileset loadFileset(NameIdentifier identifier) {
    Catalog catalog =
        client.loadCatalog(NameIdentifier.ofCatalog(metalakeName, identifier.namespace().level(1)));
    return catalog.asFilesetCatalog().loadFileset(identifier);
  }

  private FilesetPrefixProperties getFilesetPrefixProperties(FilesetContext context) {
    Preconditions.checkArgument(
        context.getFileset().properties().containsKey(FilesetProperties.PREFIX_PATTERN_KEY),
        "Fileset: `%s` does not contain the property: `%s`, please set this property."
            + " Following options are supported: `%s`.",
        context.getIdentifier(),
        FilesetProperties.PREFIX_PATTERN_KEY,
        Arrays.asList(FilesetPrefixPattern.values()));
    FilesetPrefixPattern prefixPattern =
        FilesetPrefixPattern.valueOf(
            context.getFileset().properties().get(FilesetProperties.PREFIX_PATTERN_KEY));

    Preconditions.checkArgument(
        context.getFileset().properties().containsKey(FilesetProperties.DIR_MAX_LEVEL_KEY),
        "Fileset: `%s` does not contain the property: `%s`, please set this property.",
        context.getIdentifier(),
        FilesetProperties.DIR_MAX_LEVEL_KEY);
    int maxLevel =
        Integer.parseInt(
            context.getFileset().properties().get(FilesetProperties.DIR_MAX_LEVEL_KEY));
    Preconditions.checkArgument(
        maxLevel > 0,
        "Fileset: `%s`'s max level should be greater than 0.",
        context.getIdentifier());

    return FilesetPrefixProperties.builder()
        .withPattern(prefixPattern)
        .withMaxLevel(maxLevel)
        .build();
  }

  private boolean checkSubDirValid(
      FilesetContext context, Pattern pattern, FilesetPrefixProperties prefixProperties) {
    Path storageLocation = new Path(context.getFileset().storageLocation());
    // match sub dir like `/xxx/yyy`
    String subDir =
        context.getActualPath().toString().substring(storageLocation.toString().length());
    if (StringUtils.isNotBlank(subDir)) {
      Matcher matcher = pattern.matcher(subDir);
      if (!matcher.matches()) {
        // In this case, the sub dir level must be greater than the dir max level
        String[] dirNames =
            subDir.startsWith(SLASH) ? subDir.substring(1).split(SLASH) : subDir.split(SLASH);
        // Try to check subdirectories before max level having temporary directory,
        // if so, we pass the check
        for (int index = 0;
            index < prefixProperties.getMaxLevel() && index < dirNames.length;
            index++) {
          if (dirNames[index].startsWith(UNDER_SCORE) || dirNames[index].startsWith(DOT)) {
            return true;
          }
        }
        return false;
      }
    }
    return true;
  }

  private void checkPathValid(FilesetContext context, boolean isFile) {
    FilesetPrefixProperties prefixProperties = getFilesetPrefixProperties(context);
    Pattern pattern =
        FilesetPrefixPatternUtils.combinePrefixPattern(
            prefixProperties.getPattern(), prefixProperties.getMaxLevel(), isFile);
    boolean valid = checkSubDirValid(context, pattern, prefixProperties);
    if (!valid) {
      throw new InvalidPathException(
          context.getVirtualPath().toString(),
          prefixErrorMessage(prefixProperties.getPattern(), prefixProperties.getMaxLevel()));
    }
  }

  private void checkRenamePathValid(FilesetContext srcContext, FilesetContext dstContext)
      throws IOException {
    FilesetPrefixProperties prefixProperties = getFilesetPrefixProperties(srcContext);

    FileStatus srcPathStatus = srcContext.getFileSystem().getFileStatus(srcContext.getActualPath());
    if (srcPathStatus != null) {
      Pattern pattern =
          FilesetPrefixPatternUtils.combinePrefixPattern(
              prefixProperties.getPattern(),
              prefixProperties.getMaxLevel(),
              srcPathStatus.isFile());

      boolean srcValid = checkSubDirValid(srcContext, pattern, prefixProperties);
      if (!srcValid) {
        throw new InvalidPathException(
            srcContext.getVirtualPath().toString(),
            prefixErrorMessage(prefixProperties.getPattern(), prefixProperties.getMaxLevel()));
      }

      boolean dstValid = checkSubDirValid(dstContext, pattern, prefixProperties);
      if (!dstValid) {
        throw new InvalidPathException(
            dstContext.getVirtualPath().toString(),
            prefixErrorMessage(prefixProperties.getPattern(), prefixProperties.getMaxLevel()));
      }
    }
  }

  private String prefixErrorMessage(FilesetPrefixPattern pattern, Integer maxLevel) {
    return String.format(
        "The path should like `%s`, and max sub directory level after fileset identifier should be less than %d.",
        pattern.getExample(), maxLevel);
  }

  private void logOperations(String methodName, FilesetContext context) {
    Logger.debug(
        "[{}]: Accessing fileset: `{}`'s path: `{}`.",
        methodName,
        context.getIdentifier().toString(),
        context.getVirtualPath().toString());
  }

  @Override
  public URI getUri() {
    return this.uri;
  }

  @Override
  public synchronized Path getWorkingDirectory() {
    return this.workingDirectory;
  }

  @Override
  public synchronized void setWorkingDirectory(Path newDir) {
    FilesetContext context = getFilesetContext(newDir);
    logOperations("setWorkingDirectory", context);
    context.getFileSystem().setWorkingDirectory(context.getActualPath());
    this.workingDirectory = newDir;
  }

  @Override
  public FSDataInputStream open(Path path, int bufferSize) throws IOException {
    FilesetContext context = getFilesetContext(path);
    logOperations("open", context);
    return context.getFileSystem().open(context.getActualPath(), bufferSize);
  }

  @Override
  public FSDataOutputStream create(
      Path path,
      FsPermission permission,
      boolean overwrite,
      int bufferSize,
      short replication,
      long blockSize,
      Progressable progress)
      throws IOException {
    FilesetContext context = getFilesetContext(path);
    // Create operation is only supported for files.
    checkPathValid(context, true);
    logOperations("create", context);
    return context
        .getFileSystem()
        .create(
            context.getActualPath(),
            permission,
            overwrite,
            bufferSize,
            replication,
            blockSize,
            progress);
  }

  @Override
  public FSDataOutputStream append(Path path, int bufferSize, Progressable progress)
      throws IOException {
    FilesetContext context = getFilesetContext(path);
    // Append operation is only supported for files.
    checkPathValid(context, true);
    logOperations("append", context);
    return context.getFileSystem().append(context.getActualPath(), bufferSize, progress);
  }

  @Override
  public boolean rename(Path src, Path dst) throws IOException {
    // There are two cases that cannot be renamed:
    // 1. Fileset identifier is not allowed to be renamed, only its subdirectories can be renamed
    // which not in the storage location of the fileset;
    // 2. Fileset only mounts a single file, the storage location of the fileset cannot be renamed;
    // Otherwise the metadata in the Gravitino server may be inconsistent.
    NameIdentifier srcIdentifier = extractIdentifier(src.toUri());
    NameIdentifier dstIdentifier = extractIdentifier(dst.toUri());
    Preconditions.checkArgument(
        srcIdentifier.equals(dstIdentifier),
        "Destination path fileset identifier: %s should be same with src path fileset identifier: %s.",
        srcIdentifier,
        dstIdentifier);

    FilesetContext srcFileContext = getFilesetContext(src);
    if (checkMountsSingleFile(
        Pair.of(srcFileContext.getFileset(), srcFileContext.getFileSystem()))) {
      throw new UnsupportedOperationException(
          String.format(
              "Cannot rename the fileset: %s which only mounts to a single file.", srcIdentifier));
    }

    FilesetContext dstFileContext = getFilesetContext(dst);

    checkRenamePathValid(srcFileContext, dstFileContext);

    logOperations("rename", srcFileContext);
    return srcFileContext
        .getFileSystem()
        .rename(srcFileContext.getActualPath(), dstFileContext.getActualPath());
  }

  @Override
  public boolean delete(Path path, boolean recursive) throws IOException {
    FilesetContext context = getFilesetContext(path);
    logOperations("delete", context);
    return context.getFileSystem().delete(context.getActualPath(), recursive);
  }

  @Override
  public FileStatus getFileStatus(Path path) throws IOException {
    FilesetContext context = getFilesetContext(path);
    FileStatus fileStatus = context.getFileSystem().getFileStatus(context.getActualPath());
    logOperations("getFileStatus", context);
    return convertFileStatusPathPrefix(
        fileStatus,
        context.getFileset().storageLocation(),
        getVirtualLocation(context.getIdentifier(), true));
  }

  @Override
  public FileStatus[] listStatus(Path path) throws IOException {
    FilesetContext context = getFilesetContext(path);
    FileStatus[] fileStatusResults = context.getFileSystem().listStatus(context.getActualPath());
    logOperations("listStatus", context);
    return Arrays.stream(fileStatusResults)
        .map(
            fileStatus ->
                convertFileStatusPathPrefix(
                    fileStatus,
                    new Path(context.getFileset().storageLocation()).toString(),
                    getVirtualLocation(context.getIdentifier(), true)))
        .toArray(FileStatus[]::new);
  }

  @Override
  public boolean mkdirs(Path path, FsPermission permission) throws IOException {
    FilesetContext context = getFilesetContext(path);
    // Mkdirs operation is only supported for dirs.
    checkPathValid(context, false);
    logOperations("mkdirs", context);
    return context.getFileSystem().mkdirs(context.getActualPath(), permission);
  }

  @Override
  public synchronized void close() throws IOException {
    // close all actual FileSystems
    for (Pair<Fileset, FileSystem> filesetPair : filesetCache.asMap().values()) {
      try {
        filesetPair.getRight().close();
      } catch (IOException e) {
        // ignore
      }
    }
    filesetCache.invalidateAll();
    // close the client
    try {
      if (client != null) {
        client.close();
      }
    } catch (Exception e) {
      // ignore
    }
    scheduler.shutdownNow();
    super.close();
  }
}
