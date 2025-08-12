// package com.xammer.cloud.controller.gcp;

// import com.xammer.cloud.dto.gcp.GcpVpcTopology;
// import com.xammer.cloud.service.gcp.GcpDataService;
// import org.springframework.web.bind.annotation.GetMapping;
// import org.springframework.web.bind.annotation.RequestMapping;
// import org.springframework.web.bind.annotation.RequestParam;
// import org.springframework.web.bind.annotation.RestController;

// import java.util.concurrent.CompletableFuture;

// @RestController
// @RequestMapping("/api/gcp/cloudmap")
// public class GcpCloudmapController {

//     private final GcpDataService gcpDataService;

//     public GcpCloudmapController(GcpDataService gcpDataService) {
//         this.gcpDataService = gcpDataService;
//     }

//     @GetMapping("/topology")
//     public CompletableFuture<GcpVpcTopology> getVpcTopology(@RequestParam String accountId) {
//         return gcpDataService.getVpcTopology(accountId);
//     }
// }
