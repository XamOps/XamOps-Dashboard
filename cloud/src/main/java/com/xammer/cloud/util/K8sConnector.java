package com.xammer.cloud.util;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;

public class K8sConnector {
    public static KubernetesClient getClient(String kubeConfigYaml) {
        Config config = Config.fromKubeconfig(kubeConfigYaml);
        return new DefaultKubernetesClient(config);
    }
}