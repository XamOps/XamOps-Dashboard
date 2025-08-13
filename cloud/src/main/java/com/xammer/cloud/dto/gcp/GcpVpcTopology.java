package com.xammer.cloud.dto.gcp;

import com.google.cloud.compute.v1.Instance;
import com.google.cloud.compute.v1.Network;
import com.google.cloud.compute.v1.Subnetwork;
import lombok.Data;

import java.util.List;

@Data
public class GcpVpcTopology {
    public void setNetworks(List<Network> networks) {
        this.networks = networks;
    }
    public void setSubnetworks(List<Subnetwork> subnetworks) {
        this.subnetworks = subnetworks;
    }
    public void setInstances(List<Instance> instances) {
        this.instances = instances;
    }
    private List<Network> networks;
    private List<Subnetwork> subnetworks;
    private List<Instance> instances;
}