/*
 * Copyright 2019 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package coollog.experiments.autojib;

import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.DockerDaemonImage;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.configuration.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.event.DefaultEventDispatcher;
import com.google.cloud.tools.jib.event.EventDispatcher;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.event.JibEventType;
import com.google.cloud.tools.jib.filesystem.AbsoluteUnixPath;
import com.google.cloud.tools.jib.filesystem.DirectoryWalker;
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory;
import com.google.cloud.tools.jib.frontend.MainClassFinder;
import com.google.cloud.tools.jib.image.DescriptorDigest;
import com.google.cloud.tools.jib.image.ImageReference;
import com.google.cloud.tools.jib.image.InvalidImageReferenceException;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Use this as the main class when you want to self-containerize.
 *
 * <p>Set environment variables:
 *
 * <ul>
 *   <li>{@code AUTOJIB_IMAGE} - image to containerize to
 *   <li>{@code AUTOJIB_BASE_IMAGE} - base image
 *   <li>{@code AUTOJIB_BUILD_TO_DOCKER} - {@code false} (default); use {@code true} to build to
 *       Docker daemon
 *   <li>{@code AUTOJIB_REGISTRY_USERNAME} (optional) - registry username
 *   <li>{@code AUTOJIB_REGISTRY_PASSWORD} (optional) - registry password
 * </ul>
 */
public class Main {

  private static class Configuration {

    private static Configuration processEnvironment() throws InvalidImageReferenceException {
      String imageReferenceString = System.getenv("AUTOJIB_IMAGE");
      if (imageReferenceString == null) {
        throw new IllegalArgumentException("must set AUTOJIB_IMAGE env var");
      }
      ImageReference imageReference = ImageReference.parse(imageReferenceString);

      String baseImageReferenceString = System.getenv("AUTOJIB_BASE_IMAGE");
      ImageReference baseImageReference = ImageReference.parse("gcr.io/distroless/java");
      if (baseImageReferenceString != null) {
        baseImageReference = ImageReference.parse(baseImageReferenceString);
      }

      String buildToDockerString = System.getenv("AUTOJIB_BUILD_TO_DOCKER");
      boolean buildToDocker = false;
      if (buildToDockerString != null) {
        buildToDocker = Boolean.parseBoolean(buildToDockerString);
      }

      String registryUsername = System.getenv("AUTOJIB_REGISTRY_USERNAME");
      String registryPassword = System.getenv("AUTOJIB_REGISTRY_PASSWORD");

      return new Configuration(
          imageReference, baseImageReference, buildToDocker, registryUsername, registryPassword);
    }

    private final ImageReference targetImageReference;
    private final ImageReference baseImageReference;
    private final boolean buildToDocker;
    @Nullable private final String registryUsername;
    @Nullable private final String registryPassword;

    private Configuration(
        ImageReference targetImageReference,
        ImageReference baseImageReference,
        boolean buildToDocker,
        @Nullable String registryUsername,
        @Nullable String registryPassword) {
      this.targetImageReference = targetImageReference;
      this.baseImageReference = baseImageReference;
      this.buildToDocker = buildToDocker;
      this.registryUsername = registryUsername;
      this.registryPassword = registryPassword;
    }
  }

  /**
   * Arguments passed in will be used as arguments to the entrypoint of the built container image.
   *
   * @param args args to set on the container image entrypoint
   */
  public static void main(String[] args)
      throws IOException, InvalidImageReferenceException, InterruptedException, ExecutionException,
          CacheDirectoryCreationException {
    Configuration configuration = Configuration.processEnvironment();

    // Pass LogEvents to stderr.
    EventHandlers eventHandlers =
        new EventHandlers()
            .add(
                JibEventType.LOGGING,
                logEvent ->
                    System.err.println(
                        "[" + logEvent.getLevel().name() + "] " + logEvent.getMessage()));
    EventDispatcher eventDispatcher = new DefaultEventDispatcher(eventHandlers);

    // Gets all the files to package.
    ImmutableList<Path> classpathFiles = ClasspathResolver.getClasspathFiles();

    // Finds the main class.
    String mainClass = findMainClass(classpathFiles, eventDispatcher);

    // Creates an thread pool to run on.
    ExecutorService executorService = Executors.newCachedThreadPool();

    // Builds the container image.
    List<String> entrypoint = new ArrayList<>();
    entrypoint.addAll(Arrays.asList("java", "-cp", "/app/:/app/*", mainClass));
    entrypoint.addAll(Arrays.asList(args));

    Containerizer containerizer;
    if (configuration.buildToDocker) {
      containerizer = Containerizer.to(DockerDaemonImage.named(configuration.targetImageReference));

    } else {
      RegistryImage registryImage = RegistryImage.named(configuration.targetImageReference);
      if (configuration.registryUsername != null) {
        registryImage.addCredential(configuration.registryUsername, configuration.registryPassword);
      }
      CredentialRetrieverFactory credentialRetrieverFactory =
          CredentialRetrieverFactory.forImage(configuration.targetImageReference, eventDispatcher);
      registryImage
          .addCredentialRetriever(credentialRetrieverFactory.dockerConfig())
          .addCredentialRetriever(credentialRetrieverFactory.inferCredentialHelper());

      containerizer = Containerizer.to(registryImage);
    }

    DescriptorDigest descriptorDigest =
        Jib.from(configuration.baseImageReference)
            .addLayer(classpathFiles, AbsoluteUnixPath.get("/app"))
            .setEntrypoint(entrypoint)
            .containerize(
                containerizer
                    .setApplicationLayersCache(Containerizer.DEFAULT_BASE_CACHE_DIRECTORY)
                    .setExecutorService(executorService)
                    .setEventHandlers(eventHandlers))
            .getDigest();

    // Shuts down the thread pool.
    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(100, TimeUnit.MICROSECONDS)) {
        executorService.shutdownNow();
      }
    } catch (InterruptedException ex) {
      executorService.shutdownNow();
      Thread.currentThread().interrupt();
    }

    // Outputs the built digest.
    System.err.println("Containerized to:");
    System.out.println(configuration.targetImageReference + "@" + descriptorDigest);
  }

  private static String findMainClass(
      ImmutableList<Path> classpathFiles, EventDispatcher eventDispatcher) {
    // Walks any directories in the classpath.
    ImmutableList<Path> flatFiles =
        classpathFiles
            .stream()
            .flatMap(
                classpathFile -> {
                  if (Files.isDirectory(classpathFile)) {
                    try {
                      return new DirectoryWalker(classpathFile)
                          .filter(path -> !Files.isDirectory(path))
                          .walk(path -> {})
                          .stream();

                    } catch (IOException ex) {
                      throw new RuntimeException(ex);
                    }
                  }

                  return Stream.of(classpathFile);
                })
            .collect(ImmutableList.toImmutableList());

    // Finds the main class in all the classpath files.
    MainClassFinder mainClassFinder = new MainClassFinder(flatFiles, eventDispatcher);
    MainClassFinder.Result mainClassFinderResult = mainClassFinder.find();
    switch (mainClassFinderResult.getType()) {
      case MAIN_CLASS_FOUND:
        return mainClassFinderResult.getFoundMainClass();

      case MAIN_CLASS_NOT_FOUND:
        throw new RuntimeException("main class not found");

      case MULTIPLE_MAIN_CLASSES:
        throw new RuntimeException(
            "multiple main classes found: "
                + String.join(", ", mainClassFinderResult.getFoundMainClasses()));

      default:
        throw new IllegalStateException("unreachable");
    }
  }
}
