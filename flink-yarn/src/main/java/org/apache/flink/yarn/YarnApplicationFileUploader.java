/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.yarn;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.util.IOUtils;
import org.apache.flink.util.function.FunctionUtils;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * A class with utilities for uploading files
 * related to the deployment of a single application.
 */
class YarnApplicationFileUploader implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(YarnApplicationFileUploader.class);

	private final FileSystem fileSystem;

	private final ApplicationId applicationId;

	private final Path homeDir;

	private final Path applicationDir;

	private final Map<String, FileStatus> providedSharedLibs;

	private final Map<String, LocalResource> localResources;

	private YarnApplicationFileUploader(
			final FileSystem fileSystem,
			final Path homeDir,
			final List<Path> providedLibDirs,
			final ApplicationId applicationId) throws IOException {
		this.fileSystem = checkNotNull(fileSystem);
		this.homeDir = checkNotNull(homeDir);
		this.applicationId = checkNotNull(applicationId);

		this.localResources = new HashMap<>();
		this.applicationDir = getApplicationDir(applicationId);
		this.providedSharedLibs = getAllFilesInProvidedLibDirs(providedLibDirs);
	}

	Map<String, LocalResource> getRegisteredLocalResources() {
		return localResources;
	}

	Path getHomeDir() {
		return homeDir;
	}

	Path getApplicationDir() {
		return applicationDir;
	}

	@Override
	public void close() {
		IOUtils.closeQuietly(fileSystem);
	}

	/**
	 * Register a single local/remote resource and adds it to <tt>localResources</tt>.
	 * @param key the key to add the resource under
	 * @param resourcePath path of the resource to be registered
	 * @param relativeDstPath the relative path at the target location
	 *                              (this will be prefixed by the application-specific directory)
	 * @param replicationFactor number of replications of a remote file to be created
	 * @return the uploaded resource descriptor
	 */
	YarnLocalResourceDescriptor registerSingleLocalResource(
			final String key,
			final Path resourcePath,
			final String relativeDstPath,
			final int replicationFactor) throws IOException {

		if (Utils.isRemotePath(resourcePath.toString())) {
			final FileStatus fileStatus = fileSystem.getFileStatus(resourcePath);
			LOG.debug("Using remote file {} to register local resource", fileStatus.getPath());

			final YarnLocalResourceDescriptor descriptor = YarnLocalResourceDescriptor
				.fromFileStatus(key, fileStatus, LocalResourceVisibility.APPLICATION);
			localResources.put(key, descriptor.toLocalResource());
			return descriptor;
		}

		final FileStatus fileStatus = providedSharedLibs.get(resourcePath.getName());
		if (fileStatus != null) {
			LOG.debug("Using provided file {} instead of the local {}", fileStatus.getPath(), resourcePath);

			final YarnLocalResourceDescriptor descriptor = YarnLocalResourceDescriptor
				.fromFileStatus(fileStatus.getPath().getName(), fileStatus, LocalResourceVisibility.PUBLIC);
			return descriptor;
		}

		final File localFile = new File(resourcePath.toUri().getPath());
		final Tuple2<Path, Long> remoteFileInfo = uploadLocalFileToRemote(resourcePath, relativeDstPath, replicationFactor);
		final YarnLocalResourceDescriptor descriptor = new YarnLocalResourceDescriptor(
			key,
			remoteFileInfo.f0,
			localFile.length(),
			remoteFileInfo.f1,
			LocalResourceVisibility.APPLICATION);
		localResources.put(key, descriptor.toLocalResource());
		return descriptor;
	}

	Tuple2<Path, Long> uploadLocalFileToRemote(
			final Path localSrcPath,
			final String relativeDstPath,
			final int replicationFactor) throws IOException {

		final File localFile = new File(localSrcPath.toUri().getPath());
		checkArgument(!localFile.isDirectory(), "File to copy cannot be a directory: " + localSrcPath);

		final Path dst = copyToRemoteApplicationDir(localSrcPath, relativeDstPath, replicationFactor);

		// Note: If we directly used registerLocalResource(FileSystem, Path) here, we would access the remote
		//       file once again which has problems with eventually consistent read-after-write file
		//       systems. Instead, we decide to wait until the remote file be available.

		final FileStatus[] fss = waitForTransferToComplete(dst);
		if (fss == null || fss.length <=  0) {
			LOG.debug("Failed to fetch remote modification time from {}, using local timestamp {}", dst, localFile.lastModified());
			return Tuple2.of(dst, localFile.lastModified());
		}

		LOG.debug("Got modification time {} from remote path {}", fss[0].getModificationTime(), dst);
		return Tuple2.of(dst, fss[0].getModificationTime());
	}

	/**
	 * Recursively uploads (and registers) any (user and system) files in <tt>shipFiles</tt> except
	 * for files matching "<tt>flink-dist*.jar</tt>" which should be uploaded separately. If it is
	 * already a remote file, the uploading will be skipped.
	 *
	 * @param shipFiles
	 * 		local or remote files to register as Yarn local resources
	 * @param remotePaths
	 * 		paths of the remote resources (uploaded resources will be added)
	 * @param localResourcesDirectory
	 *		the directory the localResources are uploaded to
	 * @param envShipResourceList
	 * 		list of shipped resources in {@link YarnLocalResourceDescriptor} format
	 * @param replicationFactor
	 * 	     number of replications of a remote file to be created
	 *
	 * @return list of class paths with the the proper resource keys from the registration
	 */
	List<String> registerMultipleLocalResources(
			final Collection<Path> shipFiles,
			final List<Path> remotePaths,
			final String localResourcesDirectory,
			final List<YarnLocalResourceDescriptor> envShipResourceList,
			final int replicationFactor) throws IOException {

		checkArgument(replicationFactor >= 1);
		final List<Path> localPaths = new ArrayList<>();
		final List<Path> relativePaths = new ArrayList<>();
		for (Path shipFile : shipFiles) {
			if (Utils.isRemotePath(shipFile.toString())) {
				if (fileSystem.isDirectory(shipFile)) {
					final URI parentURI = shipFile.getParent().toUri();
					final RemoteIterator<LocatedFileStatus> iterable = fileSystem.listFiles(shipFile, true);
					while (iterable.hasNext()) {
						final Path current = iterable.next().getPath();
						localPaths.add(current);
						relativePaths.add(new Path(localResourcesDirectory, parentURI.relativize(current.toUri()).getPath()));
					}
					continue;
				}
			} else {
				final File file = new File(shipFile.toUri().getPath());
				if (file.isDirectory()) {
					final java.nio.file.Path shipPath = file.toPath();
					final java.nio.file.Path parentPath = shipPath.getParent();
					Files.walkFileTree(shipPath, new SimpleFileVisitor<java.nio.file.Path>() {
						@Override
						public FileVisitResult visitFile(java.nio.file.Path file, BasicFileAttributes attrs) {
							localPaths.add(new Path(file.toUri()));
							relativePaths.add(new Path(localResourcesDirectory, parentPath.relativize(file).toString()));
							return FileVisitResult.CONTINUE;
						}
					});
					continue;
				}
			}
			localPaths.add(shipFile);
			relativePaths.add(new Path(localResourcesDirectory, shipFile.getName()));
		}

		final Set<String> archives = new HashSet<>();
		final Set<String> resources = new HashSet<>();
		for (int i = 0; i < localPaths.size(); i++) {
			final Path localPath = localPaths.get(i);
			final Path relativePath = relativePaths.get(i);
			if (!isFlinkDistJar(relativePath.getName())) {
				final String key = relativePath.toString();
				final YarnLocalResourceDescriptor resourceDescriptor = registerSingleLocalResource(
						key,
						localPath,
						relativePath.getParent().toString(),
						replicationFactor);
				remotePaths.add(resourceDescriptor.getPath());
				envShipResourceList.add(resourceDescriptor);

				if (!resourceDescriptor.alreadyRegisteredAsLocalResource()) {
					if (key.endsWith("jar")) {
						archives.add(relativePath.toString());
					} else {
						resources.add(relativePath.getParent().toString());
					}
				}
			}
		}

		// construct classpath, we always want resource directories to go first, we also sort
		// both resources and archives in order to make classpath deterministic
		final ArrayList<String> classPaths = new ArrayList<>();
		resources.stream().sorted().forEach(classPaths::add);
		archives.stream().sorted().forEach(classPaths::add);
		return classPaths;
	}

	/**
	 * Register all the files in the provided lib directories as Yarn local resources with PUBLIC visibility, which
	 * means that they will be cached in the nodes and reused by different applications.
	 *
	 * @return list of class paths with the file name
	 */
	List<String> registerProvidedLocalResources() {
		checkNotNull(localResources);

		final ArrayList<String> classPaths = new ArrayList<>();
		providedSharedLibs.forEach(
			(fileName, fileStatus) -> {
				localResources.put(
					fileName,
					Utils.registerLocalResource(
						fileStatus.getPath(),
						fileStatus.getLen(),
						fileStatus.getModificationTime(),
						LocalResourceVisibility.PUBLIC));

				if (!isFlinkDistJar(fileName)) {
					classPaths.add(fileName);
				}
			});
		return classPaths;
	}

	static YarnApplicationFileUploader from(
			final FileSystem fileSystem,
			final Path homeDirectory,
			final List<Path> providedLibDirs,
			final ApplicationId applicationId) throws IOException {
		return new YarnApplicationFileUploader(fileSystem, homeDirectory, providedLibDirs, applicationId);
	}

	private Path copyToRemoteApplicationDir(
			final Path localSrcPath,
			final String relativeDstPath,
			final int replicationFactor) throws IOException {

		final Path applicationDir = getApplicationDirPath(homeDir, applicationId);
		final String suffix = (relativeDstPath.isEmpty() ? "" : relativeDstPath + "/") + localSrcPath.getName();
		final Path dst = new Path(applicationDir, suffix);

		LOG.debug("Copying from {} to {} with replication factor {}", localSrcPath, dst, replicationFactor);

		fileSystem.copyFromLocalFile(false, true, localSrcPath, dst);
		fileSystem.setReplication(dst, (short) replicationFactor);
		return dst;
	}

	private FileStatus[] waitForTransferToComplete(Path dst) throws IOException {
		final int noOfRetries = 3;
		final int retryDelayMs = 100;

		int iter = 1;
		while (iter <= noOfRetries + 1) {
			try {
				return fileSystem.listStatus(dst);
			} catch (FileNotFoundException e) {
				LOG.debug("Got FileNotFoundException while fetching uploaded remote resources at retry num {}", iter);
				try {
					LOG.debug("Sleeping for {}ms", retryDelayMs);
					TimeUnit.MILLISECONDS.sleep(retryDelayMs);
				} catch (InterruptedException ie) {
					LOG.warn("Failed to sleep for {}ms at retry num {} while fetching uploaded remote resources", retryDelayMs, iter, ie);
				}
				iter++;
			}
		}
		return null;
	}

	private static boolean isFlinkDistJar(String fileName) {
		return fileName.startsWith("flink-dist") && fileName.endsWith("jar");
	}

	static Path getApplicationDirPath(final Path homeDir, final ApplicationId applicationId) {
		return new Path(checkNotNull(homeDir), ".flink/" + checkNotNull(applicationId) + '/');
	}

	private Path getApplicationDir(final ApplicationId applicationId) throws IOException {
		final Path applicationDir = getApplicationDirPath(homeDir, applicationId);
		if (!fileSystem.exists(applicationDir)) {
			final FsPermission permission = new FsPermission(FsAction.ALL, FsAction.NONE, FsAction.NONE);
			fileSystem.mkdirs(applicationDir, permission);
		}
		return applicationDir;
	}

	private Map<String, FileStatus> getAllFilesInProvidedLibDirs(final List<Path> providedLibDirs) {
		final Map<String, FileStatus> allFiles = new HashMap<>();
		checkNotNull(providedLibDirs).forEach(
			FunctionUtils.uncheckedConsumer(
				path -> {
					if (!fileSystem.exists(path) || !fileSystem.isDirectory(path)) {
						LOG.warn("Provided lib dir {} does not exist or is not a directory. Ignoring.", path);
					} else {
						final RemoteIterator<LocatedFileStatus> iterable = fileSystem.listFiles(path, true);
						while (iterable.hasNext()) {
							final LocatedFileStatus locatedFileStatus = iterable.next();
							final String fileName = locatedFileStatus.getPath().getName();

							final FileStatus prevMapping = allFiles.put(fileName, locatedFileStatus);
							if (prevMapping != null) {
								throw new IOException(
									"Two files with the same filename exist in the shared libs: " +
										prevMapping.getPath() + " - " + locatedFileStatus.getPath() +
										". Please deduplicate.");
							}
						}

						if (LOG.isDebugEnabled()) {
							LOG.debug("The following files were found in the shared lib dir: {}",
									allFiles.values().stream()
											.map(fileStatus -> fileStatus.getPath().toString())
											.collect(Collectors.joining(", ")));
						}
					}
				})
		);
		return Collections.unmodifiableMap(allFiles);
	}
}