package com.datasqrl.cmd;

import static com.datasqrl.config.ConnectorFactoryFactory.PRINT_SINK_NAME;
import static com.datasqrl.packager.Packager.*;
import static com.datasqrl.util.NameUtil.namepath2Path;

import com.datasqrl.canonicalizer.Name;
import com.datasqrl.canonicalizer.NamePath;
import com.datasqrl.config.PackageJson.DependenciesConfig;
import com.datasqrl.config.Dependency;
import com.datasqrl.config.PackageJson;
import com.datasqrl.config.PackageJson.ScriptConfig;
import com.datasqrl.config.SqrlConfigCommons;
import com.datasqrl.error.ErrorCollector;
import com.datasqrl.error.ErrorPrefix;
import com.datasqrl.loaders.ModuleLoaderImpl;
import com.datasqrl.loaders.StandardLibraryLoader;
import com.datasqrl.packager.ImportExportAnalyzer;
import com.datasqrl.packager.ImportExportAnalyzer.Result;
import com.datasqrl.packager.Packager;
import com.datasqrl.packager.repository.Repository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

import lombok.AllArgsConstructor;
import lombok.SneakyThrows;

@AllArgsConstructor
public class PackageBootstrap {
  Path rootDir;
  List<Path> packageFiles;
  String[] profiles;
  Path[] files;
  boolean inferDependencies;
  

  @SneakyThrows
  public PackageJson bootstrap(Repository repository, ErrorCollector errors,
      Function<ErrorCollector, PackageJson> defaultConfigFnc,
      Function<PackageJson, PackageJson> postProcess, Path targetDir) {
    errors = errors.withLocation(ErrorPrefix.CONFIG).resolve("package");

    //Create build dir to unpack resolved dependencies
    Path buildDir = rootDir.resolve(Packager.BUILD_DIR_NAME);
    Packager.cleanBuildDir(buildDir);
    Packager.createBuildDir(buildDir);

    Optional<List<Path>> existingPackage = Packager.findPackageFile(rootDir, packageFiles);
    Optional<PackageJson> existingConfig;
    if (existingPackage.isPresent()) {
      existingConfig = Optional.of(SqrlConfigCommons.fromFilesPackageJson(errors, existingPackage.get()));
    } else {
      existingConfig = Optional.empty();
    }

    Map<String, Dependency> dependencies = new HashMap<>();
    // Check if 'profiles' key is set, merge result with switches
    String[] profiles;
    if (existingConfig.isPresent() && existingConfig.get().hasProfileKey()) {
      List<String> configProfiles = existingConfig.get().getProfiles();
      Set<String> profileSet = new LinkedHashSet<>();
      profileSet.addAll(configProfiles);
      profileSet.addAll(Arrays.asList(this.profiles));
      profiles = profileSet.toArray(String[]::new);
    } else {
      profiles = this.profiles;
    }

    //Create package.json from project root if exists
    List<Path> configFiles = new ArrayList<>(existingPackage.orElseGet(ArrayList::new));

    //Download any profiles
    for (String profile : profiles) {
      Path localProfile = rootDir.resolve(profile).resolve(PACKAGE_JSON);

      if (Files.isRegularFile(localProfile)) {
        configFiles.add(localProfile);
      } else { // repo profile
        //check to see if it's already in the package json, download the correct dep
        Optional<Dependency> dependency;
        if (hasVersionedProfileDependency(existingConfig, profile)) {
          dependency = existingConfig.get().getDependencies()
              .getDependency(profile);
        } else {
          dependency = repository.resolveDependency(profile);
        }

        if (dependency.isPresent()) {
          boolean success = repository.retrieveDependency(rootDir.resolve("build"),
              dependency.get());
          if (success) {
            dependencies.put(profile, dependency.get());
          } else {
            throw new RuntimeException("Could not retrieve profile dependency: " + profile);
          }
        }

        Path remoteProfile = rootDir.resolve(Packager.BUILD_DIR_NAME).resolve(profile).resolve(PACKAGE_JSON);
        if (Files.isRegularFile(remoteProfile)) {
          configFiles.add(remoteProfile);
        }
      }
    }

    if (packageFiles.isEmpty() && configFiles.isEmpty()) { //No profiles found, use default
      PackageJson defaultConfig = defaultConfigFnc.apply(errors);
      Path path = buildDir.resolve(PACKAGE_JSON);
      defaultConfig.toFile(path, true);
      configFiles.add(path);
    }

    // Could not find any package json
    if (configFiles.isEmpty()) {
      throw new RuntimeException("Could not find package.json");
    }

    // Merge all configurations
    PackageJson packageJson = SqrlConfigCommons.fromFilesPackageJson(errors, configFiles);
    packageJson.setProfiles(profiles);

    //Add dependencies of discovered profiles
    dependencies.forEach((key, dep) -> {
      DependenciesConfig dependenciesConfig = packageJson.getDependencies();
      dependenciesConfig.addDependency(key, dep);
    });

    //Override main and graphql if they are specified as command line arguments
    Optional<Path> mainScript = (files.length > 0 && files[0].getFileName().toString().toLowerCase().endsWith(".sqrl")) ? Optional.of(files[0]) : Optional.empty();
    Optional<Path> graphQLSchemaFile = (files.length > 1) ? Optional.of(files[1]) : Optional.empty();

    ScriptConfig scriptConfig = packageJson.getScriptConfig();
    boolean isMainScriptSet = scriptConfig.getMainScript().isPresent();
    boolean isGraphQLSet = scriptConfig.getGraphql().isPresent();

    // Set main script if not already set and if it's a regular file
    if (mainScript.isPresent() && Files.isRegularFile(relativize(mainScript))) {
      scriptConfig.setMainScript(mainScript.get().toString());
    } else if (!isMainScriptSet && mainScript.isPresent()) {
      errors.fatal("Main script is not a regular file: %s", mainScript.get());
    } else if (!isMainScriptSet && files.length > 0) {
      errors.fatal("Main script is not a sqrl script: %s", files[0].getFileName().toString());
    } else if (!isMainScriptSet && mainScript.isEmpty()){
      errors.fatal("No main sqrl script specified");
    }

    // Set GraphQL schema file if not already set and if it's a regular file
    if (graphQLSchemaFile.isPresent() && Files.isRegularFile(relativize(graphQLSchemaFile))) {
      scriptConfig.setGraphql(graphQLSchemaFile.get().toString());
    } else if (!isGraphQLSet && graphQLSchemaFile.isPresent()) {
      errors.fatal("GraphQL schema file is not a regular file: %s", graphQLSchemaFile.get());
    }

    if (inferDependencies) {
      inferDependencies(repository, packageJson, errors);
    }

    return postProcess.apply(packageJson);
  }

  private Path relativize(Optional<Path> path) {
    return path.get().isAbsolute() ? path.get() : rootDir.resolve(path.get());
  }

  private boolean hasVersionedProfileDependency(Optional<PackageJson> existingConfig, String profile) {
    return existingConfig.isPresent()
        && existingConfig.get().getDependencies().getDependency(profile).isPresent()
        && existingConfig.get().getDependencies().getDependency(profile).get().getVersion().isPresent();
  }

  private void inferDependencies(Repository repository, PackageJson config, ErrorCollector errors) throws IOException {
    //Analyze all local SQRL files to discovery transitive or undeclared dependencies
    //At the end, we'll add the new dependencies to the package config.
    ImportExportAnalyzer analyzer = new ImportExportAnalyzer();

    BiPredicate<Path, BasicFileAttributes> FIND_SQRL_SCRIPT = (p, f) ->
        f.isRegularFile() && p.getFileName().toString().toLowerCase().endsWith(".sqrl");

    // Find all SQRL script files
    Result allResults = Files.find(rootDir, 128, FIND_SQRL_SCRIPT)
        .map(script -> analyzer.analyze(script, errors))
        .reduce(Result.EMPTY, Result::add);

    StandardLibraryLoader standardLibraryLoader = new StandardLibraryLoader();
    Set<NamePath> pkgs = new HashSet<>(allResults.getPkgs());
    pkgs.removeAll(standardLibraryLoader.loadedLibraries());
    pkgs.remove(Name.system(PRINT_SINK_NAME).toNamePath());

    Set<NamePath> unloadedDeps = new HashSet<>();
    for (NamePath packagePath : pkgs) {
      Path dir = namepath2Path(rootDir, packagePath);
      if (!Files.exists(dir)) {
        unloadedDeps.add(packagePath);
      }
    }

    LinkedHashMap<String, Dependency> inferredDependencies = new LinkedHashMap<>();

    //Resolve dependencies
    for (NamePath unloadedDep : unloadedDeps) {
      repository
          .resolveDependency(unloadedDep.toString())
          .ifPresentOrElse((dep) -> inferredDependencies.put(unloadedDep.toString(), dep),
              () -> errors.checkFatal(true, "Could not infer dependency: %s", unloadedDep));
    }

    // Add inferred dependencies to package config
    inferredDependencies.forEach((key, dep) -> {
      config.getDependencies().addDependency(key, dep);
    });
  }
}
