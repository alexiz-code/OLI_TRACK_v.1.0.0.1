package com.example.backend;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.storage.Storage;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class BackendApplicationTests {

    @MockitoBean(name = "googleCredentials")
    GoogleCredentials googleCredentials;

    @MockitoBean(name = "firebaseApp")
    FirebaseApp firebaseApp;

    @MockitoBean(name = "firestore")
    Firestore firestore;

    @MockitoBean(name = "firebaseAuth")
    FirebaseAuth firebaseAuth;

    @MockitoBean(name = "cloudStorage")
    Storage storage;

    @Test
    void contextLoads() {
    }

}
