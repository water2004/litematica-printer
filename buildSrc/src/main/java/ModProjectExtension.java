import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class ModProjectExtension {
    private final Project project;

    public ModProjectExtension(Project project) {
        this.project = project;
    }

    public Object propOrNull(String key) {
        return project.findProperty(key);
    }

    public Object prop(String key) {
        Object value = propOrNull(key);
        if (value == null) {
            throw new GradleException("buildSrc: 属性 " + key + " 未配置/值为空");
        }
        return value;
    }

    public String propStrOrNull(String key) {
        Object value = propOrNull(key);
        return value == null ? null : value.toString();
    }

    public String propStr(String key) {
        String value = propStrOrNull(key);
        if (value == null) {
            throw new GradleException("buildSrc: 属性 " + key + " 未配置/值为空，或无法转换为字符串");
        }
        return value;
    }

    public File downloadDependencyMod(String downloadUrl) {
        return downloadDependencyMod(downloadUrl, null);
    }

    public File downloadDependencyMod(String downloadUrl, String fileName) {
        return ExternalModDownloader.download(
                project,
                downloadUrl,
                new File(project.getRootProject().getProjectDir(), "libs"),
                fileName
        );
    }

    public String getModId() {
        return propStr("mod_id");
    }

    public String getWrapperModId() {
        return getModId() + "-wrapper";
    }

    public String getModName() {
        return propStr("mod_name");
    }

    public String getModVersion() {
        return propStr("mod_version");
    }

    public String getModMavenGroup() {
        return propStr("mod_maven_group");
    }

    public String getModArchivesBaseName() {
        return propStr("mod_archives_base_name");
    }

    public String getModDescription() {
        return propStrOrNull("mod_description");
    }

    public String getModHomepage() {
        return propStrOrNull("mod_homepage");
    }

    public String getModLicense() {
        return propStrOrNull("mod_license");
    }

    public String getModSources() {
        return propStrOrNull("mod_sources");
    }

    public String getMcDependency() {
        return propStrOrNull("minecraft_dependency");
    }

    public String getMcVersion() {
        return propStrOrNull("minecraft_version");
    }

    public int getMcVersionInt() {
        String value = propStrOrNull("mcVersion");
        if (value == null) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public String getFabricLoaderVersion() {
        return propStrOrNull("loader_version");
    }

    public String getFabricApiVersion() {
        return propStrOrNull("fabric_version");
    }

    public String getMalilib() {
        return propStrOrNull("malilib_dependency");
    }

    public String getLitematica() {
        return propStrOrNull("litematica_dependency");
    }

    public String getLombokVersion() {
        return propStr("lombok_version");
    }

    public JavaVersion getJavaVersion() {
        int version = getMcVersionInt();
        if (version >= 260000) return JavaVersion.VERSION_25;
        if (version >= 12005) return JavaVersion.VERSION_21;
        if (version >= 11800) return JavaVersion.VERSION_17;
        if (version >= 11700) return JavaVersion.VERSION_16;
        return JavaVersion.VERSION_1_8;
    }

    public String getMixinJavaVersion() {
        return "JAVA_" + getJavaVersion();
    }

    public String getFullProjectVersion() {
        return createFullProjectVersion(getModVersion());
    }

    public Map<String, Object> getPlaceholderProps() {
        Map<String, Object> properties = new LinkedHashMap<>();
        putIfPresent(properties, "mod_id", getModId());
        putIfPresent(properties, "mod_wrapper_id", getWrapperModId());
        putIfPresent(properties, "mod_name", getModName());
        putIfPresent(properties, "mod_version", getFullProjectVersion());
        putIfPresent(properties, "mod_description", getModDescription());
        putIfPresent(properties, "mod_homepage", getModHomepage());
        putIfPresent(properties, "mod_license", getModLicense());
        putIfPresent(properties, "mod_sources", getModSources());
        putIfPresent(properties, "loader_version", getFabricLoaderVersion());
        putIfPresent(properties, "fabric_api_version", getFabricApiVersion());
        putIfPresent(properties, "minecraft_dependency", getMcDependency());
        putIfPresent(properties, "compatibility_level", getMixinJavaVersion());
        putIfPresent(properties, "malilib", getMalilib());
        putIfPresent(properties, "litematica", getLitematica());
        return properties;
    }

    private static void putIfPresent(Map<String, Object> properties, String key, Object value) {
        if (value != null) properties.put(key, value);
    }

    private String createFullProjectVersion(String modVersion) {
        Integer commitCount = getCommitCountNumber();
        String commitHash = System.getenv("COMMIT_HASH");
        boolean release = Boolean.parseBoolean(System.getenv("IS_THIS_RELEASE"));
        boolean pullRequest = Boolean.parseBoolean(System.getenv("PR_BUILD"));
        boolean ci = "true".equals(System.getenv("CI"))
                || "true".equals(System.getenv("GITHUB_ACTIONS"));

        if (release) return modVersion;
        if (pullRequest) return modVersion + "-" + commitCount + "-" + commitHash + "-pr";
        if (ci) return modVersion + "-" + commitCount + "-" + commitHash + "-ci";
        return modVersion + "-" + System.currentTimeMillis() + "-development";
    }

    private Integer getCommitCountNumber() {
        try {
            Process process = new ProcessBuilder("git", "rev-list", "--count", "HEAD")
                    .directory(project.getRootDir())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line);
            }
            int exitCode = process.waitFor();
            return exitCode == 0 ? Integer.parseInt(output.toString().trim()) : null;
        } catch (Exception exception) {
            project.getLogger().debug("Unable to determine Git commit count", exception);
            return null;
        }
    }
}
