package com.example.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración de Firebase. Los valores provienen de variables de entorno,
 * nunca se escriben en el código fuente.
 *
 * firebase.project-id        -> ID del proyecto de Firebase
 * firebase.storage-bucket    -> bucket de Firebase Storage
 */
@Validated
@ConfigurationProperties(prefix = "firebase")
public class FirebaseProperties {

    @NotBlank(message = "firebase.project-id es obligatorio")
    private String projectId;

    @NotBlank(message = "firebase.storage-bucket es obligatorio")
    private String storageBucket;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getStorageBucket() {
        return storageBucket;
    }

    public void setStorageBucket(String storageBucket) {
        this.storageBucket = storageBucket;
    }
}
