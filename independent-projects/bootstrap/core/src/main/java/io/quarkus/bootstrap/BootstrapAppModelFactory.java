package io.quarkus.bootstrap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.jboss.logging.Logger;

import io.quarkus.bootstrap.app.CurationResult;
import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppArtifactCoords;
import io.quarkus.bootstrap.model.AppDependency;
import io.quarkus.bootstrap.model.AppModel;
import io.quarkus.bootstrap.resolver.AppModelResolver;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.resolver.BootstrapAppModelResolver;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalWorkspace;
import io.quarkus.bootstrap.resolver.maven.workspace.ModelUtils;
import io.quarkus.bootstrap.resolver.update.DefaultUpdateDiscovery;
import io.quarkus.bootstrap.resolver.update.DependenciesOrigin;
import io.quarkus.bootstrap.resolver.update.UpdateDiscovery;
import io.quarkus.bootstrap.resolver.update.VersionUpdate;
import io.quarkus.bootstrap.resolver.update.VersionUpdateNumber;
import io.quarkus.bootstrap.util.IoUtils;
import io.quarkus.bootstrap.util.ZipUtils;

/**
 * The factory that creates the application dependency model.
 *
 * This is used to build the application class loader.
 */
public class BootstrapAppModelFactory {

    private static final String QUARKUS = "quarkus";
    private static final String BOOTSTRAP = "bootstrap";
    private static final String DEPLOYMENT_CP = "deployment.cp";

    public static final String CREATOR_APP_GROUP_ID = "creator.app.groupId";
    public static final String CREATOR_APP_ARTIFACT_ID = "creator.app.artifactId";
    public static final String CREATOR_APP_CLASSIFIER = "creator.app.classifier";
    public static final String CREATOR_APP_TYPE = "creator.app.type";
    public static final String CREATOR_APP_VERSION = "creator.app.version";

    private static final int CP_CACHE_FORMAT_ID = 2;

    private static final Logger log = Logger.getLogger(BootstrapAppModelFactory.class);
    private AppArtifact managingProject;

    public static BootstrapAppModelFactory newInstance() {
        return new BootstrapAppModelFactory();
    }

    private Path appClasses;
    private List<Path> appCp = new ArrayList<>(0);
    private Boolean localProjectsDiscovery;
    private Boolean offline;
    private boolean enableClasspathCache;
    private boolean test;
    private boolean devMode;
    private AppModelResolver bootstrapAppModelResolver;

    private VersionUpdateNumber versionUpdateNumber;
    private VersionUpdate versionUpdate;
    private DependenciesOrigin dependenciesOrigin;
    private AppArtifact appArtifact;
    private MavenArtifactResolver mavenArtifactResolver;

    private LocalProject appClassesWorkspace;

    private BootstrapAppModelFactory() {
    }

    public BootstrapAppModelFactory setTest(boolean test) {
        this.test = test;
        return this;
    }

    public BootstrapAppModelFactory setDevMode(boolean devMode) {
        this.devMode = devMode;
        return this;
    }

    public BootstrapAppModelFactory setAppClasses(Path appClasses) {
        this.appClasses = appClasses;
        return this;
    }

    public BootstrapAppModelFactory addToClassPath(Path path) {
        this.appCp.add(path);
        return this;
    }

    public BootstrapAppModelFactory setLocalProjectsDiscovery(Boolean localProjectsDiscovery) {
        this.localProjectsDiscovery = localProjectsDiscovery;
        return this;
    }

    public BootstrapAppModelFactory setOffline(Boolean offline) {
        this.offline = offline;
        return this;
    }

    public BootstrapAppModelFactory setEnableClasspathCache(boolean enable) {
        this.enableClasspathCache = enable;
        return this;
    }

    public BootstrapAppModelFactory setBootstrapAppModelResolver(AppModelResolver bootstrapAppModelResolver) {
        this.bootstrapAppModelResolver = bootstrapAppModelResolver;
        return this;
    }

    public BootstrapAppModelFactory setVersionUpdateNumber(VersionUpdateNumber versionUpdateNumber) {
        this.versionUpdateNumber = versionUpdateNumber;
        return this;
    }

    public BootstrapAppModelFactory setVersionUpdate(VersionUpdate versionUpdate) {
        this.versionUpdate = versionUpdate;
        return this;
    }

    public BootstrapAppModelFactory setDependenciesOrigin(DependenciesOrigin dependenciesOrigin) {
        this.dependenciesOrigin = dependenciesOrigin;
        return this;
    }

    public BootstrapAppModelFactory setAppArtifact(AppArtifact appArtifact) {
        this.appArtifact = appArtifact;
        return this;
    }

    public AppModelResolver getAppModelResolver() {

        if (bootstrapAppModelResolver != null) {
            return bootstrapAppModelResolver;
        }
        if (appClasses == null) {
            throw new IllegalArgumentException("Application classes path has not been set");
        }

        try {
            if (!Files.isDirectory(appClasses)) {
                final MavenArtifactResolver mvn;
                if (mavenArtifactResolver == null) {
                    final MavenArtifactResolver.Builder mvnBuilder = MavenArtifactResolver.builder();
                    if (offline != null) {
                        mvnBuilder.setOffline(offline);
                    }
                    final LocalProject localProject = isWorkspaceDiscoveryEnabled()
                            ? LocalProject.loadWorkspace(Paths.get("").normalize().toAbsolutePath(), false)
                                    : null;
                    if (localProject != null) {
                        mvnBuilder.setWorkspace(localProject.getWorkspace());
                        if (managingProject == null) {
                            managingProject = localProject.getAppArtifact();
                        }
                    }
                    mvn = mvnBuilder.build();
                } else {
                    mvn = mavenArtifactResolver;
                }

                return bootstrapAppModelResolver = new BootstrapAppModelResolver(mvn)
                        .setTest(test)
                        .setDevMode(devMode);
            }

            MavenArtifactResolver mvn = mavenArtifactResolver;
            if (mvn == null) {
                final MavenArtifactResolver.Builder builder = MavenArtifactResolver.builder();
                final LocalProject localProject = isWorkspaceDiscoveryEnabled()
                        ? loadAppClassesWorkspace()
                                : null;
                if (localProject != null) {
                    builder.setWorkspace(localProject.getWorkspace());
                }
                if (offline != null) {
                    builder.setOffline(offline);
                }
                mvn = builder.build();
            }
            return bootstrapAppModelResolver = new BootstrapAppModelResolver(mvn)
                    .setTest(test)
                    .setDevMode(devMode);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create resolver for " + appClasses, e);
        }
    }

    public CurationResult resolveAppModel() throws BootstrapException {
        if (test || devMode) {
            //gradle tests and dev encode the result on the class path
            final String serializedModel = System.getProperty(BootstrapConstants.SERIALIZED_APP_MODEL);
            if (serializedModel != null) {
                final Path p = Paths.get(serializedModel);
                if (Files.exists(p)) {
                    try (InputStream existing = Files.newInputStream(Paths.get(serializedModel))) {
                        final AppModel appModel = (AppModel) new ObjectInputStream(existing).readObject();
                        return new CurationResult(appModel);
                    } catch (IOException | ClassNotFoundException e) {
                        log.error("Failed to load serialized app mode", e);
                    }
                    IoUtils.recursiveDelete(p);
                } else {
                    log.error("Failed to locate serialized application model at " + serializedModel);
                }
            }
        }
        if (appClasses == null) {
            throw new IllegalArgumentException("Application classes path has not been set");
        }

        if (!Files.isDirectory(appClasses)) {
            return createAppModelForJar(appClasses);
        }

        final LocalProject localProject = isWorkspaceDiscoveryEnabled() || enableClasspathCache
                ? loadAppClassesWorkspace()
                        : LocalProject.load(appClasses, false);
        LocalWorkspace workspace = null;
        AppArtifact appArtifact = this.appArtifact;
        if (localProject == null) {
            log.warn("Unable to locate maven project, falling back to classpath discovery");
            if(appArtifact == null) {
                throw new BootstrapException("Failed to determine the Maven artifact associated with the application");
            }
        } else {
            workspace = localProject.getWorkspace();
            if(appArtifact == null) {
                appArtifact = localProject.getAppArtifact();
            } else if(!appArtifact.equals(localProject.getAppArtifact())) {
                log.warn("Provided application artifact attributes " + appArtifact +
                        " do not match the actual project loaded from the disk " + localProject.getAppArtifact());
            }
        }

        try {
            Path cachedCpPath = null;
            if (workspace != null && enableClasspathCache) {
                cachedCpPath = resolveCachedCpPath(localProject);
                if (Files.exists(cachedCpPath)) {
                    try (DataInputStream reader = new DataInputStream(Files.newInputStream(cachedCpPath))) {
                        if (reader.readInt() == CP_CACHE_FORMAT_ID) {
                            if (reader.readInt() == workspace.getId()) {
                                ObjectInputStream in = new ObjectInputStream(reader);
                                return new CurationResult((AppModel) in.readObject());
                            } else {
                                debug("Cached deployment classpath has expired for %s", appArtifact);
                            }
                        } else {
                            debug("Unsupported classpath cache format in %s for %s", cachedCpPath,
                                    appArtifact);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to read deployment classpath cache from " + cachedCpPath + " for "
                                + appArtifact, e);
                    }
                }
            }
            AppModelResolver appModelResolver = getAppModelResolver();
            CurationResult curationResult = new CurationResult(appModelResolver
                    .resolveManagedModel(appArtifact, Collections.emptyList(), managingProject));
            if (cachedCpPath != null) {
                Files.createDirectories(cachedCpPath.getParent());
                try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(cachedCpPath))) {
                    out.writeInt(CP_CACHE_FORMAT_ID);
                    out.writeInt(workspace.getId());
                    ObjectOutputStream obj = new ObjectOutputStream(out);
                    obj.writeObject(curationResult.getAppModel());
                } catch (Exception e) {
                    log.warn("Failed to write classpath cache", e);
                }
            }
            return curationResult;
        } catch (Exception e) {
            throw new BootstrapException("Failed to create the application model for " + appArtifact, e);
        }
    }

    private boolean isWorkspaceDiscoveryEnabled() {
        return localProjectsDiscovery == null ? test || devMode : localProjectsDiscovery;
    }

    private LocalProject loadAppClassesWorkspace() throws BootstrapException {
        return appClassesWorkspace == null
                ? appClassesWorkspace = LocalProject.loadWorkspace(appClasses, false)
                : appClassesWorkspace;
    }

    private CurationResult createAppModelForJar(Path appArtifactPath) {
        log.debugf("provideOutcome depsOrigin=%s, versionUpdate=%s, versionUpdateNumber=%s", dependenciesOrigin, versionUpdate,
                versionUpdateNumber);

        AppArtifact stateArtifact = null;
        boolean loadedFromState = false;
        AppModelResolver modelResolver = getAppModelResolver();
        final AppModel initialDepsList;
        AppArtifact appArtifact = this.appArtifact;
        try {
            if (appArtifact == null) {
                appArtifact = ModelUtils.resolveAppArtifact(appArtifactPath);
            }
            modelResolver.relink(appArtifact, appArtifactPath);

            if (dependenciesOrigin == DependenciesOrigin.LAST_UPDATE) {
                log.info("Looking for the state of the last update");
                Path statePath = null;
                try {
                    stateArtifact = ModelUtils.getStateArtifact(appArtifact);
                    final String latest = modelResolver.getLatestVersion(stateArtifact, null, false);
                    if (!stateArtifact.getVersion().equals(latest)) {
                        stateArtifact = new AppArtifact(stateArtifact.getGroupId(), stateArtifact.getArtifactId(),
                                stateArtifact.getClassifier(), stateArtifact.getType(), latest);
                    }
                    statePath = modelResolver.resolve(stateArtifact);
                    log.info("- located the state at " + statePath);
                } catch (AppModelResolverException e) {
                    // for now let's assume this means artifact does not exist
                    // System.out.println(" no state found");
                }

                if (statePath != null) {
                    Model model;
                    try {
                        model = ModelUtils.readModel(statePath);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read application state " + statePath, e);
                    }
                    final List<Dependency> modelStateDeps = model.getDependencies();
                    final List<AppDependency> updatedDeps = new ArrayList<>(modelStateDeps.size());
                    final String groupIdProp = "${" + CREATOR_APP_GROUP_ID + "}";
                    for (Dependency modelDep : modelStateDeps) {
                        if (modelDep.getGroupId().equals(groupIdProp)) {
                            continue;
                        }
                        updatedDeps.add(new AppDependency(new AppArtifact(modelDep.getGroupId(), modelDep.getArtifactId(),
                                modelDep.getClassifier(), modelDep.getType(), modelDep.getVersion()), modelDep.getScope(),
                                modelDep.isOptional()));
                    }
                    initialDepsList = modelResolver.resolveModel(appArtifact, updatedDeps);
                    loadedFromState = true;
                } else {
                    initialDepsList = modelResolver.resolveModel(appArtifact);
                }
            } else {
                initialDepsList = modelResolver.resolveManagedModel(appArtifact, Collections.emptyList(), managingProject);
            }
        } catch (AppModelResolverException | IOException e) {
            throw new RuntimeException("Failed to resolve initial application dependencies", e);
        }

        if (versionUpdate == VersionUpdate.NONE) {
            return new CurationResult(initialDepsList, Collections.emptyList(),
                    loadedFromState, appArtifact,
                    stateArtifact);
        }

        log.info("Checking for available updates");
        List<AppDependency> appDeps;
        try {
            appDeps = modelResolver.resolveUserDependencies(appArtifact, initialDepsList.getUserDependencies());
        } catch (AppModelResolverException e) {
            throw new RuntimeException("Failed to determine the list of dependencies to update", e);
        }
        final Iterator<AppDependency> depsI = appDeps.iterator();
        while (depsI.hasNext()) {
            final AppArtifact appDep = depsI.next().getArtifact();
            if (!appDep.getType().equals(AppArtifactCoords.TYPE_JAR)) {
                depsI.remove();
                continue;
            }
            final Path path = appDep.getPath();
            if (Files.isDirectory(path)) {
                if (!Files.exists(path.resolve(BootstrapConstants.DESCRIPTOR_PATH))) {
                    depsI.remove();
                }
            } else {
                try (FileSystem artifactFs = ZipUtils.newFileSystem(path)) {
                    if (!Files.exists(artifactFs.getPath(BootstrapConstants.DESCRIPTOR_PATH))) {
                        depsI.remove();
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to open " + path, e);
                }
            }
        }

        final UpdateDiscovery ud = new DefaultUpdateDiscovery(modelResolver, versionUpdateNumber);
        List<AppDependency> availableUpdates = null;
        int i = 0;
        while (i < appDeps.size()) {
            final AppDependency dep = appDeps.get(i++);
            final AppArtifact depArtifact = dep.getArtifact();
            final String updatedVersion = versionUpdate == VersionUpdate.NEXT ? ud.getNextVersion(depArtifact)
                    : ud.getLatestVersion(depArtifact);
            if (updatedVersion == null || depArtifact.getVersion().equals(updatedVersion)) {
                continue;
            }
            log.info(dep.getArtifact() + " -> " + updatedVersion);
            if (availableUpdates == null) {
                availableUpdates = new ArrayList<>();
            }
            availableUpdates.add(new AppDependency(new AppArtifact(depArtifact.getGroupId(), depArtifact.getArtifactId(),
                    depArtifact.getClassifier(), depArtifact.getType(), updatedVersion), dep.getScope()));
        }

        if (availableUpdates != null) {
            try {
                return new CurationResult(modelResolver.resolveManagedModel(appArtifact, availableUpdates, managingProject),
                        availableUpdates,
                        loadedFromState, appArtifact, stateArtifact);
            } catch (AppModelResolverException e) {
                throw new RuntimeException(e);
            }
        } else {
            log.info("- no updates available");
            return new CurationResult(initialDepsList, Collections.emptyList(),
                    loadedFromState, appArtifact,
                    stateArtifact);
        }
    }

    private static Path resolveCachedCpPath(LocalProject project) {
        return project.getOutputDir().resolve(QUARKUS).resolve(BOOTSTRAP).resolve(DEPLOYMENT_CP);
    }

    private static void debug(String msg, Object... args) {
        if (log.isDebugEnabled()) {
            log.debug(String.format(msg, args));
        }
    }

    public BootstrapAppModelFactory setMavenArtifactResolver(MavenArtifactResolver mavenArtifactResolver) {
        this.mavenArtifactResolver = mavenArtifactResolver;
        return this;
    }

    public BootstrapAppModelFactory setManagingProject(AppArtifact managingProject) {
        this.managingProject = managingProject;
        return this;
    }
}
