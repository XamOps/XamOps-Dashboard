package com.xammer.cloud.controller;

import com.xammer.cloud.dto.k8s.K8sClusterInfo;
import com.xammer.cloud.dto.k8s.K8sDeploymentInfo;
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.dto.k8s.K8sPodInfo;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/k8s")
public class CloudK8sController {

    private final AwsDataService awsDataService;

    public CloudK8sController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @GetMapping("/clusters")
    public ResponseEntity<List<K8sClusterInfo>> getClusters(@RequestParam String accountId) throws ExecutionException, InterruptedException {
        return ResponseEntity.ok(awsDataService.getEksClusterInfo(accountId).get());
    }

    @GetMapping("/clusters/{clusterName}/nodes")
    public ResponseEntity<List<K8sNodeInfo>> getNodes(@RequestParam String accountId, @PathVariable String clusterName) throws ExecutionException, InterruptedException {
        return ResponseEntity.ok(awsDataService.getK8sNodes(accountId, clusterName).get());
    }

    @GetMapping("/clusters/{clusterName}/namespaces")
    public ResponseEntity<List<String>> getNamespaces(@RequestParam String accountId, @PathVariable String clusterName) throws ExecutionException, InterruptedException {
        return ResponseEntity.ok(awsDataService.getK8sNamespaces(accountId, clusterName).get());
    }

    @GetMapping("/clusters/{clusterName}/namespaces/{namespace}/deployments")
    public ResponseEntity<List<K8sDeploymentInfo>> getDeployments(@RequestParam String accountId, @PathVariable String clusterName, @PathVariable String namespace) throws ExecutionException, InterruptedException {
        return ResponseEntity.ok(awsDataService.getK8sDeployments(accountId, clusterName, namespace).get());
    }

    @GetMapping("/clusters/{clusterName}/namespaces/{namespace}/pods")
    public ResponseEntity<List<K8sPodInfo>> getPods(@RequestParam String accountId, @PathVariable String clusterName, @PathVariable String namespace) throws ExecutionException, InterruptedException {
        return ResponseEntity.ok(awsDataService.getK8sPods(accountId, clusterName, namespace).get());
    }
}
