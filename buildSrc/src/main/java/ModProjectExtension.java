import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public class ModProjectExtension {
    private final Project project;

    public ModProjectExtension(Project project) {
        this.project = project;
    }

    private Object propOrNull(String key) {
        return project.findProperty(key);
    }

    public Object prop(String key) {
        Object value = propOrNull(key);
        if (value == null) {
            throw new GradleException("buildSrc: 属性 " + key + " 未配置/值为空");
        }
        return value;
    }

    private String propStrOrNull(String key) {
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
        return ExternalModDownloader.download(
                project,
                downloadUrl,
                new File(project.getRootProject().getProjectDir(), "libs")
        );
    }

    public String getModId() {
        return propStr("mod_id");
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
        return JavaVersion.toVersion(propStr("java_version"));
    }

    public String getMixinJavaVersion() {
        return "JAVA_" + getJavaVersion();
    }

    public String getFullProjectVersion() {
        return getModVersion();
    }

    public Map<String, Object> getPlaceholderProps() {
        Map<String, Object> properties = new LinkedHashMap<>();
        putIfPresent(properties, "mod_id", getModId());
        putIfPresent(properties, "mod_name", getModName());
        putIfPresent(properties, "mod_version", getFullProjectVersion());
        putIfPresent(properties, "mod_description", getModDescription());
        putIfPresent(properties, "mod_homepage", getModHomepage());
        putIfPresent(properties, "mod_license", getModLicense());
        putIfPresent(properties, "mod_sources", getModSources());
        putIfPresent(properties, "minecraft_dependency", getMcDependency());
        putIfPresent(properties, "compatibility_level", getMixinJavaVersion());
        putIfPresent(properties, "malilib", getMalilib());
        putIfPresent(properties, "litematica", getLitematica());
        return properties;
    }

    private static void putIfPresent(Map<String, Object> properties, String key, Object value) {
        if (value != null) properties.put(key, value);
    }

}
