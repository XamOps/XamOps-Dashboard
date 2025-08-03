package com.xammer.cloud.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Service
public class GcpClientProvider {

    public Storage createStorageClient(String serviceAccountJson) throws IOException {
        if (serviceAccountJson == null || serviceAccountJson.trim().isEmpty()) {
            throw new IllegalArgumentException("GCP Service Account JSON cannot be null or empty.");
        }
        
        InputStream credentialsStream = new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8));
        GoogleCredentials credentials = GoogleCredentials.fromStream(credentialsStream);
        
        return StorageOptions.newBuilder()
                .setCredentials(credentials)
                .build()
                .getService();
    }
}