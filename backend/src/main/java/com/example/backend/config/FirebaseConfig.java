package com.example.backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.FirestoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

@Configuration
@EnableConfigurationProperties(FirebaseProperties.class)
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    private static final String APP_NAME = "OLITRACK";

    private final FirebaseProperties properties;

    public FirebaseConfig(FirebaseProperties properties) {
        this.properties = properties;
    }

    @Bean
    public GoogleCredentials googleCredentials() throws IOException {
        log.info(
                "Firebase credentials are resolved through Application Default Credentials."
        );
        return GoogleCredentials.getApplicationDefault();
    }

    @Bean
    public FirebaseApp firebaseApp(GoogleCredentials credentials) {

        List<FirebaseApp> existingApps = FirebaseApp.getApps();

        for (FirebaseApp app : existingApps) {
            if (APP_NAME.equals(app.getName())) {
                FirebaseOptions existingOptions = app.getOptions();
                if (!Objects.equals(properties.getProjectId(), existingOptions.getProjectId())
                        || !Objects.equals(properties.getStorageBucket(), existingOptions.getStorageBucket())) {
                    throw new IllegalStateException(
                            "FirebaseApp '"
                                    + APP_NAME
                                    + "' ya está inicializada con un proyecto o bucket diferente."
                    );
                }
                log.info(
                        "FirebaseApp '{}' ya estaba inicializada, se reutiliza.",
                        APP_NAME
                );
                return app;
            }
        }

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .setProjectId(properties.getProjectId())
                .setStorageBucket(properties.getStorageBucket())
                .build();

        FirebaseApp app = FirebaseApp.initializeApp(options, APP_NAME);

        log.info(
                "FirebaseApp '{}' inicializada para el proyecto '{}'.",
                APP_NAME,
                properties.getProjectId()
        );

        return app;
    }

    @Bean
    public Firestore firestore(FirebaseApp firebaseApp) {
        return FirestoreClient.getFirestore(firebaseApp);
    }

    @Bean
    public FirebaseAuth firebaseAuth(FirebaseApp firebaseApp) {
        return FirebaseAuth.getInstance(firebaseApp);
    }

    @Bean
    public Storage cloudStorage(FirebaseApp firebaseApp, GoogleCredentials credentials) {
        // Deferir la consulta remota del bucket hasta que se suba un archivo.
        // Si el bucket no existe, no debe impedir que arranque toda la API.
        return StorageOptions.newBuilder()
                .setProjectId(firebaseApp.getOptions().getProjectId())
                .setCredentials(credentials)
                .build()
                .getService();
    }
}
