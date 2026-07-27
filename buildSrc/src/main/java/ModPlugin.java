import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.language.jvm.tasks.ProcessResources;

import java.util.List;
import java.util.Map;

public final class ModPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java");
        ModProjectExtension config = project.getExtensions()
                .create("modConfig", ModProjectExtension.class, project);

        configureJava(project, config);
        configureLombok(project, config);
        configureJavaCompile(project, config);
        configureResources(project, config);
        configureJar(project, config);
    }

    private static void configureJava(Project project, ModProjectExtension config) {
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.setSourceCompatibility(config.getJavaVersion());
        java.setTargetCompatibility(config.getJavaVersion());
    }

    private static void configureLombok(Project project, ModProjectExtension config) {
        project.getPluginManager().withPlugin("java", ignored -> {
            String dependency = "org.projectlombok:lombok:" + config.getLombokVersion();
            project.getDependencies().add(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, dependency);
            project.getDependencies().add(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME, dependency);
        });
    }

    private static void configureJavaCompile(Project project, ModProjectExtension config) {
        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().setEncoding("UTF-8");
            task.getOptions().getCompilerArgs().addAll(List.of(
                    "-Xlint:deprecation",
                    "-Xlint:unchecked"
            ));
            if (config.getJavaVersion().compareTo(JavaVersion.VERSION_1_8) <= 0) {
                task.getOptions().getCompilerArgs().add("-Xlint:-options");
            }
        });
    }

    private static void configureResources(Project project, ModProjectExtension config) {
        project.getTasks().withType(ProcessResources.class).configureEach(task -> {
            Map<String, Object> properties = config.getPlaceholderProps();
            task.getInputs().properties(properties);
            task.filesMatching(
                    List.of("*.mixins.json", "*.mod.json", "META-INF/*mods.toml"),
                    details -> details.expand(properties)
            );
        });
    }

    private static void configureJar(Project project, ModProjectExtension config) {
        project.getTasks().withType(Jar.class).configureEach(task -> {
            task.from(project.getRootProject().file("LICENSE.md"), spec ->
                    spec.rename(name -> "LICENSE_" + config.getModArchivesBaseName() + ".md"));
            task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
            task.getManifest().attributes(Map.of(
                    "Implementation-Title", project.getName(),
                    "Implementation-Version", project.getVersion()
            ));
        });
    }
}
