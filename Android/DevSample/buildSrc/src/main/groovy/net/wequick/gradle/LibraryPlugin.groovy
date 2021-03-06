package net.wequick.gradle

import com.android.build.api.transform.Format
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.pipeline.IntermediateFolderUtils
import com.android.build.gradle.internal.pipeline.TransformTask
import com.android.build.gradle.internal.transforms.ProGuardTransform
import org.apache.commons.io.FileUtils
import org.gradle.api.Project

class LibraryPlugin extends AppPlugin {

    private File mBakBuildFile

    void apply(Project project) {
        super.apply(project)
        mBakBuildFile = new File(project.buildFile.parentFile, "${project.buildFile.name}~")
    }

    @Override
    protected PluginType getPluginType() {
        return PluginType.Library
    }

    @Override
    protected String getSmallCompileType() {
        return 'compile'
    }

    @Override
    protected void beforeEvaluate(boolean released) {
        super.beforeEvaluate(released)
        if (!released) return

        // Change android plugin from `lib' to `application' dynamically
        // FIXME: Any better way without edit file?

        if (mBakBuildFile.exists()) {
            // With `tidyUp', should not reach here
            throw new Exception("Conflict buildFile, please delete file $mBakBuildFile or " +
                    "${project.buildFile}")
        }

        def text = project.buildFile.text.replaceAll(
                'com\\.android\\.library', 'com.android.application')
        project.buildFile.renameTo(mBakBuildFile)
        project.buildFile.write(text)
    }

    @Override
    protected void afterEvaluate(boolean released) {
        super.afterEvaluate(released)

        if (released) {
            // Set application id
            def manifest = new XmlParser().parse(android.sourceSets.main.manifestFile)
            android.defaultConfig.applicationId = manifest.@package
            mDependentLibProjects.each {
                project.preBuild.dependsOn "${it.path}:buildLib"
            }
        } else {
            // Cause `isBuildingRelease()' return false, at this time, super's
            // `hookJavacTask' will not be triggered. Provided the necessary jars here.
            def smallJar = project.fileTree(
                    dir: rootSmall.preBaseJarDir, include: [SMALL_JAR_PATTERN])
            def libJars = project.fileTree(dir: rootSmall.preLibsJarDir,
                    include: mDependentLibProjects.collect { "$it.name-${it.version}.jar" })
            project.dependencies.add('provided', smallJar)
            project.dependencies.add('provided', libJars)

            // Dependently built by `buildBundle' or `:app.xx:assembleRelease'.
            // To avoid transformNative_libsWithSyncJniLibsForRelease task error, skip it.
            // FIXME: we'd better figure out why the task failed and fix it
            def mT = rootSmall.mT
            def isSyncByIDE = (mT != null && mT.startsWith(":$rootSmall.hostModuleName:generate"))
            def isBuildingAppBundle = rootSmall.isBuildingApps()
            def skipsSyncJniLibs = isSyncByIDE || isBuildingAppBundle
            def skipsSyncLibJars = isBuildingAppBundle
            if (skipsSyncJniLibs) {
                project.preBuild.doLast {
                    def syncJniTaskName = 'transformNative_libsWithSyncJniLibsForRelease'
                    if (project.hasProperty(syncJniTaskName)) {
                        def syncJniTask = project.tasks[syncJniTaskName]
                        syncJniTask.onlyIf { false }
                    }
                }
            }
            if (skipsSyncLibJars) {

                project.preBuild.doLast {
                    def syncLibTaskName = 'transformClassesAndResourcesWithSyncLibJarsForRelease'
                    if (project.hasProperty(syncLibTaskName)) {
                        def syncLibTask = project.tasks[syncLibTaskName]
                        syncLibTask.onlyIf { false }
                    }
                }
            }
        }
    }

    @Override
    protected void createTask() {
        super.createTask()

        project.task('cleanLib', dependsOn: 'clean')
        project.task('buildLib', dependsOn: 'assembleRelease')

        project.tasks.remove(project.cleanBundle)
        project.tasks.remove(project.buildBundle)
    }

    @Override
    protected void configureProguard(BaseVariant variant, TransformTask proguard, ProGuardTransform pt) {
        super.configureProguard(variant, proguard, pt)

        // The `lib.*' modules are referenced by any `app.*' modules,
        // so keep all the public methods for them.
        pt.keep("class ${variant.applicationId}.** { public *; }")
    }

    @Override
    protected void configureReleaseVariant(BaseVariant variant) {
        super.configureReleaseVariant(variant)

        small.jar = project.jarReleaseClasses

        variant.assemble.doLast {
            // Generate jar file to root pre-jar directory
            // FIXME: Create a task for this
            def jarName = getJarName(project)
            def jarFile = new File(rootSmall.preLibsJarDir, jarName)
            if (mMinifyJar != null) {
                FileUtils.copyFile(mMinifyJar, jarFile)
            } else {
                project.ant.jar(baseDir: small.javac.destinationDir, destFile: jarFile)
            }

            // Backup R.txt to public.txt
            // FIXME: Create a task for this
            if (!small.symbolFile.exists())  return

            def publicIdsPw = new PrintWriter(small.publicSymbolFile.newWriter(false))
            small.symbolFile.eachLine { s ->
                if (!s.contains("styleable")) {
                    publicIdsPw.println(s)
                }
            }
            publicIdsPw.flush()
            publicIdsPw.close()
        }
    }

    @Override
    protected void tidyUp() {
        super.tidyUp()
        // Restore library module's android plugin to `com.android.library'
        if (mBakBuildFile != null && mBakBuildFile.exists()) {
            project.buildFile.delete()
            mBakBuildFile.renameTo(project.buildFile)
        }
    }
}
