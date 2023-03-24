/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.web3signer.signing.config;

import tech.pegasys.signers.common.MappedResults;
import tech.pegasys.web3signer.signing.ArtifactSigner;
import tech.pegasys.web3signer.signing.config.metadata.parser.SignerParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap.SimpleEntry;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SignerLoader {

  private static final Logger LOG = LogManager.getLogger();
  private static final long FILES_PROCESSED_TO_REPORT = 10;
  private static final int MAX_FORK_JOIN_THREADS = 5;

  private static final Map<Path, FileTime> metadataConfigFilesPathCache = new HashMap<>();

  public MappedResults<ArtifactSigner> load(
      final Path configsDirectory, final String fileExtension, final SignerParser signerParser) {
    final Instant start = Instant.now();
    LOG.info("Loading signer configuration metadata files from {}", configsDirectory);

    final Pair<Map<Path, String>, Integer> configFilesMapPair =
        getNewOrModifiedConfigFilesContents(configsDirectory, fileExtension);
    final Map<Path, String> configFilesContentMap = configFilesMapPair.getLeft();
    final int configFileErrorCount = configFilesMapPair.getRight();

    final String timeTaken =
        DurationFormatUtils.formatDurationHMS(Duration.between(start, Instant.now()).toMillis());
    LOG.info(
        "Signer configuration metadata files read in memory {} in {}",
        configFilesContentMap.size(),
        timeTaken);

    final MappedResults<ArtifactSigner> metadataResult =
        processMetadataFilesInParallel(configFilesContentMap, signerParser);
    metadataResult.mergeErrorCount(configFileErrorCount);
    return metadataResult;
  }

  private Pair<Map<Path, String>, Integer> getNewOrModifiedConfigFilesContents(
      final Path configsDirectory, final String fileExtension) {
    final AtomicInteger errorCount = new AtomicInteger(0);
    final Map<Path, String> configFileMap = new HashMap<>();
    // read configuration files without converting them to signers first.
    try (final Stream<Path> fileStream = Files.list(configsDirectory)) {
      final Map<Path, String> _map =
          fileStream
              .filter(path -> matchesFileExtension(fileExtension, path))
              .filter(this::isNewOrModifiedMetadataFile)
              .map(
                  path -> {
                    try {
                      return getMetadataFileContent(path);
                    } catch (final IOException e) {
                      errorCount.incrementAndGet();
                      LOG.error("Error reading config file: {}", path, e);
                      return null;
                    }
                  })
              .filter(Objects::nonNull)
              .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
      configFileMap.putAll(_map);
    } catch (final IOException e) {
      LOG.error("Unable to access the supplied key directory", e);
      errorCount.incrementAndGet();
    }
    return Pair.of(configFileMap, errorCount.get());
  }

  private boolean isNewOrModifiedMetadataFile(final Path path) {
    // only read file if is not previously read or has been since modified
    try {
      final FileTime lastModifiedTime = Files.getLastModifiedTime(path);
      if (metadataConfigFilesPathCache.containsKey(path)) {
        if (metadataConfigFilesPathCache.get(path).compareTo(lastModifiedTime) == 0) {
          return false;
        }
      }

      // keep the path and last modified time in local cache
      metadataConfigFilesPathCache.put(path, lastModifiedTime);
      return true;
    } catch (final IOException e) {
      LOG.error("Error reading config file: {}", path, e);
      return false;
    }
  }

  private SimpleEntry<Path, String> getMetadataFileContent(final Path path) throws IOException {
    return new SimpleEntry<>(path, Files.readString(path, StandardCharsets.UTF_8));
  }

  private MappedResults<ArtifactSigner> processMetadataFilesInParallel(
      final Map<Path, String> configFilesContent, final SignerParser signerParser) {
    // use custom fork-join pool instead of common. Limit number of threads to avoid Azure bug
    ForkJoinPool forkJoinPool = null;
    try {
      forkJoinPool = new ForkJoinPool(numberOfThreads());
      return forkJoinPool.submit(() -> parseMetadataFiles(configFilesContent, signerParser)).get();
    } catch (final Exception e) {
      LOG.error(
          "Unexpected error in processing configuration files in parallel: {}", e.getMessage(), e);
      return MappedResults.errorResult();
    } finally {
      if (forkJoinPool != null) {
        forkJoinPool.shutdown();
      }
    }
  }

  private MappedResults<ArtifactSigner> parseMetadataFiles(
      final Map<Path, String> configFilesContents, final SignerParser signerParser) {
    LOG.info("Parsing configuration metadata files");

    final Instant start = Instant.now();
    final AtomicLong configFilesHandled = new AtomicLong();
    final AtomicInteger errorCount = new AtomicInteger(0);

    final Set<ArtifactSigner> artifactSigners =
        configFilesContents.entrySet().parallelStream()
            .flatMap(
                metadataContent -> {
                  final long filesProcessed = configFilesHandled.incrementAndGet();
                  if (filesProcessed % FILES_PROCESSED_TO_REPORT == 0) {
                    LOG.info("{} metadata configuration files processed", filesProcessed);
                  }
                  try {
                    return signerParser.parse(metadataContent.getValue()).stream();
                  } catch (final Exception e) {
                    renderException(e, metadataContent.getKey().toString());
                    errorCount.incrementAndGet();
                    return null;
                  }
                })
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    final String timeTaken =
        DurationFormatUtils.formatDurationHMS(Duration.between(start, Instant.now()).toMillis());
    LOG.info("Total configuration metadata files processed: {}", configFilesHandled.get());
    LOG.info(
        "Total signers loaded from configuration files: {} in {} with error count {}",
        artifactSigners.size(),
        timeTaken,
        errorCount.get());
    return MappedResults.newInstance(artifactSigners, errorCount.get());
  }

  private boolean matchesFileExtension(final String validFileExtension, final Path filename) {
    final boolean isHidden = filename.toFile().isHidden();
    final String extension = FilenameUtils.getExtension(filename.toString());
    return !isHidden && extension.toLowerCase().endsWith(validFileExtension.toLowerCase());
  }

  private void renderException(final Throwable t, final String filename) {
    LOG.error(
        "Error parsing signing metadata file {}: {}",
        filename,
        ExceptionUtils.getRootCauseMessage(t));
    LOG.debug(ExceptionUtils.getStackTrace(t));
  }

  private int numberOfThreads() {
    // try to allocate between 1-5 threads (based on processor cores) to process files in parallel
    int defaultNumberOfThreads = Runtime.getRuntime().availableProcessors() / 2;

    if (defaultNumberOfThreads >= MAX_FORK_JOIN_THREADS) {
      return MAX_FORK_JOIN_THREADS;
    } else if (defaultNumberOfThreads < 1) {
      return 1;
    }
    return defaultNumberOfThreads;
  }
}
