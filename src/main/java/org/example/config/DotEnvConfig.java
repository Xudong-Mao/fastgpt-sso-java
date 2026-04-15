package org.example.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 从项目根目录下的 .env 文件加载环境变量到 Spring Environment
 * 优先级：系统环境变量 > .env 文件 > application.properties
 */
public class DotEnvConfig implements EnvironmentPostProcessor {

    private static final String DOT_ENV_FILE = ".env";
    private static final String PROPERTY_SOURCE_NAME = "dotenvProperties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 尝试多个路径查找 .env 文件
        String[] candidatePaths = {
            DOT_ENV_FILE,
            System.getProperty("user.dir") + "/" + DOT_ENV_FILE,
            System.getProperty("user.dir") + "/config/" + DOT_ENV_FILE
        };

        Resource resource = null;
        for (String path : candidatePaths) {
            Resource r = new FileSystemResource(path);
            if (r.exists() && r.isReadable()) {
                resource = r;
                break;
            }
        }

        if (resource == null) {
            return;
        }

        Map<String, Object> dotenvMap = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // 跳过空行和注释
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separatorIndex = line.indexOf('=');
                if (separatorIndex <= 0) {
                    continue;
                }
                String key = line.substring(0, separatorIndex).trim();
                String value = line.substring(separatorIndex + 1).trim();
                // 去除引号包裹
                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                dotenvMap.put(key, value);
            }
        } catch (IOException e) {
            throw new RuntimeException("读取 .env 文件失败: " + resource, e);
        }

        if (!dotenvMap.isEmpty()) {
            // .env 属性优先级低于系统环境变量，但高于 application.properties
            MapPropertySource propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, dotenvMap);
            environment.getPropertySources().addAfter("systemEnvironment", propertySource);
        }
    }
}
