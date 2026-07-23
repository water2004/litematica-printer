import org.gradle.api.Project;
import org.gradle.api.logging.LogLevel;
import org.gradle.util.GradleVersion;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class ExternalModDownloader {
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 30_000;
    private static final String USER_AGENT = "Gradle/" + GradleVersion.current().getVersion();

    private ExternalModDownloader() {}

    public static File download(Project project, String downloadUrl, File outputDir, String fileName) {
        String trimmedUrl = downloadUrl.trim();
        if (trimmedUrl.isBlank()) throw new IllegalArgumentException("下载链接不能为空！");
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new IllegalArgumentException("无法创建输出目录：" + outputDir.getAbsolutePath());
        }

        try {
            String targetFileName = fileName != null ? fileName : extractFileNameFromUrl(trimmedUrl);
            if (targetFileName == null) {
                throw new IOException("无法识别文件名，请手动指定 fileName 参数");
            }

            File targetFile = new File(outputDir, targetFileName);
            if (targetFile.exists() && targetFile.length() > 0) {
                project.getLogger().log(LogLevel.LIFECYCLE,
                        "文件已存在，跳过下载：" + targetFile.getAbsolutePath());
                return targetFile;
            }

            project.getLogger().log(LogLevel.LIFECYCLE, "开始下载：" + trimmedUrl);
            project.getLogger().log(LogLevel.LIFECYCLE, "输出目录：" + outputDir.getAbsolutePath());
            HttpURLConnection connection = createConnection(trimmedUrl);
            connection.connect();
            project.getLogger().log(LogLevel.LIFECYCLE, "正在下载：" + targetFile.getAbsolutePath());
            try (var input = connection.getInputStream()) {
                Files.copy(input, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                connection.disconnect();
            }

            if (!targetFile.exists() || targetFile.length() == 0) {
                throw new IOException("下载的文件为空或损坏");
            }
            project.getLogger().log(LogLevel.LIFECYCLE, "下载成功：" + targetFile.getAbsolutePath());
            return targetFile;
        } catch (IllegalArgumentException exception) {
            project.getLogger().log(LogLevel.ERROR, "下载参数错误：" + exception.getMessage());
        } catch (IOException exception) {
            project.getLogger().log(LogLevel.ERROR, "下载失败：" + exception.getMessage(), exception);
        } catch (Exception exception) {
            project.getLogger().log(LogLevel.ERROR, "未知错误：" + exception.getMessage(), exception);
        }
        return null;
    }

    private static HttpURLConnection createConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "*/*");
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private static String extractFileNameFromUrl(String url) {
        try {
            String cleanUrl = url.split("[?#]", 2)[0];
            String fileName = cleanUrl.substring(cleanUrl.lastIndexOf('/') + 1);
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && fileName.length() - dot - 1 >= 2) return fileName;
            return "downloaded-file-" + System.currentTimeMillis() + ".jar";
        } catch (Exception ignored) {
            return null;
        }
    }
}
