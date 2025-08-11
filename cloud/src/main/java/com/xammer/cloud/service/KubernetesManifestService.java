package com.xammer.cloud.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;

@Service
public class KubernetesManifestService {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesManifestService.class);

    public void applyManifests(KubernetesClient client, String path) throws IOException {
        logger.info("Applying Kubernetes manifests from path: {}", path);
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(path);

        // Sort resources alphabetically to ensure a predictable order (e.g., namespace first)
        Arrays.sort(resources, Comparator.comparing(Resource::getFilename));

        for (Resource resource : resources) {
            try (InputStream is = resource.getInputStream()) {
                logger.info("Applying manifest: {}", resource.getFilename());
                // The client can load and apply multiple resources from a single YAML stream
                // CORRECTED LINE: Removed .inNamespace("default")
                client.load(is).createOrReplace();
            } catch (Exception e) {
                logger.error("Failed to apply manifest {}. Halting process.", resource.getFilename(), e);
                throw new RuntimeException("Failed to apply manifest: " + resource.getFilename(), e);
            }
        }
        logger.info("Successfully applied all manifests from path: {}", path);
    }
}